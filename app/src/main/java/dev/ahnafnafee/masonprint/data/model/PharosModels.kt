package dev.ahnafnafee.masonprint.data.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * Every shape below was read out of the field, not off a marketing page. The authoritative
 * sources, all in this repository:
 *
 *   pharos/research/webartifact/script.min.js   GMU's own Print Center bundle, v4.11.24.1 —
 *                                               Backbone `Model*` defaults are the field list
 *                                               the server actually feeds this build.
 *   pharos/research/endpoint-table.txt          the same bundle's route table.
 *   pharos/research/webartifact/settings.json   live `GET /PharosAPI/settings` from GMU.
 *
 * Field names are matched case-insensitively on the way in (strCI/strIn) because the API is
 * inconsistent about them, and unknown keys are ignored so a server upgrade cannot blank a
 * screen — which is exactly how the stock Xamarin app fails today.
 */

/** `ModelUser` (`idAttribute:"LogonId"`). */
data class PharosUser(
    val logonId: String?,
    val identifier: String?,
    val displayName: String?,
    val alias: String?,
    val firstNames: String?,
    val lastName: String?,
    val email: String?,
    val cardId: String?,
    /** `{UserUri}` — the route prefix `/printjobs` and `/transactions` hang off. */
    val userUri: String?,
    /** `{UserLocation}` — `/balance` hangs off this one instead. Not the same value. */
    val location: String?,
    val balance: UserBalance?,
    val roles: List<String>,
    val privileges: Privileges,
    val costCenters: List<CostCenter>,
    val raw: JsonObject,
) {
    /** The person's actual name, when the deployment publishes one. GMU leaves both parts blank. */
    val fullName: String?
        get() = listOfNotNull(
            firstNames?.takeIf { it.isNotBlank() },
            lastName?.takeIf { it.isNotBlank() },
        ).joinToString(" ").takeIf { it.isNotBlank() }

    /**
     * What to call this person.
     *
     * A real name when the server has one, otherwise the id they signed in with — which they typed
     * themselves and will recognise. `DisplayName` is trusted only when it actually contains a
     * letter: GMU sets both it and `Alias` to the numeric campus id, and "000000" names nobody.
     */
    val preferredName: String
        get() = fullName
            ?: displayName?.takeIf { it.any(Char::isLetter) }
            ?: logonId?.takeIf { it.isNotBlank() }
            ?: alias?.takeIf { it.isNotBlank() }
            ?: "?"

    /** The numeric campus id the service desk asks for, when it is not already the headline. */
    val campusId: String? get() = alias?.takeIf { it.isNotBlank() && it != preferredName }

    /**
     * Stable key for this account's *local* preferences.
     *
     * `Identifier` first because it is the server's own opaque id and survives a rename; the sign-in
     * id is the fallback, lower-cased because a login is not case-sensitive and `ANNAFEE` must not
     * get a second, empty set of preferences. Keying on this rather than storing one global default
     * is what stops a shared phone charging one student's printing to another's department.
     */
    val accountKey: String
        get() = identifier?.takeIf { it.isNotBlank() }
            ?: logonId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: alias?.takeIf { it.isNotBlank() }
            ?: "unknown"

    /**
     * The vendor's own balance endpoint placeholders differ (`{UserUri}` vs `{UserLocation}`),
     * so prefer `Location` for `/balance` and fall back to `UserUri` — GMU returns both.
     */
    val balanceRoot: String? get() = location?.takeIf { it.isNotBlank() } ?: userUri

    companion object {
        fun from(o: JsonObject): PharosUser = PharosUser(
            logonId = o.strCI("LogonId"),
            identifier = o.strCI("Identifier"),
            displayName = o.strCI("DisplayName"),
            alias = o.strCI("Alias"),
            firstNames = o.strCI("FirstNames"),
            lastName = o.strCI("LastName"),
            // GMU publishes `EmailAddresses` as an array and no `Email` at all, so a client that
            // reads only the singular field shows no address for an account that has one.
            email = o.strCI("Email")?.takeIf { it.isNotBlank() }
                ?: o.arr("EmailAddresses")?.firstNotNullOfOrNull { asString(it)?.takeIf { s -> s.isNotBlank() } },
            cardId = o.strCI("CardId"),
            userUri = o.strCI("UserUri"),
            location = o.strCI("Location"),
            balance = o.obj("Balance")?.let { UserBalance.from(it) }
                ?: o.arr("Purses")?.let { UserBalance.fromPurses(it, o.dbl("Amount")) },
            roles = (o.arr("Roles") ?: JsonArray(emptyList())).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            privileges = Privileges.from(o.obj("Privileges")),
            costCenters = o.objectsNested("CostCenters").map(CostCenter::from),
            raw = o,
        )
    }
}

/**
 * `Privileges` as the bundle reads it. The vendor's own `privilegeTrue` helper is a *negative*
 * test — a privilege is on unless it says deny/no — so an absent privilege means **allowed**.
 * Flipping that default is how a clone ends up hiding features the server actually permits.
 */
