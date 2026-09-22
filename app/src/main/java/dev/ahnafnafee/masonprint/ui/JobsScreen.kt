@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.core.FinishingEdits

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrintDisabled
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.CostPreview
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.stillAwaitsCosting
import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.model.FinishingOptions
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.ui.theme.MasonType
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.ui.theme.SelectedCardCorner
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The queue.
 *
 * This is the screen the whole app exists for: a list of documents that are already on a server,
 * being held for their owner, with money attached. Everything in the layout follows from that — the
 * list gets the screen, funding is one row above it because it decides whether the release at the
 * bottom will work, and each row is a whole tappable object rather than a checkbox waiting to be
 * found.
 *
 * The frame (top bar, overflow, pull-to-refresh, the morphing button, the selection bar) is in
 * [QueueScaffold]; this file owns what scrolls inside it and every dialog the frame can ask for.
 *
 * Wire facts this layout has to survive, all of them observed against GMU (`docs/FINDINGS.md`,
 * `docs/CLONE-PLAN.md` §11):
 *
 *  - `Cost: -1` means *this server publishes no price for this job*, and the server will not release a
 *    job it cannot price. It is rendered as "Priced nowhere", never as `$-1.00` and never as free.
 *  - `Cost: 0` with a `CostCenterCode` is **free to the student** — the department pays, the balance
 *    does not move. Zero is not a discount.
 *  - Page counts live in `Activity.Steps[]`, and `PageCounting: Failed` is a normal GMU outcome: the
 *    job stays releasable with every counter at zero. "0 sheets · 0 pages" would tell that student
 *    their file is empty, which is a different problem than the one they have.
 *  - `Pending` is absent from the live payload, so `pending` is derived from `PrintState`; the label
 *    shown is the server's own `JobStatus` text, not a client-side enum it cannot verify.
 */
