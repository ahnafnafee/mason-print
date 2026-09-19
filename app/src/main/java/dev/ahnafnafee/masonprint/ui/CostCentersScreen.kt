@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.ui.theme.SelectedCardCorner
import kotlinx.coroutines.delay

/**
 * Who pays for this print run — the student's own balance, or a department's cost center (§3.11).
 *
 * The list is honest about where each row came from, because on GMU the two sources behave very
 * differently. `GET {UserUri}/costcenters?search=` ignores the search term entirely (the response
 * is byte-identical for every query — `evidence/probe-costcenters-costcenters.json`), so a query
 * can only be honoured by filtering on the phone; and the one centre the account actually owns
 * carries `Grant: false`, so `granted` is never the test of whether a row may be chosen. Chargeable
 * means `active && complete` — `complete: false` is a directory folder, not a code you can be
 * billed to.
 *
 * And the screen never claims a charge happened: GMU answers a costing attempt with
 * `Improper data. (Cost Center information is invalid because invalid separator used ).` for every
 * separator but the hyphen (`analysis/centre-sweep.ps1`), echoes `CostCenterCode: ""` back even for
 * the accepted code, and a job whose costing fails never gains a `Release` action. So every promise
 * on this screen is about what will be *asked*, and the hyphen warning is part of the copy.
 */