data class Privileges(
    val webUpload: Boolean,
    val payForPrint: Boolean,
    val costing: Boolean,
    val costConfirmation: Boolean,
    val costCenters: Boolean,
    val creditCardGateway: Boolean,
    val quotaView: Boolean,
    val userAdministration: Boolean,
    /**
     * `Printing.Administration.ChangeChargingUser` — whether this account may bill somebody other
     * than a job's owner. It gates the `Owner` key on a release body, never the ordinary
     * "charge me" path.
     *
     * Informational only. Nothing branches on it, for the same reason `Printing.Release` is not
     * modelled at all: GMU returns Deny here for students who release jobs every day, so these
     * flags describe an administrative capability rather than what the student may do. See
     * `GmuLivePayloadTest`. It is read so Diagnostics can *show* it, because it is the privilege
     * Pharos names when it refuses a release, and a service-desk conversation goes better when
     * the student can say which flag the server quoted.
     */
    val changeChargingUser: Boolean,
) {
    companion object {
        private fun truthy(v: String?): Boolean =
            !(v.isNullOrBlank() || v.equals("deny", true) || v.equals("no", true))

        fun from(o: JsonObject?): Privileges {
            if (o == null) {
                return Privileges(false, false, false, false, false, false, false, false, false)
            }
            val printing = o.obj("Printing")
            val payForPrint = printing?.obj("PayForPrint")
            val administration = printing?.obj("Administration")
            return Privileges(
                webUpload = truthy(printing?.strCI("WebUpload")),
                payForPrint = truthy(payForPrint?.strCI("Enabled") ?: payForPrint?.strCI("PayForPrint")),
                costing = truthy(payForPrint?.strCI("Costing")),
                costConfirmation = truthy(payForPrint?.strCI("CostConfirmation")),
                costCenters = truthy(payForPrint?.strCI("CostCenters")),
                creditCardGateway = truthy(payForPrint?.strCI("CreditCardGateway")),
                quotaView = truthy(o.obj("QuotaPrivileges")?.strCI("View")),
                userAdministration = o.obj("UserAdministration")?.let { ua ->
                    truthy(ua.strCI("View")) || truthy(ua.strCI("Update")) || truthy(ua.strCI("Create"))
                } ?: false,
                changeChargingUser = truthy(administration?.strCI("ChangeChargingUser")),
            )
        }
    }
}

/**
 * `ModelPurse` (`idAttribute:"Name"`) + `ModelTotalBalance`.
 *
 * `Id` is parsed and never sent: the client can choose *whether* to charge a purse at all (by
 * leaving `CostCenterCode` empty) but never *which* purse, and `Priority` — which the bundle
 * deserialises — appears exactly once in its whole 2.4 MB, i.e. it is read and dropped. The
 * spending order is the server's, top of the list first. See docs/FUNDING-MODELS.md §2.8.
 */
data class Purse(val name: String, val amount: Double, val priority: Long?, val id: String? = null) {
    companion object {
        fun from(o: JsonObject): Purse = Purse(
            name = o.strCI("Name") ?: "?",
            amount = o.dbl("Amount") ?: 0.0,
            priority = lngIn(o, "Priority"),
            id = o.strCI("Id"),
        )
    }
}

data class UserBalance(val amount: Double?, val purses: List<Purse>) {
    /** GMU exposes one purse; multi-purse deployments split by funding source (drop-in, print). */
    val total: Double get() = amount ?: purses.sumOf { it.amount }

    /** True only when the server said nothing; a real zero must render as $0.00, not "unknown". */
    val isEmpty: Boolean get() = purses.isEmpty() && amount == null

    companion object {
        /**
         * Reads either shape: the dedicated `{UserLocation}/balance` document (bare `Amount` +
         * `Purses`) **or** a user resource, which carries the same object nested under `Balance` —
         * that is the only shape GMU answers, and its `Amount` is the *string* `"0.00"`.
         * Live capture: `evidence/real-user.json`.
         */
        fun from(o: JsonObject): UserBalance {
            val src = o.obj("Balance") ?: o
            return UserBalance(
                amount = dblIn(src, "Amount", "Value", "Total"),
                purses = src.objects("Purses").map(Purse::from),
            )
        }

        fun fromPurses(items: JsonArray, total: Double?): UserBalance = UserBalance(
            amount = total,
            purses = items.mapNotNull { it as? JsonObject }.map(Purse::from),
        )
    }
}

/**
 * `ModelCostCenter` (`idAttribute:"Code"`).
 *
 * GMU's real one, for reference (`evidence/real-logon-kml-body.txt`):
 * `{"Code":"10111-M17041","Grant":false,"Active":true,"Complete":true,"EnforceBudget":false,`
 * `"Description":"&quot;10111-Computer Science Department&quot;"}` — so `Grant` is **false** for a
 * centre the user genuinely owns, and `Description` arrives HTML-escaped.
 */
data class CostCenter(
    val code: String,
    val description: String?,
    val granted: Boolean,
    val complete: Boolean,
    val active: Boolean = true,
) {
    companion object {
        fun from(o: JsonObject): CostCenter = CostCenter(
            code = o.strCI("Code") ?: "",
            description = cleanSentence(o.strCI("Description")),
            granted = o.bool("Grant") ?: true,
            complete = o.bool("Complete") ?: true,
            active = o.bool("Active") ?: true,
        )
    }
}

/** `ModelPrintJob.Stats`. All four counters matter: the balance sheet and the cost preview both quote them. */
data class JobStats(
    val sizeInKb: Long?,
    val totalPages: Long?,
    val blackWhitePages: Long?,
    val colorPages: Long?,
    val sheets: Long?,
) {
    companion object {
        fun from(o: JsonObject?): JobStats = JobStats(
            sizeInKb = o?.let { lngIn(it, "SizeInKb") },
            totalPages = o?.let { lngIn(it, "TotalPages") },
            blackWhitePages = o?.let { lngIn(it, "TotalBWPages", "TotalBwPages") },
            colorPages = o?.let { lngIn(it, "TotalColorPages") },
            sheets = o?.let { lngIn(it, "TotalSheets") },
        )
    }
}

