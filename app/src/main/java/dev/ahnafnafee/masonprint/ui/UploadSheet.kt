@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.ahnafnafee.masonprint.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrintDisabled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.PickedFile
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.UploadFactory
import dev.ahnafnafee.masonprint.data.model.Capabilities
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.data.upload.MimeTypes
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import java.util.Locale

/**
 * Send a document — REDESIGN-SPEC §3.6, the screen the whole redesign exists for.
 *
 * It is a **destination**, not a `ModalBottomSheet`. The Spec and the prototype draw it as a sheet,
 * but the sheet in the prototype is a fixture: the transfer in it never leaves the composable. Here
 * the transfer lives in [Session], which outlives the rotation and the share hand-off, so the screen
 * that watches it has to be somewhere Back can return from and rotation can rebuild — a sheet
 * dismissed by a stray drag would take the progress bar with it and leave the user staring at the
 * queue wondering whether 40 MB is still in flight. The Spec's own reason for preferring the native
 * picker (docs/FINDINGS.md §7.2: the stock app lost the upload result with the Activity) is the same
 * reason, applied one layer up.
 *
 * What this screen can honestly do:
 *
 *  * say what this server takes before any bytes are spent — [Capabilities.maxUploadBytes],
 *    [Capabilities.documentTypeCategories], and which of the two server switches closed uploads;
 *  * hand off to Android's own picker ([onPickDocument]) — never a WebView file input, which in the
 *    vendor's embedded Print Center meant "Print from Gmail", four taps and a login;
 *  * watch [AppState.upload] / [AppState.uploadFraction], which count bytes **written**, and say
 *    what that means: a stalled bar is a dead connection, while [PharosFailure.TooLarge] and
 *    [PharosFailure.UnsupportedType] are the server refusing;
 *  * state where the money is: nothing at upload, everything at release.
 *
 * What it cannot honestly do is offer controls the wire would ignore. [Session.upload] takes one
 * [dev.ahnafnafee.masonprint.data.net.UploadSource] and nothing else — the finishing that goes with the file
 * comes from this server's
 * own `PrintCenter.FinishingOptions`, and [AppState] carries no field for the user's choices — so
 * the copies stepper and the three segmented rows of §3.6 are described here rather than rendered as
 * dead widgets, exactly as the queue's `Copies` dialog is a read-back for the same reason.
 *
 * Picking files **is** sending them: `MainActivity`'s `OpenMultipleDocuments` callback calls
 * [Session.uploadAll] directly, because only the Activity owns `registerForActivityResult`. The sheet
 * therefore never stages a pick; it reads the batch the session recorded (and, failing that, the
 * persistable read permission `MainActivity.persist` leaves behind) so the file cards, the arithmetic
 * in a refusal, and the streaming line have real numbers instead of fixture ones.
 *
 * The batch is shown as one row per document with its own status, because "Uploaded" after two of
 * four files arrived is the exact lie this app was built not to tell.
 */