@Composable
fun CostCentersScreen(
    state: AppState,
    session: Session,
    router: Router,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    // The collection endpoint is the only source of codes (the user resource returns a 0-byte
    // body for cost centres), so prime it once with an empty query; GMU will answer with its
    // whole list either way. Every keystroke still re-asks — a server that *does* honour
    // ?search= gets a chance to — but with a short debounce, and the local filter below runs
    // regardless of what comes back.
    LaunchedEffect(Unit) {
        if (state.costCenterResults.isEmpty()) session.searchCostCenters("")
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isNotEmpty()) {
            delay(300)
            session.searchCostCenters(q)
        }
    }

    val snapshot = state.user?.costCenters ?: emptyList()
    val snapshotCodes = remember(snapshot) { snapshot.map { it.code }.toSet() }
    val merged = remember(snapshot, state.costCenterResults) {
        (snapshot + state.costCenterResults).distinctBy { it.code }
    }
    val shown = remember(merged, query) { filterCostCenters(merged, query) }
    val onAccount = remember(shown, snapshotCodes) { shown.filter { it.code in snapshotCodes } }
    val foundBySearch = remember(shown, snapshotCodes) { shown.filter { it.code !in snapshotCodes } }
    val typed = query.trim()
    val typedIsNew = typed.isNotEmpty() &&
        merged.none { it.code.equals(typed, ignoreCase = true) } &&
        filterCostCenters(merged, typed).isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Charge to a cost center") },
                navigationIcon = {
                    IconButton(onClick = { router.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
                Column(Modifier.padding(MasonScreenPadding)) {
                    Button(
                        onClick = { router.pop() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MasonPillShape,
                    ) {
                        Text(
                            state.costCenter?.let { "Charge to $it" } ?: "Keep charging my balance",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MasonScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A cost center moves the charge from your balance to a department's grant. " +
                    "The server decides whether a code is valid for you; Mason Print only asks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The way back is always one tap: this card is first, and tapping it commits.
            ChargeCard(
                selected = state.costCenter == null,
                selectedContainer = MaterialTheme.colorScheme.primaryContainer,
                onClick = { session.setCostCenter(null) },
                mark = {
                    Icon(
                        if (state.costCenter == null) Icons.Filled.CheckCircle else Icons.Filled.AccountBalanceWallet,
                        contentDescription = if (state.costCenter == null) "Selected" else null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("My own balance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Charged from your print balance when you release at a printer" +
                            state.balanceText?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (onAccount.isNotEmpty()) {
                SectionGap()
                SectionLabel("On this account")
                onAccount.forEach { cc ->
                    CostCenterCard(
                        cc = cc,
                        selected = state.costCenter == cc.code,
                        granted = snapshotCodes.contains(cc.code) && cc.granted,
                        found = false,
                        onClick = { session.setCostCenter(cc.code) },
                    )
                }
            }

            /*
             * Codes this phone remembers the account charging to — the only directory that exists,
             * because the server answers every search with the codes already on the account. A
             * hand-typed code lands here the moment it is charged to, which is the whole trick:
             * type it once, tap it forever. Hidden for any code the server already lists.
             */
            val mergedCodes = remember(merged) { merged.map { it.code.lowercase() }.toSet() }
            val savedHere = remember(state.savedCostCenters, mergedCodes) {
                state.savedCostCenters.filter { it.code.lowercase() !in mergedCodes }
            }
            if (savedHere.isNotEmpty()) {
                SectionGap()
                SectionLabel("Saved on this phone")
                savedHere.forEach { entry ->
                    val chosen = state.costCenter?.equals(entry.code, ignoreCase = true) == true
                    ChargeCard(
                        selected = chosen,
                        selectedContainer = MaterialTheme.colorScheme.tertiaryContainer,
                        onClick = { session.setCostCenter(entry.code) },
                        mark = {
                            Icon(
                                if (chosen) Icons.Filled.CheckCircle else Icons.Filled.BusinessCenter,
                                contentDescription = if (chosen) "Selected" else null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                entry.code,
                                style = LocalMasonType.current.monoLabel,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                entry.description ?: "Saved on this phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Removing a shortcut is not switching funding — the card is the choice,
                        // the ✕ only edits the list of shortcuts.
                        IconButton(onClick = { session.unsaveCostCenter(entry.code) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${entry.code} from saved",
                            )
                        }
                    }
                }
            }

            SectionGap()
            SearchField(
                query = query,
                onQuery = { query = it },
                onSearch = { focus.clearFocus() },
            )
            if (state.searchingCostCenters) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(Modifier.width(72.dp).height(4.dp))
                    Text(
                        "Asking the server…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            /*
             * The old wording said these servers "answer with their whole list", which made the field
             * look broken: you type, and nothing new ever appears. Probed live against GMU —
             * `{UserUri}/costcenters?Skip=0&PageSize=12[&search=…]` returns a **274-byte,
             * byte-identical** body for every term, carrying exactly one code: the one already on the
             * account. There is no directory to search here.
             *
             * The test is therefore not "did the server answer" (it always does) but "did it answer
             * with anything the account did not already have" — [foundBySearch]. A deployment that
             * really does publish a directory still gets the filtering explanation.
             */
            if (!state.searchingCostCenters && foundBySearch.isEmpty()) {
                Text(
                    "This server does not publish a directory of codes: it answers every search with " +
                        "the codes already on your account, listed above. If your department gave you " +
                        "one that is not there, type it in full and charge to it anyway — it is then " +
                        "saved on this phone for next time. The server decides when the job is priced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Some servers ignore the search term and answer with their whole list, so this " +
                        "list is filtered on your phone as well.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (foundBySearch.isNotEmpty()) {
                SectionGap()
                SectionLabel("Found on the server")
                Text(
                    "These were not granted to you at sign-in. The server may still refuse them " +
                        "when you release, and it will say so then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                foundBySearch.forEach { cc ->
                    CostCenterCard(
                        cc = cc,
                        selected = state.costCenter == cc.code,
                        granted = cc.granted,
                        found = true,
                        onClick = { session.setCostCenter(cc.code) },
                    )
                }
            }

            if (typedIsNew) {
                SectionGap()
                // The web client lets you charge codes nobody has seen; legality is the server's
                // question at release time, so the typed code stays offered, dashed outline and all.
                DashedRow(
                    onClick = { session.setCostCenter(typed) },
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Charge to \"$typed\" anyway",
                            style = LocalMasonType.current.monoLabel,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "The server decides whether this code is valid when the job is priced.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (query.isNotBlank() && shown.isEmpty()) {
                SectionGap()
                Text(
                    "Nothing here matches \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionGap()
            NoteCard(
                title = "Only the hyphen separates",
                body = "On ${state.host} every other spelling of a code. A tilde, dot, slash, " +
                    "space, or half a code. Is answered, verbatim: \u201CImproper data. (Cost " +
                    "Center information is invalid because invalid separator used ).\u201D A job " +
                    "whose costing fails that way is never priced, and an unpriced job cannot be " +
                    "released. Codes are offered exactly as the server sent them.",
                icon = Icons.Filled.Error,
                tone = MasonTone.Warn,
            )
            NoteCard(
                title = "This choice is a request, not a receipt",
                body = "Even an accepted code comes back in the response as an empty field. Until " +
                    "the job has actually been released and re-read, Mason Print only knows what " +
                    "the job was asked to charge. Never who paid.",
                tone = MasonTone.Neutral,
            )

            Spacer(Modifier.height(MasonScrollSpacer))
        }
    }
}

/**
 * The search pill from §3.11: radius 28, mono input, no Material label animation. It is honest
 * about being a filter, not a query the server is obliged to honour.
 */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        // Not "search every code on the server". GMU's collection endpoint returns an
                        // empty list for every query, so the field's real job is entering the code
                        // your department gave you; filtering what is already listed is the bonus.
                        "Enter a department code",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Cost center search" },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        }
    }
}

/** A real cost-centre row, from either source, in one shape language. */
@Composable
private fun CostCenterCard(
    cc: CostCenter,
    selected: Boolean,
    granted: Boolean,
    found: Boolean,
    onClick: () -> Unit,
) {
    val chargeable = cc.active && cc.complete
    val reduced = rememberReducedMotion()
    val spec: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> =
        if (reduced) snap() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
    val markCorner by animateDpAsState(if (selected) 12.dp else 18.dp, spec, label = "ccMark")
    val shape = if (selected) SelectedCardCorner else RoundedCornerShape(20.dp)

    ChargeCard(
        selected = selected,
        selectedContainer = MaterialTheme.colorScheme.tertiaryContainer,
        dashed = found && !selected,
        enabled = chargeable,
        shape = shape,
        onClick = onClick,
        mark = {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), RoundedCornerShape(markCorner)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.BusinessCenter,
                    contentDescription = if (selected) "Selected" else null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                cc.code,
                style = LocalMasonType.current.monoLabel,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            cc.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !cc.active -> StatusChip(Icons.Filled.Error, "Closed", MasonTone.Error)
                    !cc.complete -> StatusChip(Icons.Filled.VisibilityOff, "Folder. Not chargeable", MasonTone.Neutral)
                    granted -> StatusChip(Icons.Filled.CheckCircle, "Granted at sign-in", MasonTone.Ok)
                    found -> DashedTag("Found by search")
                    // GMU's only centre is on the account with Grant: false — it still gets a word.
                    else -> StatusChip(Icons.Filled.BusinessCenter, "On your account", MasonTone.Neutral)
                }
            }
        }
    }
}

/** The card shell both the own-balance row and the cost-centre rows share. */
@Composable
private fun ChargeCard(
    selected: Boolean,
    selectedContainer: Color,
    onClick: () -> Unit,
    mark: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    ChargeCard(
        selected = selected,
        selectedContainer = selectedContainer,
        dashed = false,
        enabled = true,
        shape = if (selected) SelectedCardCorner else RoundedCornerShape(20.dp),
        onClick = onClick,
        mark = mark,
        content = content,
    )
}

@Composable
private fun ChargeCard(
    selected: Boolean,
    selectedContainer: Color,
    dashed: Boolean,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    mark: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    selected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    dashed -> Modifier.drawBehind {
                        drawRoundRect(
                            color = outline,
                            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f),
                            ),
                        )
                    }
                    else -> Modifier.border(1.dp, outline, shape)
                },
            )
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        shape = shape,
        color = if (selected) selectedContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .then(if (enabled) Modifier else Modifier.alpha(0.6f)),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            mark()
            content()
        }
    }
}

/** A code the server has never named, offered because the web client offers it too. */
@Composable
private fun DashedRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)),
                )
            }
            .clickable(onClick = onClick),
        shape = shape,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            content = content,
        )
    }
}

/** The dashed "Found by search" pill from the prototype's ccOptions row. */
@Composable
private fun DashedTag(label: String) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)),
                    )
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * The server ignores `?search=` on GMU (byte-identical 274-byte responses for every query), so
 * honouring a keystroke is this pure filter's job. It folds both sides to letters-and-digits and
 * matches case-insensitively — which is also what lets a half-typed `10111 M17041` find
 * `10111-M17041` — and a blank query is not a filter, it is the whole list.
 */
internal fun filterCostCenters(items: List<CostCenter>, query: String): List<CostCenter> {
    val needle = normalizeCostCenterKey(query)
    if (needle.isEmpty()) return items
    return items.filter { item ->
        normalizeCostCenterKey(item.code).contains(needle) ||
            item.description?.let { normalizeCostCenterKey(it).contains(needle) } == true
    }
}

internal fun normalizeCostCenterKey(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }
