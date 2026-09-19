@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.ahnafnafee.masonprint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MonochromePhotos
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.CostPreview
import dev.ahnafnafee.masonprint.core.ReleaseOutcome
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.deviceTokenFromQr
import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/**
 * The release flow, redesigned: §3.7 (which printer), §3.8 (the last look before money moves),
 * §3.9 (what actually happened, per job).
 *
 * Three facts decide most of the code below, and all three are wire facts about GMU's Uniprint
 * 9.1 SP3 rather than design choices:
 *
 *  - **Charging happens at release, at the printer** — so every screen here says the canonical
 *    sentence (Spec §2.0.7) and nothing treats a balance as a gate: with `MinBalanceOverride:
 *    true` the server does not refuse release for insufficient funds (REDESIGN-SPEC §7.1 #2/#11),
 *    so the app must not invent a gate the server itself does not have.
 *  - **A release answers HTTP 200 with a bare array of per-job rows, and a refusal lives inside
 *    that 200** (`docs/PHAROS-API-FINDINGS.md` §2c) — so a *partial* release is a normal outcome,
 *    [ReleaseResult] quotes the server's own sentence per refused job, and success is never a
 *    single green tick stamped over N jobs.
 *  - **A cost centre is a request, not a receipt** — a 200 anywhere does not mean the charge took
 *    (§7.1 #4), so this flow only ever says what the release *asked to charge*
 *    ([ReleaseOutcome.fundingIntent]); the ledger quotes the two balance reads the server gave.
 *
 * The camera is here (CLONE-PLAN §6 S4): the Scan tab is a live CameraX viewfinder with ML Kit's
 * bundled decoder, so a sticker is read on the device and never needs a network round trip to find
 * the code. The typed code and the deep-link route stay, because they are what a student does when
 * the sticker has been printed over by a label or the panel is a touchscreen whose QR is only a link
 * to GMU's web page (docs/FINDINGS.md §8.5). Deviations are one line each in the port report.
 */

/** The canonical money sentence, Spec §2.0.7 `charge_timing`. Quoted verbatim; do not paraphrase. */
private const val ChargeTiming = "You are charged when you release this at a printer, not now."

/**
 * Navigation scratch for "this printer was picked by a code, not from the list" (§4 scenario 16).
 *
 * The hand-rolled [Router] deliberately takes no route arguments (its own doc says so), and the one
 * shared channel — `AppState` — lives in Session.kt, which this port may not touch. A code pickup
 * sets this; the printer-list pickup clears it; the result screen clears it on leave. It is process
 * state, not saved state: after process death the chip is simply gone, which is honest.
 */
@Stable
internal object ReleaseHandoff {
    var openedFromCode: Boolean = false

    /**
     * The printer-list filter, chosen on [Route.PrinterFilter] and read back by the list. It lives
     * here rather than in the list's own `remember` because the filter is a separate screen: the
     * list leaves composition while it is up, which would throw the choice away. `mutableStateOf`
     * so both screens recompose on a change; process state, like the flag above.
     */
    var building by mutableStateOf<String?>(null)
    var floor by mutableStateOf<String?>(null)

    fun clearFilter() {
        building = null
        floor = null
    }
}

private fun jobWord(n: Int) = if (n == 1) "job" else "jobs"

/** Spec §2.0.4: a key-pad key is 54 dp tall with an 8 dp gap. */
private val KeypadKeyHeight = 54.dp

private fun releaseMoney(state: AppState, amount: Double?): String =
    state.capabilities?.formats?.money(amount) ?: "—"

/** The top bar all three screens share: flat, never a large or collapsing app bar (Spec §2.0). */
@Composable
private fun ReleaseTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
    )
}