/**
 * Finishing options as they come **off the wire**. Read tolerantly: `Mono`/`Duplex` arrive as
 * `"Yes"`/`"No"` from `/settings` and the job list, as booleans from some upload responses, and
 * `PagesPerSide`/`Copies` as either `"1"` or `1`.
 */
data class FinishingOptions(
    val mono: Boolean?,
    val duplex: Boolean?,
    val pagesPerSide: Long?,
    val copies: Long?,
    val defaultPageSize: String?,
    val pageRange: String?,
) {
    val colourLabel: String get() = when (mono) {
        true -> "Black & white"
        false -> "Colour"
        null -> "Colour mode unknown"
    }

    companion object {
        val DEFAULT = FinishingOptions(false, false, 1, 1, "Letter", "")
        fun from(o: JsonObject?): FinishingOptions? = o?.let {
            FinishingOptions(
                mono = it.boolIn("Mono", "Monochrome"),
                duplex = it.boolIn("Duplex"),
                pagesPerSide = lngIn(it, "PagesPerSide"),
                copies = lngIn(it, "Copies"),
                defaultPageSize = it.strCI("DefaultPageSize"),
                pageRange = it.strCI("PageRange"),
            )
        }
    }
}

/**
 * Finishing options going **onto** the wire, in the two encodings the vendor's own client uses.
 *
 * GMU's bundle sends booleans for `Mono`/`Duplex` in the upload `MetaData` part
 * (`getAdditionalUploadData`) and `"Yes"`/`"No"` strings in the release/cost/patch bodies (the
 * encoding `/settings` publishes as the default). Both are accepted by 4.11.24.1; the clone
 * mirrors the vendor per call site rather than inventing a third shape. `PagesPerSide` and
 * `Copies` are strings in *every* observed body — as integers the server returns 500.
 */
data class FinishingPayload(
    val mono: Boolean,
    val duplex: Boolean,
    val pagesPerSide: Long,
    val copies: Long,
    val defaultPageSize: String,
    val pageRange: String?,
) {
    fun forUpload(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        putBool("Mono", mono)
        putBool("Duplex", duplex)
        putStrNum("PagesPerSide", pagesPerSide)
        putStrNum("Copies", copies)
        putStr("DefaultPageSize", defaultPageSize)
        putStr("PageRange", pageRange.orEmpty())
    }

    fun forJobUpdate(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        putStr("Mono", if (mono) "Yes" else "No")
        putStr("Duplex", if (duplex) "Yes" else "No")
        putStrNum("PagesPerSide", pagesPerSide)
        putStrNum("Copies", copies)
        putStr("DefaultPageSize", defaultPageSize)
        putStr("PageRange", pageRange.orEmpty())
    }

    fun toOptions() = FinishingOptions(mono, duplex, pagesPerSide, copies, defaultPageSize, pageRange)

    companion object {
        fun from(o: FinishingOptions?, fallback: FinishingOptions = FinishingOptions.DEFAULT) = FinishingPayload(
            mono = o?.mono ?: fallback.mono ?: true,
            duplex = o?.duplex ?: fallback.duplex ?: false,
            pagesPerSide = o?.pagesPerSide ?: fallback.pagesPerSide ?: 1,
            copies = o?.copies ?: fallback.copies ?: 1,
            defaultPageSize = o?.defaultPageSize ?: fallback.defaultPageSize ?: "Letter",
            pageRange = o?.pageRange ?: fallback.pageRange ?: "",
        )
    }
}

/**
 * `ModelPrintJob` (`idAttribute:"Location"`).
 *
 * `Location` is the job's identity and the URL every later call uses (`{JobLocation}/content`
 * for previews, `PrintJobs[].Location` for release/cost/patch/delete), so it is the only
 * required field — a job without it cannot be acted on, exactly like the bundle's
 * `_NO_LOCATION_` sentinel.
 */
