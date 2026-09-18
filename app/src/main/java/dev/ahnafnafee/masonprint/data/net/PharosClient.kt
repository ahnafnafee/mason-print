package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.JOB_PAGE_SIZE
import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.PharosJson
import dev.ahnafnafee.masonprint.data.model.PharosError
import dev.ahnafnafee.masonprint.data.model.PharosUser
import dev.ahnafnafee.masonprint.data.model.SettingsDocument
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.UserBalance
import dev.ahnafnafee.masonprint.data.model.cleanSentence
import dev.ahnafnafee.masonprint.data.model.dblCI
import dev.ahnafnafee.masonprint.data.model.encode
import dev.ahnafnafee.masonprint.data.model.lngIn
import dev.ahnafnafee.masonprint.data.model.obj
import dev.ahnafnafee.masonprint.data.model.objects
import dev.ahnafnafee.masonprint.data.model.objectsNested
import dev.ahnafnafee.masonprint.data.model.strCI
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import dev.ahnafnafee.masonprint.data.model.toJsonObjects
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * The whole transport, in one place, because the transport is where this API's folklore lives.
 *
 * Every oddity encoded below was observed in the field, and the file that proves it is named at
 * the call site. Nothing here is a guess: `/PharosAPI` has no published vendor contract (the
 * release notes hide it under "Print Center API and Web Services"), so the contract *is* what the
 * vendor's own two clients send — the Xamarin app's C# and GMU's 4.11.24.1 browser bundle, both
 * decompiles of which are in this repository.
 */