/** The server's failure, quoted rather than paraphrased, with a way to dismiss it. */
@Composable
private fun FailureNote(failure: PharosFailure, onDismiss: () -> Unit) {
    NoteCard(
        title = failure.headline(),
        body = failure.detailLine() ?: "No detail from the server.",
        icon = Icons.Filled.Error,
        tone = MasonTone.Error,
        action = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}

// ---------------------------------------------------------------------------------------------
// §3.7 — Release: which printer. Reached from the queue's selection bar; three tabs, one pickup.
// ---------------------------------------------------------------------------------------------

@Composable
fun ReleaseScreen(
    state: AppState,
    session: Session,
    router: Router,
) {
    Scaffold(modifier = Modifier.imePadding(), topBar = { ReleaseTopBar("Release at a printer", onBack = { router.pop() }) }) { bar ->
        var mode by rememberSaveable { mutableStateOf("printers") }
        var query by rememberSaveable { mutableStateOf("") }
        var pasted by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }
        var notFound by rememberSaveable { mutableStateOf<String?>(null) }

        // Cache-then-network. The cached list paints immediately (Session seeds it at boot) and this
        // refresh corrects it — picking up stations added since, and completing any cache written
        // before device paging fetched every page. Guarding on `isEmpty` would pin the first cache
        // forever, which is how a stale printer list outlives the printer.
        LaunchedEffect(Unit) {
            ReleaseHandoff.openedFromCode = false
            session.loadDevices()
        }
        LaunchedEffect(code, pasted) { notFound = null }

        val cameraTab = state.capabilities?.qrReleaseEnabled ?: false
        val tabs = buildList {
            add("printers" to "Printers")
            if (cameraTab) add("qr" to "Scan code")
            add("keypad" to "Type code")
        }
        val effectiveMode = if (mode == "qr" && !cameraTab) "printers" else mode

        val pick: (Device) -> Unit = { device ->
            session.selectDevice(device)
            router.push(Route.Confirm)
        }
        // The pickup both code paths share. `Device.matchesToken` accepts a bare Location, a
        // URL ending in it, an asset tag, or a serial — the shapes the sticker and the
        // deep-link payload carry, so a typed payload and a scanned one land on the same row.
        val findToken: (String) -> Device? = { token ->
            val t = token.trim()
            if (t.isEmpty()) null else state.devices.firstOrNull { it.matchesToken(t) }
        }

        /*
         * The printer list is computed here, not inside the lazy scope below (which is not a
         * composable), so the rows can be emitted as recycling `items` instead of a `forEach`. A
         * campus device list runs to hundreds of rows, and composing all of them at once is what
         * made this screen janky.
         */
        // The building/floor choice is made on its own screen, so it is read from ReleaseHandoff.
        val building = ReleaseHandoff.building
        val floor = ReleaseHandoff.floor
        val stations = remember(state.devices) { state.devices.associateWith(::stationOf) }
        val q = query.trim()
        val shownDevices = remember(state.devices, q, building, floor, stations) {
            state.devices.filter { d ->
                val where = stations[d]
                (building == null || where?.building == building) &&
                    (floor == null || where?.floor == floor) &&
                    (
                        q.isEmpty() ||
                            listOf(d.name, d.model, d.make, d.location, d.assetTag, d.serialNumber, d.description)
                                .filterNotNull().any { it.contains(q, ignoreCase = true) } ||
                            d.deviceGroups.any { it.contains(q, ignoreCase = true) }
                        )
            }
        }
        /*
         * Starred first, then recently used, then everything by building.
         *
         * Two sources, deliberately kept apart. A **favourite** is a preference the student stated,
         * so it is local and it never expires. **Recent** is history, and it comes from two places
         * that answer different questions: the account's own release log, which survives the queue
         * turning over, and the server's `PrinterName` on released jobs still loaded, which covers
         * releases made from the web portal or another device. The log leads because it is ordered
         * by when, where the wire read is only a set.
         *
         * A device appears in exactly one section: starring a printer promotes it out of Recent
         * rather than listing it twice.
         */
        val favourites = remember(shownDevices, state.favouriteDevices) {
            shownDevices.filter { it.location in state.favouriteDevices }.sortedBy { it.label }
        }
        val wireUsedNames = remember(state.jobs) {
            state.jobs.filter { !it.pending }.mapNotNull { it.printerName }.toSet()
        }
        val recent = remember(shownDevices, state.recentDevices, wireUsedNames, favourites) {
            val byLocation = shownDevices.associateBy { it.location }
            val fromLog = state.recentDevices.mapNotNull(byLocation::get)
            val fromWire = shownDevices.filter { d ->
                wireUsedNames.any { n ->
                    n.equals(d.label, ignoreCase = true) ||
                        n.equals(d.name, ignoreCase = true) ||
                        n.equals(d.location, ignoreCase = true)
                }
            }
            (fromLog + fromWire).distinct().filterNot { it in favourites }
        }
        val restDevices = remember(shownDevices, favourites, recent) {
            shownDevices.filterNot { it in favourites || it in recent }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(bar),
            contentPadding = MasonScreenPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "intro") {
                val selected = state.chosen
                if (selected.isEmpty()) {
                    NoteCard(
                        title = "Nothing selected",
                        body = "Go back to the queue and pick the jobs you want at the printer. " +
                            "The selection is kept while you choose the printer.",
                        icon = Icons.Filled.Inbox,
                    )
                } else {
                    Text(
                        "${selected.size} ${jobWord(selected.size)} selected · ${state.fundingLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "tabs") {
                TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == effectiveMode }.coerceAtLeast(0)) {
                    tabs.forEach { (id, label) ->
                        Tab(selected = effectiveMode == id, onClick = { mode = id }, text = { Text(label) })
                    }
                }
            }

            state.failure?.let { failure ->
                item(key = "failure") { FailureNote(failure, onDismiss = session::dismissFailure) }
            }

            when (effectiveMode) {
                "printers" -> printersTab(
                    state = state,
                    query = query,
                    onQuery = { query = it },
                    building = building,
                    floor = floor,
                    onOpenFilter = { router.push(Route.PrinterFilter) },
                    onClearFilter = { ReleaseHandoff.clearFilter() },
                    stations = stations,
                    favourites = favourites,
                    recent = recent,
                    rest = restDevices,
                    favouriteLocations = state.favouriteDevices,
                    onPick = pick,
                    onToggleFavourite = session::toggleFavouriteDevice,
                    onReload = session::loadDevices,
                )

                // Both tabs emit a run of siblings written for a ColumnScope, so each gets a real
                // Column inside its item. A lazy item is one slot: without this the children are
                // measured as a stack and the keypad stretches into ovals.
                "qr" -> item(key = "qr") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScanTab(
                    pasted = pasted,
                    onPasted = { pasted = it },
                    onFind = { token ->
                        val hit = findToken(token)
                        if (hit != null) {
                            ReleaseHandoff.openedFromCode = true
                            pick(hit)
                        } else {
                            notFound = token.trim()
                        }
                    },
                    // A scanned sticker is matched locally first, exactly like a pasted one: the
                    // sticker carries the device id, the device list is already on the phone, and
                    // going through Confirm rather than straight to release is the one place this
                    // clone deliberately declines to follow the vendor. In the stock app a scan
                    // calls `POST /printjobs/release` for the oldest held job with no look at the
                    // queue first (docs/FINDINGS.md §8.5) — which spends money on a mis-scan.
                    // Asking the server is still one tap away in the card below.
                    onScan = { raw ->
                        val token = deviceTokenFromQr(raw)
                        if (token == null) {
                            notFound = raw.trim()
                        } else {
                            val hit = findToken(token)
                            if (hit != null) {
                                ReleaseHandoff.openedFromCode = true
                                pick(hit)
                            } else {
                                notFound = token
                            }
                        }
                    },
                    onList = { mode = "printers" },
                    onAskServer = { session.resolveDeviceToken(it) },
                    notFound = notFound,
                    onClearNotFound = { notFound = null },
                    )
                    }
                }

                else -> item(key = "keypad") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TypeCodeTab(
                            code = code,
                            onCode = { code = it },
                            onFind = {
                                val hit = findToken(code)
                                if (hit != null) {
                                    ReleaseHandoff.openedFromCode = true
                                    pick(hit)
                                } else {
                                    notFound = code
                                }
                            },
                            onList = { mode = "printers" },
                            onAskServer = { session.resolveDeviceToken(it) },
                            notFound = notFound,
                            onClearNotFound = { notFound = null },
                        )
                    }
                }
            }

            item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
        }
    }
}

