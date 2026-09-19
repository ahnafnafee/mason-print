package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.Capabilities
import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.model.PharosJson
import dev.ahnafnafee.masonprint.data.model.FinishingPayload
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PharosUser
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.Purse
import dev.ahnafnafee.masonprint.data.model.SettingsDocument
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.cleanSentence
import dev.ahnafnafee.masonprint.data.model.dbl
import dev.ahnafnafee.masonprint.data.model.dblCI
import dev.ahnafnafee.masonprint.data.model.htmlUnescaped
import dev.ahnafnafee.masonprint.data.model.str
import dev.ahnafnafee.masonprint.data.model.strCI
import dev.ahnafnafee.masonprint.data.model.strIn
import dev.ahnafnafee.masonprint.data.model.obj
import dev.ahnafnafee.masonprint.data.model.encode
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.FinishingOptions
import kotlinx.serialization.builtins.ListSerializer
import dev.ahnafnafee.masonprint.data.net.ApiResult
import dev.ahnafnafee.masonprint.data.net.Credentials
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.data.net.PharosTarget
import dev.ahnafnafee.masonprint.data.net.TrustedCertificate
import dev.ahnafnafee.masonprint.data.net.UploadSource
import dev.ahnafnafee.masonprint.data.net.getOrNull
import dev.ahnafnafee.masonprint.data.prefs.SavedCostCenter
import dev.ahnafnafee.masonprint.data.upload.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the app is in the sign-in lifecycle. Drives which screen [ui.AppRoot] draws. */
enum class Phase { Boot, Connect, SignedOut, Working, Ready }

/** One immutable snapshot of everything the UI renders. */
data class AppState(
    val phase: Phase = Phase.Boot,
    val host: String = "",
    val apiVersion: String? = null,
    val capabilities: Capabilities? = null,
    val user: PharosUser? = null,
    val jobs: List<PrintJob> = emptyList(),
    val jobsCount: Long? = null,
    val nextJobsPage: Page<*>? = null,
    val loadedAt: Long = 0L,
    val devices: List<Device> = emptyList(),
    val selectedDevice: Device? = null,
    /** Device `Location`s this account has starred, newest-irrelevant: the picker sorts by label. */
    val favouriteDevices: Set<String> = emptySet(),
    /** Device `Location`s this account last released at, most recent first. */
    val recentDevices: List<String> = emptyList(),
    /**
     * What the next release should charge: null = the user's own balance (all of their purses, in
     * the server's spending order), non-null = that cost-centre code instead. A department that
     * pays for its students' printing shows up here, and on those accounts it is the difference
     * between a $2.50 job and a free one — which is why it is session state rather than a field on
     * a dialog, so the cost preview and the release cannot disagree.
     */
    val costCenter: String? = null,
    /**
     * Live results from `GET {UserUri}/costcenters?search=`, kept separate from the logon snapshot
     * in `Capabilities.grantedCostCenters` because they are different things: the snapshot is what
     * was granted at sign-in, the search is the whole namespace, and the web client lets you pick a
     * code it had never seen. Legality is the server's decision at release, not the client's here.
     */
    val costCenterResults: List<CostCenter> = emptyList(),
    val searchingCostCenters: Boolean = false,
    /**
     * Cost-centre codes this account has charged to before, most recent first. The server publishes
     * no directory of codes, so this phone-held list is the only place the code a department handed
     * out survives between sessions — a manually typed code becomes reusable the moment it is used.
     */
    val savedCostCenters: List<SavedCostCenter> = emptyList(),
    val busy: String? = null,
    val upload: UploadProgress? = null,
    val uploadFraction: Float = 0f,
    /**
     * The documents this device handed the app for the current — or, once finished, the most recent —
     * send, in the order they are sent.
     *
     * Android's picker returns a *list* now, and the sheet cannot reconstruct one from
     * `persistedUriPermissions`, which holds every file ever granted and carries no batch. So the
     * batch is recorded here, by the code that actually received it. Kept after the transfer ends so
     * the sheet can show one honest row per document instead of one guessed row.
     */
    val uploadFiles: List<PickedFile> = emptyList(),
    /** 1-based position within [uploadFiles] of the file now transferring; 0 when nothing is. */
    val uploadIndex: Int = 0,
    /**
     * Names from [uploadFiles] that did **not** become a job — refused by the server, held back for
     * its size, or never attempted because the send stopped.
     *
     * Kept as names because that is the only identity the queue can be checked against: the server
     * titles a job from the file, so `uploadFiles` minus this list, intersected with the queue, is
     * the whole truth the file card needs.
     */
    val uploadNotSent: List<String> = emptyList(),
    /** The file [failure] is about, when the failure came from a multi-file send. */
    val uploadFailedFile: PickedFile? = null,
    val failure: PharosFailure? = null,
    val notice: String? = null,
    /** What the snackbar offers next, if anything. `View` after an upload, `Retry` after a refusal. */
    val noticeAction: SnackAction? = null,
    /**
     * Locations of the jobs the user has selected, in [PrintJob.location] terms.
     *
     * This used to be a `remember` inside the queue screen, which is fine while the queue is the only
     * screen that cares. Selection now outlives it — Confirm and Result both read it, and the
     * bottom bar has to survive a trip to the printer picker — so it belongs with the data it
     * selects. Locations, not ids: they are what every bulk endpoint takes, and they are the only
     * stable key this API has.
     */
    val selection: Set<String> = emptySet(),
    /**
     * The last thing the user is looking at came from the server, but the most recent attempt to
     * confirm it failed. The queue then says so and shows an age, because a stale balance read as
     * live is how a student finds out at the printer that they are actually in arrears.
     */
    val stale: Boolean = false,
    /** Statement rows from `GET {UserUri}/transactions`, loaded on demand by the Account screen. */
    val transactions: List<Transaction> = emptyList(),
    val loadingTransactions: Boolean = false,
    /** Per-job outcome of the release that just happened, for the Result screen. */
    val outcome: ReleaseOutcome? = null,
) {
    val signedIn: Boolean get() = user != null

    /** `2 of 4` while a multi-file send is in flight, null for a single file or when idle. */
    val uploadPosition: String? get() = batchPosition(uploadIndex, uploadFiles.size)

    /** The selected jobs, in queue order. Everything downstream of a selection reads it through here. */
    val chosen: List<PrintJob> get() = jobs.filter { it.location in selection }

    /** Jobs the queue is currently offering: pending ones, unless released ones are being shown. */
    fun pendingJobs(showReleased: Boolean): List<PrintJob> =
        jobs.filter { if (showReleased) true else it.pending }

    /**
     * The headline balance. `Balance` is the server's own total across purses; deployments that
     * report only the per-purse list get the sum, which is what their own web UI shows.
     */
    val balanceText: String? get() = user?.balance?.let { b ->
        capabilities?.formats?.money(b.amount ?: b.total)
    }

    /** Per-funding-source breakdown, only interesting when the server reported more than one. */
    val purseBreakdown: List<Purse>
        get() = user?.balance?.purses.orEmpty().let { if (it.size > 1) it else emptyList() }

    val canPrint: Boolean get() = capabilities?.canUpload == true

    /** How the next release would be paid for, in words the campus would recognise. */
    val fundingLabel: String
        get() = costCenter?.takeIf { it.isNotBlank() } ?: "My own balance"
}

