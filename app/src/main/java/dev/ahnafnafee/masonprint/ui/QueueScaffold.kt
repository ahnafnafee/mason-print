@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.ui.theme.SelectionBarCorner
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/**
 * One decision on the selection bar: what it is, what it is set to now, and a tap to change it.
 *
 * The value gets the width because it is the answer; the label only has to name the question. A
 * whole row rather than a chip so a printer name like `FX-ENG4-4413-5860` fits without an ellipsis,
 * which is what forced this layout in the first place.
 */
@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    maxLines: Int = 1,
) {
    /*
     * A real container and a chevron, not text on the bar's own colour. Transparent rows with no
     * trailing affordance read as a summary someone printed there, and a setting nobody believes is
     * tappable is a setting that may as well still be in the overflow menu.
     */
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.End,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The current print settings, short enough to sit on a button.
 *
 * Says the value, not the noun: "2 copies" and "1-sided" tell a student what will come out of the
 * machine, where "Options" only tells them a menu exists. Copies lead because they are the setting
 * most often wrong and the only one that multiplies the cost. A selection whose jobs disagree says
 * "Mixed" rather than picking one of them to display.
 */
internal fun finishingSummary(jobs: List<dev.ahnafnafee.masonprint.data.model.PrintJob>): String {
    val all = jobs.mapNotNull { it.finishing }
    if (all.isEmpty()) return "Options"
    fun <T> agreed(pick: (dev.ahnafnafee.masonprint.data.model.FinishingOptions) -> T): T? =
        all.map(pick).distinct().singleOrNull()
    val copies = agreed { it.copies } ?: return "Mixed"
    val duplex = agreed { it.duplex }
    val mono = agreed { it.mono }
    return listOfNotNull(
        if (copies > 1L) "$copies copies" else null,
        duplex?.let { if (it) "2-sided" else "1-sided" } ?: "Mixed sides",
        mono?.let { if (it) "B&W" else "Colour" } ?: "Mixed colour",
    ).joinToString(" · ").ifBlank { "Options" }
}

/**
 * What the selection bar asks the queue to do.
 *
 * The bar lives in the scaffold because it must sit outside the scrolling list and outside the
 * pull-to-refresh box. Every action on it, though, is a queue decision — which dialog to open, which
 * route to push, what to ask the server for — so they arrive as callbacks instead of the frame trying
 * to guess them.
 */
internal class QueueSelection(
    val onClear: () -> Unit,
    val onPrinter: () -> Unit,
    val onChargeTo: () -> Unit,
    val onCopies: () -> Unit,
    val onEstimate: () -> Unit,
    val onDelete: () -> Unit,
    val onRelease: () -> Unit,
    val onAddFunds: () -> Unit,
)

/**
 * The queue's frame: pinned top bar and overflow (§3.5 `P:332–347`), the pull-to-refresh surface, the
 * morphing `Send` button, and the selection bar (`P:510–534`).
 *
 * The hero, the job rows, and the empties stay in [JobsScreen], which owns the scrollable and receives
 * the content padding through [content].
 *
 * Three choices worth stating out loud:
 *
 *  - **Pinned top bar, not collapsing.** The collapsing variant is the Material default and it is wrong
 *    for this screen: the subtitle carries the host and the API version, which is the only provenance
 *    on screen, and a list you can page a hundred jobs through has no business taking it away.
 *  - **Pull-to-refresh is the surface, not a button.** Refresh exists in three places — the gesture, the
 *    bar's icon, and the first overflow item — and all three call [Session.refresh], because in this API
 *    there is exactly one thing to refresh: the user (and with it the balance) and the queue, together.
 *  - **The bar carries one verb and one number; upload floats.** "Release at a printer" owns the bar
 *    with the queue's total beside it, and Upload is an extended FAB above them that hides while the
 *    selection bar is up, so the gesture that adds a job is always in the same corner and never
 *    competes with a decision the selection bar is asking for.
 */
@Composable
internal fun QueueScaffold(
    state: AppState,
    session: Session,
    router: Router,
    onPickDocument: () -> Unit,
    selection: QueueSelection,
    snackbar: SnackbarHostState,
    preparingDocuments: Boolean = false,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val reduced = rememberReducedMotion()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    val selecting = state.selection.isNotEmpty()
    // Uploads have their own progress in the list, so they do not also need a refresh spinner.
    val refreshing = state.busy != null && state.upload == null && !preparingDocuments

    Scaffold(
        /*
         * The queue hosts the app's snackbar itself, so Material anchors notices *above* the
         * bottom bar instead of over it — the app-level host sits at the screen's bottom edge,
         * where a notice hid the Release pill and the total until it timed out.
         */
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Print queue", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(state.host)
                                state.apiVersion?.let { append("  ·  API ").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    // The vocabulary here is the vendor's ("release", "held", "at release"), and it
                    // is shared with the service desk, so it cannot be renamed — but it can be
                    // explained one tap away rather than left to be guessed at.
                    IconButton(onClick = { router.push(Route.Help) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "How this works",
                        )
                    }
                    IconButton(onClick = { session.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh queue and balance")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        QueueOverflow(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            session = session,
                            router = router,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        /*
         * Upload as the extended FAB (§2.0.3), anchored in the same corner whether the bar below is
         * showing the total or the selection actions — the gesture that adds a job is never in two
         * places at once. It hides while the selection bar is up: that bar owns the screen then, and
         * a FAB offering to add a job over a bar asking for a decision about the chosen ones is two
         * conversations at once.
         *
         * The action green, matching the Release pill — the bar's neutral chrome carries exactly
         * one accent, and it is this one.
         */
        floatingActionButton = {
            AnimatedVisibility(
                visible = !selecting && state.upload == null && !preparingDocuments,
                enter = fadeIn(if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    scaleIn(if (reduced) snap() else MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(if (reduced) snap() else MaterialTheme.motionScheme.fastEffectsSpec()) +
                    scaleOut(if (reduced) snap() else MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onPickDocument,
                    modifier = Modifier.semantics { contentDescription = "Upload documents" },
                    containerColor = currentMasonColors.barAction,
                    contentColor = currentMasonColors.onBarAction,
                    icon = { Icon(Icons.Filled.UploadFile, null) },
                    text = { Text("Upload") },
                )
            }
        },
        bottomBar = {
            /*
             * The chosen surface: `secondaryContainer`, the same grey-blue the picker highlights a
             * chosen row in and the selection bar below is made of.
             *
             * That makes the bar one stable surface whose contents change — totals in the idle
             * state, the selection actions once jobs are chosen — instead of a bar that repaints
             * when you select. It is a scheme role, so dark theme flips it with everything else,
             * and it stays in the app's charcoal *selection* family: no hue, so the gold strip and
             * the green of the Release pill and FAB stay the only colours on screen.
             *
             * A surface with a shadow, not a bare Column on the background. The queue scrolls
             * underneath this bar, and with both painted the same colour the last card appeared to
             * dissolve into the buttons. The shadow is cast on all four edges but only the top one
             * is ever on screen, so the whole effect is the soft line of depth the list needs to
             * pass behind.
             *
             * `navigationBarsPadding`, not the Scaffold's defaults: a plain Column in the bottomBar
             * slot gets no inset handling of its own, and edge-to-edge is on, so without this the
             * release affordance and the upload button sit under the gesture bar.
             */
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    /*
                     * The verb that owns the bar, and the number that answers it.
                     *
                     * "Release at a printer" stays on the bar because it is where you are standing
                     * when you need it. Upload moves out — to the FAB above this bar — because
                     * adding a job is an anywhere-action, not a stand-at-the-printer one, and two
                     * same-weight buttons made "which one starts a print?" a real question.
                     *
                     * In its place, the queue's own total: what releasing everything waiting would
                     * cost, summed the same honest way the selection bar sums — unpriced jobs
                     * contribute nothing and are named below the number, because `-1` is not a
                     * price and a total that quietly swallowed it would be a lie the walk to the
                     * printer discovers.
                     *
                     * Swapped with the selection bar in one frame, no transition machinery: a
                     * bottom bar whose height changes over spring frames re-pads the full-screen
                     * list behind it on every one of those frames, which reads as the bar
                     * *lagging*; one frame reads as arriving. (§2.0.5 said this for reduced
                     * motion — a screen full of job cards needs it always.)
                     */
                    if (!selecting) {
                        val waiting = state.pendingJobs(false)
                        val priced = waiting.filterNot { it.costUnknown }
                        val totalText =
                            state.capabilities?.formats?.money(priced.sumOf { it.cost ?: 0.0 }) ?: "—"
                        val unpriced = waiting.size - priced.size
                        FlowRow(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            /*
                             * The Release pill, in the pair [MasonColors.barPill] spells per theme:
                             * white with Mason-green ink in light — a saturated green pill on this
                             * grey-blue read as a muddy dark lump, while white-on-grey-blue is
                             * instantly a button — and mint with dark ink in dark, where a plain
                             * surface pill would be near-black on near-dark and vanish. Same shape
                             * either way; the ink keeps the brand.
                             */
                            Surface(
                                onClick = { router.push(Route.Release) },
                                shape = MasonPillShape,
                                color = currentMasonColors.barPill,
                                contentColor = currentMasonColors.onBarPill,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp))
                                    Text(
                                        "Release at a printer",
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(totalText)
                                Text(
                                    if (unpriced > 0) "${waiting.size} waiting · $unpriced not priced yet"
                                    else "${waiting.size} waiting",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (selecting) {
                        QueueSelectionBar(
                            state = state,
                            selection = selection,
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { session.refresh() },
            // fillMaxSize, not fillMaxWidth: the gesture has to cover the whole body or the empty state
            // — a short screen — cannot be pulled at all.
            modifier = Modifier.fillMaxSize(),
        ) {
            content(padding)
        }
    }
}

/** The overflow, in the order the prototype lists it (`P:1430–1439`), with nothing added to it. */
@Composable
private fun QueueOverflow(
    expanded: Boolean,
    onDismiss: () -> Unit,
    session: Session,
    router: Router,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Released jobs") },
            onClick = { onDismiss(); router.push(Route.ReleasedJobs) },
        )
        DropdownMenuItem(
            text = { Text("Refresh") },
            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
            onClick = {
                onDismiss()
                session.refresh()
            },
        )
        DropdownMenuItem(
            text = { Text("Account") },
            leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
            onClick = {
                onDismiss()
                router.push(Route.Account)
            },
        )
        DropdownMenuItem(
            text = { Text("Diagnostics") },
            leadingIcon = { Icon(Icons.Filled.BugReport, null) },
            onClick = {
                onDismiss()
                // A push, not a dialog: Diagnostics has to survive Back.
                router.push(Route.Diagnostics)
            },
        )
        DropdownMenuItem(
            text = { Text("Print Center (web)") },
            leadingIcon = { Icon(Icons.Filled.Language, null) },
            onClick = {
                onDismiss()
                // Also a push rather than an External Intent: this is the one place a CAS/MFA redirect
                // can complete without leaving the app and leaving the session cookie behind.
                router.push(Route.PrintCenter)
            },
        )
        DropdownMenuItem(
            text = { Text("Log off") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
            onClick = {
                onDismiss()
                // `signOut()` actually calls the server's `/logout`. The stock client never did, which
                // left the abandoned cookie valid (docs/FINDINGS.md §10 S4).
                session.signOut()
            },
        )
    }
}

/**
 * The selection bar (`P:510–534`): what is selected, what it costs, what it will be charged to, the
 * five actions a student needs standing at a printer, and one primary verb.
 *
 * **Charcoal, not green.** `secondaryContainer` is this design's *selection* colour; green is reserved
 * for an outcome that already happened. A bar full of jobs you have merely chosen must not be green, or
 * "selected" and "printed" share a colour and the one distinction the queue exists to make is gone.
 * Nothing here is a filled gold button either — gold belongs to a cost centre and to nothing else.
 */
@Composable
private fun QueueSelectionBar(
    state: AppState,
    selection: QueueSelection,
) {
    /*
     * Composed only while a selection exists (the caller's `if`), with no transition of its own:
     * every animated frame of a changing bottomBar height re-pads the full-screen list behind it,
     * and on a screen of job cards that is the bar *lagging*, not moving. §2.0.5's own argument,
     * promoted from the reduced-motion branch to the rule: an object that arrived is information,
     * the travel is not.
     */
    val chosen = state.chosen
        val count = chosen.size
        val formats = state.capabilities?.formats
        // An unpriced job contributes nothing to the total, because there is no number to contribute:
        // GMU answers `-1` for a document it has not finished costing. The notice below says so.
        val priced = chosen.filterNot { it.costUnknown }
        val totalText = formats?.money(priced.sumOf { it.cost ?: 0.0 }) ?: "—"
        val unpriced = count - priced.size
        val onDepartment = state.costCenter?.isNotBlank() == true
        val balance = state.user?.balance?.let { it.amount ?: it.total }
        val arrears = balance != null && balance < 0.0
        // A negative balance is a warning. The server's cost/release response decides eligibility.
        val showBalanceWarning = arrears && !onDepartment
        var moreActionsOpen by rememberSaveable { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            shape = SelectionBarCorner,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = selection.onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$count ${if (count == 1) "job" else "jobs"} selected",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append(totalText)
                                if (unpriced > 0) append(" · ").append(unpriced).append(" not priced yet")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { moreActionsOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = moreActionsOpen, onDismissRequest = { moreActionsOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Estimate cost") },
                                leadingIcon = { Icon(Icons.Filled.Calculate, null) },
                                onClick = { moreActionsOpen = false; selection.onEstimate() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                enabled = state.busy == null && state.upload == null,
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { moreActionsOpen = false; selection.onDelete() },
                            )
                        }
                    }
                }

                /*
                 * Three decisions are made before a release: which machine, who pays, and how it
                 * prints. They are peers, so they are three peer rows.
                 *
                 * They were two buttons and an overflow, which is how the app ended up with no
                 * discoverable way to set copies, sides or colour: a student looking for them found
                 * two buttons about machines and money, and a dot menu that reads as "rare and
                 * destructive". Making them three equal buttons instead only moved the problem,
                 * because three labels plus an overflow on a phone truncates every one of them to
                 * "Pri…", "Pa…", "2-…".
                 *
                 * Full-width rows give each its whole line, so each can state its *current value*
                 * instead of a noun. The bar then answers all three questions without being opened,
                 * which is the actual fix: the settings were not merely hidden, they were invisible.
                 */
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    icon = Icons.Filled.Print,
                    label = "Printer",
                    value = state.selectedDevice?.let { device ->
                        val station = stationOf(device, state.host)
                        buildingName(station.building)?.let { name ->
                            listOfNotNull(name, stationRoom(station)).joinToString(" · ")
                        } ?: device.label
                    } ?: "Not chosen yet",
                    onClick = selection.onPrinter,
                    maxLines = 3,
                )
                SettingRow(
                    icon = if (onDepartment) Icons.Filled.BusinessCenter else Icons.Filled.Work,
                    label = "Pay with",
                    value = if (onDepartment) state.costCenter.orEmpty() else "My own balance",
                    onClick = selection.onChargeTo,
                )
                SettingRow(
                    icon = Icons.Filled.Tune,
                    label = "Print",
                    value = finishingSummary(chosen),
                    onClick = selection.onCopies,
                )

                if (showBalanceWarning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your balance is below zero. Add funds or review the price; the server decides whether release is allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = selection.onAddFunds) {
                        ButtonGlyph(Icons.Filled.Savings)
                        Text("Add funds")
                    }
                }

                if (unpriced > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A job in this selection has no cost. The server will not release a job it cannot " +
                            "price, so it is left out of the total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Spacer(Modifier.height(8.dp))
                // The same pill pair as the idle bar's Release button, for the same reason.
                Button(
                    onClick = selection.onRelease,
                    enabled = state.busy == null && count > 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = currentMasonColors.barPill,
                        contentColor = currentMasonColors.onBarPill,
                    ),
                ) {
                    Text(
                        "Review $count ${if (count == 1) "job" else "jobs"} · $totalText",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
}