@Composable
fun JobsScreen(
    state: AppState,
    session: Session,
    router: Router,
    snackbar: SnackbarHostState,
    onPickDocument: () -> Unit,
    preparingDocuments: Boolean = false,
) {
    var showReleased by rememberSaveable { mutableStateOf(false) }
    var fundingOpen by rememberSaveable { mutableStateOf(false) }
    var copiesOpen by rememberSaveable { mutableStateOf(false) }
    // Not `rememberSaveable`: a cost preview is an answer to a question that was asked a second ago,
    // and after a rotation the honest thing is to ask again rather than quote a stale total.
    var costPreview by remember { mutableStateOf<CostPreview?>(null) }

    val reduced = rememberReducedMotion()
    val visible = state.pendingJobs(showReleased)
    // Hoisted into `AppState.selection` on purpose: the cost dialog re-asks about the same jobs, and
    // the release flow downstream reads it too.
    val chosen = state.chosen

    /*
     * Estimate, then go. The button's own label already carries the server's number, so pressing it
     * asks the server the one question that can stop the walk to the printer — "can you price these" —
     * and only a refusal is interruptible. A `-1` or `JobRelease_JobsFailedCosting` answer opens the
     * preview instead of pushing a screen that would fail, because the failure is about funding and
     * the preview is where funding can be changed.
     *
     * Selection is deliberately *not* cleared here: the release flow reads `state.chosen`.
     */
    val estimate: () -> Unit = { session.previewCost(chosen) { preview -> costPreview = preview } }
    val release: () -> Unit = { router.push(Route.Release) }

    QueueScaffold(
        state = state,
        session = session,
        router = router,
        onPickDocument = onPickDocument,
        preparingDocuments = preparingDocuments,
        snackbar = snackbar,
        selection = QueueSelection(
            onClear = { session.clearSelection() },
            /*
             * The release screen, not a dialog of its own.
             *
             * There were two printer pickers. This one was an `AlertDialog` holding all 302 devices
             * as a flat, unsearchable list inside a scroll container the dialog fights for gestures,
             * so finding a named machine meant flicking past a few hundred rows that all begin
             * "FX-". The release screen's picker already searches by building, room and model, and
             * groups by building and floor, and it keeps the selection while you use it. Sending
             * this button there deletes the worse of the two rather than teaching it to search.
             */
            onPrinter = { router.push(Route.Release) },
            onChargeTo = { fundingOpen = true },
            onCopies = { copiesOpen = true },
            onEstimate = estimate,
            // No clearSelection here: `deleteJobs` keeps a refused job in the selection, which is
            // the right state — it is still in the queue and still chosen.
            onDelete = { session.deleteJobs(chosen) },
            onRelease = release,
            onAddFunds = { router.push(Route.AddFunds) },
        ),
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = MasonScreenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    /*
                     * Order is the prototype's (§3.5): what is happening now, then whether the data is
                     * still true, then who pays, then the list. The strip and the offline card sit
                     * *above* the list because both of them change how a release from it should be
                     * read.
                     */
                    if (state.upload == null && !preparingDocuments) {
                        state.busy?.let { BusyStrip(it) }
                    }
                    UploadQueueStatus(
                        state = state,
                        preparing = preparingDocuments,
                        onDismiss = session::dismissUploadIssue,
                    )

                    if (state.stale) {
                        /*
                         * Stale is not an error screen (§5.6). The list on screen is real and cached,
                         * so it stays; what changed is the trust level of the balance. A full-screen
                         * error here would hide the queue a student needs in order to walk to a
                         * printer that is already in the building.
                         */
                        NoteCard(
                            title = "No connection to ${state.host}",
                            body = "This is the queue Mason Print cached " +
                                "${humanDuration(System.currentTimeMillis() - state.loadedAt)} ago. " +
                                "Jobs may already have expired, and nothing can be released until you " +
                                "are back on campus Wi-Fi.",
                            icon = Icons.Filled.CloudOff,
                            tone = MasonTone.Warn,
                            action = {
                                TextButton(onClick = { session.refresh() }) {
                                    ButtonGlyph(Icons.Filled.CloudOff)
                                    Text("Retry")
                                }
                            },
                        )
                    } else {
                        FailureSummary(state.failure)
                    }

                    FundingStrip(
                        state = state,
                        reduced = reduced,
                        onChooseFunding = { fundingOpen = true },
                        onAddFunds = { router.push(Route.AddFunds) },
                    )

                }
            }

            item(key = "list-header") {
                ListHeader(
                    state = state,
                    visible = visible,
                    showReleased = showReleased,
                    onToggleShowReleased = { showReleased = it },
                    onSelectAll = { session.toggleSelectAll(showReleased) },
                    onClear = { session.clearSelection() },
                )
                TextButton(onClick = { router.push(Route.ReleasedJobs) }) { Text("Released jobs and charges") }
            }

            items(visible, key = { it.location }) { job ->
                JobRow(
                    job = job,
                    state = state,
                    checked = job.location in state.selection,
                    reduced = reduced,
                    onToggle = { on ->
                        val next = if (on) state.selection + job.location else state.selection - job.location
                        session.setSelection(next)
                    },
                    onPreview = {
                        PreviewHandoff.location = job.location
                        router.push(Route.Preview)
                    },
                )
            }

            if (visible.isEmpty() && state.upload == null && !preparingDocuments && state.busy == null) {
                item(key = "empty") {
                    QueueEmpty(
                        state = state,
                        showReleased = showReleased,
                        onPickDocument = onPickDocument,
                        onRetry = { session.refresh() },
                        onDiagnostics = { router.push(Route.Diagnostics) },
                    )
                }
            }

            if (state.nextJobsPage != null && visible.isNotEmpty()) {
                item(key = "load-more") {
                    LoadMorePill(state = state, onLoadMore = { session.loadMoreJobs() })
                }
            }

            // §2.0.4: the scroll spacer is the height of the tallest possible bottom bar, so the last
            // row can always be dragged clear of it.
            item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
        }
    }

    if (copiesOpen) {
        CopiesDialog(
            jobs = chosen,
            // The server's own switch (`PrintCenter."Finishing Options Update"`). Where a campus
            // turns it off the controls are visible but inert, with the reason said out loud,
            // rather than the dialog pretending the settings do not exist.
            canEdit = state.capabilities?.finishingUpdateAllowed != false,
            saving = state.updatingFinishing,
            onDismiss = { copiesOpen = false },
            onApply = { options ->
                copiesOpen = false
                session.applyFinishing(chosen, options)
            },
        )
    }

    costPreview?.let { preview ->
        EstimateDialog(
            preview = preview,
            onDismiss = { costPreview = null },
            onRetry = { session.previewCost(chosen) { costPreview = it } },
            onChargeTo = {
                costPreview = null
                fundingOpen = true
            },
        )
    }

    if (fundingOpen) {
        FundingPicker(
            state = state,
            onDismiss = { fundingOpen = false },
            onSearch = session::searchCostCenters,
            onRemove = { session.unsaveCostCenter(it) },
            onPick = { code ->
                session.setCostCenter(code)
                fundingOpen = false
            },
        )
    }
}

// --------------------------------------------------------------------------- strip