/** What a snackbar offers besides dismissing itself. */
enum class SnackAction { ViewQueue, RetryRelease }

/**
 * What a release actually did, per job.
 *
 * The vendor's web client reports release results one job at a time (`Responses[]`, each with its own
 * `Status`), and the reasons are the server's — `This document expired and is no longer held.` is not
 * something the client could invent. So the clone keeps the server's sentence next to the job it
 * applies to and shows both, rather than a count.
 *
 * `balanceBefore` / `balanceAfter` are kept because the money is the point of the screen: a student
 * who was told "released" and sees an unchanged balance needs to know whether the department paid or
 * nothing was charged at all.
 */
data class ReleaseOutcome(
    val printer: String,
    val fundingIntent: String,
    val requested: Int,
    val released: List<String>,
    val refused: List<RefusedJob>,
    val balanceBefore: String?,
    val balanceAfter: String?,
    /**
     * Document names for [released], by location, captured *before* the call.
     *
     * The receipt cannot look them up afterwards: a released job leaves the pending queue, so by
     * the time the result screen draws, `state.jobs` no longer holds it and the row falls back to
     * the location, which on this server is a 65-character opaque id. A refusal never had this
     * problem because [RefusedJob] already carries its name.
     */
    val releasedNames: Map<String, String> = emptyMap(),
    /** Set when the call never got an answer at all, which is different from every job being refused. */
    val transport: PharosFailure? = null,
) {
    val allReleased: Boolean get() = transport == null && refused.isEmpty() && released.isNotEmpty()
    val releasedText: String
        get() = when {
            transport != null -> "No answer from the server"
            released.isEmpty() && refused.isNotEmpty() -> "No jobs released"
            else -> "${released.size} of $requested job${if (requested == 1) "" else "s"} released"
        }
}

/** One job a release did not move, with the server's own reason. */
data class RefusedJob(val name: String, val reason: String?, val status: Int?)

/**
 * The answer to `POST /printjobs/cost`, already qualified by what it was priced *against*.
 *
 * The funding source is part of the result rather than something the dialog remembers, because the
 * number is meaningless without it: the same five pages are $0.50 on a student's own purse and $0.00
 * on a department that has free printing.
 */
data class CostPreview(
    val totalText: String,
    val fundingLabel: String,
    val perJob: List<String> = emptyList(),
    val failed: Boolean = false,
    /**
     * The server answered, and answered `-1`: it declined to price this selection. The stock web
     * client will not release a job in this state (`JobRelease_JobsFailedCosting`), so the clone
     * warns instead of sending the student to a printer that will refuse them.
     */
    val blocked: Boolean = false,
    /**
     * Why there is no price, in the server's own words when it had any.
     *
     * Distinguished from [failed] on purpose: "this server refused to price that document, and said
     * it is still being processed" is retryable and belongs next to the price, whereas a transport
     * failure belongs in the normal failure slot. GMU says `JobActionNotAllowedStillProcessing` for
     * every job uploaded in the last few seconds, which is the common case, not the exotic one.
     */
    val reason: String? = null,
)

/**
 * The only mutable object in the app, and the only place that talks to [dev.ahnafnafee.masonprint.data.net.PharosClient].
 *
 * It is app-scoped rather than a `ViewModel` because the sequence it owns — probe → sign in →
 * load jobs → upload → refresh — must survive a rotation and, more importantly, must survive
 * *leaving the screen an upload was started from*. The stock app attached this state to the
 * Activity and lost the upload result on rotation, which is one of the two mechanisms behind
 * "it said it uploaded and there is nothing in the queue" (docs/FINDINGS.md §14 U3).
 */