data class PrintJob(
    val location: String,
    val name: String?,
    val owner: String?,
    val printerName: String?,
    val deviceGroup: String?,
    val submissionTimeUtc: String?,
    val lastModified: String?,
    val expiryTimeUtc: String?,
    /**
     * Server-provided display text. The vendor bundle never compares it — it renders it — so the
     * clone does the same instead of inventing an enum it cannot verify.
     */
    val jobStatus: String?,
    /**
     * `PrintState`, verbatim. Live on GMU 4.11.24.1 a queued job says `"Queued"`; this is the field
     * that actually distinguishes a waiting job from a released one on this deployment, because
     * `Pending` is **absent from the payload entirely** (see [pending]).
     */
    val printState: String?,
    val jobFormat: String?,
    val documentType: String?,
    /**
     * "Still in the queue, can still be released."
     *
     * Derived, not read. The bundle's model has a `Pending` boolean, and the natural client reads it
     * and moves on — but a live GMU queue page (`evidence/probe-queue-with-job.json`) contains no
     * `Pending` key at all, so reading it yields `false` for every job, which renders the whole queue
     * as already-released and, with "Show released" off, prints "Nothing waiting to print" on an
     * account that has a job in it. `PrintState` is the field the server really populates, so it
     * drives this, with the boolean honoured wherever a deployment does send it.
     *
     * An unrecognised state counts as pending on purpose: a job that is wrongly hidden can never be
     * released, while a job that is wrongly shown is only a stale row with a Release button.
     */
    val pending: Boolean,
    val stats: JobStats,
    val finishing: FinishingOptions?,
    /** Server-side analysis: `Converting` → `PageCounting` → `Costing`. Drives the "still processing" UI. */
    val activity: JobActivity?,
    /** `AllowableActions`, split on commas. Observed on a freshly queued job: `Read, Update, Delete, Content, Preview`. */
    val allowableActions: List<String>,
    /** `-1` means "this server does not publish a cost for the job" (bundle: `Cost:-1`). */
    val cost: Double?,
    /** `"None"`/absent = releasable; anything else means the job needs its owner's password. */
    val protectedBy: String?,
    val costCenterCode: String?,
    val delegates: List<String>,
    val originatingMachineName: String?,
    /**
     * `SubmissionTimeDelta`: the server's own "seconds since submitted", sent on every item
     * (`2.0000184` on a job queued two seconds earlier). Preferred over subtracting clock times,
     * because the emulator and GMU do not have to agree on the date for this to be right.
     */
    val submissionAgeSeconds: Double?,
    val applicationName: String?,
    /** Per-document restrictions; an absent flag leaves the decision to the server. */
    val supportedFinishing: SupportedFinishing = SupportedFinishing(),
) {
    val id: String get() = location
    val isReleased: Boolean get() = !pending
    val needsPassword: Boolean get() = !protectedBy.isNullOrBlank() && !protectedBy.equals("None", true)
    val costUnknown: Boolean get() = cost == null || cost < 0.0
    val submittedAt: Instant? get() = parsePharosTime(submissionTimeUtc)
    val expiresAt: Instant? get() = parsePharosTime(expiryTimeUtc)

    /** True while the server is still converting/counting/costing — costing is refused until then. */
    val isProcessing: Boolean get() = activity?.processing == true

    /** The server has already refused one of the analysis steps, so a price is not coming. */
    val analysisFailed: Boolean get() = activity?.failed == true

    /**
     * The server has not counted this document: both counters are zero *and* the analysis is either
     * still running or already gave up. A live GMU job can sit in the queue like this indefinitely —
     * the probe's PDF converted fine, failed page counting, and came back with `Stats` all zero.
     * Printing "0 pages · 0 B" for that would tell the student the file is empty, which is a different
     * problem than the one they have.
     */
    val awaitingPageCount: Boolean
        get() = (stats.totalPages ?: 0L) == 0L && (stats.sheets ?: 0L) == 0L && (isProcessing || analysisFailed)

    /** "3 pages, 1 colour" — the summary line on a job card. Empty when there is nothing counted to say. */
    val pageSummary: String
        get() {
            if (awaitingPageCount) return ""
            val parts = mutableListOf<String>()
            stats.sheets?.let { parts += "$it sheet" + if (it == 1L) "" else "s" }
            stats.totalPages?.let { parts += "$it page" + if (it == 1L) "" else "s" }
            val colour = stats.colorPages ?: 0
            val bw = stats.blackWhitePages ?: 0
            if (colour > 0 && bw > 0) parts += "$colour colour / $bw b&w"
            else if (colour > 0) parts += "$colour colour"
            else if (bw > 0) parts += "$bw b&w"
            // A size of 0 means "not measured", not "empty file". Measured on 2026-09-18: a real
            // 1,665,254-byte PDF uploaded from the phone came back with `SizeInKb: 0` while
            // `TotalPages` and `TotalSheets` were correct, so printing "0 B" next to a document the
            // student can see in their own file manager reads as if the upload arrived empty.
            stats.sizeInKb?.takeIf { it > 0L }?.let { parts += humanBytes(it * 1024) }
            return parts.joinToString(" · ")
        }

    companion object {
        fun from(o: JsonObject): PrintJob {
            val state = o.strCI("PrintState")
            return PrintJob(
                location = o.strCI("Location") ?: "",
                name = o.strCI("Name"),
                owner = o.strCI("Owner"),
                printerName = o.strCI("PrinterName"),
                deviceGroup = o.strCI("DeviceGroup"),
                submissionTimeUtc = o.strCI("SubmissionTimeUtc"),
                lastModified = o.strCI("LastModified"),
                expiryTimeUtc = o.strCI("ExpiryTimeUtc"),
                jobStatus = o.strCI("JobStatus"),
                printState = state,
                jobFormat = o.strCI("JobFormat"),
                documentType = o.strCI("DocumentType"),
                pending = pendingOf(o, state),
                stats = JobStats.from(o.obj("Stats")),
                finishing = FinishingOptions.from(o.obj("FinishingOptions")),
                activity = JobActivity.from(o.obj("Activity")),
                allowableActions = o.strCI("AllowableActions")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                cost = o.dbl("Cost"),
                protectedBy = o.strCI("ProtectedBy"),
                costCenterCode = o.strCI("CostCenterCode"),
                delegates = (o.arr("Delegates") ?: JsonArray(emptyList())).mapNotNull { asString(it) },
                originatingMachineName = o.strCI("OriginatingMachineName"),
                submissionAgeSeconds = o.dblCI("SubmissionTimeDelta"),
                applicationName = o.strCI("ApplicationName"),
                supportedFinishing = SupportedFinishing.from(o.obj("SupportedFinishingOptions")),
            )
        }

        /**
         * States that still mean "in the queue". `Released` is the terminal one GMU moves a job to;
         * `Aborted`/`Deleted`/`Cancelled`/`Completed`/`Printed`/`Archived` are the other endings this
         * API family is known to use, and anything not listed here is treated as still pending.
         */
        private val FINISHED_STATES = setOf(
            "released", "aborted", "deleted", "cancelled", "canceled", "completed", "printed", "archived", "error",
        )

        private fun pendingOf(o: JsonObject, state: String?): Boolean {
            o.bool("Pending")?.let { return it }
            val s = state?.trim()?.lowercase() ?: return true
            return s !in FINISHED_STATES
        }
    }
}