/** The one "something is in flight" row. It scrolls with the list rather than covering it. */
@Composable
private fun BusyStrip(label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// --------------------------------------------------------------------------- funding strip

/**
 * Which funding story the strip is telling.
 *
 * The order is §5.6's precedence, and it is a *precedence* rather than a set of toggles because two
 * of these are claims about the same number. A cost centre in effect means the number on screen will
 * not move, which outranks everything; arrears outranks the cached flag because a debt is actionable
 * even from a stale read; and "this is a cache" outranks the purse breakdown because a stale purse
 * split is not a split you should plan on.
 */
private enum class FundingVariant { Department, Arrears, Cached, Purses, Plain }

private fun fundingVariantFor(state: AppState): FundingVariant {
    val balance = state.user?.balance?.let { it.amount ?: it.total }
    return when {
        state.costCenter?.isNotBlank() == true -> FundingVariant.Department
        balance != null && balance < 0.0 -> FundingVariant.Arrears
        state.stale -> FundingVariant.Cached
        state.purseBreakdown.isNotEmpty() -> FundingVariant.Purses
        else -> FundingVariant.Plain
    }
}

/** The radius inside [SelectedCardCorner], as a `Dp` so it can be animated. */
private val SelectedCardRadius = 26.dp
private val JobCardRadius = 20.dp

/**
 * The balance and who pays, as one row.
 *
 * This was a hero — a 45 sp number, a paragraph about when money moves, purse chips and a "Charged
 * to" editor — which pushed the queue's first job card a third of the way down the screen. Every
 * sentence the hero carried still exists where it does its work: "at release" is on every job row,
 * the purse order and the campus-card distinction are in the funding picker (which is where "where
 * printing money comes from" always pointed), a stale read is explained by the NoteCard directly
 * above, and arrears escalates to an "Add funds" affordance right here.
 *
 * What is left is the pair of facts that decide the next release — how much is there, who it comes
 * from — and a tap target the width of the screen, which is the control students use most here. The
 * row-plus-chevron is [QueueScaffold]'s SettingRow pattern: a value that does not look tappable is a
 * setting that may as well still be in the overflow menu.
 *
 * The variant still repaints and re-words the row, animated, because a strip that silently switches
 * from "your money" to "the department's money" is the exact moment students get tricked by print
 * software.
 */
@Composable
private fun FundingStrip(
    state: AppState,
    reduced: Boolean,
    onChooseFunding: () -> Unit,
    onAddFunds: () -> Unit,
) {
    val variant = fundingVariantFor(state)

    AnimatedContent(
        targetState = variant,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            // ~350 ms cross-fade with a short upward drift. The MotionScheme does not know about the
            // system's "no animations" setting, so every spec here snaps when the user asked for none.
            val fade: FiniteAnimationSpec<Float> =
                if (reduced) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
            val drift: FiniteAnimationSpec<IntOffset> =
                if (reduced) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
            (fadeIn(fade) + slideInVertically(drift) { it / 6 })
                .togetherWith(fadeOut(fade) + slideOutVertically(drift) { it / 6 })
        },
        label = "fundingStrip",
    ) { which ->
        StripBody(
            state = state,
            variant = which,
            onChooseFunding = onChooseFunding,
            onAddFunds = onAddFunds,
        )
    }
}