class Session(private val graph: AppGraph) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        AppState(
            host = graph.prefs.host,
            // The last account's remembered funding source, so a warm start does not flash "my own
            // balance" at someone who always charges a department. Re-applied for real at sign-in,
            // when the server has said who this actually is.
            costCenter = graph.prefs.lastAccount?.let { graph.prefs.costCenterFor(it) },
            // Codes this account has charged to before, so the funding picker offers them at once.
            savedCostCenters = graph.prefs.lastAccount?.let { graph.prefs.savedCostCentersFor(it) } ?: emptyList(),
            // Printers are stable; show the cached list at once and let a live load supersede it.
            devices = cachedDevices(),
        ),
    )
    val state: StateFlow<AppState> = _state

    /** The printer list cached on disk, or empty if none is stored or it will not decode. */
    private fun cachedDevices(): List<Device> =
        graph.prefs.cachedDevicesJson?.let {
            runCatching { PharosJson.decodeFromString(ListSerializer(Device.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private var settings: SettingsDocument? = null
    private var savedJobsPage: Page<PrintJob>? = null

    /** The background look that waits for a document to be priced — see [watchAnalysis]. */
    private var analysisWatcher: Job? = null

    init {
        boot()
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Restore whatever can be restored without asking a question.
     *
     * Cookies come from encrypted storage first: Pharos' session cookie outlives the app process,
     * so a warm start can skip `/logon` entirely. The stock app could not do this — it kept
     * credentials in plaintext prefs and always re-authenticated, and it never called the server's
     * `/logout` at all, so the cookie it abandoned was still valid (docs/FINDINGS.md §10 S4).
     */
    private fun boot() {
        scope.launch {
            graph.cookieJar.restore()
            val host = graph.prefs.host
            val creds = graph.secrets.storedCredentials()
            if (host.isBlank()) {
                _state.update { it.copy(phase = Phase.Connect) }
                return@launch
            }
            /**
             * A remembered host has to become a live target even when there are no credentials to
             * try. This used to return *before* `useTarget`, which left `graph.target` null, and
             * `signIn` starts with `graph.target ?: return` — so a launch into "host remembered,
             * password not remembered" drew a sign-in form whose button silently did nothing (found
             * on device: the tap landed on the button and no request ever left the process).
             */
            val target = PharosTarget.parse(host)
                ?: run {
                    _state.update { it.copy(phase = Phase.Connect) }
                    return@launch
                }
            graph.useTarget(target)
            /*
             * The offline snapshot goes on screen before anything is asked of the network: a cold
             * start with no signal then shows the held jobs and the cached balance with their age
             * on them, instead of a spinner. It is marked `stale`, so the queue's offline card and
             * the grey hero say exactly how much to trust it — the same rendering a failed refresh
             * produces, never a modal (Spec §2.0.7 `offline_stale`).
             */
            val restored = graph.snapshot.loadAsync()
            if (restored != null && restored.snapshot.host == target.displayHost) {
                settings = restored.snapshot.settingsBody?.let {
                    runCatching { SettingsDocument(it.toJsonObject()) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        jobs = restored.jobsPage?.items.orEmpty(),
                        jobsCount = restored.jobsPage?.count,
                        nextJobsPage = null,
                        loadedAt = restored.snapshot.savedAt,
                        user = restored.user ?: it.user,
                        capabilities = restored.capabilities ?: it.capabilities,
                        apiVersion = restored.snapshot.apiVersion ?: it.apiVersion,
                        stale = true,
                    )
                }
            }
            if (creds == null) {
                _state.update { it.copy(phase = Phase.SignedOut, host = target.displayHost) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.Working, busy = "Restoring session") }
            signIn(target, creds, announce = false)
        }
    }

    // ------------------------------------------------------------------ connect

    /**
     * Point the app at a server. Uses the unauthenticated `GET /PharosAPI/settings` probe, which
     * answers both "is Pharos here" and "which version" before a password is typed — the two
     * questions the stock app only answered after a failed login.
     */
    fun connect(input: String) {
        val target = PharosTarget.parse(input.trim())
        _state.update {
            it.copy(
                failure = if (target == null) PharosFailure.NotAPharosServer("\"$input\" is not a hostname or URL") else it.failure,
                busy = if (target != null) "Contacting ${target.displayHost}" else null,
            )
        }
        val parsed = target ?: return
        graph.useTarget(parsed)
        scope.launch {
            when (val result = graph.api.probe(parsed)) {
                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
                is ApiResult.Ok -> {
                    graph.api.apiVersion = result.value.apiVersion
                    val doc = graph.api.settings(parsed).getOrNull()
                    settings = doc
                    _state.update {
                        it.copy(
                            phase = Phase.SignedOut,
                            host = parsed.displayHost,
                            apiVersion = result.value.apiVersion ?: graph.prefs.lastApiVersion,
                            capabilities = doc?.capabilities(result.value.apiVersion, null),
                            busy = null,
                            failure = null,
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sign in

    fun signIn(username: String, password: String, remember: Boolean) {
        // A tap on "Sign in" must never be a silent no-op. If nothing installed a target yet — a
        // host restored from prefs, or a deep link that skipped Connect — derive one from the host
        // shown on screen instead of returning without a request or a message.
        val target = graph.target
            ?: _state.value.host.takeIf { it.isNotBlank() }
                ?.let { PharosTarget.parse(it) }
                ?.also { graph.useTarget(it) }
        if (target == null) {
            MpLog.warn("session", "sign-in with no usable target (host=\"${_state.value.host}\")")
            _state.update { it.copy(phase = Phase.Connect) }
            return
        }
        signIn(target, Credentials(username, password, remember), announce = true)
    }

    private fun signIn(target: PharosTarget, creds: Credentials, announce: Boolean) {
        scope.launch {
            _state.update { it.copy(phase = Phase.Working, busy = "Signing in", failure = null) }
            graph.api.credentials = creds
            when (val result = graph.api.logon(target, creds)) {
                is ApiResult.Err -> {
                    graph.api.credentials = null
                    /*
                     * A silent restore that failed because the network is gone is not a sign-out:
                     * the cookie may still be perfectly alive. With a cached queue on screen the
                     * honest state is Ready + stale — the offline card and the aged hero — so a
                     * student underground still sees their held jobs. Only an answer from the
                     * server (bad password, revoked session) sends this to the sign-in form.
                     */
                    val offline = result.failure is PharosFailure.Offline ||
                        (result.failure is PharosFailure.Timeout && !announce)
                    if (!announce && offline && _state.value.jobs.isNotEmpty()) {
                        _state.update { it.copy(phase = Phase.Ready, busy = null, failure = null, stale = true) }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            // Both the explicit "sign in" tap and a failed silent restore land on
                            // the same form: the restore path must not throw the user back to the
                            // host field, because the host is fine and only the cookie is dead.
                            phase = Phase.SignedOut,
                            busy = null,
                            failure = result.failure,
                        )
                    }
                    /**
                     * The CAS fallback under the error card is gated on `capabilities.sso`, and the
                     * restore path reaches this line without ever having probed (a persisted host
                     * goes straight to `SignedOut`), so capabilities would be null and a GMU user
                     * whose session expired would never see the button that gets them to
                     * `login.gmu.edu`. `/settings` is anonymous on every deployment observed, so
                     * this costs one unauthenticated GET and only happens after a rejection.
                     */
                    if (settings == null) {
                        val doc = graph.api.settings(target).getOrNull()
                        if (doc != null) {
                            settings = doc
                            _state.update {
                                it.copy(
                                    apiVersion = graph.api.apiVersion ?: target.headerApiVersion ?: it.apiVersion,
                                    capabilities = doc.capabilities(
                                        graph.api.apiVersion ?: target.headerApiVersion,
                                        null,
                                    ),
                                )
                            }
                        }
                    }
                }

                is ApiResult.Ok -> {
                    if (creds.rememberMe) graph.secrets.saveCredentials(creds)
                    graph.prefs.lastUserName = creds.username
                    val doc = settings ?: graph.api.settings(target).getOrNull()
                    settings = doc
                    /*
                     * Restore *this* account's saved funding source. Sign-out drops the in-memory
                     * state, so without this a student who always charges a department got their own
                     * balance back every time they logged in again.
                     */
                    val account = result.value.accountKey
                    graph.prefs.lastAccount = account
                    val remembered = graph.prefs.costCenterFor(account)
                    _state.update {
                        it.copy(
                            phase = Phase.Ready,
                            user = result.value,
                            costCenter = remembered,
                            favouriteDevices = graph.prefs.favouriteDevicesFor(account),
                            recentDevices = graph.prefs.recentDevicesFor(account),
                            savedCostCenters = graph.prefs.savedCostCentersFor(account),
                            apiVersion = graph.api.apiVersion ?: it.apiVersion,
                            capabilities = doc?.capabilities(graph.api.apiVersion, result.value),
                            busy = null,
                            failure = null,
                            notice = if (announce) null else "Signed in as ${result.value.preferredName.ifBlank { creds.username }}",
                        )
                    }
                    refresh(jobsFirst = true)
                }
            }
        }
    }

    /**
     * Refresh the balance and the queue. `jobsFirst` renders the list before the balance so the
     * thing the user came for appears first — the stock app waited for every call in its sync
     * sequence before drawing anything (docs/FINDINGS.md §8.3).
     */
    fun refresh(jobsFirst: Boolean = false) {
        val target = graph.target ?: return
        // A deliberate look at the queue takes over from the background one. The two must not race
        // for the same page, and this path starts the next background look itself when it lands.
        analysisWatcher?.cancel()
        scope.launch {
            _state.update { it.copy(busy = "Refreshing") }
            val jobs = graph.api.printJobs(target, skip = 0)
            when (jobs) {
                is ApiResult.Ok -> applyJobsPage(jobs.value)

                is ApiResult.Err -> _state.update {
                    /*
                     * If there is already a queue on screen, this is a refresh that failed, not a
                     * first load: keep the list, mark it stale, and let the queue say "cached N
                     * minutes ago". Dropping the jobs to an error screen would be the worst possible
                     * answer for someone standing at a printer with a full list they can still read.
                     */
                    val keepList = it.jobs.isNotEmpty()
                    it.copy(
                        failure = if (keepList) null else jobs.failure,
                        stale = keepList,
                        busy = if (keepList) null else it.busy,
                    )
                }
            }
            val user = graph.api.refreshUser(target, includeBalance = true)
            val doc = settings
            _state.update {
                it.copy(
                    user = (user as? ApiResult.Ok)?.value ?: it.user,
                    capabilities = doc?.capabilities(graph.api.apiVersion, (user as? ApiResult.Ok)?.value ?: it.user)
                        ?: it.capabilities,
                    busy = null,
                    stale = it.stale || (user is ApiResult.Err && it.user != null),
                )
            }
            /*
             * A refresh that produced both a queue page and a user is the moment to persist the
             * offline snapshot. Wire bodies, re-parsed on boot by the same code that parsed them
             * here — see [SnapshotCache]. Best-effort by design: a cache write that fails must
             * never turn a successful refresh into a failure.
             */
            val jobsBody = (jobs as? ApiResult.Ok)?.body
            val userBody = (user as? ApiResult.Ok)?.body
            if (jobsBody != null && userBody != null) {
                graph.snapshot.saveAsync(
                    SnapshotCache.Snapshot(
                        savedAt = System.currentTimeMillis(),
                        host = target.displayHost,
                        apiVersion = graph.api.apiVersion ?: _state.value.apiVersion,
                        jobsBody = jobsBody,
                        userBody = userBody,
                        settingsBody = settings?.raw?.encode(),
                    ),
                )
            }
        }
    }

    /**
     * Put one freshly-read first page of the queue on screen.
     *
     * Split out of [refresh] because two callers need it: the deliberate refresh, and the background
     * look that waits for a document to be priced. Both must produce exactly the same state, or a
     * queue would change character depending on who asked for it.
     */
    private fun applyJobsPage(page: Page<PrintJob>) {
        savedJobsPage = page
        _state.update {
            it.copy(
                jobs = page.items,
                jobsCount = page.count,
                nextJobsPage = if (page.hasMoreAfter(page.items.size)) page else null,
                loadedAt = System.currentTimeMillis(),
                failure = it.failure,
                stale = false,
            )
        }
        watchAnalysis()
    }

    /**
     * Keep looking at the queue while the server is still working on a document, then stop.
     *
     * Pharos pushes nothing to a client: `/printjobs` is polled, and the SignalR channel the web
     * portal uses is not something this API hands a token to (§4.1 item 9). A document GMU has
     * accepted is *not* priced yet — conversion, page counting and costing run server-side and
     * complete in about six seconds — so the single queue read that fires the instant an upload
     * returns always lands mid-analysis, and its row says "still counting". Before this, nothing
     * asked again: the price appeared only after a restart or a manual pull, which reads to a
     * student as the app having failed the first time (defect 19, reported from a Pixel).
     *
     * Bounded, because some jobs never become priced. GMU answers `PageCounting: Failed` for a
     * document it cannot parse and `-1` for a cost centre it rejects, and 60 more requests will not
     * change either answer; [stillAwaitsCosting] excludes the first and the budget covers the
     * second. When the budget runs out the row keeps its honest words and the refresh arrow in the
     * top bar remains the unbounded option.
     */
    private fun watchAnalysis() {
        if (analysisWatcher?.isActive == true) return
        analysisWatcher = scope.launch {
            var spent = 0L
            while (isActive && spent < AnalysisPollBudgetMs && _state.value.jobs.any { stillAwaitsCosting(it) }) {
                delay(AnalysisPollMs)
                spent += AnalysisPollMs
                val target = graph.target ?: return@launch
                // A manual refresh, an upload, or a release owns the screen: let it land. It calls
                // applyJobsPage itself, which is what will start the next look.
                if (_state.value.busy != null) continue
                when (val jobs = graph.api.printJobs(target, skip = 0)) {
                    is ApiResult.Ok -> applyJobsPage(jobs.value)
                    // Quiet on purpose: a background look that fails must not trade a readable
                    // queue for an error screen. The queue marks itself stale and says so.
                    is ApiResult.Err -> _state.update { it.copy(stale = it.jobs.isNotEmpty()) }
                }
            }
        }
    }


    fun loadMoreJobs() {
        val target = graph.target ?: return
        val page = savedJobsPage?.takeIf { it.hasMore } ?: return
        scope.launch {
            _state.update { it.copy(busy = "Loading more jobs") }
            val more = graph.api.nextPage(target, page)
            if (more is ApiResult.Ok) {
                savedJobsPage = more.value
                val merged = _state.value.jobs + more.value.items
                _state.update {
                    it.copy(
                        jobs = merged,
                        nextJobsPage = if (more.value.hasMoreAfter(merged.size)) more.value else null,
                        jobsCount = more.value.count ?: it.jobsCount,
                        busy = null,
                    )
                }
            } else {
                _state.update { it.copy(busy = null, failure = (more as ApiResult.Err).failure) }
            }
        }
    }

    // ------------------------------------------------------------------ upload

    /**
     * Queue a document.
     *
     * Everything checkable locally is checked locally *before* the bytes move — capability, size,
     * extension — because the server's answer to each is a 403/413/415 that arrives after the user
     * has watched a progress bar for the whole upload. The server's answer still wins
     * ([PharosFailure.UploadDenied] and friends are rendered when they arrive); this only avoids
     * paying for the round trip.
     */
    fun upload(source: UploadSource) = uploadAll(listOf(source))

    /**
     * Queue one or more documents.
     *
     * Everything checkable locally is checked locally *before* the bytes move — capability, size,
     * extension — because the server's answer to each is a 403/413/415 that arrives after the user
     * has watched a progress bar for the whole upload. The server's answer still wins
     * ([PharosFailure.UploadDenied] and friends are rendered when they arrive); this only avoids
     * paying for the round trip.
     *
     * A multi-file pick is sent **one file at a time, in order**, because that is what the wire is:
     * `POST {UserUri}/printjobs` takes one `MetaData`+`Content` pair, and Pharos has no batch upload
     * (docs/PHAROS-API-FINDINGS.md §2 — the only bulk endpoints are `cost`, `PATCH` and `DELETE`, and
     * all three take jobs that already exist). Sequential is also the only ordering that keeps the
     * progress bar honest. A file the server refuses does not stop the others, unless the failure is
     * about the connection or the account — see [stopsBatch].
     */
    fun uploadAll(sources: List<UploadSource>) {
        val target = graph.target ?: return
        if (sources.isEmpty()) return
        val caps = _state.value.capabilities
        val limit = caps?.maxUploadBytes ?: settings?.maxUploadBytes

        if (caps != null && !caps.canUpload) {
            // Local gate, so the user learns before 40 MB has moved. The server still has the last
            // word — 403 arrives as PharosFailure.UploadDenied and is rendered the same way.
            _state.update { it.copy(notice = caps.uploadBlockReason ?: "This account cannot upload documents") }
            return
        }

        val files = sources.map(PickedFile::of)
        val plan = planUpload(sources, limit)
        if (plan.nothingToSend) {
            _state.update {
                it.copy(
                    uploadFiles = files,
                    uploadIndex = 0,
                    uploadNotSent = files.map { f -> f.name },
                    uploadFailedFile = files.singleOrNull(),
                    failure = PharosFailure.TooLarge(limit, oversizeSentence(plan.overLimit, limit), locallyGated = true),
                )
            }
            return
        }
        plan.overLimit.forEach {
            MpLog.warn("upload", "${it.fileName} is over the published limit and will not be sent")
        }
        sources.filter { MimeTypes.forExtension(it.extension) == null }
            // Not fatal: the resolver may have supplied a type the vendor table lacks. Say so and
            // still try, because the server is the authority.
            .forEach { MpLog.warn("upload", "extension .${it.extension} is not in the vendor allowlist") }

        val sending = plan.send
        scope.launch {
            val finishing = settings?.defaultFinishing() ?: FinishingPayload.from(null)
            val body = PrintRequests.metaData(finishing, _state.value.selectedDevice?.location)
            val sent = mutableListOf<String>()
            val refused = mutableListOf<String>()
            var lastFailure: PharosFailure? = null
            var failedFile: PickedFile? = null
            var stopIndex = -1

            for ((position, source) in sending.withIndex()) {
                val progress = UploadProgress()
                _state.update {
                    it.copy(
                        upload = progress,
                        uploadFraction = 0f,
                        uploadFiles = files,
                        uploadIndex = position + 1,
                        // A refusal about file 2 of 4 must not still be on screen while file 3 goes up.
                        uploadNotSent = emptyList(),
                        uploadFailedFile = null,
                        busy = sendingLabel(source.fileName, position + 1, sending.size),
                        failure = null,
                    )
                }
                when (
                    val result = graph.api.upload(target, source, body) { fraction ->
                        progress.set(fraction)
                        _state.update { it.copy(uploadFraction = fraction) }
                    }
                ) {
                    is ApiResult.Err -> {
                        refused += source.fileName
                        lastFailure = result.failure
                        failedFile = PickedFile.of(source)
                        if (result.failure.stopsBatch) {
                            // The reasons that stop a batch (no network, session gone, this host is
                            // not Pharos at all) will not fix themselves between file 2 and file 3, so
                            // stop rather than fail the rest of the pick the same way.
                            stopIndex = position
                            break
                        }
                    }

                    is ApiResult.Ok -> sent += source.fileName
                }
            }

            val notAttempted = if (stopIndex >= 0) sending.drop(stopIndex + 1).map { it.fileName } else emptyList()
            val skippedForSize = plan.overLimit.map { it.fileName }
            val summary = batchSummary(sent, refused, skippedForSize, notAttempted)
            /*
             * Name the files, keep the server's own words on screen, and re-read the queue once at
             * the end. A status code alone is not enough to act on: when GMU answered a real upload
             * with `405` (wrong URL — see [dev.ahnafnafee.masonprint.data.net.uploadUrl]), the queue still said
             * "0 on the server", and the only way a student could tell that nothing had arrived was
             * to pull to refresh themselves. `refresh` keeps the failure ([refresh] deliberately
             * preserves it) and replaces the remembered queue with whatever the server actually
             * holds. One refresh for the whole batch, not one per file: each is a full page of jobs.
             */
            _state.update {
                it.copy(
                    upload = null,
                    uploadIndex = 0,
                    uploadNotSent = refused + skippedForSize + notAttempted,
                    // Only name a file for the failure card when exactly one file failed; with two
                    // the card would quote the last refusal while pretending it covered both.
                    uploadFailedFile = failedFile.takeIf { f -> refused.size == 1 },
                    busy = if (refused.isEmpty()) "Refreshing queue" else null,
                    failure = if (refused.isEmpty()) null else lastFailure,
                    notice = summary,
                )
            }
            refresh()
        }
    }

    // ------------------------------------------------------------------ devices & release

    fun loadDevices() {
        val target = graph.target ?: return
        scope.launch {
            _state.update { it.copy(busy = "Finding printers") }
            /*
             * Every page, not just the first. GMU answers `Count: 302` and serves 200 at a time, so a
             * single call silently lost 102 stations — and a student whose printer was one of them
             * could not find it in the list at all. The list is read once and cached, so paging it
             * out in full here is cheaper than making the screen page on scroll.
             */
            val pageSize = 200
            val cap = 2_000
            val all = mutableListOf<Device>()
            while (true) {
                when (val result = graph.api.devices(target, skip = all.size, pageSize = pageSize)) {
                    is ApiResult.Err -> {
                        // A refusal partway keeps what already arrived: a partial list still finds
                        // most printers, where an empty one finds none.
                        if (all.isEmpty()) {
                            _state.update { it.copy(busy = null, failure = result.failure) }
                            return@launch
                        }
                        break
                    }

                    is ApiResult.Ok -> {
                        val page = result.value
                        all += page.items
                        if (page.items.isEmpty() || !page.hasMoreAfter(all.size) || all.size >= cap) break
                    }
                }
            }
            graph.prefs.cachedDevicesJson = runCatching {
                PharosJson.encodeToString(ListSerializer(Device.serializer()), all)
            }.getOrNull()
            _state.update { it.copy(devices = all, busy = null, failure = null) }
        }
    }

    /**
     * The document behind a job, downloaded for preview and kept in the cache directory.
     *
     * Cached by job location: a job's content cannot change once it is on the server, so a second
     * look costs nothing. The cache directory is the right home for it — the OS may reclaim it, and
     * a queued document is always re-downloadable. Returns null and publishes the failure, so the
     * screen renders the same error wording as everything else.
     */
    suspend fun documentFor(job: PrintJob): java.io.File? {
        val target = graph.target ?: return null
        val dir = java.io.File(graph.app.cacheDir, "preview").apply { mkdirs() }
        val dest = java.io.File(dir, "${job.location.hashCode()}.pdf")
        if (dest.exists() && dest.length() > 0L) return dest
        return when (val result = graph.api.jobContent(target, job.location, dest)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> {
                runCatching { dest.delete() }
                _state.update { it.copy(failure = result.failure) }
                null
            }
        }
    }

    /** A scanned/pasted QR payload resolves to a device before it can release anything. */
    fun resolveDeviceToken(token: String) {
        val target = graph.target ?: return
        scope.launch {
            _state.update { it.copy(busy = "Reading printer code") }
            when (val result = graph.api.deviceById(target, token)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(selectedDevice = result.value, busy = null, notice = "Release at ${result.value.label}") }
                    releaseSelected(listOfNotNull(_state.value.jobs.firstOrNull { it.pending }))
                }

                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
            }
        }
    }

    fun selectDevice(device: Device?) = _state.update { it.copy(selectedDevice = device) }

    /**
     * Choose what pays for the next release. `null` means the user's own balance.
     *
     * Not validated against `User.CostCenters`: a department code the bursar issued yesterday is
     * not in the list the server sends today, and the server is the authority anyway — a wrong code
     * comes back as a refusal naming the code, which [dev.ahnafnafee.masonprint.ui.headline] surfaces.
     */
    fun setCostCenter(code: String?) {
        val trimmed = code?.trim()?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(costCenter = trimmed) }
        // Remembered against the signed-in account, so it comes back on the next sign-in.
        val account = _state.value.user?.accountKey ?: graph.prefs.lastAccount
        if (account != null) scope.launch {
            graph.prefs.setCostCenterFor(account, trimmed)
            /*
             * Charging to a code is also a vote to use it again. The code is saved with whatever
             * description any loaded list knows, so the next "Pay with" offers it as a tap instead
             * of a typing exercise — the server's directory cannot do this, because it does not
             * exist (a search answers with the codes already on the account).
             */
            if (trimmed != null) {
                graph.prefs.noteCostCenterUsed(account, trimmed, knownCostCenter(trimmed)?.description)
                _state.update { it.copy(savedCostCenters = graph.prefs.savedCostCentersFor(account)) }
            }
        }
    }

    /**
     * Removes a code from the saved list. The active funding source is untouched: forgetting a
     * shortcut is not switching funding, and the code stays chargeable by typing it.
     */
    fun unsaveCostCenter(code: String) {
        val account = _state.value.user?.accountKey ?: graph.prefs.lastAccount ?: return
        val next = _state.value.savedCostCenters.filterNot { it.code.equals(code, ignoreCase = true) }
        if (next.size == _state.value.savedCostCenters.size) return
        _state.update { it.copy(savedCostCenters = next) }
        scope.launch { graph.prefs.setSavedCostCentersFor(account, next) }
    }

    /** The server's own record of a code, from any list this session has loaded, or null. */
    private fun knownCostCenter(code: String): CostCenter? {
        val snapshot = _state.value
        return snapshot.capabilities?.usableCostCenters?.firstOrNull { it.code.equals(code, true) }
            ?: snapshot.costCenterResults.firstOrNull { it.code.equals(code, true) }
            ?: snapshot.user?.costCenters?.firstOrNull { it.code.equals(code, true) }
    }

    /**
     * `GET {UserUri}/costcenters?search=`. The web client pages these six at a time; the clone asks
     * for a dozen because someone typing a department prefix wants to scan the list, not page it.
     *
     * A failure here is deliberately *not* surfaced as a `failure`: on this API an unknown path
     * answers 401 rather than 404, so a deployment with cost-centre search switched off would look
     * like a broken sign-in. The dialog keeps the free-text field for exactly that case.
     */
    fun searchCostCenters(query: String) {
        val target = graph.target ?: return
        if (!_state.value.signedIn) {
            _state.update { it.copy(notice = "Sign in first.") }
            return
        }
        scope.launch {
            _state.update { it.copy(searchingCostCenters = true) }
            when (val result = graph.api.costCenters(target, query, pageSize = 12)) {
                is ApiResult.Ok -> _state.update { state ->
                    state.copy(
                        searchingCostCenters = false,
                        costCenterResults = (state.costCenterResults + result.value)
                            .distinctBy { it.code }
                            .filter { it.code.isNotBlank() }
                            .take(40),
                    )
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        searchingCostCenters = false,
                        notice = "This server did not answer the cost-centre search. You can still type the code.",
                    )
                }
            }
        }
    }

    /**
     * Release [jobs] at the chosen printer and record what happened **per job**.
     *
     * Two things learned the hard way shape this. The endpoint answers 200 with a per-job array, so a
     * refusal is *inside* the success — a release that moved nothing looks like one that moved
     * everything unless each row is read (docs/PHAROS-API-FINDINGS.md §2c). And `CostCenterCode` is a
     * request, not a receipt: GMU has echoed an empty code back from a 200 write, so the Result
     * screen says what the release was *asked* to charge, never "Charged to X".
     */
    /**
     * What this release will actually be billed to, for the receipt.
     *
     * Not the same as [AppState.fundingLabel], which reports the *session's* choice. When the user
     * has not overridden anything, the release sends no funding key and the server bills whatever
     * the job itself carries, so a job uploaded against a department is charged to that department
     * while the session still says "my own balance". Reading the jobs is the only way the receipt
     * can name the account the money came from. Mixed selections say so rather than picking one.
     */
    private fun fundingIntentFor(jobs: List<PrintJob>, chosen: String?): String {
        chosen?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val stored = jobs.mapNotNull { it.costCenterCode?.trim()?.takeIf { code -> code.isNotEmpty() } }.distinct()
        return when (stored.size) {
            0 -> "My own balance"
            1 -> stored.single()
            else -> "${stored.size} department accounts"
        }
    }

    /**
     * Star or unstar a printer for the signed-in account.
     *
     * Local, and deliberately so: Pharos has no notion of a favourite, and inventing a server field
     * for one would be a write this app cannot verify. A star is a preference about a person, not a
     * fact about a device.
     */
    fun toggleFavouriteDevice(device: Device) {
        val account = _state.value.user?.accountKey ?: graph.prefs.lastAccount ?: return
        val location = device.location.takeIf { it.isNotBlank() } ?: return
        val next = _state.value.favouriteDevices.let {
            if (location in it) it - location else it + location
        }
        graph.prefs.setFavouriteDevicesFor(account, next)
        _state.update { it.copy(favouriteDevices = next) }
    }

    fun releaseSelected(jobs: List<PrintJob>) {
        val target = graph.target ?: return
        val device = _state.value.selectedDevice
        val costCenter = _state.value.costCenter
        if (jobs.isEmpty()) return
        val names = jobs.associate { it.location to (it.name ?: it.location.substringAfterLast('/')) }
        val before = _state.value.balanceText
        val intent = fundingIntentFor(jobs, costCenter)
        val printer = device?.label ?: "the selected printer"
        scope.launch {
            _state.update { it.copy(busy = "Releasing ${jobs.size} job(s)") }
            val body = PrintRequests.release(jobs, device?.location, _state.value.user?.cardId, costCenterCode = costCenter)
            when (val result = graph.api.release(target, body)) {
                is ApiResult.Err -> {
                    val failure = result.failure
                    _state.update {
                        it.copy(
                            busy = null,
                            outcome = ReleaseOutcome(
                                printer = printer,
                                fundingIntent = intent,
                                requested = jobs.size,
                                released = emptyList(),
                                // `reason = null` on purpose: the Result screen quotes
                                // `transport.headline()` for all of these, so the wording stays the
                                // one the failure-text tests already pin.
                                refused = jobs.map { j -> RefusedJob(names[j.location] ?: j.location, null, null) },
                                balanceBefore = before,
                                balanceAfter = before,
                                transport = failure,
                            ),
                        )
                    }
                }

                is ApiResult.Ok -> {
                    val op = result.value
                    val refused = op.failures.map { row ->
                        val loc = row.strIn("Location", "JobLocation")
                        RefusedJob(
                            name = names[loc] ?: loc?.substringAfterLast('/').orEmpty().ifBlank { "A job" },
                            reason = cleanSentence(row.strIn("UserMessage", "Message")?.htmlUnescaped()),
                            status = (row.dbl("Status") ?: row.dbl("StatusCode"))?.toInt(),
                        )
                    }
                    val moved = op.actedLocations.filter { it !in op.failures.mapNotNull { r -> r.strIn("Location", "JobLocation") } }
                    val after = op.updatedUser?.let { u ->
                        _state.value.capabilities?.formats?.money(u.balance?.amount ?: u.balance?.total)
                    } ?: before
                    /*
                     * Remember the machine, but only when something actually came out of it. A
                     * refused release is not a visit, and recording one would put a printer the
                     * student never successfully used at the top of their list.
                     */
                    val account = _state.value.user?.accountKey ?: graph.prefs.lastAccount
                    if (moved.isNotEmpty() && account != null && device != null) {
                        graph.prefs.noteDeviceUsed(account, device.location)
                    }
                    _state.update {
                        it.copy(
                            busy = null,
                            user = op.updatedUser ?: it.user,
                            selection = emptySet(),
                            recentDevices = account?.let { a -> graph.prefs.recentDevicesFor(a) } ?: it.recentDevices,
                            outcome = ReleaseOutcome(
                                printer = printer,
                                fundingIntent = intent,
                                requested = jobs.size,
                                released = moved,
                                releasedNames = names,
                                refused = refused,
                                balanceBefore = before,
                                balanceAfter = after,
                            ),
                            notice = if (refused.isEmpty() && moved.isNotEmpty()) {
                                "Released ${moved.size} job(s) at $printer"
                            } else {
                                null
                            },
                        )
                    }
                    refresh()
                }
            }
        }
    }

    fun clearOutcome() = _state.update { it.copy(outcome = null) }

    fun toggleJob(location: String) = _state.update {
        it.copy(selection = if (location in it.selection) it.selection - location else it.selection + location)
    }

    fun setSelection(locations: Set<String>) = _state.update { it.copy(selection = locations) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Select or deselect every job the queue is currently showing. */
    fun toggleSelectAll(showReleased: Boolean) = _state.update {
        val visible = it.pendingJobs(showReleased).map { j -> j.location }
        val allSelected = visible.isNotEmpty() && visible.all { l -> l in it.selection }
        it.copy(selection = if (allSelected) emptySet() else visible.toSet())
    }

    /** Statement rows for the Account screen. Loaded lazily; the queue does not need them. */
    fun loadTransactions() {
        val target = graph.target ?: return
        if (_state.value.loadingTransactions) return
        scope.launch {
            _state.update { it.copy(loadingTransactions = true) }
            when (val result = graph.api.transactions(target, skip = 0, pageSize = 50)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(loadingTransactions = false, transactions = result.value.items)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(loadingTransactions = false, failure = result.failure)
                }
            }
        }
    }

    /**
     * `PATCH {UserUri}/printjobs/` — change copies, sides, colour, pages-per-side or page size on
     * jobs already sitting in the queue.
     *
     * The server is the authority on whether this took. GMU answers 200 with a bare array of
     * per-job rows and has been observed echoing a value back unchanged after accepting the write
     * (docs/FINDINGS.md), so this reads the rows for refusals and then [refresh]es rather than
     * assuming the request became the truth. The queue redraws from what the server now reports,
     * which is why the dialog can be an editor at all: a change that silently did not stick shows
     * up as the old value on the card a second later instead of as a lie in the UI.
     */
    fun applyFinishing(jobs: List<PrintJob>, options: FinishingOptions) {
        val target = graph.target ?: return
        if (jobs.isEmpty()) return
        scope.launch {
            _state.update { it.copy(busy = "Updating ${jobs.size} job(s)") }
            val body = PrintRequests.update(
                jobs = jobs,
                finishing = FinishingPayload.from(options),
                deviceLocation = _state.value.selectedDevice?.location,
                costCenterCode = _state.value.costCenter,
            )
            when (val result = graph.api.patchJobs(target, body)) {
                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
                is ApiResult.Ok -> {
                    val op = result.value
                    _state.update {
                        it.copy(
                            busy = null,
                            notice = when {
                                op.failures.isNotEmpty() ->
                                    op.failures.firstNotNullOfOrNull { r ->
                                        cleanSentence(r.strIn("UserMessage", "Message")?.htmlUnescaped())
                                    } ?: "${op.failures.size} of ${jobs.size} job(s) were not changed."

                                else -> "Asked the server to change ${jobs.size} job" +
                                    "${if (jobs.size == 1) "" else "s"}. The queue shows what it stored."
                            },
                        )
                    }
                    refresh()
                }
            }
        }
    }

    fun deleteJobs(jobs: List<PrintJob>) {
        val target = graph.target ?: return
        if (jobs.isEmpty()) return
        scope.launch {
            _state.update { it.copy(busy = "Deleting ${jobs.size} job(s)") }
            when (val result = graph.api.deleteJobs(target, PrintRequests.delete(jobs))) {
                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
                is ApiResult.Ok -> {
                    val op = result.value
                    val gone = op.actedLocations.size
                    _state.update {
                        it.copy(
                            busy = null,
                            selection = it.selection - jobs.map { j -> j.location }.toSet(),
                            /**
                             * Counted from the per-job rows, not from `jobs.size`. This endpoint also
                             * answers 200 with a bare array, so a delete the server refused still
                             * looks like a delete unless the rows are read — the redesign's "Deleted
                             * 2 jobs from the server" has to be a claim the server actually made.
                             */
                            notice = when {
                                op.failures.isNotEmpty() ->
                                    op.failures.firstNotNullOfOrNull { r -> cleanSentence(r.strIn("UserMessage", "Message")?.htmlUnescaped()) }
                                        ?: "${op.failures.size} of ${jobs.size} job(s) could not be deleted."

                                gone == 0 -> "The server did not confirm the delete. Pull to refresh."
                                else -> "Deleted $gone job${if (gone == 1) "" else "s"} from the server."
                            },
                            noticeAction = SnackAction.ViewQueue,
                        )
                    }
                    refresh()
                }
            }
        }
    }

    /**
     * `POST /printjobs/cost` — the pre-release price the stock app never showed, asked **with the
     * currently selected funding source**. That qualifier is the whole reason this is one function
     * rather than a helper on the dialog: on a department-funded account the same selection
     * estimates at zero, and a price is only worth showing if it was computed against the thing that
     * will actually be charged. `cost` and `PATCH` share a body on this API (see [PrintRequests]),
     * so the preview and the change that follows cannot disagree.
     */
    fun previewCost(jobs: List<PrintJob>, onResult: (CostPreview) -> Unit) {
        val target = graph.target ?: return
        val funding = _state.value.fundingLabel
        val body = PrintRequests.update(
            jobs,
            jobs.firstOrNull()?.finishing?.let { FinishingPayload.from(it) },
            _state.value.selectedDevice?.location,
            _state.value.costCenter,
        )
        scope.launch {
            when (val result = graph.api.cost(target, body)) {
                is ApiResult.Ok -> {
                    val formats = _state.value.capabilities?.formats
                    val estimate = result.value
                    /*
                     * Prices are read the way the bundle reads them: the costing object is merged
                     * over the job (`var t = e.Costing || e.PrintJob || {}`), and a cost of **-1
                     * means the server would not price this job**, which the bundle treats as a
                     * reason to refuse release. Zero is free and -1 is not zero; conflating them is
                     * how a student discovers a refused release standing at the printer.
                     * docs/FUNDING-MODELS.md §2.9.
                     */
                    val byLocation = jobs.associateBy { it.location }
                    val refusedLocations = estimate.refusals.mapNotNull { it.jobLocation }.toSet()
                    val lines = estimate.lines.mapNotNull { raw ->
                        val location = raw.strIn("Location", "JobLocation")
                        if (location != null && location in refusedLocations) return@mapNotNull null
                        val line = raw.obj("Costing") ?: raw.obj("PrintJob") ?: raw
                        /*
                         * The queue's own title comes before anything derived from the Location. The
                         * fallbacks used to run the other way, so a cost line without a DocumentName
                         * printed the tail of the job Location — a 64-character opaque id — at a
                         * student, next to a price. The id is never a name; if the document cannot be
                         * named, the row says so rather than showing wire detail.
                         */
                        val name = line.strCI("DocumentName") ?: line.strCI("Name")
                            ?: byLocation[location]?.name
                            ?: "Untitled document"
                        val amount = line.dblCI("Cost") ?: line.dblCI("Price") ?: line.dblCI("Amount")
                        val shown = when {
                            amount == null -> "?"
                            amount < 0 -> "unknown"
                            else -> formats?.money(amount) ?: amount.toString()
                        }
                        "$name  ·  $shown"
                    }
                    val total = estimate.total
                    /*
                     * HTTP 200 is not an answer here. This endpoint prices per job and reports each
                     * job's own status inside the array, so a document GMU is still analysing comes
                     * back 200-with-a-405 and has to be shown as "no price yet, because …" — the
                     * server's sentence, not a paraphrase. docs/FUNDING-MODELS.md §2.9,
                     * evidence/probe-cost.json.
                     */
                    val refusal = estimate.refusals.firstOrNull()
                    onResult(
                        CostPreview(
                            totalText = when {
                                total == null -> if (refusal != null) "Not available" else "?"
                                total < 0 -> "Unknown"
                                else -> formats?.money(total) ?: total.toString()
                            },
                            fundingLabel = funding,
                            perJob = lines,
                            blocked = (total != null && total < 0) || estimate.allRefused,
                            reason = refusal?.let {
                                it.message ?: "This server would not price the selection (HTTP ${it.status})."
                            },
                        ),
                    )
                }

                is ApiResult.Err -> {
                    // The reason goes in the normal failure slot; the dialog only says it could not
                    // price the selection. `core` does not format prose — that is ui's job.
                    _state.update { it.copy(failure = result.failure) }
                    onResult(CostPreview("Not available", funding, emptyList(), failed = true))
                }
            }
        }
    }

    // ------------------------------------------------------------------ sign out

    fun signOut() {
        val target = graph.target ?: return
        analysisWatcher?.cancel()
        scope.launch {
            // The stock app's "sign out" cleared its own prefs and left the server session alive
            // (docs/FINDINGS.md §10 S4). Call the endpoint, then drop everything locally.
            runCatching { graph.api.logout(target) }
            graph.forgetSession()
            // Spec `logged_out`: say what actually happened, and say which of the user's things
            // survived. Saved campuses and trusted certificates do; the cached queue and balance do
            // not, because the state object below is a fresh one.
            _state.value = AppState(
                phase = Phase.SignedOut,
                host = target.displayHost,
                apiVersion = graph.prefs.lastApiVersion,
                notice = "Logged off. The session was revoked on ${target.displayHost}.",
            )
        }
    }

    /**
     * A certificate the user has just approved: remember it and retry whatever failed.
     *
     * The stock app's equivalent was `OnReceivedSslError → handler.Proceed()`, which approved every
     * certificate forever and silently (docs/FINDINGS.md §10 S1). Here the approval is one tap, it
     * names the fingerprint it applies to, and it can be revoked from Diagnostics.
     */
    fun trustPendingCertificate() {
        val failure = _state.value.failure as? PharosFailure.TlsNotTrusted ?: return
        graph.trusts.trust(
            TrustedCertificate(failure.host, failure.fingerprint, failure.subject, System.currentTimeMillis()),
        )
        _state.update { it.copy(failure = null) }
        val creds = graph.secrets.storedCredentials()
        val target = graph.target
        if (creds != null && target != null) signIn(target, creds, announce = false) else connect(graph.prefs.host)
    }

    fun dismissFailure() = _state.update { it.copy(failure = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Address of the server-hosted Print Center, for the WebView escape hatch.
     *
     * `NoHeaderMode=1` is what the stock app sent to hide the SPA's own chrome inside its WebView,
     * and the cache-buster is required: the SPA caches against a session cookie that changes on
     * every sign-in (docs/FINDINGS.md §8.2). Keeping both means the clone's fallback renders the
     * same page the vendor's does.
     */
    fun printCenterUrl(): String? {
        val target = graph.target ?: return null
        val base = settings?.printCenter?.str("Url") ?: settings?.printCenter?.str("url")
            ?: "https://${target.host}/myprintcenter"
        return "$base?NoHeaderMode=1&cachebuster=${System.nanoTime()}"
    }

    /**
     * The app's live cookies as strings, ready for `CookieManager.setCookie`.
     *
     * This is the cookie handoff the stock app performed in `GetAuthWebUrl`, done against one jar
     * instead of two stacks. It is what lets the WebView fallback be a *fallback*: the SPA sees the
     * same authenticated session the native screens established, so opening it is not a second
     * sign-in.
     */
    fun printCenterCookies(): List<String> {
        val target = graph.target ?: return emptyList()
        return graph.cookieJar.loadForRequest(target.root).map { it.toString() }
    }

    /** Sign the user out of the SPA too, by dropping the jar the WebView was fed from. */
    fun forgetWebSession() = Unit
}

/**
 * Whether one job in the queue is still expected to become priced, and is therefore worth another
 * `GET /printjobs`.
 *
 * Two of the three cases are the server's own words: it said the job is `Processing`
 * ([PrintJob.isProcessing]), or it gave no price and did not say the analysis failed. The third is
 * the window that produced defect 19: a document accepted a second ago, whose `Activity` block has
 * not been written yet, so the row had nothing to say for itself and nothing ever re-read it.
 *
 * The [PrintJob.pending] guard is deliberate. A released job's price is history — it will never
 * change, however often the queue is read — and a released job with no price (GMU does have those)
 * must not hold the background watcher open every time the queue loads. `pending` is derived from
 * `PrintState: "Queued"` because the queue item carries no `Pending` key at all.
 *
 * A job carrying a cost centre GMU rejects stays `-1` forever, so this does return true for it; the
 * budget in [AnalysisPollBudgetMs], not this predicate, is what stops the requests there.
 */
internal fun stillAwaitsCosting(job: PrintJob): Boolean =
    job.pending && (job.isProcessing || (job.costUnknown && !job.analysisFailed))

/** Gap between background looks while a document is still being priced. */
private const val AnalysisPollMs = 4_000L

/**
 * How long to keep looking. GMU's conversion → page counting → costing finished in about six seconds
 * in every live probe, so this is roughly ten times the observed answer: long enough for a scan that
 * came in as images, short enough that the app eventually stops asking a question the server has
 * already answered "no" to. The refresh arrow in the queue's top bar stays the unbounded option.
 */
private const val AnalysisPollBudgetMs = 60_000L