class PharosClient(
    private val http: OkHttpClient,
    private val uploadHttp: OkHttpClient,
) {

    @Volatile var credentials: Credentials? = null
    @Volatile var apiVersion: String? = null

    // ---------------------------------------------------------------- discovery

    /**
     * `GET /PharosAPI/settings` — works **unauthenticated** on GMU, which makes it the cheapest
     * possible "is there a Pharos server behind this hostname" probe, and it answers the version
     * question before the user has typed a password.
     */
    suspend fun probe(target: PharosTarget): ApiResult<ProbeResult> {
        val url = target.apiPath("settings")
        return when (val r = execute(target, url, auth = false)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val body = r.body.orEmpty()
                // Two independent signs that this is Pharos behind the hostname: the version
                // header on any response, or a settings document that mentions its own sections.
                val versioned = apiVersion != null || target.headerApiVersion != null
                val documentish = body.contains("PrintCenter") || body.contains("ServerVersion") ||
                    body.contains("\"Version\"")
                if (!versioned && !documentish) {
                    ApiResult.Err(
                        PharosFailure.NotAPharosServer("HTTP ${r.status} from ${url.host}, but no Pharos settings document"),
                    )
                } else {
                    ApiResult.Ok(
                        ProbeResult(
                            apiVersion = apiVersion ?: target.headerApiVersion,
                            settings = runCatching { body.toJsonObject() }.getOrNull(),
                            printCenterUri = "https://${url.host}/myprintcenter",
                        ),
                        r.status,
                        r.body,
                    )
                }
            }
        }
    }

    /** Server-driven capability source. `expanded` adds the sections the UI branches on. */
    suspend fun settings(target: PharosTarget): ApiResult<SettingsDocument> {
        val url = target.apiPath("settings").newBuilder().addQueryParameter("expanded", "").build()
        return execute(target, url, auth = false).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> {
                    val obj = runCatching { r.body.orEmpty().toJsonObject() }.getOrNull()
                        ?: return ApiResult.Err(PharosFailure.Server(PharosError.from(r.status, r.body.orEmpty())))
                    ApiResult.Ok(SettingsDocument(obj), r.status, r.body)
                }
            }
        }
    }

    /** Print Center's own localised strings, so the clone can quote the server's wording. */
    suspend fun clientText(target: PharosTarget): ApiResult<Map<String, String>> {
        val url = target.apiPath("client", "text")
            .newBuilder().addQueryParameter("jsVariable", "PharosPrintCenterText").build()
        return execute(target, url, auth = false).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> ApiResult.Ok(parseJsStringMap(r.body.orEmpty()), r.status, r.body)
            }
        }
    }

    // ---------------------------------------------------------------- session

    /**
     * `GET /PharosAPI/logon` — authentication is a **GET**, not a POST, and the credential is
     * carried in a header, computed locally.
     *
     * Two headers, same value: the Xamarin client put it in `Authorization` and GMU's browser
     * bundle puts it in `X-Authorization`, and no deployment was observed insisting on one, so the
     * clone sends both rather than learning which campus needs which the hard way.
     *
     * The success body **is** the user object. On GMU it carries no `UserUri` key at all — the
     * user's own resource arrives as `"Location":"/users/POYJ8x6E7Ias2Uj6cdAJZA2"`, a *relative*
     * path, and `Location` is also the response header. `{UserUri}` therefore has to be built from
     * that, which is what [captureFromUser] does.
     *
     * Deliberately **not** sent: `excudeLocation=yes` (the vendor's misspelling of "exclude", which
     * the server parses exactly). Measured against GMU with one set of credentials and everything
     * else equal, it suppresses the `Location` response header while returning an identical
     * 4070-byte body — the stock client sends it and so throws away the cheapest route to
     * `{UserUri}`. The clone keeps the header, and still parses the body.
     */
    suspend fun logon(target: PharosTarget, creds: Credentials): ApiResult<PharosUser> {
        val url = target.apiPath("logon").newBuilder()
            .addQueryParameter("KeepMeLoggedIn", if (creds.rememberMe) "yes" else "no")
            .addQueryParameter("includeprintjobs", "no")
            .addQueryParameter("includedeviceactivity", "no")
            .addQueryParameter("includeprivileges", "yes")
            .addQueryParameter("includecostcenters", "yes")
            .build()
        credentials = creds
        return when (val r = execute(target, url, auth = true)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                captureUserUri(target, r)
                val user = runCatching { PharosUser.from(r.body.orEmpty().toJsonObject()) }.getOrNull()
                user?.let { captureFromUser(target, it) }
                ApiResult.Ok(user ?: PharosUser.from(emptyJsonObject()), r.status, r.body)
            }
        }
    }

    /**
     * Re-read the signed-in user. This is also how a restored session is validated at launch:
     * if the persisted cookie jar is still honoured, this returns 200 and the app never has to
     * ask for a password again — which is what "Keep me logged in" was supposed to mean.
     */
    suspend fun refreshUser(target: PharosTarget, includeBalance: Boolean = true): ApiResult<PharosUser> {
        val url = target.apiPath("logon").newBuilder()
            .addQueryParameter("includeprintjobs", "no")
            .addQueryParameter("includedeviceactivity", "no")
            .addQueryParameter("includeprivileges", "yes")
            .addQueryParameter("includecostcenters", "yes")
            .apply { if (includeBalance) addQueryParameter("includeBalance", "yes") }
            .build()
        return execute(target, url, auth = true).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> {
                    // A restored session is the other half of the `{UserUri}` problem: the cookie jar
                    // survives a reinstall-free reboot, `userUri` does not, and on GMU nothing else
                    // re-derives it. Take the `Location` header here as well as from the body.
                    captureUserUri(target, r)
                    val user = runCatching { PharosUser.from(r.body.orEmpty().toJsonObject()) }.getOrNull()
                        ?: return ApiResult.Err(PharosFailure.Server(PharosError.from(r.status, r.body.orEmpty())))
                    captureFromUser(target, user)
                    ApiResult.Ok(user, r.status, r.body)
                }
            }
        }
    }

    /**
     * Real server logout. The stock app never called it — it cleared SharedPreferences and left
     * the Pharos session alive server-side (docs/FINDINGS.md §12), so "logging out" on a shared
     * device left the account open until the cookie timed out. Best-effort: local state is
     * cleared by the caller no matter what this returns.
     */
    suspend fun logout(target: PharosTarget) {
        runCatching { execute(target, target.apiPath("logout"), auth = true) }
    }

    // ---------------------------------------------------------------- jobs

    /**
     * `GET {UserUri}/printjobs?Skip=&PageSize=` — paged. The bundle's own paging defaults to 15
     * rows for a desktop grid; 50 suits a phone list and still keeps the first page under ~40 KB.
     */
    suspend fun printJobs(target: PharosTarget, skip: Int, pageSize: Int = JOB_PAGE_SIZE): ApiResult<Page<PrintJob>> {
        val user = target.userUri ?: return ApiResult.Err(PharosFailure.NotFound("{UserUri} not yet known. Sign in first"))
        val url = jobsUrl(user, skip, pageSize)
        return execute(target, url).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> {
                    val obj = runCatching { r.body.orEmpty().toJsonObject() }.getOrNull()
                        ?: return ApiResult.Err(PharosFailure.Server(PharosError.from(r.status, r.body.orEmpty())))
                    ApiResult.Ok(Page.from(obj, PrintJob::from), r.status, r.body)
                }
            }
        }
    }

    /** Follows `NextPageLink` verbatim — the server puts its own query string in it. */
    suspend fun nextPage(target: PharosTarget, page: Page<*>): ApiResult<Page<PrintJob>> {
        val url = target.resolve(page.absoluteNextPage)
            ?: return ApiResult.Err(PharosFailure.NotFound(page.absoluteNextPage ?: "no NextPageLink"))
        return execute(target, url).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> ApiResult.Ok(
                    Page.from(r.body.orEmpty().toJsonObject(), PrintJob::from), r.status, r.body,
                )
            }
        }
    }

    /**
     * `POST {UserUri}/printjobs`, multipart, two parts named exactly `MetaData` and `Content`.
     *
     * Success is **201**, not 200 — the stock app checked for a 200-ish code in one path and a
     * body in another, which is part of why "it said it uploaded" was not evidence of anything.
     * The part names are the vendor's own spelling from `WebReqeustHelper.UploadAsync`; GMU's
     * browser bundle uses lowercase `content`, and both are accepted by 4.11.24.1.
     *
     * The body streams from a `FileDescriptor`, never a `ByteArray`: the stock app read the whole
     * document into a `byte[]` (`UploadDocument(byte[] fileContent, …)`), so a 45 MB scan cost
     * 45 MB of heap plus a base64 copy in the log, and OOM-killed the app on cheap phones.
     */
    suspend fun upload(
        target: PharosTarget,
        source: UploadSource,
        metaDataJson: String,
        onProgress: (fraction: Float) -> Unit,
    ): ApiResult<UploadOutcome> {
        val user = target.userUri ?: return ApiResult.Err(PharosFailure.NotFound("{UserUri} not yet known. Sign in first"))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(
                Headers.headersOf("Content-Disposition", "form-data; name=\"MetaData\""),
                metaDataJson.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .addPart(
                Headers.headersOf(
                    "Content-Disposition",
                    "form-data; name=\"Content\"; filename=\"${source.fileName.replace("\"", "")}\"",
                ),
                source.toRequestBody(onProgress),
            )
            .build()
        return when (val r = execute(target, uploadUrl(user), method = "POST", body = body, upload = true)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> ApiResult.Ok(
                UploadOutcome(r.status, r.value.firstOrNull { it.first.equals("Location", true) }?.second),
                r.status,
                r.body,
            )
        }
    }

    /**
     * `POST /printjobs/cost` — priced server-side. The same body also goes to
     * `PATCH /printjobs/` when the user is *changing* options, because both are built by the
     * bundle's single `buildJobUpdateRequestData(attrs)`; the clone keeps that symmetry so a cost
     * preview and the real release cannot disagree.
     */
    suspend fun cost(target: PharosTarget, bodyJson: String): ApiResult<CostEstimate> {
        val url = target.apiPath("printjobs", "cost")
        return when (val r = execute(target, url, method = "POST", body = bodyJson.toRequestBody(JSON))) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> ApiResult.Ok(CostEstimate.from(r.body.orEmpty()), r.status, r.body)
        }
    }

    /** `POST /printjobs/release` — `{PrintJobs,Device,CardId}` → `{Responses,User}`. */
    suspend fun release(target: PharosTarget, bodyJson: String): ApiResult<dev.ahnafnafee.masonprint.data.model.JobOperationResult> =
        postOperation(target.apiPath("printjobs", "release"), target, bodyJson)

    /** `PATCH /printjobs/` — finishing options and cost-centre changes. */
    suspend fun patchJobs(target: PharosTarget, bodyJson: String): ApiResult<dev.ahnafnafee.masonprint.data.model.JobOperationResult> =
        postOperation(target.apiPath("printjobs"), target, bodyJson, method = "PATCH")

    /** `DELETE /printjobs` — a DELETE *with a body*, which is why the stock app used HttpClient. */
    suspend fun deleteJobs(target: PharosTarget, bodyJson: String): ApiResult<dev.ahnafnafee.masonprint.data.model.JobOperationResult> =
        postOperation(target.apiPath("printjobs"), target, bodyJson, method = "DELETE")

    private suspend fun postOperation(
        url: HttpUrl,
        target: PharosTarget,
        bodyJson: String,
        method: String = "POST",
    ): ApiResult<dev.ahnafnafee.masonprint.data.model.JobOperationResult> =
        when (val r = execute(target, url, method = method, body = bodyJson.toRequestBody(JSON))) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> ApiResult.Ok(
                dev.ahnafnafee.masonprint.data.model.JobOperationResult.from(r.body), r.status, r.body,
            )
        }

    // ---------------------------------------------------------------- devices

    suspend fun devices(target: PharosTarget, skip: Int = 0, pageSize: Int = 200): ApiResult<Page<Device>> {
        val url = target.apiPath("devices").newBuilder()
            .addQueryParameter("Skip", skip.toString())
            .addQueryParameter("PageSize", pageSize.toString())
            .build()
        return execute(target, url).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> ApiResult.Ok(
                    Page.from(r.body.orEmpty().toJsonObject(), Device::from), r.status, r.body,
                )
            }
        }
    }

    /**
     * `GET /devices/{id}` — the QR scanner's whole job. A station's QR text is a device id, and
     * the stock app resolved it here before `POST /printjobs/release`.
     *
     * Note the trap this endpoint exposed during recon: on a deployment where the id is *not*
     * known, Pharos answers **401, not 404**, because the auth filter runs before routing. So a
     * 401 here must not silently bounce the user to the sign-in screen.
     */
    suspend fun deviceById(target: PharosTarget, id: String): ApiResult<Device> {
        val url = target.apiPath("devices", id)
        return execute(target, url).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> ApiResult.Ok(
                    Device.from(r.body.orEmpty().toJsonObject()), r.status, r.body,
                )
            }
        }
    }

    // ---------------------------------------------------------------- money

    /**
     * Balance. Two documented routes exist (`{UserUri}?includeBalance=yes` and
     * `{UserLocation}/balance?includeZero=true`) and the bundle uses both depending on screen;
     * the clone tries the user-resource form first and falls back, because GMU answers either.
     */
    suspend fun balance(target: PharosTarget, cardId: String?): ApiResult<UserBalance> {
        val primary = target.userUri?.newBuilder()
            ?.addQueryParameter("includeprintjobs", "no")
            ?.addQueryParameter("includedeviceactivity", "no")
            ?.addQueryParameter("includeprivileges", "no")
            ?.addQueryParameter("includecostcenters", "no")
            ?.addQueryParameter("includeBalance", "yes")
            ?.apply { if (!cardId.isNullOrBlank()) addQueryParameter("cardId", cardId) }
            ?.build()
        val fallback = target.userLocation?.newBuilder()
            ?.addPathSegment("balance")
            ?.addQueryParameter("includeZero", "true")
            ?.build()
            ?: target.userUri?.newBuilder()?.addPathSegment("balance")?.addQueryParameter("includeZero", "true")?.build()

        for (url in listOfNotNull(primary, fallback)) {
            when (val r = execute(target, url)) {
                is ApiResult.Ok -> {
                    val obj = runCatching { r.body.orEmpty().toJsonObject() }.getOrNull() ?: continue
                    val balance = UserBalance.from(obj)
                    if (!balance.isEmpty) return ApiResult.Ok(balance, r.status, r.body)
                }
                is ApiResult.Err -> if (url == fallback) return r
            }
        }
        return ApiResult.Ok(UserBalance(null, emptyList()), 200)
    }

    /** `GET {UserUri}/transactions?Skip=&PageSize=` — the statement screen's data. */
    suspend fun transactions(target: PharosTarget, skip: Int, pageSize: Int = 50): ApiResult<Page<Transaction>> {
        val user = target.userUri ?: return ApiResult.Err(PharosFailure.NotFound("{UserUri} not yet known"))
        val url = user.newBuilder().addPathSegment("transactions")
            .addQueryParameter("Skip", skip.toString())
            .addQueryParameter("PageSize", pageSize.toString())
            .build()
        return execute(target, url).let { r ->
            when (r) {
                is ApiResult.Err -> r
                is ApiResult.Ok -> ApiResult.Ok(
                    Page.from(r.body.orEmpty().toJsonObject(), Transaction::from), r.status, r.body,
                )
            }
        }
    }

    /**
     * `GET {UserUri}/costcenters?search=&Skip=&PageSize=` — department codes, searched rather than
     * listed.
     *
     * The web client pages these six at a time and, tellingly, pushes whatever the user picks into
     * its *own* local collection (`script.min.js:1@2308739`). Two consequences the clone has to
     * respect: the `User.CostCenters` snapshot from logon is not the whole namespace, and legality
     * is the server's decision at release, so a code the student typed by hand is submitted as-is.
     * Codes are hierarchical, joined with `\`, where `Complete == false` marks a folder rather than
     * a chargeable leaf (docs/FUNDING-MODELS.md §2.7).
     */
    suspend fun costCenters(
        target: PharosTarget,
        search: String,
        skip: Int = 0,
        pageSize: Int = 6,
    ): ApiResult<List<CostCenter>> {
        val user = target.userUri ?: return ApiResult.Err(PharosFailure.NotFound("{UserUri} not yet known"))
        val builder = user.newBuilder().addPathSegment("costcenters")
            .addQueryParameter("Skip", skip.toString())
            .addQueryParameter("PageSize", pageSize.toString())
        if (search.isNotBlank()) builder.addQueryParameter("search", search)
        return when (val r = execute(target, builder.build())) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val text = r.body.orEmpty()
                val items = runCatching {
                    when (val root = PharosJson.parseToJsonElement(text)) {
                        is JsonArray -> root.mapNotNull { it as? JsonObject }
                        is JsonObject -> root.objects("Items").ifEmpty { root.objectsNested("CostCenters") }
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList()).map(CostCenter::from)
                ApiResult.Ok(items, r.status, r.body)
            }
        }
    }

    /**
     * Hosted add-funds. GMU publishes `"Add Funds":"Deny"` and `"Credit Card Gateway":"No"`, so
     * on this campus the UI hides the entry point entirely (capabilities, not disabled buttons) —
     * but the route is here for the deployments where money *does* arrive from the app. Card data
     * never touches this app: the server hands back a gateway URL and the WebView posts to it.
     */
    suspend fun gatewayStart(target: PharosTarget, amount: String?): ApiResult<String> {
        val user = target.userUri ?: return ApiResult.Err(PharosFailure.NotFound("{UserUri} not yet known"))
        val get = user.newBuilder().addPathSegments("balance/gateway").build()
        val post = user.newBuilder().addPathSegments("balance/gateway")
            .apply { if (!amount.isNullOrBlank()) addQueryParameter("amount", amount) }.build()
        return when (val r = execute(target, if (amount.isNullOrBlank()) get else post,
            method = if (amount.isNullOrBlank()) "GET" else "POST")) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> ApiResult.Ok(r.body.orEmpty(), r.status, r.body)
        }
    }

    // ---------------------------------------------------------------- document content

    /**
     * `GET {job}/content` — the document itself, streamed to [dest].
     *
     * Deliberately not routed through [execute]: that reads every body as a `String`, which mangles
     * a PDF. Pharos converts on ingest (`JobFormat: Intermediate`), so this answers `application/pdf`
     * with the `%PDF-` magic even for a job submitted from Word — which is why one renderer covers
     * the whole queue.
     *
     * There is no preview endpoint to use instead: `/preview`, `/preview/1`, `/thumbnail` and every
     * other shape answer **401**, which on this API means "no such route" rather than "signed out"
     * (the auth filter runs before routing — see [deviceById]). `Content` is the action the server
     * actually advertises in a job's `AllowableActions`, and it is the one that answers 200.
     */
    suspend fun jobContent(target: PharosTarget, jobLocation: String, dest: File): ApiResult<File> {
        val url = target.apiPath(jobLocation, "content")
        val builder = Request.Builder().url(url).get()
        credentials?.headerValue?.let {
            builder.header("Authorization", it)
            builder.header("X-Authorization", it)
        }
        return try {
            http.await(builder.build()).use { response ->
                if (response.code !in 200..299) {
                    val text = runCatching { response.body.string() }.getOrNull().orEmpty()
                    ApiResult.Err(
                        classify(
                            response.code,
                            text,
                            url,
                            expectedRoute = true,
                            hadCredentials = credentials != null,
                        ),
                        text,
                    )
                } else {
                    withContext(Dispatchers.IO) {
                        response.body.byteStream().use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    ApiResult.Ok(dest, response.code, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ApiResult.Err(e.toPharosFailureException(upload = false).failure)
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun execute(
        target: PharosTarget,
        url: HttpUrl,
        method: String = "GET",
        body: RequestBody? = null,
        auth: Boolean = true,
        upload: Boolean = false,
    ): ApiResult<List<Pair<String, String>>> {
        val raw = try {
            executeRaw(
                client = if (upload) uploadHttp else http,
                url = url,
                method = method,
                body = body,
                authHeader = if (auth) credentials?.headerValue else null,
                upload = upload,
            )
        } catch (e: PharosTransportException) {
            return ApiResult.Err(e.failure)
        }
        // `X-PHAROS-API-VERSION` rides *every* response, not just the probe's — GMU sends it on the
        // 300 that rejects a bad password, for instance. Recording it here means a session restored
        // straight into `SignedOut` (which never probes) still knows what it is talking to, which
        // is what the ≥2.4 gate, the queue subtitle, and Diagnostics all read.
        raw.headers.firstOrNull { it.first.equals("X-PHAROS-API-VERSION", true) }?.second
            ?.takeIf { it.isNotBlank() }
            ?.let { version ->
                target.headerApiVersion = version
                if (apiVersion == null) apiVersion = version
            }
        return when {
            raw.status in 200..299 -> ApiResult.Ok(raw.headers, raw.status, raw.body)
            else -> ApiResult.Err(
                classify(raw.status, raw.body.orEmpty(), url, auth, hadCredentials = credentials != null),
                raw.body,
            )
        }
    }

    private suspend fun executeRaw(
        client: OkHttpClient,
        url: HttpUrl,
        method: String,
        body: RequestBody?,
        authHeader: String?,
        upload: Boolean,
    ): RawResponse = try {
        val builder = Request.Builder().url(url)
        if (authHeader != null) {
            builder.header("Authorization", authHeader)
            builder.header("X-Authorization", authHeader)
        }
        builder.method(method, body)
        val response = client.await(builder.build())
        response.use {
            RawResponse(
                status = it.code,
                body = if (method == "HEAD") null else runCatching { it.body.string() }.getOrNull(),
                headers = it.headers.map { (k, v) -> k to v },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e.toPharosFailureException(upload)
    }

    private fun classify(
        status: Int,
        body: String,
        url: HttpUrl,
        expectedRoute: Boolean,
        hadCredentials: Boolean,
    ): PharosFailure {
        val error = PharosError.from(status, body)
        val code = error.errorCode.orEmpty()
        val looksLikeMissingRoute = code.contains("404", true) ||
            code.contains("notfound", true) || code.contains("not_found", true) ||
            error.userMessage.orEmpty().contains("not found", true)
        /**
         * GMU rejects a wrong password with **HTTP 300 Multiple Choices** carrying
         * `"Status":300` (captured live at `phrarosprint/evidence/bad-logon-body.json`),
         * `pharosweb.westernu.edu` does the same, and `print.uw.edu` sends 401 with `"Status":403`
         * in the body — so the envelope is read for the *auth* verdict while the transport status
         * still decides the transport facts (404/413/415 come from ARR and IIS, not from Pharos).
         * OkHttp follows the redirectable 3xx on its own, which is why 300 is the one that lands
         * here. Routing it to [PharosFailure.Server] would say "the print server returned an error"
         * for a typo'd password *and* suppress the CAS fallback `SignInScreen` keys off
         * [PharosFailure.Unauthenticated].
         */
        if (status in 300..399 && hadCredentials && error.userText("").isNotBlank()) {
            return PharosFailure.Unauthenticated(error)
        }
        return when (status) {
            // 401 is overloaded: expired session *and* unknown route. Only the body tells them
            // apart, so that is what gets read — see `deviceById` for why this matters.
            401 -> if (looksLikeMissingRoute || !hadCredentials) PharosFailure.NotFound(url.encodedPath)
            else PharosFailure.Unauthenticated(error)
            403 -> PharosFailure.Unauthenticated(error)
            404 -> PharosFailure.NotFound(url.encodedPath)
            413 -> PharosFailure.TooLarge(null, error.userText("The document is too large for this server."))
            415 -> PharosFailure.UnsupportedType(null, error.userText("This file type is not accepted here."))
            else -> PharosFailure.Server(error)
        }
    }

    /**
     * `UserUri` has three sources and deployments differ about which they populate: the
     * `Location` **header**, the JSON `UserUri` field, and a URL-encoded
     * `PharosAPI.X-PHAROS-USER-URI` cookie. The stock app tried them in exactly this order, so
     * the clone does too — dropping any one of them breaks one campus or another.
     */
    private fun captureUserUri(target: PharosTarget, r: ApiResult.Ok<List<Pair<String, String>>>) {
        val headers = r.value
        val locationHeader = headers.firstOrNull { it.first.equals("Location", true) }?.second
        if (!locationHeader.isNullOrBlank()) target.setUserUriFromValue(locationHeader)

        val cookie = headers.filter { it.first.equals("Set-Cookie", true) }
            .firstOrNull { it.second.contains("X-PHAROS-USER-URI", true) }
            ?.second
            ?.substringAfter("X-PHAROS-USER-URI=", "")
            ?.substringBefore(';')
        if (!cookie.isNullOrBlank()) {
            val decoded = runCatching { java.net.URLDecoder.decode(cookie, "UTF-8") }.getOrDefault(cookie)
            target.setUserUriFromValue(decoded)
        }
    }

    private fun captureFromUser(target: PharosTarget, user: PharosUser) {
        // A user object's own `Location` *is* `{UserUri}` on 4.x — GMU sends that and no `UserUri`
        // key at all (live capture: `"Location":"/users/POYJ8x6E7Ias2Uj6cdAJZA2"`), so treating
        // `Location` as merely the print location left `{UserUri}` null and every money, cost-centre
        // and queue call failed with "sign in first" *after a successful sign-in*.
        (user.userUri ?: user.location)?.let { target.setUserUriFromValue(it) }
        user.location?.let { target.setUserLocationFromValue(it) }
    }

    private class RawResponse(val status: Int, val body: String?, val headers: List<Pair<String, String>>)
}

/** Thrown out of [PharosClient.executeRaw] so the caller's `when` stays exhaustive. */
class PharosTransportException(val failure: PharosFailure, cause: Throwable?) : IOException(cause)

fun IOException.toPharosFailureException(upload: Boolean): PharosTransportException {
    val f = if (this is PharosTransportException) failure else toPharosFailure(writePhase = upload)
    return if (this is PharosTransportException) this else PharosTransportException(f, this)
}

private val JSON = "application/json".toMediaType()

/** OkHttp call as a cancellable continuation — a cancelled upload must stop burning radio time. */
private suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled || call.isCanceled()) return
                cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // `resumeWith` is the non-internal way to settle a continuation from a callback;
                // if the caller already went away, the response body has to be closed here or the
                // connection is held until the idle timeout.
                if (cont.isCancelled || call.isCanceled()) {
                    response.close()
                    return
                }
                cont.resumeWith(Result.success(response))
            }
        })
    }

private fun parseJsStringMap(body: String): Map<String, String> {
    // `PharosPrintCenterText = { Key: "value", … };` — a JS object literal, not JSON. Best-effort.
    val out = LinkedHashMap<String, String>()
    Regex("""([A-Za-z0-9_]+)\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(body).forEach { m ->
        out[m.groupValues[1]] = m.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n")
    }
    return out
}

private fun emptyJsonObject(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())

data class ProbeResult(
    val apiVersion: String?,
    val settings: kotlinx.serialization.json.JsonObject?,
    val printCenterUri: String,
)

/**
 * The queue URL, split out so a test can pin it.
 *
 * `userPrintJobs` is `get {UserUri}/printjobs` in GMU's own endpoint table
 * (`script.min.js:1@1316092`). Asking `{UserUri}` without the segment instead returns **200 with
 * the user resource**, which parses into a page with no `Items` and no `Count` — so the queue
 * renders "nothing to print" and nothing else looks wrong. On a test account that genuinely has no
 * jobs, that is indistinguishable from working, which is exactly how long it stayed hidden.
 */
internal fun jobsUrl(user: HttpUrl, skip: Int, pageSize: Int): HttpUrl =
    user.newBuilder()
        .addPathSegment("printjobs")
        .setQueryParameter("Skip", skip.toString())
        .setQueryParameter("PageSize", pageSize.toString())
        .build()

/**
 * The upload endpoint is `{UserUri}/printjobs` — the same collection the queue is read from, with
 * `POST` instead of `GET` (`script.min.js:1@1316092` lists `userPrintJobs` for both verbs).
 *
 * This helper exists because posting the multipart body to `{UserUri}` itself is a *silent-looking*
 * mistake that is not silent at all: on 2026-09-18 the clone POSTed a 1.67 MB PDF to
 * `https://mobileprint.gmu.edu/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2` and GMU answered
 * **405 with `allow: GET,DELETE,PATCH,PUT`** after the whole body had been uploaded. The document
 * never reached the server, so the queue stayed empty and the only sign of trouble was a status
 * code. Keep the segment.
 */
internal fun uploadUrl(user: HttpUrl): HttpUrl =
    user.newBuilder().addPathSegment("printjobs").build()

data class UploadOutcome(val status: Int, val jobLocation: String?)

/**
 * Priced preview. `TotalCost` is the server's own arithmetic, which is the only number a student
 * will accept as authoritative when a $0.10/$0.25 dispute happens.
 *
 * One line per job, because that is what the endpoint returns: a **bare JSON array**, and each
 * element carries its own `Status`. HTTP 200 therefore does *not* mean "priced" — a job still being
 * analysed answers 200 at the transport level and 405 inside the array
 * (`JobActionNotAllowedStillProcessing`), which is the shape `refusals` exists to surface.
 */
data class CostEstimate(
    val total: Double?,
    val lines: List<JsonObject>,
    val refusals: List<CostRefusal>,
    val raw: String,
) {
    /** True when the server answered and refused every job it was asked about. */
    val allRefused: Boolean get() = refusals.isNotEmpty() && refusals.size >= lines.size

    companion object {
        fun from(body: String): CostEstimate {
            val objs = runCatching { body.toJsonObjects() }.getOrDefault(emptyList())
            val envelope = objs.singleOrNull { it.size > 1 && it.keys.any { k -> k.equals("TotalCost", true) } }
            val publishedTotal = envelope?.let {
                dev.ahnafnafee.masonprint.data.model.dblIn(it, "TotalCost", "Cost", "Total", "Amount")
            }
            val lines = if (objs.size == 1 && objs[0].keys.any { k -> k.equals("Responses", true) }) {
                (objs[0].objects("Responses")).ifEmpty { objs[0].objects("Items") }
            } else {
                objs
            }
            val refusals = lines.mapNotNull { line ->
                val status = lngIn(line, "Status", "StatusCode") ?: return@mapNotNull null
                if (status in 200..299) return@mapNotNull null
                CostRefusal(
                    status = status.toInt(),
                    errorCode = line.strCI("ErrorCode"),
                    // The vendor's own sentence, HTML-unwrapped: GMU localises these, and its text
                    // service is what a student is expecting to read.
                    message = line.strCI("UserMessage")?.let { cleanSentence(it) },
                    jobLocation = line.strCI("Location") ?: line.strCI("JobLocation"),
                )
            }
            /*
             * GMU never sends `TotalCost`. The priced answer is a bare array whose rows carry the
             * price inside `Costing` as a *string* (`"Cost":"1.20"` for 12 black-and-white pages =
             * $0.10 each, evidence/probe-cost.json), so the total a student is about to be charged
             * has to be summed here — and only when **every** accepted row has a price, because a
             * selection half of which is still being analysed has no total worth showing. `-1` is
             * "uncostable", not a number: one `-1` in the selection makes the total unknown, which
             * is exactly how the queue renders it.
             */
            val summed = run {
                val priced = lines.filter { row ->
                    val status = lngIn(row, "Status", "StatusCode")
                    status == null || status in 200..299
                }
                if (priced.isEmpty() || priced.size != lines.size) return@run null
                val amounts = priced.map { row ->
                    (row.obj("Costing") ?: row.obj("PrintJob") ?: row).dblCI("Cost") ?: return@run null
                }
                if (amounts.any { it < 0.0 }) return@run null
                amounts.sum()
            }
            return CostEstimate(publishedTotal ?: summed, lines, refusals, body)
        }
    }
}

/**
 * A job the server declined to price, with the server's own reason. `-1`-style "no cost published"
 * is *not* a refusal — that is a priced answer saying "unknown", and the two get different copy.
 */
data class CostRefusal(
    val status: Int,
    val errorCode: String?,
    val message: String?,
    val jobLocation: String?,
)