@Composable
private fun StripBody(
    state: AppState,
    variant: FundingVariant,
    onChooseFunding: () -> Unit,
    onAddFunds: () -> Unit,
) {
    /*
     * Colour is never the only carrier: every variant below also says what it means in words, in an
     * icon, or both. Green is *outcome*, gold is *a cost centre*, red is *a problem* — and a screenshot
     * in greyscale or a colour-blind reading has to still get the same sentence.
     */
    val (container, content, outlined) = when (variant) {
        FundingVariant.Department ->
            Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, false)

        FundingVariant.Arrears ->
            Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, false)

        FundingVariant.Cached ->
            Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, true)

        FundingVariant.Purses ->
            Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, false)

        FundingVariant.Plain ->
            Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, false)
    }
    val icon = when (variant) {
        FundingVariant.Department -> Icons.Filled.BusinessCenter
        FundingVariant.Arrears -> Icons.Filled.Error
        FundingVariant.Cached -> Icons.Filled.Schedule
        FundingVariant.Purses -> Icons.Filled.Savings
        FundingVariant.Plain -> Icons.Filled.AccountBalanceWallet
    }
    val balance = state.balanceText ?: "—"
    val purses = state.purseBreakdown
    val department = state.costCenter?.takeIf { it.isNotBlank() }
    val centre = department?.let { code ->
        state.capabilities?.usableCostCenters?.firstOrNull { it.code.equals(code, true) }
    }

    Surface(
        onClick = if (variant == FundingVariant.Arrears) onAddFunds else onChooseFunding,
        modifier = Modifier.fillMaxWidth(),
        // `large` (16) — the NoteCard radius. The strip is a control, but it is not a job: one step
        // quieter than a job card, one step louder than a plain row.
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                /*
                 * The label names the control. A row that states only a value — "$4.50", "10111" —
                 * is an answer looking for its question, and one student's "what is this?" is
                 * another's "it says 10111, is that my balance?". "PAY WITH" is the question, in
                 * the same vocabulary as the selection bar's "Pay with" row and the picker's "Who
                 * pays for this?", so the strip, the bar and the dialog are three sizes of one
                 * control rather than three controls. (The old hero said "Charged to" here; the
                 * bar's wording won because the strip sits beside that bar, not beside the hero.)
                 */
                Text("PAY WITH", style = MaterialTheme.typography.labelMedium, color = content)
                /*
                 * The number is shown only while the student's own purse is what would pay — the
                 * variants where it gates the release. On a cost centre the personal balance does
                 * not move, and a `$0.00` sitting next to a department code reads as the
                 * department's $0.00, which is the confusion the old eyebrow existed to patch.
                 */
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (variant == FundingVariant.Department) {
                        Text(
                            centre?.code ?: department ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Text(
                            "· " + (centre?.description ?: "Department account"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                        )
                    } else {
                        Text(
                            balance,
                            style = MasonType.monoCost,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Text(
                            when (variant) {
                                FundingVariant.Arrears -> "below zero — what you owe"
                                FundingVariant.Cached -> "last known balance"
                                FundingVariant.Purses ->
                                    purses.joinToString(" then ") { it.name }

                                FundingVariant.Plain -> "My own balance"
                                FundingVariant.Department -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                        )
                    }
                }
                if (variant == FundingVariant.Department) {
                    Text(
                        "For every job you select",
                        style = MaterialTheme.typography.bodySmall,
                        color = content,
                    )
                }
            }
            if (variant == FundingVariant.Arrears) {
                // The one variant that cannot be released gets the only explicit button: a chevron
                // says "somewhere else", "Add funds" says the thing to do.
                TextButton(
                    onClick = onAddFunds,
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                ) { Text("Add funds", maxLines = 1) }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = content,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- list header

@Composable
private fun ListHeader(
    state: AppState,
    visible: List<PrintJob>,
    showReleased: Boolean,
    onToggleShowReleased: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showReleased) "All jobs" else "Waiting to release",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                InfoTip(
                    term = "Waiting to release",
                    meaning = "These are on the server, not printed yet. Take one to any campus printer " +
                        "and release it there. That is when it prints and when you pay.",
                )
            }
            if (visible.isNotEmpty()) {
                if (state.selection.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                } else {
                    TextButton(onClick = onSelectAll) { Text("Select all") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildString {
                    state.jobsCount?.let { append("$it on the server") }
                    if (state.loadedAt > 0L) {
                        if (isNotEmpty()) append("  ·  ")
                        append("read ").append(humanDuration(System.currentTimeMillis() - state.loadedAt)).append(" ago")
                    }
                }.ifBlank { "not read yet" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            /*
             * A button that names what it will do, not a switch labelled "Released". The switch sat
             * in a row of status text, which read as a statement about the jobs rather than a control
             * over the list, and "Released" on its own never said whether it meant show them or hide
             * them. The heading above changes with it, so the two always agree.
             */
            TextButton(onClick = { onToggleShowReleased(!showReleased) }) {
                Text(if (showReleased) "Show waiting only" else "Show all jobs")
            }
        }
    }
}

// --------------------------------------------------------------------------- job row

/** The four states the design admits, in the print system's own vocabulary. */
private class JobStatusSpec(val label: String, val icon: ImageVector, val tone: MasonTone)

private fun statusOf(job: PrintJob): JobStatusSpec = when {
    job.isReleased -> JobStatusSpec(MasonJobStatus.Released, MasonJobStatus.ReleasedIcon, MasonTone.Ok)
    job.analysisFailed && job.costUnknown -> JobStatusSpec(MasonJobStatus.Problem, MasonJobStatus.ProblemIcon, MasonTone.Error)
    // Only an *outcome* gets a colour. A job that is merely waiting on the owner is charcoal, the
    // same way a selection is charcoal: green in this design means something already happened.
    job.isProcessing -> JobStatusSpec(MasonJobStatus.Received, MasonJobStatus.ReceivedIcon, MasonTone.Neutral)
    else -> JobStatusSpec(MasonJobStatus.Held, MasonJobStatus.HeldIcon, MasonTone.Neutral)
}

/** A selectable document, its print options, and a separate preview action. */
@Composable
private fun JobRow(
    job: PrintJob,
    state: AppState,
    checked: Boolean,
    reduced: Boolean,
    onToggle: (Boolean) -> Unit,
    onPreview: () -> Unit,
) {
    val radius by animateDpAsState(
        targetValue = if (checked) SelectedCardRadius else JobCardRadius,
        animationSpec = if (reduced) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "jobCardCorner",
    )
    val status = statusOf(job)
    val settings = queuePrintSettings(job)
    var restriction by remember(job.id) { mutableStateOf<QueuePrintSetting?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = checked,
            enabled = job.pending,
            role = Role.Checkbox,
            onClick = { onToggle(!checked) },
        ),
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (checked) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
        ),
        border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)) {
            val stackPrice = maxWidth < 280.dp * LocalDensity.current.fontScale
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = job.pending,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            job.name ?: "Untitled document",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val summary = queuePageCount(job) ?: when {
                            job.isProcessing -> "Preparing document"
                            job.analysisFailed -> "Page count unavailable"
                            else -> null
                        }
                        summary?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!stackPrice) JobPrice(job, state)
                }
                if (stackPrice) JobPrice(job, state, inline = true)

                if (settings.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        settings.forEach { setting ->
                            QueueSettingChip(setting, onExplain = { restriction = setting })
                        }
                    }
                }

                if (status.label != MasonJobStatus.Held || job.needsPassword) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status.label != MasonJobStatus.Held) StatusChip(status.icon, status.label, status.tone)
                        if (job.needsPassword) FactChip(Icons.Filled.VisibilityOff, "Needs its password", tone = MasonTone.Warn)
                    }
                }
                // Pending jobs use the shared Pay with choice; a saved code is historical metadata.
                if (job.isReleased) job.costCenterCode?.takeIf { it.isNotBlank() }?.let {
                    Text("Cost center: $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        timingLine(job),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onPreview,
                        modifier = Modifier.semantics { contentDescription = "Preview ${job.name ?: "this document"}" },
                    ) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Preview")
                    }
                }
            }
        }
    }
    restriction?.let { setting ->
        AlertDialog(
            onDismissRequest = { restriction = null },
            icon = { Icon(Icons.Filled.Lock, null, tint = currentMasonColors.warn) },
            title = { Text("Setting fixed by the server") },
            text = {
                Text("The print server does not allow changes to ${setting.restriction} for this document. " +
                    "This setting stays unchanged when you edit the selected jobs.")
            },
            confirmButton = { TextButton(onClick = { restriction = null }) { Text("Got it") } },
        )
    }
}