@Composable
fun UploadSheet(
    state: AppState,
    session: Session,
    router: Router,
    onPickDocument: () -> Unit,
) {
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    val caps = state.capabilities
    val uploading = state.upload != null

    // What this device last handed the app, for the case where the sheet was reopened with no batch
    // in `AppState`. `remember`, because resolving it opens a file descriptor to stat the size; the
    // keys are the moments at which "the last file" can have changed.
    val restored = remember(context, uploading, state.busy, state.failure) {
        lastPickedFiles(context)
    }
    val files = state.uploadFiles.ifEmpty { restored }
    val current = files.getOrNull(state.uploadIndex - 1)

    val shown by animateFloatAsState(
        targetValue = state.uploadFraction,
        animationSpec = if (reduced) snap() else tween(240),
        label = "upload-fraction",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload a document") },
                navigationIcon = {
                    IconButton(onClick = { router.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { SendDock(state) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(MasonScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (uploading) {
                SendingCard(state, current, shown, router)
            }

            // The per-file list comes before the refusal, not after: with several documents in
            // flight the reader's first question is "which one?", and the answer is the card with
            // one row and one status per file. The failure card below then quotes the server about
            // the file it is actually about.
            if (files.isNotEmpty()) {
                FilesCard(state, files)
                val unfamiliar = files.firstOrNull { !MimeTypes.isSupported(it.extension) }
                if (uploading && unfamiliar != null) {
                    NoteCard(
                        title = "This type is not on the list",
                        body = ".${unfamiliar.extension} is not among the extensions this client " +
                            "knows. You can send it anyway. The server decides, and it may " +
                            "refuse the upload.",
                        icon = Icons.Filled.Error,
                        tone = MasonTone.Warn,
                    )
                }
            }

            UploadFailures(state, session, state.uploadFailedFile, onPickDocument, router)

            PickCard(state, files.isNotEmpty(), caps != null, onPickDocument)
            if (caps != null && !state.canPrint) {
                UploadClosedCard(caps, router)
            }
            LimitsCard(caps)
            FinishingCard()
            ChargeToSection(state, caps, session, router)

            Spacer(Modifier.height(MasonScrollSpacer))
        }
    }
}

// ------------------------------------------------------------------ the file, and the transfer --

/**
 * The fallback "what was picked" list: at most the newest document this device handed the app,
 * resolved through the same factory the upload itself uses so the name and size on screen are the
 * name and size that would have gone on the wire.
 *
 * `MainActivity.persist` takes a persistable read grant so this works after a rotation. It is a
 * fallback only — the platform's grant list holds every file ever permitted and has no notion of a
 * batch, so it cannot reconstruct a multi-file pick. The batch itself lives in
 * [AppState.uploadFiles], recorded by the code that received it.
 */
private fun lastPickedFiles(context: Context): List<PickedFile> =
    runCatching {
        val newest = context.contentResolver.persistedUriPermissions
            .filter { permission ->
                val scheme = permission.uri?.scheme
                scheme == "content" || scheme == "file"
            }
            // `UriPermission.persistedTime` is what the framework exposes publicly here;
            // INVALID_TIME (0) sorts to the back, which is the right answer for a grant the
            // platform cannot date.
            .maxByOrNull { it.persistedTime }
        newest?.let { UploadFactory.fromUri(context, it.uri) }?.let { listOf(PickedFile.of(it)) }
    }.getOrNull().orEmpty()

@Composable
private fun SendingCard(state: AppState, picked: PickedFile?, shown: Float, router: Router) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RowAvatar(Icons.Filled.UploadFile)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Uploading to ${state.host ?: "the print server"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    picked?.let {
                        Text(
                            it.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.uploadPosition?.let {
                        Text(
                            "File $it in this pick",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MonoDetail(streamingDetail(shown, picked?.sizeBytes))
                }
            }
            if (shown <= 0f) {
                MonoDetail("The request is open; no bytes have been accepted for writing yet.")
            } else {
                UploadProgressBar(shown, "${(shown * 100).toInt()} %")
            }
            Text(
                "The bar counts bytes written to the socket. No answer yet is not the server " +
                    "refusing the file. A refusal is a card with the server's own words in it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.uploadPosition != null) {
                Text(
                    "Pharos has no batch upload: several files go up one request at a time, each " +
                        "accepted or refused on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { router.reset(Route.Queue) }) {
                Text("Watch the queue instead")
            }
        }
    }
}

/**
 * The file card of §3.6, one row per document: icon, name, and the monospaced size line.
 *
 * `upMeta` in the prototype reads `1.2 MB · Word document (.docx) · 12 pages`. The last field is
 * the one this build cannot claim: the page count is produced by the server during conversion
 * (`Activity.Steps` → `PageCounting`), never by the client reading the file, so the card says the
 * two things it knows and names the third as the server's answer.
 *
 * "In the queue" is checked against the queue the server actually returned, not against the upload
 * having answered 201. That distinction is the whole reason this screen is honest: a pick of four
 * files where two came back 201 and two were refused has to show four rows with four different
 * statuses, which is more than the stock app's single `PendingFileUpload` field could represent.
 */
@Composable
private fun FilesCard(state: AppState, files: List<PickedFile>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (files.size > 1) {
                SectionLabel("This pick · ${files.size} documents")
            }
            files.forEachIndexed { index, file ->
                FileRow(state, index, file)
            }
            if (state.upload == null && files.none { jobNamed(state.jobs, it.name) != null } &&
                files.none { it.name in state.uploadNotSent }
            ) {
                Text(
                    if (files.size == 1) {
                        "Not by itself proof that the server has it. The queue is."
                    } else {
                        "None of these are in the queue below. Pull to refresh if you expect them there."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileRow(state: AppState, index: Int, file: PickedFile) {
    val inQueue = jobNamed(state.jobs, file.name)
    val sending = state.upload != null && state.uploadIndex == index + 1
    val notSent = file.name in state.uploadNotSent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowAvatar(if (sending) Icons.Filled.UploadFile else Icons.Filled.Description)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            MonoDetail(fileMetaText(file.name, file.mimeType, file.sizeBytes))
            if (inQueue != null) {
                /*
                 * A queue match is by *name*, and names repeat — re-sending an edited essay finds the
                 * old job still sitting there. While this row's own bytes are in flight, "It is in the
                 * queue" would describe the earlier send, and a student who believed it would walk
                 * away before this one had landed. Verified on-device with `mp262-configurator.pdf`,
                 * which was in the queue at $1.10 while its second copy was still uploading.
                 */
                Text(
                    if (sending) {
                        sendingRowNote(file.name, inQueue.costText(state))
                    } else {
                        "It is in the queue" +
                            (inQueue.costText(state)?.let { ". Priced at $it" } ?: "") +
                            ". Nothing has left your balance for it yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            sending -> StatusChip(Icons.Filled.Schedule, "Sending", MasonTone.Warn)

            notSent -> StatusChip(Icons.Filled.Error, "Not sent", MasonTone.Error)

            inQueue != null -> StatusChip(Icons.Filled.CheckCircle, "In the queue", MasonTone.Ok)

            else -> StatusChip(Icons.Filled.Info, "In this pick", MasonTone.Neutral)
        }
    }
}

/**
 * What the row of the file currently going up may say about a queue match.
 *
 * Separate from the settled wording because the two states must not share a sentence: one is a
 * statement about a job, the other is about a transfer that has not finished.
 */
internal fun sendingRowNote(fileName: String, priced: String?): String =
    "A job called $fileName sent earlier is " +
        (priced?.let { "in the queue at $it" } ?: "already in the queue") +
        ". The copy going up now has not arrived yet."

// ---------------------------------------------------------------------- what the server allows --

@Composable
private fun PickCard(
    state: AppState,
    hasFile: Boolean,
    gateExplainedBelow: Boolean,
    onPickDocument: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RowAvatar(Icons.Filled.Print)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Choose files, and they start uploading", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The picker is Android's own. Long-press the first file and tap the rest to " +
                            "upload a stack at once. Choosing is the upload. There is no second " +
                            "button, and it survives rotating the phone or going back to the queue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = onPickDocument,
                enabled = state.canPrint && state.upload == null,
            ) {
                ButtonGlyph(Icons.Filled.UploadFile)
                Text(if (hasFile) "Choose more documents" else "Choose documents")
            }
            when {
                !state.canPrint && gateExplainedBelow -> Text(
                    "The card below says which server switch is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                !state.canPrint -> Text(
                    "The server has not said whether uploads are allowed yet, so it cannot promise " +
                        "the file will be taken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Uploads closed: name **which** of the two independent switches did it.
 *
 * `PrintCenter."Web Upload"` and `SecureRelease."Document Upload"` are separate settings with
 * separate owners, and the remedy differs — one is a campus-wide switch, the other belongs to the
 * release server. Guessing "uploading is disabled" is what makes a student email the service desk
 * with no details.
 */
@Composable
private fun UploadClosedCard(caps: Capabilities, router: Router) {
    NoteCard(
        title = "This server will not take an upload",
        body = (caps.uploadBlockReason ?: "This account cannot upload documents") +
            " Mason Print cannot change it. Printing from a lab machine still works, and the web " +
            "Print Center is the place to check whether uploading is allowed for you at all.",
        icon = Icons.Filled.PrintDisabled,
        tone = MasonTone.Warn,
        action = {
            OutlinedButton(onClick = { router.push(Route.PrintCenter) }) {
                Text("Open the Print Center")
            }
        },
    )
}

@Composable
private fun LimitsCard(caps: Capabilities?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("What this server takes")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    acceptedTypesSentence(caps?.maxUploadBytes, caps?.documentTypeCategories.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (caps == null) {
                        StatusChip(Icons.Filled.Info, "Server settings not read yet", MasonTone.Neutral)
                    } else {
                        SwitchChip(caps.webUploadAllowed, "Web upload")
                        SwitchChip(caps.uploadAllowedByRelease, "Upload at release")
                        if (caps.maxUploadBytes == null) {
                            StatusChip(Icons.Filled.Info, "No published size limit", MasonTone.Neutral)
                        }
                        if (caps.previewAllowed) {
                            FactChip(Icons.Filled.Check, "Preview allowed")
                        }
                    }
                }
            }
        }
    }
}

/** A switch's state as icon + word, never as colour alone. */
@Composable
private fun SwitchChip(allowed: Boolean, label: String) {
    StatusChip(
        icon = if (allowed) Icons.Filled.Check else Icons.Filled.Close,
        label = if (allowed) "$label allowed" else "$label off",
        tone = if (allowed) MasonTone.Ok else MasonTone.Error,
    )
}

/**
 * Finishing, described rather than offered.
 *
 * §3.6 draws a copies stepper, three segmented rows and a page-size dropdown. None of them can be
 * wired without inventing a `Session` method, which the brief forbids: [Session.upload] builds the
 * job from the server's own `PrintCenter.FinishingOptions`, and [AppState] carries no finishing
 * field to write into. A stepper that changes nothing on the wire is worse than a sentence.
 */
@Composable
private fun FinishingCard() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Finishing")
        Text(
            "Colour, sides, pages per side, copies and page size come from what this server " +
                "publishes as its defaults. Once the job is in the queue, the queue's Copies dialog " +
                "reads back what the server recorded for it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------- money, and who pays --

/**
 * Funding — shown, chosen, and explicitly **not** attached at upload.
 *
 * GMU answers a cost centre written onto a job before it has been costed with
 * `Improper data. (Cost Center information is invalid because invalid separator used )`, the job
 * never gains `Release`, and a `PATCH` that returns 200 does not prove the write took — GMU echoes
 * `CostCenterCode: ""`. So the choice here is a preference the user repeats at the printer; the
 * app does not pre-PATCH it and does not claim it took.
 */
@Composable
private fun ChargeToSection(
    state: AppState,
    caps: Capabilities?,
    session: Session,
    router: Router,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Charge to")
        val centres = caps?.usableCostCenters.orEmpty()
        if (caps?.costCentersAllowed != true) {
            NoteCard(
                title = "No cost center here",
                body = "This server does not let your account charge a job to a department. " +
                    "Every page comes out of your own balance.",
                icon = Icons.Filled.Work,
                tone = MasonTone.Neutral,
            )
            return@Column
        }

        FundingRow(
            title = "My own balance",
            subtitle = state.balanceText?.let { "Print balance: $it" } ?: "Comes out of your balance",
            icon = Icons.Filled.AccountBalanceWallet,
            badge = null,
            selected = state.costCenter.isNullOrBlank(),
            onClick = { session.setCostCenter(null) },
        )
        centres.forEach { centre ->
            FundingRow(
                title = centre.code,
                subtitle = centre.description ?: "Cost center",
                icon = Icons.Filled.BusinessCenter,
                badge = if (centre.granted) "Grant" else null,
                selected = state.costCenter.equals(centre.code, ignoreCase = true),
                onClick = { session.setCostCenter(centre.code) },
            )
        }
        OutlinedButton(onClick = { router.push(Route.CostCenters) }) {
            Text("Find another cost center")
        }

        Text(
            "The centre is applied when you release, not at upload: GMU rejects a centre written " +
                "onto a job before the server has priced it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Selection is charcoal (`secondaryContainer`), never green — green means the campus or the
 * department has already paid for something. An unselected row carries a hairline border so the
 * pair is not distinguished by fill alone.
 */
@Composable
private fun FundingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) cs.secondaryContainer else cs.surfaceContainerLow,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        border = if (selected) null else BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (badge != null) FactChip(Icons.Filled.Check, badge, tone = MasonTone.Grant)
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Chosen", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The dock where §3.6's cost readout sits — with no number, on purpose.
 *
 * The prototype's `$0.10 · 4 copies` is fabricated from the flags in its own fixture. GMU prices a
 * document after converting it (`POST {UserUri}/printjobs/cost`), a job it cannot price comes back
 * `-1` and will not release, and `0` means free; none of that is knowable at pick time, so the dock
 * says when the money moves instead of inventing how much.
 */
@Composable
private fun SendDock(state: AppState) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            MasonHairline(Modifier.fillMaxWidth())
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Cost at release")
                        Text(
                            "You are charged when you release this at a printer, not now.",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    FactChip(Icons.Filled.Work, state.fundingLabel)
                }
                Text(
                    "Uploading costs nothing. The server prices the job after it converts it, and " +
                        "that price rides on the job in the queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ refusals, in words ----

/**
 * One card per upload refusal, each carrying what the server actually said.
 *
 * §5.3's port note is the rule here: the client-side checks are *mirrors* of the server's 413 and
 * 415, so a refusal is attributed to the server and quoted, never paraphrased into "Upload failed"
 * the way the stock app's class-name lookup did.
 */
@Composable
private fun UploadFailures(
    state: AppState,
    session: Session,
    failed: PickedFile?,
    onPickDocument: () -> Unit,
    router: Router,
) {
    val failure = state.failure ?: return
    val dismissThen = {
        session.dismissFailure()
        onPickDocument()
    }
    when (failure) {
        is PharosFailure.TooLarge -> UploadFailureCard(
            title = if (state.uploadFiles.size > 1) "The server will not take one of these files" else "The server will not take this file",
            icon = Icons.Filled.Error,
            tone = MasonTone.Error,
            // A locally-gated refusal is *this app's* arithmetic against the limit the server
            // published, so it is shown as a line, not inside "The server said:".
            quote = failure.serverText?.takeIf { !failure.locallyGated },
            mono = oversizeArithmetic(failed?.sizeBytes, failure.limitBytes) ?: failure.detailLine(),
            lines = listOfNotNull(
                failure.serverText?.takeIf { failure.locallyGated },
                when {
                    state.uploadFraction > 0f ->
                        "The transfer had reached ${(state.uploadFraction * 100).toInt()}% when it " +
                            "stopped. Check the queue before sending it again. If the job is already " +
                            "there, sending it twice prints twice."

                    failure.locallyGated ->
                        "Held back before any bytes left the device, against the limit this server " +
                            "publishes. The server never saw it."

                    else -> null
                },
                "Split the file, or print it from a lab machine.",
                if (state.uploadFiles.size > 1) siblingsStillSent(state) else "Nothing has been uploaded.",
            ),
            actions = {
                OutlinedButton(onClick = dismissThen) { Text("Choose another file") }
            },
        )

        is PharosFailure.UnsupportedType -> UploadFailureCard(
            title = if (state.uploadFiles.size > 1) "Unsupported file type in this pick" else "Unsupported file type",
            icon = Icons.Filled.Error,
            tone = MasonTone.Warn,
            quote = failure.serverText,
            mono = failure.extension?.let { ".$it" } ?: failure.detailLine(),
            lines = listOf(
                "You can send it anyway. The server decides, and it may refuse the upload. This " +
                    "time it did. This app does not block an unusual extension on its own: the " +
                    "client's own list is only a hint of what a deployment prints.",
                "Pick a file whose type this server prints, or print it from a lab machine.",
            ) + listOfNotNull(siblingsStillSent(state).takeIf { state.uploadFiles.size > 1 }),
            actions = {
                OutlinedButton(onClick = dismissThen) { Text("Choose another file") }
            },
        )

        is PharosFailure.UploadDenied -> UploadFailureCard(
            title = "This server refused to take the upload",
            icon = Icons.Filled.PrintDisabled,
            tone = MasonTone.Warn,
            quote = failure.headline(),
            mono = failure.detailLine(),
            lines = listOf(
                "This is an answer about the account, not about the file: the upload path is closed " +
                    "to you even though the rest of the queue works. The switch belongs to the " +
                    "server, so the Print Center is where to check it.",
            ),
            actions = {
                OutlinedButton(onClick = { router.push(Route.PrintCenter) }) { Text("Open the Print Center") }
            },
        )

        is PharosFailure.Timeout -> if (failure.writePhase) {
            UploadFailureCard(
                title = "The connection died while the file was still being sent",
                icon = Icons.Filled.CloudOff,
                tone = MasonTone.Warn,
                quote = null,
                mono = streamingDetail(state.uploadFraction, failed?.sizeBytes),
                lines = listOf(
                    "The bar counts bytes written, and no answer came back. So this is not the " +
                        "server refusing the file, and it is not proof that it received it either.",
                    "Look at the queue before sending it again. If the job is there, sending it " +
                        "twice prints twice and costs twice.",
                ) + listOfNotNull(
                    if (state.uploadFiles.size > 1) {
                        "The files already sent are in the queue; the ones after this point were " +
                            "not attempted. Nothing needs re-sending until the queue has been read."
                    } else {
                        null
                    },
                ),
                actions = {
                    OutlinedButton(onClick = { router.reset(Route.Queue) }) { Text("Open the queue") }
                    OutlinedButton(onClick = dismissThen) { Text("Choose another file") }
                },
            )
        } else {
            FailureSummary(failure)
        }

        else -> FailureSummary(failure)
    }
}

@Composable
private fun UploadFailureCard(
    title: String,
    icon: ImageVector,
    tone: MasonTone,
    quote: String?,
    mono: String?,
    lines: List<String>,
    actions: @Composable () -> Unit,
) {
    val (bg, fg) = MasonToneSurfaces(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = bg,
        contentColor = fg,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = fg)
                Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            }
            if (!quote.isNullOrBlank()) {
                Text("The server said: “$quote”", style = MaterialTheme.typography.bodyMedium, color = fg)
            }
            if (!mono.isNullOrBlank()) {
                Text(
                    mono,
                    style = LocalMasonType.current.monoSmall,
                    color = fg,
                    textAlign = TextAlign.Start,
                )
            }
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
// ---------------------------------------------------------------------------- pure derivations --

/**
 * Size with one decimal in MB, for a line whose three numbers have to add up:
 * `68.4 MB selected · 50 MB allowed · 18.4 MB over`.
 *
 * [dev.ahnafnafee.masonprint.data.model.humanBytes] deliberately rounds megabytes to whole numbers so a 50 MiB
 * limit never reads as `52.4 MB` — right for a limit, wrong for arithmetic, where `68 MB − 50 MB =
 * 18 MB` looks like an off-by-one in the app. Same binary divisors, more precision, and `Locale.US`
 * because this line gets pasted into a service-desk ticket next to the server's own US-formatted
 * sentence.
 */
internal fun uploadSizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** The §3.6 arithmetic line, or null when there is nothing honest to compute. */
internal fun oversizeArithmetic(selectedBytes: Long?, limitBytes: Long?): String? {
    val selected = selectedBytes?.takeIf { it > 0 } ?: return null
    val limit = limitBytes?.takeIf { it > 0 } ?: return null
    val over = selected - limit
    if (over <= 0) return null
    return "${uploadSizeText(selected)} selected · ${uploadSizeText(limit)} allowed · ${uploadSizeText(over)} over"
}

/** `4.2 MB of 9.8 MB · streaming to the server`, from the write-phase fraction alone. */
internal fun streamingDetail(fraction: Float, totalBytes: Long?): String {
    val f = fraction.coerceIn(0f, 1f)
    val pct = (f * 100).toInt()
    val total = totalBytes?.takeIf { it > 0 } ?: return "$pct% · no size was reported, so there is no total to count against"
    return "${uploadSizeText((f * total).toLong())} of ${uploadSizeText(total)} · streaming to the server"
}

/**
 * The limits sentence, built from what this deployment published rather than from the Spec's
 * fixture list. GMU answers `SecureRelease."Document Types"` with ten category *names* and keeps
 * the 58 extensions in `PrintCenter.extension_allow`; either way, if the server published nothing,
 * the sentence says so instead of reciting a list the server never sent.
 */
internal fun acceptedTypesSentence(maxUploadBytes: Long?, categories: List<String>): String {
    val limit = maxUploadBytes?.takeIf { it > 0 }?.let { uploadSizeText(it) }
    val types = categories.map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        limit == null && types.isEmpty() ->
            "This server did not publish an upload limit or a list of document types, so its " +
                "refusal is the only authority on what it will take."

        limit == null ->
            "This server did not publish an upload limit. It does publish the types it prints: " +
                "${conjunction(types)}."

        types.isEmpty() ->
            "This server accepts files up to $limit. It did not publish a list of document types, " +
                "so an unfamiliar extension is decided by the server, not by this app."

        else ->
            "This server accepts files up to $limit, in ${conjunction(types)}."
    }
}

/** `a`, `a and b`, `a, b, and c`. */
internal fun conjunction(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.first()
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

/**
 * `upMeta`, from what the picker actually reported: `.docx · 1.2 MB · application/vnd…`.
 *
 * No page count — that is the server's `PageCounting` step, and GMU's `PageCounting: Failed` is a
 * normal outcome for a real file, so a client that printed "12 pages" here would be guessing.
 */
internal fun fileMetaText(fileName: String, mimeType: String, sizeBytes: Long): String {
    val ext = fileName.substringAfterLast('.', "")
    val extLabel = if (ext.isEmpty() || ext == fileName) "no extension" else ".$ext"
    val size = if (sizeBytes > 0) uploadSizeText(sizeBytes) else "size not reported"
    return "$extLabel · $size · $mimeType"
}

/** The queued job this file became, matched on the title the server took from the file name. */
internal fun jobNamed(jobs: List<PrintJob>, fileName: String): PrintJob? =
    jobs.firstOrNull { it.name?.equals(fileName, ignoreCase = true) == true }

/**
 * What became of the *other* files in a pick, next to a refusal that names only one of them.
 *
 * Derived from the queue the server actually returned — a batch ends with a refresh, so this states
 * what the deployment took, not what this app tried. Without the line, a refusal on the third of
 * four documents reads as though the whole send failed, and the user re-picks everything and prints
 * twice. Null when there were no other files, because then there is nothing to say.
 */
internal fun siblingsStillSent(
    files: List<PickedFile>,
    failedName: String?,
    queuedNames: List<String?>,
): String? {
    val others = files.filter { it.name != failedName }
    if (others.isEmpty()) return null
    val landed = others.count { other -> queuedNames.any { it.equals(other.name, ignoreCase = true) } }
    val howMany = "${others.size} other file${if (others.size == 1) "" else "s"}"
    return if (landed == 0) {
        "None of the $howMany in this pick are in the queue either. The list above names each one."
    } else {
        "$landed of the $howMany in this pick did reach the queue. The list above names each one."
    }
}

/** [siblingsStillSent] for the batch now in [AppState]. */
private fun siblingsStillSent(state: AppState): String? =
    siblingsStillSent(state.uploadFiles, state.uploadFailedFile?.name, state.jobs.map { it.name })

/** A priced job's own money, through the server's format string — never a locally built number. */
private fun PrintJob.costText(state: AppState): String? {
    val c = cost ?: return null
    if (costUnknown) return null
    return state.capabilities?.formats?.money(c)
}