/**
 * The printer list, emitted into the screen's [LazyColumn] rather than composed all at once: a
 * campus device list runs to hundreds of rows, and a `forEach` inside a scrolling `Column` builds
 * every one of them up front. The filter state and the groupings are computed by the caller,
 * because a `LazyListScope` is not a composable and cannot `remember`.
 */
private fun LazyListScope.printersTab(
    state: AppState,
    query: String,
    onQuery: (String) -> Unit,
    building: String?,
    floor: String?,
    onOpenFilter: () -> Unit,
    onClearFilter: () -> Unit,
    stations: Map<Device, Station>,
    favourites: List<Device>,
    recent: List<Device>,
    rest: List<Device>,
    favouriteLocations: Set<String>,
    onPick: (Device) -> Unit,
    onToggleFavourite: (Device) -> Unit,
    onReload: () -> Unit,
) {
    item(key = "search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search building, room or model") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
    }

    if (state.devices.isEmpty()) {
        item(key = "devices-empty") {
            if (state.busy != null) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text(state.busy ?: "Loading printers", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmptyState(
                        icon = Icons.Filled.Print,
                        title = "No printers from this server",
                        body = "The server returned no release stations. Try again, or type the code from " +
                            "the panel screen. Both reach the same printers.",
                    )
                    OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                        Text("Try again")
                    }
                }
            }
        }
        return
    }

    /*
     * One row, not a chip per building: GMU has ~60 of them across 302 stations, and a chip wall
     * pushes the actual printers off the screen. The choice is made on its own screen; this only
     * reports it and offers to clear it.
     */
    item(key = "filter") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onOpenFilter, modifier = Modifier.weight(1f)) {
                ButtonGlyph(Icons.Filled.FilterList)
                Text(filterSummary(building, floor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (building != null || floor != null) {
                TextButton(onClick = onClearFilter) { Text("Clear") }
            }
        }
    }

    if (favourites.isEmpty() && recent.isEmpty() && rest.isEmpty()) {
        item(key = "no-match") {
            EmptyState(
                icon = Icons.Filled.Search,
                title = "No printer matches \"${query.trim()}\"",
                body = "Try a building, a room, or the model printed under the panel screen.",
            )
        }
        return
    }

    if (favourites.isNotEmpty()) {
        item(key = "favourites") { SectionLabel("Starred") }
        items(favourites, key = { "fav-" + it.location }) { d ->
            DeviceRow(
                device = d,
                last = false,
                favourite = true,
                onClick = { onPick(d) },
                onToggleFavourite = { onToggleFavourite(d) },
            )
        }
    }
    if (recent.isNotEmpty()) {
        item(key = "last-used") { SectionLabel("Recently used") }
        items(recent, key = { "recent-" + it.location }) { d ->
            DeviceRow(
                device = d,
                last = true,
                favourite = d.location in favouriteLocations,
                onClick = { onPick(d) },
                onToggleFavourite = { onToggleFavourite(d) },
            )
        }
    }
    if (rest.isNotEmpty()) {
        item(key = "all-printers") {
            SectionLabel(if (building == null) "All printers, by building" else "$building, by floor")
        }
        val byBuilding = rest.groupBy { stations[it]?.building ?: "Other" }
        byBuilding.toList()
            .sortedWith(compareBy({ it.first == "Other" }, { it.first }))
            .forEach { (bldg, inBuilding) ->
                // The building header is redundant once a single building is filtered to.
                if (building == null && (byBuilding.size > 1 || bldg != "Other")) {
                    item(key = "building-$bldg") {
                        Text(
                            bldg,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                        )
                    }
                }
                val byFloor = inBuilding.groupBy { stations[it]?.floor }
                byFloor.toList()
                    .sortedWith(compareBy({ it.first == null }, { it.first?.toIntOrNull() ?: Int.MAX_VALUE }, { it.first }))
                    .forEach { (floor, inFloor) ->
                        if (floor != null && byFloor.size > 1) {
                            item(key = "floor-$bldg-$floor") {
                                Text(
                                    "Floor $floor",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                                )
                            }
                        }
                        items(inFloor.sortedBy { it.label }, key = { it.location }) { d ->
                            DeviceRow(
                                device = d,
                                last = false,
                                favourite = d.location in favouriteLocations,
                                onClick = { onPick(d) },
                                onToggleFavourite = { onToggleFavourite(d) },
                            )
                        }
                    }
            }
    }
}