data class SupportedFinishing(
    val color: Boolean? = null,
    val duplex: Boolean? = null,
    val copies: Boolean? = null,
) {
    companion object {
        fun from(o: JsonObject?) = SupportedFinishing(
            color = o?.boolIn("Color", "Mono"),
            duplex = o?.boolIn("Duplex"),
            copies = o?.boolIn("Copies"),
        )
    }
}

/**
 * `ModelPrintJob.Activity` — the server's own analysis pipeline for an uploaded document.
 *
 * This is why "what does it cost?" cannot be asked immediately after an upload: on the first live
 * probe the job came back `Analysing / Processing...` with `Converting: Completed`,
 * `PageCounting: Failed`, `Costing: Not yet started`, and `POST /printjobs/cost` answered **HTTP 200
 * carrying a per-job 405** (`JobActionNotAllowedStillProcessing`). A client that treats 200 as
 * success shows the student a price of "?" and never explains why.
 */
data class JobActivity(
    val name: String?,
    val state: String?,
    val message: String?,
    val steps: List<JobActivityStep>,
) {
    /**
     * The vendor's in-progress literal is `"Processing..."`, with the dots; matched loosely on
     * purpose. A *failed* analysis never becomes in-progress: on the first live probe the failed job
     * still had `Costing: "Not yet started"`, so "an unfinished step" alone would wait forever on a
     * price that is never coming.
     */
    val processing: Boolean
        get() {
            if (failed) return false
            val s = state?.lowercase().orEmpty()
            return s.contains("process") || s.contains("pending") || s.contains("queued") ||
                steps.any { !it.done && !it.failed }
        }

    val failed: Boolean get() = state?.lowercase()?.contains("fail") == true || steps.any { it.failed }

    /** "Costing: not yet started" — the honest line to show while a price is unavailable. */
    val blocker: String?
        get() = steps.firstOrNull { it.failed }?.label ?: steps.firstOrNull { !it.done }?.label

    companion object {
        fun from(o: JsonObject?): JobActivity? {
            if (o == null) return null
            val steps = o.objects("Steps").map { JobActivityStep.from(it) }
            val activity = JobActivity(
                name = o.strCI("Name"),
                state = o.strCI("State"),
                message = o.strCI("Message"),
                steps = steps,
            )
            return if (activity.name == null && activity.state == null && steps.isEmpty()) null else activity
        }
    }
}

/** One step of [JobActivity]: `Converting`, `PageCounting`, `Costing`. */
data class JobActivityStep(
    val name: String?,
    val state: String?,
    val message: String?,
    /** `0001-01-01T00:00:00` means "has not started" and arrives as null, not as a year-1 date. */
    val startedAt: Instant?,
    val finishedAt: Instant?,
) {
    private val s: String get() = state?.lowercase().orEmpty()
    val done: Boolean get() = s.contains("complete") || s == "done" || s == "finished"
    val failed: Boolean get() = s.contains("fail") || s.contains("error") || s.contains("abort")

    /** e.g. `Costing: not yet started`. */
    val label: String get() = listOfNotNull(name, state?.takeIf { it.isNotBlank() }).joinToString(": ")
        .ifBlank { "Analysing" }

    companion object {
        fun from(o: JsonObject) = JobActivityStep(
            name = o.strCI("Name"),
            state = o.strCI("State"),
            message = o.strCI("Message"),
            startedAt = parsePharosTime(o.strCI("Started")),
            finishedAt = parsePharosTime(o.strCI("Finished")),
        )
    }
}

/**
 * `ModelDevice` (`idAttribute:"Location"`); `Location` is also what a station QR code carries.
 *
 * `@Serializable` so the printer list can be cached to disk (AppPrefs) and shown instantly on the
 * next launch — printers do not move, and re-reading the whole list every time is slow. The manual
 * [from] parser stays the wire path; the generated serializer is only used for the local cache.
 */
@Serializable
data class Device(
    val location: String,
    val name: String?,
    val make: String?,
    val model: String?,
    val assetTag: String?,
    val serialNumber: String?,
    val server: String?,
    val description: String?,
    val deviceGroups: List<String>,
    val duplexSupported: Boolean,
    val colorSupported: Boolean,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: location
    val sublabel: String
        get() = listOfNotNull(
            make?.takeIf { it.isNotBlank() },
            model?.takeIf { it.isNotBlank() },
            deviceGroups.firstOrNull(),
            location.takeIf { it != label },
        ).joinToString(" · ")

    /** QR text may be the bare Location, a URL ending in it, or an asset tag. */
    fun matchesToken(token: String): Boolean {
        val t = token.trim().trimEnd('/')
        if (t.isEmpty()) return false
        if (t.equals(location, true)) return true
        if (t.substringAfterLast('/').equals(location, true)) return true
        assetTag?.let { if (t.equals(it, true)) return true }
        serialNumber?.let { if (t.equals(it, true)) return true }
        return false
    }

    companion object {
        fun from(o: JsonObject): Device {
            val caps = o.obj("Capabilities")
            return Device(
                location = o.strCI("Location") ?: "",
                name = o.strCI("Name"),
                make = o.strCI("Make"),
                model = o.strCI("Model"),
                assetTag = o.strCI("AssetTag"),
                serialNumber = o.strCI("SerialNumber"),
                server = o.strCI("Server"),
                description = o.strCI("Description"),
                deviceGroups = (o.arr("DeviceGroups") ?: JsonArray(emptyList())).mapNotNull { asString(it) },
                duplexSupported = caps?.boolCI("DuplexSupported") ?: false,
                colorSupported = caps?.boolCI("ColorSupported") ?: false,
            )
        }
    }
}

