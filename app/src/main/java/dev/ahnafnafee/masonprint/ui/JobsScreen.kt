@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.ahnafnafee.masonprint.ui

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.CostPreview
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.uploadProgressDetail
import dev.ahnafnafee.masonprint.core.stillAwaitsCosting
import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.ui.theme.MasonType
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.ui.theme.SelectedCardCorner
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
 * balance is at the top because it decides whether the release at the bottom will work, and each row
 * is a whole tappable object rather than a checkbox waiting to be found.
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
    onPickDocument: () -> Unit,
) {
    var showReleased by rememberSaveable { mutableStateOf(false) }
    var devicePickerOpen by rememberSaveable { mutableStateOf(false) }
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
    val estimate = { session.previewCost(chosen) { preview -> costPreview = preview } }
    val release = {
        session.previewCost(chosen) { preview ->
            if (preview.blocked || preview.reason != null) costPreview = preview else router.push(Route.Release)
        }
    }

    QueueScaffold(
        state = state,
        session = session,
        router = router,
        onPickDocument = onPickDocument,
        selection = QueueSelection(
            onClear = { session.clearSelection() },
            onPrinter = {
                devicePickerOpen = true
                if (state.devices.isEmpty()) session.loadDevices()
            },
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
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    /*
                     * Order is the prototype's (§3.5): what is happening now, then whether the data is
                     * still true, then the balance, then the list. The strip and the offline card sit
                     * *above* the balance because both of them change how the number should be read.
                     */
                    state.busy?.let { BusyStrip(it) }

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

                    BalanceHero(
                        state = state,
                        reduced = reduced,
                        onChooseFunding = { fundingOpen = true },
                        onAddFunds = { router.push(Route.AddFunds) },
                    )

                    if (state.upload != null) {
                        UploadProgressBar(
                            fraction = state.uploadFraction,
                            detail = uploadProgressDetail(state.uploadFiles, state.uploadIndex, state.uploadFraction),
                        )
                    }
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

            if (visible.isEmpty()) {
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

    if (devicePickerOpen) {
        DevicePicker(
            devices = state.devices,
            current = state.selectedDevice,
            onDismiss = { devicePickerOpen = false },
            onOpen = {
                devicePickerOpen = false
                session.loadDevices()
            },
            onPick = {
                session.selectDevice(it)
                devicePickerOpen = false
            },
        )
    }

    if (copiesOpen) {
        CopiesDialog(
            jobs = chosen,
            onDismiss = { copiesOpen = false },
            onSendInstead = {
                copiesOpen = false
                router.push(Route.Send)
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

// --------------------------------------------------------------------------- hero

/**
 * Which balance story the hero is telling.
 *
 * The order is §5.6's precedence, and it is a *precedence* rather than a set of toggles because two
 * of these are claims about the same number. A cost centre in effect means the number on screen will
 * not move, which outranks everything; arrears outranks the cached flag because a debt is actionable
 * even from a stale read; and "this is a cache" outranks the purse breakdown because a stale purse
 * split is not a split you should plan on.
 */
private enum class HeroVariant { Department, Arrears, Cached, Purses, Plain }

private fun heroVariantFor(state: AppState): HeroVariant {
    val balance = state.user?.balance?.let { it.amount ?: it.total }
    return when {
        state.costCenter?.isNotBlank() == true -> HeroVariant.Department
        balance != null && balance < 0.0 -> HeroVariant.Arrears
        state.stale -> HeroVariant.Cached
        state.purseBreakdown.isNotEmpty() -> HeroVariant.Purses
        else -> HeroVariant.Plain
    }
}

/** The radius inside [SelectedCardCorner], as a `Dp` so it can be animated. */
private val SelectedCardRadius = 26.dp
private val JobCardRadius = 20.dp

/**
 * The balance hero, and the only place on this screen that states what will be charged.
 *
 * Its *identity* changes — container colour, eyebrow, sub-line, and which extra rows appear — and the
 * change is animated, because a hero that silently repaints from "your money" to "the department's
 * money" is the exact moment students get tricked by print software.
 *
 * The number itself is `displayMedium` (45 / 52, tabular) and is never ellipsised: `$1,234.56` is the
 * thing the user came to read, and a hero that truncates it has failed at its one job.
 */
@Composable
private fun BalanceHero(
    state: AppState,
    reduced: Boolean,
    onChooseFunding: () -> Unit,
    onAddFunds: () -> Unit,
) {
    val variant = heroVariantFor(state)

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
        label = "balanceHero",
    ) { which ->
        HeroBody(
            state = state,
            variant = which,
            onChooseFunding = onChooseFunding,
            onAddFunds = onAddFunds,
        )
    }
}

@Composable
private fun HeroBody(
    state: AppState,
    variant: HeroVariant,
    onChooseFunding: () -> Unit,
    onAddFunds: () -> Unit,
) {
    /*
     * Colour is never the only carrier: every variant below also says what it means in words, in an
     * icon, or both. Green is *outcome*, gold is *a cost centre*, red is *a problem* — and a screenshot
     * in greyscale or a colour-blind reading has to still get the same sentence.
     */
    val (container, content, outlined) = when (variant) {
        HeroVariant.Department ->
            Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, false)

        HeroVariant.Arrears ->
            Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, false)

        HeroVariant.Cached ->
            Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, true)

        HeroVariant.Purses ->
            Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, false)

        HeroVariant.Plain ->
            Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, false)
    }

    val eyebrow = when (variant) {
        // The eyebrow sits directly above the number, so it has to name *that number* — which is
        // always the student's own balance. Labelling it "charging to a cost center" while showing a
        // personal balance is what made this read as two contradictory facts.
        HeroVariant.Department -> "My printing balance"
        // "In arrears" is accounting language. The eyebrow labels the number below it, and that
        // number is negative, so it says so.
        HeroVariant.Arrears -> "My printing balance is negative"
        HeroVariant.Cached -> "Last known balance"
        HeroVariant.Purses, HeroVariant.Plain -> "My printing balance"
    }
    val icon = when (variant) {
        HeroVariant.Department -> Icons.Filled.BusinessCenter
        HeroVariant.Arrears -> Icons.Filled.Error
        HeroVariant.Cached -> Icons.Filled.Schedule
        HeroVariant.Purses -> Icons.Filled.Savings
        HeroVariant.Plain -> Icons.Filled.AccountBalanceWallet
    }
    val balance = state.balanceText ?: "—"

    Surface(
        onClick = if (variant == HeroVariant.Arrears) onAddFunds else onChooseFunding,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp))
                // `textCase` is not in the resolvable compose-ui-text, so eyebrows are written in
                // literal caps at the call site — the same convention SectionLabel uses.
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                )
                if (variant == HeroVariant.Cached) {
                    Spacer(Modifier.width(4.dp))
                    FactChip(
                        icon = Icons.Filled.Schedule,
                        label = "${humanDuration(System.currentTimeMillis() - state.loadedAt)} old",
                        tone = MasonTone.Neutral,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                balance,
                style = MaterialTheme.typography.displayMedium,
                color = content,
                maxLines = 1,
            )

            Spacer(Modifier.height(2.dp))
            val purses = state.purseBreakdown
            val subline = when (variant) {
                // Deliberately the same sentence as Plain. It used to read "$0.00 to me — 10111 pays",
                // which put a second $0.00 next to the balance's $0.00 meaning something different.
                // Who pays is stated once, by the funding row below.
                HeroVariant.Department ->
                    "You are charged when you release this at a printer, not now."

                HeroVariant.Arrears ->
                    "This is what you owe, not what you have. Money arrives through Atrium; this " +
                        "server cannot take a card."

                HeroVariant.Cached ->
                    "Cached ${humanDuration(System.currentTimeMillis() - state.loadedAt)} ago. This is " +
                        "not the live balance. A job released since then would have changed it."

                HeroVariant.Purses ->
                    purses.joinToString(" then ") { it.name } +
                        ". The server spends them in that order; Mason Print only reports it."

                HeroVariant.Plain ->
                    "You are charged when you release this at a printer, not now."
            }
            Text(
                subline,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )

            if (purses.isNotEmpty() && variant != HeroVariant.Cached) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    purses.forEach { purse ->
                        FactChip(
                            icon = Icons.Filled.Savings,
                            label = "${purse.name}  ${state.capabilities?.formats?.money(purse.amount) ?: purse.amount}",
                            tone = MasonTone.Money,
                        )
                    }
                }
            }

            /*
             * Who pays, and one obvious control to change it — on every variant, not just when a
             * department is already set. This was a line of text reading "tap to change", which is
             * not a control, and then nothing at all, which left switching funding to an unmarked tap
             * on a card that does not look tappable. It is the thing students change most often.
             */
            val department = state.costCenter?.takeIf { it.isNotBlank() }
            val centre = department?.let { code ->
                state.capabilities?.usableCostCenters?.firstOrNull { it.code.equals(code, true) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (department != null) Icons.Filled.BusinessCenter else Icons.Filled.Work,
                    null,
                    Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("Charged to", style = MaterialTheme.typography.labelSmall, color = content)
                    Text(
                        centre?.description ?: department ?: "My own balance",
                        style = MaterialTheme.typography.titleSmall,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(onClick = onChooseFunding) { Text("Change") }
            }

            if (variant == HeroVariant.Arrears) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = onAddFunds) {
                    ButtonGlyph(Icons.Filled.Savings)
                    Text("How to add funds")
                }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showReleased) "All jobs" else "Waiting to release",
                style = MaterialTheme.typography.titleMedium,
            )
            // The densest bit of vendor jargon on the screen, explained where it is read rather than
            // in a glossary the student has to go and find.
            InfoTip(
                term = "Waiting to release",
                meaning = "These are on the server, not printed yet. Take one to any campus printer " +
                    "and release it there. That is when it prints and when you pay.",
            )
            Spacer(Modifier.weight(1f))
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