/** A printer row: 44 dp print avatar, name, mono model line, capability facts, and where it leads. */
@Composable
private fun DeviceRow(
    device: Device,
    last: Boolean,
    favourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowAvatar(Icons.Filled.Print)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(device.label, style = MaterialTheme.typography.titleMedium)
                MonoDetail(device.sublabel)
                // Wrapping, not scrolling: a long model line plus three chips has to grow a second
                // line rather than push the chevron off the card.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Capability facts, not colour-coded status: what this machine can do.
                    if (device.colorSupported) FactChip(Icons.Filled.Palette, "Colour")
                    else FactChip(Icons.Filled.MonochromePhotos, "B&W only")
                    if (device.duplexSupported) FactChip(Icons.Filled.Description, "Two-sided")
                    if (last) FactChip(Icons.Filled.History, "Used before")
                }
            }
            /*
             * Its own button, not a swipe or a long-press: starring has to be discoverable from
             * looking, and the row's tap already means "release here", which is the one gesture
             * that must never be ambiguous when a student is standing at a machine.
             */
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (favourite) {
                        "Remove ${device.label} from starred"
                    } else {
                        "Star ${device.label}"
                    },
                    tint = if (favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanTab(
    pasted: String,
    onPasted: (String) -> Unit,
    onFind: (String) -> Unit,
    onScan: (String) -> Unit,
    onList: () -> Unit,
    onAskServer: (String) -> Unit,
    notFound: String?,
    onClearNotFound: () -> Unit,
) {
    // §3.7: the camera is the primary path here because the code on the panel is the only thing that
    // names the printer a student is standing next to, and GMU switches off the affordance the web
    // portal uses for it (`Display Print Button: No` alongside `EnableCameraScanner: true` —
    // docs/FINDINGS.md §8.5). Decoding is on-device, so this works with no signal; what the scan
    // then does — `GET /devices/{id}` — needs the network like everything else in the app.
    PrinterCodeScanner(onScan = onScan, modifier = Modifier.padding(top = 2.dp))
    Text(
        "Every printer has a square code on its label. Scanning it picks the printer so you do not " +
            "have to read a model number off the front of it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The manual route stays on the same tab as the camera, not behind another tab, because the
    // reason it is needed is a physical one: a sticker printed over, worn off, or a station whose
    // only QR is the touchscreen's link to GMU's web page.
    SectionLabel("If the code will not scan")
    OutlinedTextField(
        value = pasted,
        onValueChange = onPasted,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Paste or type the code from the printer") },
        supportingText = { Text("The sticker payload, its URL, or the station number all match.") },
        singleLine = true,
    )
    Button(
        onClick = { onFind(pasted) },
        enabled = pasted.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Find this printer") }

    if (notFound != null) {
        NotFoundCodeCard(code = notFound, onAskServer = onAskServer, onList = onClearNotFound.merge(onList))
    }
}

@Composable
private fun TypeCodeTab(
    code: String,
    onCode: (String) -> Unit,
    onFind: () -> Unit,
    onList: () -> Unit,
    onAskServer: (String) -> Unit,
    notFound: String?,
    onClearNotFound: () -> Unit,
) {
    Text(
        "The station code is on the panel screen, under “Release code”. It is four digits.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The typed code, read back like a panel screen would: big, mono, spaced, spoken in full.
    val spoken = if (code.isEmpty()) "Station code, nothing typed yet"
    else "Station code " + code.toCharArray().joinToString(" ")
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = if (code.isEmpty()) "\u2003\u2003\u2003\u2003" else code,
            style = LocalMasonType.current.keypad.copy(
                fontSize = 34.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.32.em,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        )
    }

    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("clear", "0", "back"),
    )
    keys.forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { key ->
                when (key) {
                    "clear" -> OutlinedButton(
                        onClick = { onCode("") },
                        enabled = code.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = KeypadKeyHeight),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) { Text("Clear") }

                    "back" -> OutlinedButton(
                        onClick = { onCode(code.dropLast(1)) },
                        enabled = code.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = KeypadKeyHeight),
                    ) { Icon(Icons.Filled.Backspace, contentDescription = "Delete last digit") }

                    // Spec §2.0.6: the key pad is a `FilledTonalButton` grid. A TextButton here
                    // reads as three unlabeled gaps, not as keys.
                    else -> FilledTonalButton(
                        onClick = { if (code.length < 4) onCode(code + key) },
                        enabled = code.length < 4,
                        modifier = Modifier.weight(1f).heightIn(min = KeypadKeyHeight),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            key,
                            style = LocalMasonType.current.keypad.copy(fontWeight = FontWeight.W700),
                        )
                    }
                }
            }
        }
    }

    FilledTonalButton(
        onClick = onFind,
        enabled = code.length == 4,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Text("Find this printer", textAlign = TextAlign.Center) }
    TextButton(onClick = onList, modifier = Modifier.fillMaxWidth()) {
        Text("Or pick the printer from the list", textAlign = TextAlign.Center)
    }

    if (notFound != null) {
        NotFoundCodeCard(code = notFound, onAskServer = onAskServer, onList = onClearNotFound.merge(onList))
    }
}