/**
 * `ModelUserTransaction` (`idAttribute:"Identifier"`).
 *
 * GMU encodes the *kind* of movement in `TransactionType`/`SubType` short codes and the human
 * wording in `Description`; the vendor maps them through `/settings`' `TransactionLabels`
 * ("Credit,Print,…,Transfer Funds,…"). [describes] keeps that mapping server-driven, because a
 * hardcoded table would be wrong on the next campus that is not GMU.
 */
data class Transaction(
    val identifier: String?,
    val cashier: String?,
    val purse: String?,
    val user: String?,
    val time: String?,
    val subType: String?,
    val transactionType: String?,
    val amount: Double?,
    val fee: Double?,
    val description: String?,
    val costCenters: String?,
    val application: String?,
    val jobName: String?,
    val reason: String?,
    val server: String?,
    val printer: String?,
    val bank: String?,
    val offline: Boolean?,
    val pages: Long?,
    val sheets: Long?,
) {
    val isCredit: Boolean get() = (amount ?: 0.0) > 0.0
    val at: Instant? get() = parsePharosTime(time)

    /**
     * GMU does not write a sentence into `Description`. On 2026-09-18 the live statement for
     * `jdoe` posted, for a print charge, the page-counter transcript itself —
     * `"I-94.pdf&#10;1 page of 612x792,Color,Letter,Simplex"` — i.e. the document name, an
     * entity-encoded line feed, and the raw `RawPageCounterResult` line. (`evidence/
     * port-09-account.xml`; the entity is why the first render of this screen printed a literal
     * `&#10;`.) It is also the *same string* `JobName` carries, so a row that shows it twice says
     * nothing twice.
     *
     * So the transcript is split where the server put the line feed: the first line is the thing a
     * student recognises (the document), everything after it is paper detail.
     */
    private val descriptionLines: List<String>
        get() = description?.replace('\r', '\n')
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

    /** The document name GMU hid in the first line of [description], or null if there is none. */
    val descriptionHead: String? get() = descriptionLines.firstOrNull()

    /** The rest of [description] — page/sheet counts, media, sidedness, the page counter's own words. */
    val descriptionTail: String?
        get() = descriptionLines.drop(1).joinToString(" · ").takeIf { it.isNotBlank() }

    val detail: String
        get() = listOfNotNull(
            descriptionTail,
            jobName?.takeIf { it.isNotBlank() && it != descriptionHead },
            printer ?: server,
            purse?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    companion object {
        fun from(o: JsonObject): Transaction = Transaction(
            identifier = o.strIn("Identifier", "Id"),
            cashier = o.strCI("Cashier"),
            purse = o.strCI("Purse"),
            user = o.strCI("User"),
            time = o.strIn("Time", "Timestamp", "Date"),
            subType = o.strCI("SubType"),
            transactionType = o.strCI("TransactionType"),
            amount = o.dbl("Amount"),
            fee = o.dbl("TransactionFee"),
            // GMU entity-encodes the line feed inside a charge's description (`…pdf&#10;1 page of …`),
            // so this is the one field that has to be unescaped before the row can be laid out —
            // see [Transaction.descriptionHead].
            description = o.strCI("Description")?.htmlUnescaped()?.trim()?.takeIf { it.isNotEmpty() },
            costCenters = o.strCI("CostCenters"),
            application = o.strCI("Application"),
            jobName = o.strCI("JobName"),
            reason = o.strCI("Reason"),
            server = o.strCI("Server"),
            printer = o.strCI("Printer"),
            bank = o.strCI("Bank"),
            offline = o.bool("Offline"),
            pages = o.lng("Pages"),
            sheets = o.lng("Sheets"),
        )
    }
}

/** `{PreviousPageLink,NextPageLink,Count,Items[]}` — the shape every list endpoint returns. */
data class Page<T>(
    val items: List<T>,
    val count: Long?,
    val previousPageLink: String?,
    val nextPageLink: String?,
) {
    /**
     * There is a page after this one. Two live facts from GMU shape this, and a client that only
     * looks at `NextPageLink` gets both wrong:
     *
     *  * GMU sends a `NextPageLink` even to an account with **zero** jobs, so link presence alone
     *    makes an empty queue look infinitely long — the list asks for page 2 after page 2 forever.
     *    `Count` is the total, so when it is known it wins.
     *  * the links come back **HTML-escaped** (`?PageSize=20&amp;skip=20`). Followed verbatim,
     *    OkHttp parses a parameter literally named `amp;skip`, the server ignores it, and every
     *    "next page" is page 1 again — which is how a paging bug turns into an endless spinner on a
     *    busy account and never shows up on an empty one.
     */
    val hasMore: Boolean get() = !nextPageLink.isNullOrBlank() && (count == null || count > items.size)
    val absoluteNextPage: String? get() = nextPageLink

    /**
     * Whether to ask for the page after this one, given `loaded` rows are already on screen. Two
     * extra stops beyond [hasMore], both from the live shape: an empty page is the end whatever the
     * link says (GMU keeps answering with the same total and an empty `Items`), and once the rows
     * on screen reach `Count` there is nothing left to fetch.
     */
    fun hasMoreAfter(loaded: Int): Boolean =
        hasMore && items.isNotEmpty() && (count == null || loaded < count)

    companion object {
        fun <T> from(o: JsonObject, map: (JsonObject) -> T): Page<T> = Page(
            items = o.objects("Items").map(map),
            count = lngIn(o, "Count", "TotalCount", "TotalItems"),
            previousPageLink = o.strIn("PreviousPageLink", "PreviousLink")?.htmlUnescaped(),
            nextPageLink = o.strIn("NextPageLink", "NextLink")?.htmlUnescaped(),
        )

        fun <T> of(items: List<T>): Page<T> = Page(items, items.size.toLong(), null, null)
    }
}

/** The generic prefix Pharos pastes in front of the real sentence inside `UserMessage`. */
private const val USER_MESSAGE_WRAPPER = "The operation could not be completed."

/**
 * Decode the entity references the error envelope actually emits (`&#39;`, `&quot;`, `&amp;`,
 * numeric refs). Handwritten rather than `android.text.Html` so the error path stays a pure
 * function of its input and is unit-testable off-device.
 */
internal fun String.htmlUnescaped(): String {
    if (!contains('&')) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '&') {
            out.append(c); i++; continue
        }
        val end = indexOf(';', startIndex = (i + 1).coerceAtMost(length))
        val ref = if (end < 0 || end - i > 12) null else substring(i + 1, end)
        val decoded = when {
            ref == null -> null
            ref.equals("amp", true) -> "&"
            ref.equals("lt", true) -> "<"
            ref.equals("gt", true) -> ">"
            ref.equals("quot", true) -> "\""
            ref.equals("apos", true) -> "'"
            ref.equals("nbsp", true) -> " "
            // GMU uses typographic entities in the strings an administrator can edit (its own
            // settings document contains `&ldquo;Advance&rdquo;`), so a cost-centre description
            // typed with curly quotes arrives escaped and must not read as `&ldquo;CS Dept&rdquo;`.
            ref.equals("ldquo", true) -> "\u201C"
            ref.equals("rdquo", true) -> "\u201D"
            ref.equals("lsquo", true) -> "\u2018"
            ref.equals("rsquo", true) -> "\u2019"
            ref.equals("ndash", true) -> "\u2013"
            ref.equals("mdash", true) -> "\u2014"
            ref.equals("hellip", true) -> "\u2026"
            ref.startsWith("#") -> {
                val n = ref.drop(1).let {
                    if (it.startsWith("x", true)) it.drop(1).toIntOrNull(16) else it.toIntOrNull()
                }
                // Tab, line feed and carriage return are allowed because they are real content: GMU
                // writes a transaction's page-counter transcript as
                // `"I-94.pdf&#10;1 page of 612x792,Color,Letter,Simplex"`, and the line feed is the
                // only thing separating the document from its paper (see
                // [Transaction.descriptionHead]). Every other C0 code stays encoded — NUL and its
                // friends are junk in a display string, where a raw control character is invisible.
                val printable = n != null &&
                    (n == 9 || n == 10 || n == 13 || (n in 32..0x10FFFF && !Character.isSurrogate(n.toChar())))
                if (printable) String(Character.toChars(n!!)) else null
            }

            else -> null
        }
        if (decoded == null) {
            out.append(c); i++
        } else {
            out.append(decoded); i = end + 1
        }
    }
    return out.toString()
}