/**
 * One held document.
 *
 * The whole row is the control: `Modifier.selectable(role = Role.Checkbox)` on the card, so the tap
 * target is 100 % of the object rather than the 20 dp square on its left. The tick over the avatar is
 * drawn as well, because a row whose only change is a fill is a row whose state disappears in a
 * greyscale screenshot.
 *
 * Selection grows the corner from 20 dp ([JobCardRadius]) to 26 dp ([SelectedCardRadius], the radius
 * inside [SelectedCardCorner]) and repaints to `secondaryContainer`. Charcoal, not green: green in this
 * design means an outcome that already happened, and a job you have merely selected has not happened
 * yet.
 */
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                // A released job has nothing to select it *for*: every bulk action in this API takes
                // queue locations, and acting on a released one is how a refusal is born.
                enabled = job.pending,
                role = Role.Checkbox,
                onClick = { onToggle(!checked) },
            ),
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (checked) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            /*
             * Centred, not top-aligned. The trailing preview control is an `IconButton`, whose 24 dp
             * glyph sits in the middle of a 48 dp touch target it cannot give up without dropping
             * below the minimum. Top-aligning the row therefore lines up the checkbox and the price
             * with the title while leaving that one glyph a dozen dp lower, which reads as a
             * mistake. Centring is also what `ListItem` does with its own leading and trailing slots.
             */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /*
                 * An always-visible checkbox. The card was already selectable, but it showed nothing
                 * to say so until *after* it had been selected — and a queue that cannot be released
                 * without a selection has to make the selection obvious before it is made.
                 *
                 * The whole card is still the tap target; this is the signifier, not the control, so
                 * it takes no click of its own and is silent to TalkBack — the card already announces
                 * itself as a checkbox. Disabled on a released job, which cannot be selected at all.
                 *
                 * Losing the avatar loses no status: the chip below carries it as icon *and* word.
                 */
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = job.pending,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name ?: "Untitled document",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    val spec = specLine(job)
                    when {
                        spec.isNotBlank() -> Text(
                            spec,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        job.isProcessing -> Text(
                            "The server is still counting this document.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        job.analysisFailed -> Text(
                            "The server could not count this document. It is still yours to release.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        /**
                         * The document is on the server and the server has not told us anything about
                         * it yet — no `Activity`, no price. This is the state a job is in for the few
                         * seconds after `POST printjobs` returns 201, and saying so is what stops it
                         * reading as a failed upload. [dev.ahnafnafee.masonprint.core.Session.watchAnalysis]
                         * re-reads the queue while this line is up.
                         */
                        stillAwaitsCosting(job) -> Text(
                            "The server has the document and has not priced it yet. The queue checks again by itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    /*
                     * `-1` is not a price. GMU answers `-1` for a document it has not finished costing,
                     * and `MoneyText` renders null as an em dash, so the honest rendering of "no cost
                     * exists" is the one the server's own format string would mangle into `$-1.00`.
                     */
                    MoneyText(if (job.costUnknown) "—" else moneyOf(state, job.cost))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            job.costUnknown && stillAwaitsCosting(job) -> "costing…"
                            job.costUnknown -> "priced nowhere"
                            (job.cost ?: 0.0) == 0.0 && !job.costCenterCode.isNullOrBlank() -> "department pays"
                            else -> "at release"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                    )
                }
                /*
                 * Its own button, not the card: tapping the card selects, which is what the release
                 * flow needs, so "let me look at it first" has to be a separate target rather than a
                 * second meaning for the same tap.
                 */
                IconButton(onClick = onPreview) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = "Preview ${job.name ?: "this document"}",
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            /*
             * One status chip, plus the two facts that change what the row means (a cost centre, a
             * password). Finishing used to be four more chips here; it is one quiet line under the
             * title instead ([specLine]), because the row's job is "which document, what state" —
             * not a second copy of the Copies dialog.
             */
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(status.icon, status.label, status.tone)
                job.costCenterCode?.takeIf { it.isNotBlank() }?.let {
                    FactChip(Icons.Filled.BusinessCenter, it, tone = MasonTone.Grant)
                }
                if (job.needsPassword) {
                    FactChip(Icons.Filled.VisibilityOff, "Needs its password", tone = MasonTone.Warn)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                timingLine(job),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

        }
    }
}