@Composable
private fun QueueSettingChip(setting: QueuePrintSetting, onExplain: () -> Unit) {
    val locked = setting.restriction != null
    val colours = currentMasonColors
    val (background, foreground) = when {
        locked -> colours.warnContainer to colours.onWarnContainer
        setting.kind == QueueSettingKind.Colour -> colours.colourPrintContainer to colours.onColourPrintContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (locked) Icons.Filled.Lock else when (setting.kind) {
        QueueSettingKind.Colour -> Icons.Filled.Palette
        QueueSettingKind.Mono -> Icons.Filled.Tonality
        QueueSettingKind.Sides -> Icons.Filled.FlipToFront
        QueueSettingKind.Copies -> Icons.Filled.ContentCopy
        QueueSettingKind.Layout -> Icons.Filled.ViewModule
    }
    val label = setting.label + if (locked) " · fixed" else ""
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (locked) {
        Surface(
            onClick = onExplain,
            shape = MaterialTheme.shapes.small,
            color = background,
            contentColor = foreground,
            modifier = Modifier.semantics { contentDescription = "${setting.label}, fixed by the server. Show details" },
            content = content,
        )
    } else {
        Surface(shape = MaterialTheme.shapes.small, color = background, contentColor = foreground, content = content)
    }
}

@Composable
private fun JobPrice(job: PrintJob, state: AppState, inline: Boolean = false) {
    val amount = if (job.costUnknown) "—" else moneyOf(state, job.cost)
    val label = when {
        job.costUnknown && stillAwaitsCosting(job) -> "calculating"
        job.costUnknown -> "not priced"
        job.isReleased -> "reported"
        else -> "estimate"
    }
    if (inline) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            MoneyText(amount)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MoneyText(amount)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2)
        }
    }
}


/**
 * The one quiet line of facts under a job's title: the page summary plus the finishing the server
 * recorded, `·`-joined. Finishing only appears when it says something beyond the default, so a
 * plain 1-copy simplex B&W job keeps its one-line reading.
 */
internal fun specLine(job: PrintJob): String {
    val finishing = job.finishing?.let { fin ->
        // Colour is deliberately omitted here: job.pageSummary already carries it ("2 b&w"), so
        // repeating fin.colourLabel put "Black & white" on the line twice.
        listOfNotNull(
            (fin.copies ?: 1L).takeIf { it > 1L }?.let { "$it copies" },
            fin.duplex?.let { if (it) "double-sided" else "one-sided" },
            fin.pagesPerSide?.takeIf { it > 1 }?.let { "$it per side" },
        ).joinToString(" · ").ifBlank { null }
    }
    /*
     * A comma joins the two halves, not a third interpunct. The line is too long for a narrow card
     * either way, and the wrap falls at a space: an interpunct left at the end of a line reads as a
     * fact that went missing, where a comma in the same position is invisible. The interpuncts
     * inside `pageSummary` are safe because that half wraps as a unit.
     */
    return listOfNotNull(
        job.pageSummary.takeIf { it.isNotBlank() },
        finishing,
    ).joinToString(", ")
}

/** "Sent 4 minutes ago · expires in 6 days", built from the server's own fields. */
private fun timingLine(job: PrintJob): String {
    val parts = mutableListOf<String>()
    val age = job.submissionAgeSeconds
    when {
        age != null -> parts += "sent ${humanDuration((age * 1000).toLong())} ago"
        job.submittedAt != null -> parts += "sent ${formatInstant(job.submittedAt)}"
    }
    job.expiresAt?.let { exp ->
        val until = Duration.between(Instant.now(), exp)
        parts += when {
            until.isNegative -> "expired ${humanDuration(-until.toMillis())} ago"
            until.toDays() >= 1 -> "expires in ${until.toDays()} d"
            else -> "expires in ${humanDuration(until.toMillis())}"
        }
    }
    job.printerName?.takeIf { it.isNotBlank() }?.let { parts += "for $it" }
    return parts.joinToString("  ·  ").ifBlank { "no timing reported" }
}