/**
 * Turn a raw envelope field into something worth putting on screen: un-escape, drop the vendor's
 * wrapper, and unwrap the single quotes the wrapper puts around the real sentence. Null when the
 * field held nothing, so callers can fall through to the next field.
 */
internal fun cleanSentence(raw: String?): String? {
    val s = raw?.takeIf { it.isNotBlank() } ?: return null
    var t = s.htmlUnescaped()
    // The vendor double-escapes some fields (`&amp;#39;`), so a value can still hold an entity
    // reference after the first pass. Re-running only when the first pass actually changed
    // something keeps a literal `&` (very common in these sentences) from being re-examined.
    if (t != s && t.contains('&')) t = t.htmlUnescaped()
    if (t.startsWith(USER_MESSAGE_WRAPPER, ignoreCase = true)) t = t.substring(USER_MESSAGE_WRAPPER.length)
    t = t.trim().removeSurrounding("'").removeSurrounding("\u2018", "\u2019")
    // GMU stores a cost centre's description with the quotes *inside* the value
    // (`"Description":"&quot;10111-Computer Science Department&quot;"`, live capture), so after
    // unescaping it arrives as `"10111-Computer Science Department"`. Those quotes are data-entry
    // residue from whoever created the account, and they read as a bug in the funding list.
    t = t.removeSurrounding("\"").removeSurrounding("\u201C", "\u201D").trim()
    return t.takeIf { it.isNotBlank() }
}

/**
 * The error envelope. `UserMessage` is the server's own sentence; quoting it beats the stock app's
 * `Err_*` string sniffing, and `ErrorCode` is what the service desk needs.
 *
 * Two properties of this envelope are deployment traps and are handled here rather than at the
 * call sites, with a live GMU capture kept at `phrarosprint/evidence/bad-logon-body.json`:
 *
 *  1. The envelope's `"Status"` does not have to match the transport status. GMU answers a wrong
 *     password with **HTTP 300 Multiple Choices**; `print.uw.edu` sends 401 while its body says
 *     403; `pharosweb.westernu.edu` uses 300 (docs/PHAROS-API-FINDINGS.md §3). The body is the
 *     verdict, so [verdict] prefers it.
 *  2. `UserMessage` is HTML-escaped *and* prefixed with a generic wrapper:
 *     `"The operation could not be completed. &#39;Your username and password cannot be
 *     verified. Please try again.&#39;"` — with `ErrorCode: "TranslationNotFound"`, i.e. this
 *     deployment has no entry in the table the stock client looked up by exception class name
 *     (FINDINGS §11). `DeveloperMessage` carries the same sentence clean, which is why it stays
 *     in the fallback chain.
 */