/**
 * The one quiet line of facts under a job's title: the page summary plus the finishing the server
 * recorded, `·`-joined. Finishing only appears when it says something beyond the default, so a
 * plain 1-copy simplex B&W job keeps its one-line reading.
 */
private fun specLine(job: PrintJob): String {
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
 * Copies and finishing, as a read-back rather than an editor.
 *
 * `Session` has no call that updates finishing, and that is deliberate: GMU accepts a `PATCH` to
 * `{UserUri}/printjobs/` with HTTP 200 while echoing `CostCenterCode: ""` back, so a 200 does not mean
 * the write took (docs/FINDINGS.md). Showing steppers that might silently not persist is worse than
 * saying where the number is really set — at send time — and offering to go there.
 */
@Composable
private fun CopiesDialog(
    jobs: List<PrintJob>,
    onDismiss: () -> Unit,
    onSendInstead: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ContentCopy, null) },
        title = { Text("Copies and finishing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (jobs.isEmpty()) {
                    Text("Select at least one job to read its options back.", style = MaterialTheme.typography.bodyMedium)
                }
                jobs.forEach { job ->
                    val fin = job.finishing
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                job.name ?: "Untitled document",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    fin?.copies?.let { "$it cop" + if (it == 1L) "y" else "ies" } ?: "copies unknown",
                                    fin?.let { if (it.duplex == true) "double-sided" else "one-sided" },
                                    fin?.pagesPerSide?.takeIf { it > 1 }?.let { "$it pages per side" },
                                    fin?.colourLabel,
                                    fin?.defaultPageSize?.takeIf { it.isNotBlank() },
                                    fin?.pageRange?.takeIf { it.isNotBlank() }?.let { "pages $it" },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Mason Print shows what the server recorded for these jobs. Copies, sides, and " +
                        "colour are chosen when the document is uploaded, and this server does not accept " +
                        "a reliable change afterwards. A 200 from the update call does not mean the " +
                        "new value stuck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSendInstead) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Change when uploading")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
    onPick: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(state.costCenter.orEmpty()) }
    val centres = state.capabilities?.usableCostCenters.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Work, null) },
        title = { Text("Who pays for this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectableFundingRow(
                    label = "My own balance",
                    supporting = state.purseBreakdown
                        .joinToString(" · ") {
                            "${it.name} ${state.capabilities?.formats?.money(it.amount) ?: it.amount}"
                        }
                        .ifBlank { state.balanceText ?: "Balance shown at the top of the screen" },
                    selected = draft.isBlank(),
                    onClick = { draft = "" },
                )
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
                    "Type a code your department gave you, or search. The server decides whether a " +
                        "code is valid when the job is released, so a code that is not listed here can " +
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
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
        }
    }
}