// --------------------------------------------------------------------------- empties / load more

@Composable
private fun QueueEmpty(
    state: AppState,
    showReleased: Boolean,
    onPickDocument: () -> Unit,
    onRetry: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    when {
        showReleased -> {
            EmptyState(
                title = "No jobs",
                body = "This account has no jobs on this server, released or waiting.",
                icon = Icons.Filled.PrintDisabled,
            )
        }

        state.stale && state.jobs.isEmpty() -> {
            /*
             * The empty state that is not really empty: there may well be jobs, but they are on a
             * server this phone cannot reach right now, and nothing was ever cached to show instead.
             */
            EmptyState(
                title = "No cached queue",
                body = "Mason Print could not reach ${state.host}, and there is no earlier queue to " +
                    "show. Jobs you sent from another device stay invisible until the server answers.",
                icon = Icons.Filled.CloudOff,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }

        state.canPrint.not() -> {
            // Permission-denied has its own empty state: telling this student to press "Send a
            // document" when the server will refuse the upload is how the app earns a support ticket.
            EmptyState(
                title = "Nothing waiting to print",
                body = state.capabilities?.uploadBlockReason
                    ?: "This account is not allowed to upload documents to this queue.",
                icon = Icons.Filled.PrintDisabled,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                ButtonGlyph(Icons.Filled.Language)
                Text("Why, in detail")
            }
        }

        else -> {
            EmptyState(
                title = "Nothing waiting to print",
                body = "Upload a document from this app, or share a file to Mason Print from any " +
                    "other app. Jobs are held here for seven days, and you pay when you release " +
                    "one at a printer.",
                icon = Icons.Filled.PrintDisabled,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onPickDocument, modifier = Modifier.fillMaxWidth()) {
                ButtonGlyph(Icons.Filled.Add)
                Text("Upload a document")
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun LoadMorePill(state: AppState, onLoadMore: () -> Unit) {
    /*
     * A pill, not an infinite-scroll trigger. This API reports `Count` and `NextPageLink`, so the app
     * can say exactly how much is left; scrolling blindly into a queue of 800 jobs cannot promise that.
     */
    OutlinedButton(
        onClick = onLoadMore,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MasonPillShape,
    ) {
        Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            // The server's `Count`, not a page size: the app does not get to promise "20 more" when the
            // page it was handed happened to be shorter.
            state.jobsCount?.let { "Load more  ·  $it on the server" } ?: "Load more",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// --------------------------------------------------------------------------- dialogs

/**
 * What the selection will cost, in the server's words.
 *
 * `blocked` here is a wire fact, not a guess: GMU answered `-1` for the total, or the bulk cost call
 * came back with `JobRelease_JobsFailedCosting` inside an HTTP 200. Both mean "the server will not
 * release this", so the dialog says so before the walk rather than after.
 */
@Composable
private fun EstimateDialog(
    preview: CostPreview,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onChargeTo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Estimated cost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview.totalText, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Charged to ${preview.fundingLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    preview.reason != null -> Text(
                        preview.reason,
                        style = MasonType.monoSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    preview.blocked -> Text(
                        "This server would not put a price on these. It will not release a job it " +
                            "cannot price, so check who is paying before walking to a printer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.perJob.isNotEmpty()) {
                    SectionLabel("Each document")
                    preview.perJob.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    // "Tap the wallet" pointed at a control that does not exist. The button below is
                    // the one that changes who pays, so that is what this says.
                    "You pay when you release these at a printer, not now. \"Pay with\" below changes " +
                        "who the money comes from, which changes this number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = if (preview.reason != null || preview.blocked) onRetry else onDismiss) {
                Text(if (preview.reason != null || preview.blocked) "Try again" else "OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onChargeTo) { Text("Pay with…") }
        },
    )
}

/**
 * Copies, sides and colour, as an editor.
 *
 * It was a read-back, on the grounds that GMU answers a `PATCH` to `{UserUri}/printjobs/` with 200
 * while sometimes echoing a value back unchanged, so a 200 is not proof the write took. That is
 * still true, and it argues for *not trusting the response*, not for having no controls: with no
 * editor here and none on the upload screen either, the app could not change a single print
 * setting, and this dialog's own advice to "change when uploading" pointed at a screen that has no
 * such control.
 *
 * The resolution is write-then-re-read. [Session.applyFinishing] PATCHes and refreshes, and the
 * queue redraws from what the server now reports, so a change that did not stick shows up as the
 * old value on the card rather than as a lie in a form. This dialog claims only that the change was
 * asked for.
 *
 * Mixed fields stay unchanged until explicitly edited. The draft is keyed by selection identity
 * so a queue refresh cannot discard choices while this dialog is open.
 */
@Composable
private fun CopiesDialog(
    jobs: List<PrintJob>,
    canEdit: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onApply: (FinishingEdits) -> Unit,
) {
    var edits by remember(jobs.map { it.location }.toSet()) { mutableStateOf(FinishingEdits()) }
    fun <T> agreed(pick: (FinishingOptions) -> T): T? =
        jobs.map { it.finishing?.let(pick) }.distinct().singleOrNull()
    val copies = edits.copies ?: agreed { it.copies }
    val duplex = edits.duplex ?: agreed { it.duplex }
    val mono = edits.mono ?: agreed { it.mono }
    val restrictions = jobs.mapNotNull { job ->
        edits.unsupportedFor(job).takeIf { it.isNotEmpty() }?.let {
            "${job.name ?: "Document"}: ${it.joinToString(" and ")} will stay unchanged."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ContentCopy, null) },
        title = { Text("Copies and finishing") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (jobs.isEmpty()) {
                    Text("Select at least one job first.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        if (jobs.size == 1) jobs.single().name ?: "Untitled document" else "${jobs.size} documents",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // A stepper, not a text field: the count is small, and typing a number on a
                    // phone to print two of something is a keyboard nobody needed.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Copies", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { edits = edits.copy(copies = ((copies ?: 2L) - 1L).coerceAtLeast(1L)) },
                            enabled = canEdit && !saving && (copies == null || copies > 1L),
                        ) { Text("-", maxLines = 1) }
                        Text(
                            copies?.toString() ?: "Mixed",
                            Modifier.width(64.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(
                            onClick = { edits = edits.copy(copies = ((copies ?: 0L) + 1L).coerceAtMost(99L)) },
                            enabled = canEdit && !saving && (copies == null || copies < 99L),
                        ) { Text("+", maxLines = 1) }
                    }

                    ChoiceRow(
                        label = "Sides",
                        options = listOf("One-sided" to false, "Two-sided" to true),
                        selected = duplex,
                        enabled = canEdit && !saving,
                        onSelect = { edits = edits.copy(duplex = it) },
                    )
                    ChoiceRow(
                        label = "Colour",
                        options = listOf("Black & white" to true, "Colour" to false),
                        selected = mono,
                        enabled = canEdit && !saving,
                        onSelect = { edits = edits.copy(mono = it) },
                    )

                    if (restrictions.isNotEmpty()) {
                        Text(
                            "The server limits these settings:\n" + restrictions.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (saving) {
                            "Saving the previous changes. Wait for the queue to update before editing again."
                        } else if (canEdit) {
                            "Only settings you change are applied to each document. The server reprices " +
                                "the jobs, so the cost can change. Nothing is charged " +
                                "until you release at a printer."
                        } else {
                            "This server does not accept finishing changes after upload."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(edits) },
                enabled = canEdit && !saving && jobs.isNotEmpty() && edits != FinishingEdits(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A labelled two-way choice, as a pair of buttons rather than a dropdown nobody can see the state of. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T?,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (selected == null) "$label · Mixed" else label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) ->
                if (value == selected) {
                    Button(onClick = { onSelect(value) }, enabled = enabled) { Text(text, textAlign = TextAlign.Center) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }, enabled = enabled) { Text(text, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/**
 * Who pays.
 *
 * Only [dev.ahnafnafee.masonprint.data.model.Capabilities.usableCostCenters] is offered, never the granted-only
 * view: on GMU the account's *own* cost centre arrives with `Grant: false`, so filtering by grant
 * hides the one code that actually works. `Active` is the flag that means "this code still accepts
 * charges" — legality itself is the server's decision at release, which is why a typed-in code that
 * appears nowhere in this list can still succeed.
 */
@Composable
private fun FundingPicker(
    state: AppState,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPick: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(state.costCenter.orEmpty()) }
    val centres = state.capabilities?.usableCostCenters.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Work, null) },
        title = { Text("Who pays for this?") },
        text = {
            // Scrollable: saved codes, the account's own list and search results together can be
            // taller than a dialog, and a cut-off confirm button is no confirm button.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectableFundingRow(
                    label = "My own balance",
                    supporting = state.purseBreakdown
                        .joinToString(" · ") {
                            "${it.name} ${state.capabilities?.formats?.money(it.amount) ?: it.amount}"
                        }
                        .ifBlank {
                            /*
                             * Which purse this actually is, said where the choice is made.
                             *
                             * The print system keeps its own bank (`PrintCenter."Bank"`), separate
                             * from the campus card. A student holding money on their card and reading
                             * $0.00 here concludes the app is broken, which is the most reliable way
                             * to be wrong about this app: the number is exactly what `/logon`
                             * returned, and at GMU that payload is literally
                             * `"Balance":{"Amount":"0.00","Purses":[]}`.
                             *
                             * Said only when the number is zero and the server refuses top-ups,
                             * which is the one combination that looks like a fault. A deployment
                             * that takes payments in-app has an Add Funds button instead and needs
                             * no explaining. (This used to be a third paragraph on the queue hero;
                             * the hero is one row now, and this is the screen it was pointing at.)
                             */
                            val zero = (state.user?.balance?.let { it.amount ?: it.total } ?: 0.0) == 0.0
                            val bank = state.capabilities?.bankName?.takeIf { it.isNotBlank() }
                            if (zero && bank != null && state.capabilities?.canAddFunds != true) {
                                "Your $bank balance — not your campus card"
                            } else {
                                state.balanceText ?: "Balance shown at the top of the screen"
                            }
                        },
                    selected = draft.isBlank(),
                    onClick = { draft = "" },
                )
                /*
                 * Codes this phone remembers the account charging to. The server publishes no
                 * directory of codes, so this is the only place the code a department handed out
                 * lives between uses — including one typed by hand, which is saved the moment it is
                 * charged to. Shown before the account's own list because these are the ones this
                 * student actually uses; hidden for any code the server already lists, so no code
                 * appears twice.
                 */
                val listed = centres.map { it.code.lowercase() }.toSet()
                val saved = state.savedCostCenters.filter { it.code.lowercase() !in listed }
                if (saved.isNotEmpty()) {
                    SectionLabel("Saved for reuse")
                    saved.forEach { entry ->
                        SelectableFundingRow(
                            label = entry.code,
                            supporting = entry.description ?: "Saved on this phone",
                            selected = draft.equals(entry.code, ignoreCase = true),
                            onClick = { draft = entry.code },
                            trailing = {
                                // Removing a shortcut is not switching funding — the row is the
                                // choice, the ✕ only edits the list of shortcuts.
                                IconButton(onClick = { onRemove(entry.code) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove ${entry.code} from saved",
                                    )
                                }
                            },
                        )
                    }
                }
                centres.forEach { centre ->
                    SelectableFundingRow(
                        label = centre.code,
                        supporting = listOfNotNull(
                            centre.description,
                            if (centre.granted) "Grant" else "Department account",
                            // `Complete` is the server saying the code has every segment it needs, not a
                            // yes/no on the student being allowed to use it — so this warns, it does not veto.
                            if (!centre.complete) "Incomplete code. This server may refuse it" else null,
                        ).joinToString("  ·  "),
                        selected = draft.equals(centre.code, ignoreCase = true),
                        onClick = { draft = centre.code },
                    )
                }
                if (state.costCenterResults.isNotEmpty()) {
                    SectionLabel("Found on this server")
                    state.costCenterResults.forEach { centre ->
                        SelectableFundingRow(
                            label = centre.code,
                            supporting = listOfNotNull(
                                centre.description,
                                if (!centre.active) "Not active" else null,
                            ).joinToString("  ·  "),
                            selected = draft.equals(centre.code, ignoreCase = true),
                            onClick = { draft = centre.code },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Department code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(draft) }),
                    trailingIcon = {
                        IconButton(
                            onClick = { onSearch(draft) },
                            enabled = !state.searchingCostCenters && draft.isNotBlank(),
                        ) {
                            if (state.searchingCostCenters) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = "Search cost centres")
                            }
                        }
                    },
                )
                Text(
                    "Type a code your department gave you, or search. Every code you charge to is " +
                        "saved on this phone for next time. The server decides whether a code is " +
                        "valid when the job is released, so a code that is not listed here can " +
                        "still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        /*
         * The confirm button names what it is about to do, rather than saying "Use this" and leaving
         * the reader to work out which of the rows above "this" refers to.
         *
         * The other button is now a plain Cancel. It used to read "My own balance" and *apply* that
         * — the same words as a row in the list directly above it, but a different control doing a
         * different thing, so the dialog offered two ways to choose one option and no way to back
         * out. Choosing is what the rows are for; a dismiss button dismisses.
         */
        confirmButton = {
            TextButton(onClick = { onPick(draft.ifBlank { null }) }) {
                Text(
                    if (draft.isBlank()) "Use my own balance" else "Charge to ${draft.trim()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A pickable funding row.
 *
 * Not `ListItem`: in Material3 1.5 every `headlineContent` overload of it is deprecated, with the
 * headline moved into a trailing `content` lambda, so a row built on it compiles with a warning today
 * and breaks on the next bump. A `Surface` + `Row` is four lines and is not going anywhere.
 */
@Composable
private fun SelectableFundingRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.HelpOutline,
                null,
                Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting.isNotBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}


// --------------------------------------------------------------------------- small pure helpers

/** Server money format, with a dash rather than a made-up number when the server said nothing. */
private fun moneyOf(state: AppState, amount: Double?): String =
    state.capabilities?.formats?.money(amount) ?: "—"

private val dateTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant?): String =
    instant?.let { dateTimeFmt.format(it) } ?: "unknown"