data class PharosError(
    val status: Int?,
    val request: String?,
    val errorCode: String?,
    val errorContext: String?,
    val errorContextOverride: String?,
    val developerMessage: String?,
    val userMessage: String?,
    val exceptionMessage: String?,
    val bodyStatus: Int? = null,
) {
    /** What the server thinks happened, as a number: its `Status` first, the status line second. */
    val verdict: Int? get() = bodyStatus ?: status

    /** The best sentence the server offered, cleaned of the wrapper and the entity escapes. */
    fun userText(fallback: String): String =
        cleanSentence(userMessage)
            ?: cleanSentence(errorContextOverride)
            ?: cleanSentence(developerMessage)
            ?: cleanSentence(exceptionMessage)
            ?: fallback

    val detail: String
        get() = listOfNotNull(
            status?.let { "HTTP $it" },
            bodyStatus?.takeIf { it != status }?.let { "Status $it" },
            errorCode?.takeIf { it.isNotBlank() },
            developerMessage?.takeIf { it.isNotBlank() },
            request?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    companion object {
        val EMPTY = PharosError(null, null, null, null, null, null, null, null)

        fun from(status: Int, body: String): PharosError {
            val o = runCatching { body.toJsonObject() }.getOrElse { return EMPTY.copy(status = status) }
            val inner = o.obj("Error") ?: o
            return PharosError(
                status = status,
                request = inner.strCI("Request"),
                errorCode = inner.strIn("ErrorCode", "Code"),
                errorContext = inner.strCI("ErrorContext"),
                errorContextOverride = inner.strCI("ErrorContextOverride"),
                developerMessage = inner.strCI("DeveloperMessage"),
                userMessage = inner.strCI("UserMessage"),
                exceptionMessage = inner.strCI("ExceptionMessage"),
                bodyStatus = lngIn(inner, "Status")?.toInt(),
            )
        }
    }
}

/** The jobs named in a set of per-job rows, in the order the server answered them. */
internal fun List<JsonObject>.jobLocations(): List<String> =
    mapNotNull { it.strIn("Location", "JobLocation")?.takeIf { l -> l.isNotBlank() } }

/**
 * Per-job outcome of a bulk `release` / `delete` / `patch` call.
 *
 * Two envelope shapes, both live: `{Responses:[…]}` (what the bundle's model expects, and what
 * release is documented to return together with a refreshed `User`) and a **bare array** — GMU's
 * `DELETE /printjobs` answered the first live probe with exactly
 * `[{"Location":"/printjobs/1H9Y…","Status":200}]`. Reading only the first shape made every bulk
 * call look like total success, because an array body failed to parse into an empty result, which is
 * the same value "no per-job detail" produced. A delete that actually failed per job would have been
 * reported as done.
 *
 * The bundle reads `e.Responses` for the per-job results and `e.User` for the refreshed user
 * (release debits the balance server-side and hands the new balance straight back), so the clone
 * refreshes the wallet from it instead of firing a second round trip.
 */
data class JobOperationResult(
    val responses: List<JsonObject>,
    val updatedUser: PharosUser?,
    val raw: JsonObject,
) {
    val failures: List<JsonObject>
        get() = responses.filter { row ->
            val ok = row.boolIn("Success", "Succeeded", "IsSuccess")
            if (ok != null) !ok
            else (row.dbl("Status") ?: 0.0).toInt().let { it >= 400 } ||
                !row.strIn("ErrorCode", "Error").isNullOrBlank()
        }

    /** The jobs this call actually acted on, read straight from the per-job rows. */
    val actedLocations: List<String> get() = responses.jobLocations()

    companion object {
        fun from(body: String?): JobOperationResult {
            val objs = runCatching { body.orEmpty().toJsonObjects() }.getOrDefault(emptyList())
            val envelope = objs.singleOrNull { it.keys.any { k -> k.equals("Responses", true) || k.equals("User", true) } }
                ?: objs.singleOrNull { objs.size == 1 && it.keys.any { k -> k.equals("Items", true) } }
                ?: JsonObject(emptyMap())
            val perJob = objs.filter { it !== envelope }.ifEmpty {
                envelope.objects("Responses").ifEmpty { envelope.objects("Items") }
            }
            return JobOperationResult(
                responses = perJob,
                updatedUser = envelope.obj("User")?.let(PharosUser::from),
                raw = envelope,
            )
        }

        val EMPTY = JobOperationResult(emptyList(), null, JsonObject(emptyMap()))
    }
}

/** GMU emits `2024-05-08T14:22:31.1234567Z` and `2024-05-08T14:22:31Z`; some servers omit the zone. */
fun parsePharosTime(raw: String?): Instant? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("0001-01-01") } ?: return null
    runCatching { return Instant.parse(s) }
    runCatching { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
    runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }
    // A bare `2024-05-08T14:22:31` with no zone is UTC in every Pharos response observed; the
    // field names all end in `Utc`, so assuming the local zone would drift the "expires in" label.
    runCatching { return OffsetDateTime.parse(s + "Z").toInstant() }
    return null
}

/**
 * Quote a byte count to the user. **Binary** divisors, decimal abbreviations: the only number this
 * app quotes this way is `"Maximum Allowed Upload"`, which GMU sets to `"52428800"` meaning 50 MiB.
 * Dividing by 1000 would print "52.4 MB" — a limit that looks *larger* than the server enforces, so
 * a 51 MB file would pass the local pre-check and die on a 413 after a 40-second upload. Android's
 * own file-size formatter makes the same choice for the same reason.
 */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