/**
 * Which printer the code will be sent to. "Server default" is a real option and is the one that matches
 * what the Print Center web UI does when you do not choose.
 */
@Composable
private fun DevicePicker(
    devices: List<Device>,
    current: Device?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPick: (Device?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Print, null) },
        title = { Text("Release at") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(
                        "No printers have been read from this server yet. Load them, or scan the code " +
                            "on the machine. Reading the code is the only way to be sure the release " +
                            "goes to the printer standing in front of you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Load printers") }
                }
                devices.forEach { device ->
                    SelectableFundingRow(
                        label = device.label,
                        supporting = device.sublabel,
                        selected = current?.location == device.location,
                        onClick = { onPick(device) },
                    )
                }
            }
        },
        // Same correction as the funding picker: the confirm button performs the action and the
        // dismiss button dismisses. These were inverted — "Close" sat in the confirm slot while the
        // dismiss slot quietly chose a printer.
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Use server default") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --------------------------------------------------------------------------- small pure helpers

/** Server money format, with a dash rather than a made-up number when the server said nothing. */
private fun moneyOf(state: AppState, amount: Double?): String =
    state.capabilities?.formats?.money(amount) ?: "—"

private val dateTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatInstant(instant: Instant?): String =
    instant?.let { dateTimeFmt.format(it) } ?: "unknown"
