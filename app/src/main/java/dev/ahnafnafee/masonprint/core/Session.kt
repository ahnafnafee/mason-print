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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val updatingFinishing: Boolean = false,
    val releasing: Boolean = false,
    val codeResolved: Boolean = false,
    val upload: UploadProgress? = null,
    val uploadFraction: Float = 0f,
    /**
     * The documents this device handed the app for the current — or, once finished, the most recent —
     * send, in the order they are sent.
     *
     * Android's picker returns a list, which cannot be reconstructed from
     * `persistedUriPermissions`, which holds every file ever granted and carries no batch. So the
     * batch is recorded here, by the code that actually received it. Kept after the transfer ends so
     * the queue can identify documents needing attention after a partial upload.
     */
    val uploadFiles: List<PickedFile> = emptyList(),
    /** 1-based position within [uploadFiles] of the file now transferring; 0 when nothing is. */
    val uploadIndex: Int = 0,
    /**
     * Names from [uploadFiles] without confirmed success — refused by the server, held back for
     * size, never attempted, or left unconfirmed by a lost response.
     *
     * Keep the attempted names for an actionable issue list. A lost response is unconfirmed:
     * neither a matching old job name nor a timeout proves what happened to this upload.
     */
    val uploadNotSent: List<String> = emptyList(),
    /** The file [uploadFailure] is about when exactly one attempted upload failed. */
    val uploadFailedFile: PickedFile? = null,
    /** Kept separately so refreshing the queue cannot erase the last upload issue. */
    val uploadFailure: PharosFailure? = null,
    val failure: PharosFailure? = null,
    val notice: String? = null,
    /** What the snackbar offers next, if anything, such as reviewing a refused release. */
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
    val transactionsFailure: PharosFailure? = null,
    /** Per-job outcome of the release that just happened, for the Result screen. */
    val outcome: ReleaseOutcome? = null,
    val releaseHistory: List<ReleaseRecord> = emptyList(),
    val checkingReleaseHistory: Boolean = false,
    val releaseHistoryFailure: PharosFailure? = null,
    val cancellingReleaseId: String? = null,
) {
    val signedIn: Boolean get() = phase == Phase.Ready && user != null

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

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val previewDownloads = Mutex()
    private val receiptChecks = Mutex()

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
    private var notificationJob: Job? = null
    private var receiptWatcher: Job? = null

    /** Called only while the signed-in app is in the foreground. Coalesce notifications during work. */
    suspend fun watchQueueChanges() {
        val target = graph.target ?: return
        val account = _state.value.user?.accountKey ?: return
        val watcher = kotlinx.coroutines.currentCoroutineContext().job
        notificationJob?.cancelAndJoin()
        notificationJob = watcher
        try {
            graph.api.watchQueueChanges(target) {
                delay(700)
                while (_state.value.busy != null || _state.value.upload != null) delay(500)
                if (_state.value.signedIn && graph.target === target && _state.value.user?.accountKey == account) refresh()
            }
        } finally {
            if (notificationJob === watcher) notificationJob = null
        }
    }

    private fun activityKey(): String? {
        val account = _state.value.user?.accountKey ?: return null
        val server = graph.target?.savedAddress ?: return null
        return printActivityKey(server, account)
    }

    private fun loadReleaseHistory(server: String, account: String): List<ReleaseRecord> =
        graph.secrets.readPrintActivity(printActivityKey(server, account))?.let {
            runCatching { PharosJson.decodeFromString(ListSerializer(ReleaseRecord.serializer()), it) }.getOrNull()
        }.orEmpty()

    private fun saveReleaseHistory() {
        val key = activityKey() ?: return
        graph.secrets.savePrintActivity(key, PharosJson.encodeToString(ListSerializer(ReleaseRecord.serializer()), _state.value.releaseHistory))
    }

    fun clearReleaseHistory() {
        if (_state.value.cancellingReleaseId != null) return
        _state.update { it.copy(releaseHistory = emptyList()) }
        saveReleaseHistory()
    }

    /** Foreground-only follow-up, bounded to five reads and one minute per new receipt batch. */
    suspend fun pollReleaseHistory() {
        val watcher = kotlinx.coroutines.currentCoroutineContext().job
        receiptWatcher?.cancelAndJoin()
        receiptWatcher = watcher
        try { withTimeoutOrNull(60_000) {
            for (pause in listOf(0L, 1_500L, 3_500L, 7_000L, 15_000L)) {
                delay(pause)
                while (_state.value.busy != null) delay(250)
                val now = System.currentTimeMillis()
                if (!_state.value.signedIn || _state.value.releaseHistory.none {
                        it.charge == null && it.requestedAt in (now - 120_000)..now
                    }) break
                checkReleaseHistory()
            }
        } } finally { if (receiptWatcher === watcher) receiptWatcher = null }
    }

    fun refreshReleaseHistory() { scope.launch { checkReleaseHistory() } }

    private suspend fun checkReleaseHistory() {
        val target = graph.target ?: return
        val key = activityKey() ?: return
        if (!_state.value.signedIn || _state.value.releaseHistory.isEmpty() || !receiptChecks.tryLock()) return
        fun current() = graph.target === target && activityKey() == key && _state.value.signedIn
        try {
            _state.update { it.copy(checkingReleaseHistory = true, releaseHistoryFailure = null) }
            val result = if (target.userUri == null) {
                when (val restored = graph.api.refreshUser(target, includeBalance = false)) {
                    is ApiResult.Err -> restored
                    is ApiResult.Ok -> readReleaseTransactions { skip, size ->
                        if (current()) graph.api.transactions(target, skip, size, newestFirst = true)
                        else ApiResult.Err(PharosFailure.NotFound("Session changed"))
                    }
                }
            } else readReleaseTransactions { skip, size ->
                if (current()) graph.api.transactions(target, skip, size, newestFirst = true)
                else ApiResult.Err(PharosFailure.NotFound("Session changed"))
            }
            if (!current()) return
            when (result) {
                is ApiResult.Err -> _state.update { it.copy(releaseHistoryFailure = result.failure) }
                is ApiResult.Ok -> {
                    _state.update { it.copy(releaseHistory = matchReleaseCharges(it.releaseHistory, result.value, System.currentTimeMillis())) }
                    saveReleaseHistory()
                }
            }
        } finally {
            if (current()) _state.update { it.copy(checkingReleaseHistory = false) }
            receiptChecks.unlock()
        }
    }

    /** The original Location reaches Secure Release even after it disappears from the user queue. */
    fun attemptReleaseCancellation(receiptId: String) {
        val target = graph.target ?: return
        val key = activityKey() ?: return
        val before = _state.value
        val receipt = before.releaseHistory.firstOrNull { it.id == receiptId } ?: return
        if (!before.signedIn || before.busy != null || before.upload != null || before.cancellingReleaseId != null ||
            !canAttemptCancellation(before.releaseHistory, receipt)) return
        val attemptedAt = System.currentTimeMillis()
        fun current() = graph.target === target && activityKey() == key && _state.value.signedIn
        fun record(result: ReleaseCancellation) {
            if (!current()) return
            _state.update { state -> state.copy(releaseHistory = state.releaseHistory.map {
                if (it.id == receiptId) it.copy(cancellation = result) else it
            }) }
            saveReleaseHistory()
        }
        _state.update { it.copy(busy = "Requesting cancellation", cancellingReleaseId = receiptId) }
        record(ReleaseCancellation(CancelVerdict.Unconfirmed, "Cancellation requested; no acknowledgement received yet.", attemptedAt))
        scope.launch {
            try {
                when (val result = graph.api.deleteJobs(target, PrintRequests.deleteLocations(listOf(receipt.jobLocation)))) {
                    is ApiResult.Err -> record(unconfirmedCancellation(result.failure, attemptedAt))
                    is ApiResult.Ok -> record(cancellationVerdict(result.value, receipt.jobLocation, attemptedAt))
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) { record(unconfirmedCancellation(PharosFailure.Unknown(error), attemptedAt))
            } finally {
                if (current()) _state.update { it.copy(busy = null, cancellingReleaseId = null) }
            }
            if (current()) refresh()
        }
    }

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
            _state.update { it.copy(host = target.displayHost) }
            /*
             * The offline snapshot goes on screen before anything is asked of the network: a cold
             * start with no signal then shows the held jobs and the cached balance with their age
             * on them, instead of a spinner. It is marked `stale`, so the queue's offline card and
             * the grey hero say exactly how much to trust it — the same rendering a failed refresh
             * produces, never a modal (Spec §2.0.7 `offline_stale`).
             */
            val restored = graph.snapshot.loadAsync()
            if (creds != null && restored != null && restored.snapshot.host == target.displayHost) {
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
                        releaseHistory = restored.user?.let { user -> loadReleaseHistory(target.savedAddress, user.accountKey) }.orEmpty(),
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
        notificationJob?.cancel()
        receiptWatcher?.cancel()
        if (_state.value.busy != null) return
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
        if (_state.value.busy != null || username.isBlank() || password.isBlank()) return
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
        signIn(target, Credentials(username.trim(), password, remember), announce = true)
    }

    private fun signIn(target: PharosTarget, creds: Credentials, announce: Boolean) {
        notificationJob?.cancel()
        receiptWatcher?.cancel()
        scope.launch {
            _state.update { it.copy(phase = Phase.Working, busy = "Signing in", failure = null) }
            graph.api.credentials = creds
            when (val result = graph.api.logon(target, creds)) {
                is ApiResult.Err -> {
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
                    graph.api.credentials = null
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
                    if (creds.rememberMe) graph.secrets.saveCredentials(creds) else graph.secrets.forgetCredentialOnly()
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
                            jobs = if (it.user?.accountKey == account) it.jobs else emptyList(),
                            selection = if (it.user?.accountKey == account) it.selection else emptySet(),
                            transactions = if (it.user?.accountKey == account) it.transactions else emptyList(),
                            outcome = null,
                            releaseHistory = loadReleaseHistory(target.savedAddress, account),
                            costCenter = remembered,
                            favouriteDevices = graph.prefs.favouriteDevicesFor(account),
                            recentDevices = graph.prefs.recentDevicesFor(account),
                            savedCostCenters = graph.prefs.savedCostCentersFor(account),
                            apiVersion = graph.api.apiVersion ?: it.apiVersion,
                            capabilities = doc?.capabilities(graph.api.apiVersion, result.value),
                            busy = null,
                            failure = null,
                            notice = if (creds.rememberMe && !graph.secrets.encryptedAtRest)
                                "Signed in for this session. Secure storage is unavailable, so your password was not saved."
                            else if (announce) null else "Signed in as ${result.value.preferredName.ifBlank { creds.username }}",
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
        if (_state.value.busy != null || !_state.value.signedIn) return
        // A deliberate look at the queue takes over from the background one. The two must not race
        // for the same page, and this path starts the next background look itself when it lands.
        analysisWatcher?.cancel()
        scope.launch {
            _state.update { it.copy(busy = "Refreshing") }
            // An offline cold start has no user route yet. Restore it before asking for jobs.
            val restoredUser = if (target.userUri == null) graph.api.refreshUser(target, includeBalance = true) else null
            val jobs = readLoadedQueue(target)
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
            val user = restoredUser ?: graph.api.refreshUser(target, includeBalance = true)
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
    private suspend fun readLoadedQueue(target: PharosTarget): ApiResult<Page<PrintJob>> {
        val first = graph.api.printJobs(target, skip = 0)
        if (first !is ApiResult.Ok) return first
        var page = first.value
        val jobs = page.items.associateBy { it.location }.toMutableMap()
        val wanted = _state.value.jobs.size
        val visited = mutableSetOf<String>()
        while (jobs.size < wanted && page.hasMoreAfter(jobs.size)) {
            val next = page.absoluteNextPage ?: break
            if (!visited.add(next)) break
            when (val result = graph.api.nextPage(target, page)) {
                is ApiResult.Err -> return result
                is ApiResult.Ok -> {
                    page = result.value
                    val before = jobs.size
                    jobs.putAll(page.items.associateBy { it.location })
                    if (jobs.size == before) { page = page.copy(nextPageLink = null); break }
                }
            }
        }
        return first.copy(value = page.copy(items = jobs.values.toList(), count = first.value.count))
    }

    private fun applyJobsPage(page: Page<PrintJob>) {
        savedJobsPage = page
        _state.update {
            it.copy(
                jobs = page.items.distinctBy { it.location },
                selection = it.selection.intersect(page.items.map { job -> job.location }.toSet()),
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
     * Polling remains available when the notification connection cannot be established. A document GMU has
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
                when (val jobs = readLoadedQueue(target)) {
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
        if (_state.value.busy != null || _state.value.nextJobsPage == null) return
        val page = savedJobsPage?.takeIf { it.hasMoreAfter(_state.value.jobs.size) } ?: return
        scope.launch {
            _state.update { it.copy(busy = "Loading more jobs") }
            val more = graph.api.nextPage(target, page)
            if (more is ApiResult.Ok) {
                savedJobsPage = more.value
                val merged = (_state.value.jobs + more.value.items).distinctBy { it.location }
                _state.update {
                    it.copy(
                        jobs = merged,
                        nextJobsPage = if (merged.size > it.jobs.size && more.value.hasMoreAfter(merged.size)) more.value else null,
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
        if (sources.isEmpty() || _state.value.upload != null || !_state.value.signedIn) return
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
                    uploadFailure = PharosFailure.TooLarge(limit, oversizeSentence(plan.overLimit, limit), locallyGated = true),
                    failure = null,
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
        // Reserve the upload before launching so the queue shows progress immediately and another
        // picker/share callback cannot start a second batch during coroutine dispatch.
        _state.update {
            it.copy(
                upload = UploadProgress(),
                uploadFraction = 0f,
                uploadFiles = files,
                uploadIndex = sources.indexOf(sending.first()) + 1,
                uploadNotSent = plan.overLimit.map { source -> source.fileName },
                uploadFailedFile = null,
                uploadFailure = null,
                failure = null,
                busy = sendingLabel(sending.first().fileName, 1, sending.size),
            )
        }
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
                        uploadIndex = sources.indexOf(source) + 1,
                        // A refusal about file 2 of 4 must not still be on screen while file 3 goes up.
                        uploadNotSent = plan.overLimit.map { source -> source.fileName } + refused,
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
             * Preserve the upload issue separately from refresh failures, and re-read the queue at
             * the end. A status code alone is not enough to act on: when GMU answered a real upload
             * with `405` (wrong URL — see [dev.ahnafnafee.masonprint.data.net.uploadUrl]), the queue still said
             * "0 on the server", and the only way a student could tell that nothing had arrived was
             * to pull to refresh themselves. The separate uploadFailure survives a refresh that
             * fails too, while the remembered queue is replaced with whatever the server actually
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
                    busy = null,
                    uploadFailure = if (refused.isEmpty()) {
                        if (plan.overLimit.isEmpty()) null else PharosFailure.TooLarge(limit, null, locallyGated = true)
                    } else lastFailure,
                    // Certificate approval still uses the shared trust prompt. Do not let an
                    // immediate refresh replace the pending certificate with another failure.
                    failure = (lastFailure as? PharosFailure.TlsNotTrusted) ?: it.failure,
                    notice = summary,
                    noticeAction = null,
                )
            }
            if (lastFailure !is PharosFailure.TlsNotTrusted) refresh()
        }
    }

    // ------------------------------------------------------------------ devices & release

    fun loadDevices() {
        if (_state.value.busy != null) return
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
                        val before = all.size
                        all += page.items.filter { device -> all.none { it.location == device.location } }
                        if (all.size == before) break
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
    suspend fun documentFor(job: PrintJob): java.io.File? = previewDownloads.withLock {
        val target = graph.target ?: return@withLock null
        val identity = "${target.root}|${_state.value.user?.accountKey}|${job.location}"
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = java.io.File(graph.app.cacheDir, "preview").apply { mkdirs() }
        val dest = java.io.File(dir, "$key.pdf")
        if (dest.exists() && dest.length() > 0L) return@withLock dest
        val partial = java.io.File.createTempFile("download-", ".part", dir)
        try {
            when (val result = graph.api.jobContent(target, job.location, partial)) {
                is ApiResult.Ok -> withContext(Dispatchers.IO) {
                    if (partial.length() > 0 && partial.renameTo(dest)) dest else null
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(failure = result.failure) }
                    null
                }
            }
        } finally {
            partial.delete()
        }
    }

    /** Resolving a code only chooses a printer. Release always requires the review screen. */
    fun resolveDeviceToken(token: String) {
        val target = graph.target ?: return
        if (_state.value.busy != null || !_state.value.signedIn) return
        scope.launch {
            _state.update { it.copy(busy = "Reading printer code", codeResolved = false) }
            when (val result = graph.api.deviceById(target, token)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(selectedDevice = result.value, busy = null, codeResolved = true, failure = null)
                }
                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
            }
        }
    }

    fun consumeResolvedCode() = _state.update { it.copy(codeResolved = false) }

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
        if (jobs.isEmpty() || jobs.any { !it.pending } || device == null || _state.value.busy != null || _state.value.releasing) return
        val names = jobs.associate { it.location to (it.name ?: it.location.substringAfterLast('/')) }
        val before = _state.value.balanceText
        val intent = _state.value.fundingLabel
        val printer = device.label
        val requestedAt = System.currentTimeMillis()
        val batch = java.util.UUID.randomUUID().toString()
        fun record(accepted: Set<String>, refused: Set<String> = emptySet()) {
            val additions = jobs.filter { it.location !in refused }.map { job ->
                ReleaseRecord("$batch:${job.location}", job.location, names[job.location].orEmpty(),
                    device.location, printer, requestedAt, job.location in accepted)
            }
            _state.update { it.copy(releaseHistory = mergeReleaseHistory(
                it.releaseHistory.filterNot { receipt -> receipt.id.startsWith("$batch:") }, additions)) }
            saveReleaseHistory()
        }
        _state.update { it.copy(busy = "Releasing ${jobs.size} job(s)", releasing = true) }
        // Persist the attempt before sending: process death or a lost response leaves an honest receipt.
        record(emptySet())
        scope.launch {
            val body = PrintRequests.release(jobs, device.location, _state.value.user?.cardId, costCenterCode = costCenter)
            when (val result = graph.api.release(target, body)) {
                is ApiResult.Err -> {
                    record(emptySet())
                    val failure = result.failure
                    _state.update {
                        it.copy(
                            busy = null,
                            releasing = false,
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
                    record(moved.toSet(), op.failures.mapNotNull { r -> r.strIn("Location", "JobLocation") }.toSet())
                    val after = op.updatedUser?.let { u ->
                        _state.value.capabilities?.formats?.money(u.balance?.amount ?: u.balance?.total)
                    } ?: before
                    /*
                     * Remember the machine only when the server accepted a release. A
                     * refused release is not a visit, and recording one would put a printer the
                     * student never successfully used at the top of their list.
                     */
                    val account = _state.value.user?.accountKey ?: graph.prefs.lastAccount
                    if (moved.isNotEmpty() && account != null) {
                        graph.prefs.noteDeviceUsed(account, device.location)
                    }
                    _state.update {
                        it.copy(
                            busy = null,
                            releasing = false,
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
            _state.update { it.copy(loadingTransactions = true, transactionsFailure = null) }
            when (val result = graph.api.transactions(target, skip = 0, pageSize = 50)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(loadingTransactions = false, transactions = result.value.items)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(loadingTransactions = false, transactionsFailure = result.failure)
                }
            }
        }
    }

    /**
     * Apply only the edited, supported fields to each job, then verify the stored settings.
     * A document can forbid colour changes while still accepting sides and copies.
     */
    fun applyFinishing(jobs: List<PrintJob>, edits: FinishingEdits) {
        val target = graph.target ?: return
        if (jobs.isEmpty() || _state.value.updatingFinishing) return
        val deviceLocation = _state.value.selectedDevice?.location
        val costCenterCode = _state.value.costCenter
        scope.launch {
            _state.update { it.copy(busy = "Updating ${jobs.size} job(s)", updatingFinishing = true) }
            try {
                // Keep per-job writes and bounded verification retries. Unsupported fields are
                // preserved before sending, so they cannot trigger an endless false mismatch.
                val wantedByLocation = jobs.associate { it.location to edits.optionsFor(it) }
                val restrictions = jobs.mapNotNull { job ->
                    edits.unsupportedFor(job).takeIf { it.isNotEmpty() }?.let { fields ->
                        "${job.name ?: "Document"}: ${fields.joinToString(" and ")} cannot be changed."
                    }
                }
                val refused = mutableMapOf<String, String>() // location -> the server's sentence
                var remaining = jobs.filter { wantedByLocation.getValue(it.location) != FinishingPayload.from(it.finishing) }
                var readFailure: PharosFailure? = null
                pass@ for (pass in 0 until 3) {
                    if (remaining.isEmpty()) break@pass
                    for (job in remaining) {
                        val body = PrintRequests.update(
                            jobs = listOf(job),
                            finishing = wantedByLocation.getValue(job.location),
                            deviceLocation = deviceLocation,
                            costCenterCode = costCenterCode,
                        )
                        when (val result = graph.api.patchJobs(target, body)) {
                            is ApiResult.Err -> {
                                _state.update { it.copy(busy = null, failure = result.failure) }
                                return@launch
                            }

                            is ApiResult.Ok -> if (result.value.failures.isNotEmpty()) {
                                refused[job.location] =
                                    result.value.failures.firstNotNullOfOrNull { r ->
                                        cleanSentence(r.strIn("UserMessage", "Message")?.htmlUnescaped())
                                    } ?: (job.name ?: job.location)
                            }
                        }
                        delay(350)
                    }
                    // What actually stuck? A fresh read of the queue, not the PATCH's own echo.
                    when (val fresh = graph.api.printJobs(target, skip = 0)) {
                        is ApiResult.Ok -> {
                            val stored = fresh.value.items.associateBy { it.location }.toMutableMap()
                            var page = fresh.value
                            val visited = mutableSetOf<String>()
                            while (remaining.any { it.location !in stored } && page.hasMoreAfter(stored.size)) {
                                val next = page.absoluteNextPage ?: break
                                if (!visited.add(next)) break
                                when (val more = graph.api.nextPage(target, page)) {
                                    is ApiResult.Err -> { readFailure = more.failure; break }
                                    is ApiResult.Ok -> {
                                        page = more.value
                                        val before = stored.size
                                        stored.putAll(page.items.associateBy { it.location })
                                        if (stored.size == before) break
                                    }
                                }
                            }
                            // Publish the read-back before allowing another edit, keeping loaded
                            // documents and selection beyond the first page intact.
                            _state.update { state ->
                                state.copy(
                                    jobs = state.jobs.map { stored[it.location] ?: it },
                                    jobsCount = fresh.value.count,
                                    loadedAt = System.currentTimeMillis(),
                                    stale = readFailure != null,
                                )
                            }
                            remaining = remaining.filterNot { job -> refused.containsKey(job.location) }
                                .filter { job ->
                                    val fin = stored[job.location]?.finishing
                                    val wanted = wantedByLocation.getValue(job.location)
                                    // Null finishing is "not stored", not "stored as we asked".
                                    fin == null ||
                                        fin.mono != wanted.mono ||
                                        fin.duplex != wanted.duplex ||
                                        (fin.copies ?: 1L) != wanted.copies ||
                                        (fin.pagesPerSide ?: 1L) != wanted.pagesPerSide
                                }
                            if (readFailure != null) break@pass
                        }

                        is ApiResult.Err -> {
                            readFailure = fresh.failure
                            break@pass
                        }
                    }
                }
                val unchanged = remaining.size
                _state.update {
                    it.copy(
                        busy = null,
                        failure = readFailure ?: it.failure,
                        notice = (listOf(when {
                            readFailure != null -> "Changes were sent, but could not be verified. Refresh the queue before releasing."

                            refused.isNotEmpty() ->
                                refused.values.distinct().joinToString(" · ") +
                                    " — ${refused.size} of ${jobs.size} job" +
                                    "${if (refused.size == 1) "" else "s"} refused."

                            unchanged > 0 ->
                                "$unchanged of ${jobs.size} job${if (unchanged == 1) "" else "s"} would not " +
                                    "change — the server kept its own setting. Pull to refresh and check " +
                                    "before you release."

                            else -> "Saved the supported settings for ${jobs.size} job${if (jobs.size == 1) "" else "s"}."
                        }) + restrictions).joinToString(" "),
                    )
                }
            } finally {
                _state.update { it.copy(busy = null, updatingFinishing = false) }
            }
        }
    }

    fun deleteJobs(jobs: List<PrintJob>) {
        val target = graph.target ?: return
        if (jobs.isEmpty() || _state.value.busy != null || _state.value.upload != null) return
        _state.update { it.copy(busy = "Deleting ${jobs.size} job(s)") }
        scope.launch {
            when (val result = graph.api.deleteJobs(target, PrintRequests.delete(jobs))) {
                is ApiResult.Err -> _state.update { it.copy(busy = null, failure = result.failure) }
                is ApiResult.Ok -> {
                    val op = result.value
                    val gone = op.actedLocations.size
                    _state.update {
                        it.copy(
                            busy = null,
                            selection = it.selection - op.actedLocations.toSet(),
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
    fun previewCost(jobs: List<PrintJob>, onResult: (CostPreview) -> Unit): Job? {
        val target = graph.target ?: return null
        if (jobs.isEmpty()) return null
        val funding = _state.value.fundingLabel
        val body = PrintRequests.cost(
            jobs,
            _state.value.selectedDevice?.location,
            _state.value.costCenter,
        )
        return scope.launch {
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
                            blocked = !estimate.covers(jobs.map { it.location }.toSet()),
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
        if (_state.value.busy == "Signing out") return
        val watcher = notificationJob
        watcher?.cancel()
        val receipts = receiptWatcher
        receipts?.cancel()
        val previous = scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        _state.update { it.copy(phase = Phase.Working, busy = "Signing out", user = null) }
        scope.launch {
            // Await cancellation before clearing state: an old response must not restore an account.
            previous.coroutineContext.job.cancelAndJoin()
            watcher?.join()
            receipts?.join()
            withTimeoutOrNull(5_000) { graph.api.logout(target) }
            graph.forgetSession()
            forgetWebSession()
            settings = null
            savedJobsPage = null
            _state.value = AppState(
                phase = Phase.SignedOut,
                host = target.displayHost,
                apiVersion = graph.prefs.lastApiVersion,
                notice = "Signed out. This phone's session, cached queue, and release receipts were cleared.",
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

    fun dismissUploadIssue() = _state.update {
        if (it.upload != null) it else it.copy(
            uploadNotSent = emptyList(), uploadFailedFile = null, uploadFailure = null,
        )
    }

    fun dismissNotice(expected: String? = _state.value.notice) = _state.update {
        if (it.notice == expected) it.copy(notice = null, noticeAction = null) else it
    }

    fun notify(message: String) = _state.update { it.copy(notice = message, noticeAction = null) }

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
            ?: "/myprintcenter"
        return target.root.resolve(base)?.newBuilder()
            ?.setQueryParameter("NoHeaderMode", "1")
            ?.setQueryParameter("cachebuster", System.nanoTime().toString())?.build()?.toString()
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
    private suspend fun forgetWebSession() {
        val cookies = android.webkit.CookieManager.getInstance()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            cookies.removeAllCookies {
                cookies.flush()
                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
            }
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
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