/** Inline instead of a snackbar: the reason must still be on screen while the user decides. */
@Composable
private fun NotFoundCodeCard(code: String, onAskServer: (String) -> Unit, onList: () -> Unit) {
    val (background, foreground) = MasonToneSurfaces(MasonTone.Warn)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = background,
        contentColor = foreground,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Help, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No printer here carries the code $code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Check the panel code, choose from the list, or ask the server to find it. You will review before release.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // Actions use the full card width and grow when their labels wrap at larger fonts.
            Button(
                onClick = { onAskServer(code) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Look up this code", textAlign = TextAlign.Center)
            }
            TextButton(onClick = onList, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Pick from the list", textAlign = TextAlign.Center)
            }
        }
    }
}

/** Small combinator so "clear the error, then go" reads as one action at both call sites. */
private fun (() -> Unit).merge(next: () -> Unit): () -> Unit = {
    this()
    next()
}

// ---------------------------------------------------------------------------------------------
// §3.8 — Confirm: the server's price, the left-behind jobs, the money sentence, one button.
// ---------------------------------------------------------------------------------------------

@Composable
fun ConfirmRelease(
    state: AppState,
    session: Session,
    router: Router,
) {
    Scaffold(topBar = { ReleaseTopBar("Confirm release", onBack = { router.pop() }) }) { bar ->
        val reduced = rememberReducedMotion()

        // A confirmed release may finish while this screen is still visible.
        LaunchedEffect(state.outcome) {
            if (state.outcome != null) router.replaceTop(Route.Result)
        }

        // The price is asked for, never computed here: POST /printjobs/cost answers per job, and a
        // local sum would disagree with the portal. CostPreview is not Saveable, so it is `remember`
        // — on rotation the ask simply runs again, which is what a price check should do anyway.
        val selected = state.chosen
        var preview by remember(selected, state.selectedDevice, state.costCenter) { mutableStateOf<CostPreview?>(null) }
        var retryPrice by remember { mutableStateOf(0) }
        LaunchedEffect(selected, state.selectedDevice, state.costCenter, retryPrice) {
            preview = null
            if (selected.isNotEmpty() && selected.all { it.pending } && state.selectedDevice != null) {
                val request = session.previewCost(selected) { preview = it }
                try { request?.join() } finally { request?.cancel() }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(bar)
                .verticalScroll(rememberScrollState())
                .padding(MasonScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val device = state.selectedDevice
            val chosen = state.chosen

            if (device == null) {
                EmptyState(
                    icon = Icons.Filled.Print,
                    title = "No printer chosen",
                    body = "Choose a printer to check the price for your selected documents.",
                )
                Button(
                    onClick = { if (router.canGoBack) router.pop() else router.replaceTop(Route.Release) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pick a printer") }
                Spacer(Modifier.height(MasonScrollSpacer))
                return@Column
            }
            if (chosen.isEmpty() && state.outcome == null) {
                EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = "Nothing selected",
                    body = "Go back to the queue and pick the jobs you want at this printer.",
                )
                Button(onClick = { router.reset(Route.Queue) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to the queue")
                }
                Spacer(Modifier.height(MasonScrollSpacer))
                return@Column
            }

            if (chosen.any { !it.pending }) {
                NoteCard(title = "Some documents have already been released",
                    body = "Return to the queue and select only documents that are waiting to release.", tone = MasonTone.Warn)
            }

            // --- The printer, with its model line: a service-desk call starts by reading this. ----
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RowAvatar(Icons.Filled.Print)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.label, style = MaterialTheme.typography.titleMedium)
                        MonoDetail(device.sublabel)
                        if (ReleaseHandoff.openedFromCode) {
                            // §4 scenario 16: say how the printer was reached; it changes what the
                            // service desk would check first.
                            FactChip(
                                icon = Icons.Filled.QrCodeScanner,
                                label = "Opened from a code",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            // --- Jobs this printer will take, and the ones it will be asked to leave behind. ------
            SectionLabel("Selected documents")
            chosen.forEach { job ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                job.name ?: "Unnamed job",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    specLine(job),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text("Queue: ${releaseMoney(state, job.cost)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // --- The money, in the server's words. ------------------------------------------------
            SectionLabel("Estimated total")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Crossfade(targetState = preview, label = "price") { pv ->
                        when {
                            pv == null -> Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp))
                                Text(
                                    "Asking the server what this costs…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            pv.failed -> Text(
                                "Could not check the price. Try again before releasing.",
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            else -> MoneyText(
                                pv.totalText,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    MasonHairline()
                    Text("Pay with: ${state.fundingLabel}", style = MaterialTheme.typography.bodyMedium)
                    Text("Applies to all ${chosen.size} selected ${jobWord(chosen.size)}.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "The server confirms the charge when you release. Check the result for each document.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview?.failed == true || preview?.blocked == true) {
                        preview?.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { retryPrice++ }) { Text("Check price again") }
                    }
                }
            }

            // Arrears is information, never a gate: GMU answers with MinBalanceOverride: true, so
            // an app-side block would refuse a release the server would have accepted (§7.1 #2/#11).
            val balance = state.balanceText
            if (balance != null && balance.contains('-')) {
                NoteCard(
                    title = "Your balance is already $balance",
                    body = "Releasing may lower this balance further. The server decides whether the selected funding source can pay.",
                    icon = Icons.Filled.Payments,
                    tone = MasonTone.Warn,
                )
            }

            state.failure?.let { FailureNote(it, onDismiss = session::dismissFailure) }

            // --- The one button, and the sentence under it. ---------------------------------------
            val busy = state.busy
            Button(
                onClick = { session.releaseSelected(state.chosen) },
                enabled = busy == null && !state.releasing && chosen.all { it.pending } && preview?.let { !it.failed && !it.blocked } == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (busy != null) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(busy)
                } else {
                    Text("Release ${chosen.size} ${jobWord(chosen.size)} at ${device.label}", textAlign = TextAlign.Center)
                }
            }
            Text(
                ChargeTiming,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (preview?.blocked == true) {
                Text(
                    "A price is not available for every selected document. Retry, or return to the queue to change your selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = currentMasonColors.warn,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(MasonScrollSpacer))
        }
    }
}

@Composable
private fun MoneyLine(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount)
    }
}

// ---------------------------------------------------------------------------------------------
// §3.9 — Result: an acknowledgement screen, not a page. No back arrow; the user must acknowledge.
// ---------------------------------------------------------------------------------------------

@Composable
fun ReleaseResult(
    state: AppState,
    session: Session,
    router: Router,
) {
    val outcome = state.outcome
    Scaffold(topBar = { ReleaseTopBar("Release result", onBack = null) }) { bar ->
        val haptics = LocalHapticFeedback.current
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current

        LaunchedEffect(outcome) {
            if (outcome != null) {
                haptics.performHapticFeedback(
                    if (outcome.allReleased) HapticFeedbackType.Confirm else HapticFeedbackType.Reject,
                )
            }
        }
        val acknowledge = {
            ReleaseHandoff.openedFromCode = false
            session.clearOutcome()
            router.reset(Route.Queue)
        }
        // No back arrow, but Android's back still has to land somewhere: treat it as acknowledging.
        BackHandler(onBack = acknowledge)

        Column(
            Modifier
                .fillMaxSize()
                .padding(bar)
                .verticalScroll(rememberScrollState())
                .padding(MasonScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (outcome == null) {
                EmptyState(
                    icon = Icons.Filled.DoneAll,
                    title = "Nothing to report",
                    body = "This release was already acknowledged.",
                )
                Button(onClick = { router.reset(Route.Queue) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to the queue")
                }
                Spacer(Modifier.height(MasonScrollSpacer))
                return@Column
            }

            val transport = outcome.transport
            val tone = when {
                outcome.allReleased -> MasonTone.Ok
                transport != null -> MasonTone.Error
                else -> MasonTone.Warn
            }
            val (bg, fg) = MasonToneSurfaces(tone)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = bg,
                contentColor = fg,
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RowAvatar(
                        icon = if (transport != null) Icons.Filled.Error else Icons.Filled.DoneAll,
                        tone = tone,
                        size = 56,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // "3 of 4 jobs released" — the count is the headline, not a green tick.
                        Text(
                            transport?.headline() ?: outcome.releasedText,
                            style = MaterialTheme.typography.titleLarge,
                            color = fg,
                        )
                        Text(
                            when {
                                transport != null -> transport.detailLine()
                                    ?: "${outcome.printer} · nothing was charged; the jobs are " +
                                        "still in your queue."

                                outcome.refused.isEmpty() ->
                                    "${outcome.printer} · every selected job moved."

                                else -> {
                                    val k = outcome.refused.size
                                    "${outcome.printer} · $k ${jobWord(k)} " +
                                        "${if (k == 1) "was" else "were"} refused and " +
                                        "${if (k == 1) "is" else "are"} still in the queue."
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = fg,
                        )
                    }
                }
            }

            SectionLabel("Each job")
            outcome.released.forEach { loc ->
                val job = state.jobs.firstOrNull { it.location == loc }
                ResultRow(
                    icon = Icons.Filled.CheckCircle,
                    tone = MasonTone.Ok,
                    name = job?.name ?: outcome.releasedNames[loc] ?: loc.substringAfterLast('/'),
                    amount = job?.let { releaseMoney(state, it.cost) },
                    line = "Released.",
                )
            }
            outcome.refused.forEach { rj ->
                val reason = transport?.headline() ?: rj.reason
                ResultRow(
                    icon = Icons.Filled.Error,
                    tone = MasonTone.Error,
                    name = rj.name,
                    amount = null,
                    chip = "Not charged",
                    // The server's sentence, verbatim inside quotes — the prototype's exact frame.
                    // Client prose never fronts a server-supplied one (§7.1 #1); GMU's own
                    // JobRelease_JobsFailedCosting line wins over anything this app could say.
                    line = if (reason != null) "Refused. The server said: “$reason”"
                    else "Refused. The server gave no reason for this job. Ask the service desk.",
                )
            }

            SectionLabel("What moved")
            MonoBlock(
                buildString {
                    appendLine("Printer".padEnd(16) + outcome.printer)
                    appendLine(
                        "Balance".padEnd(16) +
                            "${outcome.balanceBefore ?: "—"} → ${outcome.balanceAfter ?: "—"}",
                    )
                    append("Asked to charge".padEnd(16) + outcome.fundingIntent)
                },
            )
            Text(
                if (outcome.fundingIntent != "My own balance") {
                    // §7.1 #4: never "Charged to X" off a write — quote what was asked, and who
                    // actually confirms it.
                    "The release asked to charge ${outcome.fundingIntent}. A cost centre leaves " +
                        "your balance untouched. And the server, not this app, confirms it moved."
                } else {
                    "Money moves when the server says it moved. If it drew from more than one " +
                        "purse, the server set the order; Mason Print only reports what came back."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = acknowledge,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Back to the queue") }
            OutlinedButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            releaseReportText(outcome, state.host, state.apiVersion) { loc ->
                                state.jobs.firstOrNull { it.location == loc }?.name
                                    ?: loc.substringAfterLast('/')
                            },
                        ),
                    )
                    android.widget.Toast
                        .makeText(context, "Copied for the service desk", android.widget.Toast.LENGTH_SHORT)
                        .show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonGlyph(Icons.Filled.ContentCopy)
                Text("Copy this for the service desk")
            }

            Spacer(Modifier.height(MasonScrollSpacer))
        }
    }
}

@Composable
private fun ResultRow(
    icon: ImageVector,
    tone: MasonTone,
    name: String,
    amount: String?,
    chip: String? = null,
    line: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowAvatar(icon, tone = tone, size = 40)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chip != null) {
                    FactChip(
                        icon = Icons.Filled.Block,
                        label = chip,
                        tone = MasonTone.Error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (amount != null) MoneyText(amount)
        }
    }
}

/**
 * The clipboard artifact for the service desk, as a pure function so a test can pin it.
 *
 * It quotes, it does not summarise: the refused reason is the server's byte-for-byte sentence and
 * the money line is the pair of balance reads — the two things the desk asks for on the phone.
 */
internal fun releaseReportText(
    outcome: ReleaseOutcome,
    host: String,
    apiVersion: String?,
    nameFor: (String) -> String,
): String = buildString {
    appendLine("Mason Print. Release report")
    appendLine("Server: $host${apiVersion?.let { " (API $it)" } ?: ""}")
    appendLine("Printer: ${outcome.printer}")
    appendLine(
        "Requested ${outcome.requested} · released ${outcome.released.size} · " +
            "refused ${outcome.refused.size}",
    )
    appendLine("Asked to charge: ${outcome.fundingIntent}")
    appendLine("Balance: ${outcome.balanceBefore ?: "—"} → ${outcome.balanceAfter ?: "—"}")
    outcome.transport?.let { appendLine("No answer from the server: ${it.headline()}") }
    if (outcome.released.isNotEmpty()) {
        appendLine("Released:")
        outcome.released.forEach { appendLine("- ${nameFor(it)}") }
    }
    if (outcome.refused.isNotEmpty()) {
        appendLine("Refused:")
        outcome.refused.forEach {
            appendLine("- ${it.name}: ${it.reason ?: "no reason given by the server"}")
        }
    }
}.trimEnd()
