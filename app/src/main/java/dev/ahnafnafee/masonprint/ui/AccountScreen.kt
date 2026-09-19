@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.data.model.Capabilities
import dev.ahnafnafee.masonprint.data.model.Gateway
import dev.ahnafnafee.masonprint.data.model.Purse
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import dev.ahnafnafee.masonprint.ui.theme.ThemeChoice
import dev.ahnafnafee.masonprint.ui.theme.ThemeMode
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Account (§3.10). This is the screen a student comes to when the printer swallowed their money,
 * so the two things it has to get right are the number and the explanation under it.
 *
 * The number comes from the server already formatted (`AppState.balanceText` is rendered through
 * `Localisation`'s .NET format strings), and it is never re-formatted here: GMU's format string is
 * one-section, so a client-side `String.format` would print `$-1.25` where the server prints
 * `$1.25-`, and the whole point of this screen is that the amount matches the kiosk receipt.
 *
 * The explanation is the part the vendor app omits. `Add Funds: Deny` and
 * `Credit Card Gateway: No` are not inconveniences to hide — they say that this app *cannot* take
 * a payment, that the money arrives as a campus transfer, and that a printed row costing $1.25 was
 * charged at release. Every claim on this screen is a server answer, quoted.
 */

/**
 * @param graph carried for signature parity with the other routes in [dev.ahnafnafee.masonprint.ui.AppRoot];
 *   everything this screen reads is already on [AppState], which is deliberate — a screen that
 *   reaches into the graph starts its own requests and the state stops being the truth.
 */
@Composable
fun AccountScreen(
    state: AppState,
    session: Session,
    graph: AppGraph,
    router: Router,
) {
    val reduced = rememberReducedMotion()
    var logOffOpen by rememberSaveable { mutableStateOf(false) }
    // History is its own tab: it is the one unbounded list on this screen, and stacking it under the
    // balance pushed the settings and the log-off button past the end of a long statement.
    var historyTab by rememberSaveable { mutableStateOf(false) }
    var txnQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // The statement is the only thing on this screen the session is not already holding, and it
        // is deliberately not part of a queue refresh — the queue does not need 50 rows of history.
        if (state.transactions.isEmpty() && !state.loadingTransactions) session.loadTransactions()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { router.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Account", style = MaterialTheme.typography.titleMedium)
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
                    IconButton(onClick = { session.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh balance and queue")
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = state.busy != null,
            onRefresh = { session.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = MasonScreenPadding,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "identity") { IdentityHeader(state) }

                item(key = "tabs") {
                    TabRow(selectedTabIndex = if (historyTab) 1 else 0) {
                        Tab(
                            selected = !historyTab,
                            onClick = { historyTab = false },
                            text = { Text("Account") },
                        )
                        Tab(
                            selected = historyTab,
                            onClick = { historyTab = true },
                            text = { Text("History") },
                        )
                    }
                }

                if (historyTab) {
                    transactionHistory(
                        state = state,
                        query = txnQuery,
                        onQuery = { txnQuery = it },
                        onRetry = { session.loadTransactions() },
                    )
                    item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
                    return@LazyColumn
                }

                item(key = "balance") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Balance")
                        BalanceCard(state = state, reduced = reduced)
                    }
                }

                item(key = "money-rows") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FundingRow(state) { router.push(Route.CostCenters) }
                        AddFundsRow(state) { router.push(Route.AddFunds) }
                    }
                }

                item(key = "appearance") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Appearance")
                        // Three chips rather than a row that opens a dialog: there are exactly three
                        // choices, they are one word each, and the change is visible the instant it
                        // is made, so making someone open a screen to see it would be silly.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = ThemeChoice.mode == mode,
                                    onClick = { ThemeChoice.set(graph.prefs, mode) },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                        Text(
                            "System follows your phone's own light and dark setting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "settings") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Settings")
                        AccountRow(
                            icon = Icons.Filled.BusinessCenter,
                            label = "Cost centers",
                            sub = costCenterSub(state.capabilities),
                            onClick = { router.push(Route.CostCenters) },
                        )
                        AccountRow(
                            icon = Icons.Filled.BugReport,
                            label = "Diagnostics",
                            sub = "Server, capabilities, fingerprints, session log",
                            onClick = { router.push(Route.Diagnostics) },
                        )
                        AccountRow(
                            icon = Icons.Filled.Language,
                            label = "Print Center (web)",
                            sub = "The server's own page, inside the app",
                            onClick = { router.push(Route.PrintCenter) },
                        )
                    }
                }

                state.capabilities?.let { caps -> item(key = "hidden") { HiddenByServerCard(caps) } }

                item(key = "log-off") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogOffButton { logOffOpen = true }
                        Text(
                            logOffNote(state.host),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
            }
        }
    }

    if (logOffOpen) {
        LogOffDialog(
            host = state.host,
            onDismiss = { logOffOpen = false },
            onConfirm = {
                logOffOpen = false
                // Server-side revocation, not a local wipe: `Session.signOut()` calls
                // `/session/logout`, drops the cookies and the credential, and leaves the approved
                // certificates alone. The phase flip resets the router to sign-in from AppRoot.
                session.signOut()
            },
        )
    }
}

// --------------------------------------------------------------------------- identity

/**
 * The header (§3.10 `P:866–876`): 52 dp monogram, the name the server called them, and the mono
 * `alias · host` line. Nothing here is decoration — the alias is what a service desk asks for, and
 * the card number is what they ask for next.
 */
@Composable
private fun IdentityHeader(state: AppState) {
    val user = state.user
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                accountInitials(user?.preferredName ?: "?"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                user?.preferredName ?: "Signed in",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The host is already in the top bar, so what belongs here is who this is. GMU publishes
            // no name at all (`FirstNames`/`LastName` come back empty and `DisplayName` is the
            // numeric id), so the headline is the id they signed in with and these are the two
            // identifiers a student — or the service desk — would actually quote.
            MonoDetail(
                listOfNotNull(
                    user?.email?.takeIf { it.isNotBlank() },
                    user?.campusId,
                ).joinToString("  ·  ").ifBlank { state.host },
                selectable = false,
            )
        }
    }

    // The email moved into the line above, where it identifies the account instead of trailing off
    // the side of a scrolling strip.
    val chips = buildList {
        user?.cardId?.takeIf { it.isNotBlank() }?.let { add("Card $it") }
        user?.roles?.filter { it.isNotBlank() }?.forEach { add(it) }
    }
    if (chips.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        // Wrapping, not scrolling: a chip parked off the right edge is a chip nobody reads.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { label -> FactChip(icon = null, label = label) }
        }
    }
}

// --------------------------------------------------------------------------- balance

/**
 * The balance card (§3.10 `P:882–895`): the number, the purses under it, and the sentence that
 * tells the reader which of the two they are looking at.
 *
 * Stale is a different reading of the same number, not a different screen (§5.6): the card goes
 * outlined and grey, says *Last known balance*, and says how old it is. No dialog, because a dialog
 * you must dismiss is how the vendor app turns a cached balance into an error.
 */
@Composable
private fun BalanceCard(state: AppState, reduced: Boolean) {
    val formats = state.capabilities?.formats
    // `Capabilities.purses` is what the server actually published. `AppState.purseBreakdown` hides
    // a single purse, which is right for the queue hero and wrong here: on this screen the honest
    // answer to "which purse is it in" is often "there is only one, and it is called this".
    // Sorted on `Priority`, so the number on the badge is the spending order and not array order.
    val purses = purseSpendOrder(state.capabilities?.purses.orEmpty())

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (state.stale) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (state.stale) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.stale) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.CloudOff,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "LAST KNOWN BALANCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                state.balanceText ?: "—",
                // 45 / 52 at weight 520 with tabular figures — the same treatment as the queue hero
                // (§2.3), so the number reads as one object in two places.
                style = MaterialTheme.typography.displayMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (state.stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )

            AnimatedVisibility(
                visible = state.stale,
                enter = fadeIn(if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(if (reduced) snap() else MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                val age = humanDuration(System.currentTimeMillis() - state.loadedAt)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FactChip(icon = Icons.Filled.Schedule, label = "$age old", tone = MasonTone.Neutral)
                    Text(
                        "Cached $age ago. This is not the live balance. A job released since then " +
                            "would have changed it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (purses.isNotEmpty()) {
                MasonHairline()
                purses.forEachIndexed { index, purse ->
                    PurseRow(
                        position = index + 1,
                        purse = purse,
                        amount = formats?.money(purse.amount) ?: purse.amount.toString(),
                    )
                }
            }

            Text(
                if (state.stale) purseOrderSentence(purses)
                else chargeTimingSentence + " " + purseOrderSentence(purses),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A purse line: numbered badge in the server's own order, name, and the server's money format. */
@Composable
private fun PurseRow(position: Int, purse: Purse, amount: String) {
    val first = position == 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    if (first) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                position.toString(),
                style = LocalMasonType.current.monoSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (first) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            purse.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            accountMinusSign(amount),
            style = LocalMasonType.current.monoLabel,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// --------------------------------------------------------------------------- money rows

/**
 * Where the money is charged to. The sentence after the label is the one that has to survive
 * contact with the wire: GMU echoes an empty `CostCenterCode` back after a successful `PATCH`, and
 * prices the job at the student's own purse rate anyway, so nothing here may say *charged to X*.
 */
@Composable
private fun FundingRow(state: AppState, onClick: () -> Unit) {
    val chosen = state.costCenter?.takeIf { it.isNotBlank() }
    AccountRow(
        icon = Icons.Filled.BusinessCenter,
        label = "Charge to",
        // Says who pays, which is what the row is for. It used to repeat the balance card's "you are
        // charged at the printer" sentence, which answered a question nobody asked here twice.
        sub = if (chosen == null) {
            "Your own printing balance"
        } else {
            "Set to $chosen. Confirmed only when the server echoes it back at release."
        },
        onClick = onClick,
    )
}

/**
 * How to get money in. On a server that answers `Add Funds: Deny`, the row is not a payment button
 * wearing a different label: it opens the screen that explains where the campus takes money.
 */
@Composable
private fun AddFundsRow(state: AppState, onClick: () -> Unit) {
    val caps = state.capabilities
    val denied = caps != null && !caps.canAddFunds
    AccountRow(
        icon = Icons.Filled.Savings,
        label = "How to add funds",
        sub = when {
            caps == null -> "Reading what this server allows"
            denied -> "This server takes no payments. Read why"
            else -> "Takes payment through ${gatewayLabel(caps.gateway)} · ${caps.bankName ?: "the server's own gateway"}"
        },
        onClick = onClick,
    )
}

/** Spelling out the enum so the row does not read `Takes payment through CYBERSOURCE`. */
private fun gatewayLabel(gateway: Gateway): String = when (gateway) {
    Gateway.NONE -> "the gateway"
    Gateway.PAYPAL_WEB_ACCEPT -> "PayPal"
    Gateway.CYBERSOURCE -> "Cybersource"
    Gateway.CHASE -> "Chase Paymentech"
    Gateway.CAMPUS_BILLING -> "the campus bill"
}

// --------------------------------------------------------------------------- transactions

/**
 * The statement, as its own tab: a search box and one *recycling* row per transaction.
 *
 * Emitted into the screen's `LazyColumn` rather than composed as one block, because this is the only
 * unbounded list on the screen — an account with a term's worth of printing is hundreds of rows.
 *
 * The search is client-side over the statement already loaded. `GET {UserUri}/transactions` takes no
 * query parameter, so filtering here is honest about what it can see; it never implies the server was
 * asked. It matches on what a student would actually search by — a document name, a printer, a date,
 * an amount.
 */
private fun LazyListScope.transactionHistory(
    state: AppState,
    query: String,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "txn-search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search the statement") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
    }

    val caps = state.capabilities
    if (caps != null && !caps.canAddFunds && state.transactions.isNotEmpty()) {
        // The row list alone invites the wrong reading — a `+` row looks like someone paid in.
        item(key = "txn-note") {
            Text(
                "A row that raises the balance is the campus moving money in. This app cannot take " +
                    "a payment itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    item(key = "txn-coverage") {
        Text("Latest ${state.transactions.size} transactions loaded", style = MaterialTheme.typography.bodySmall)
    }
    state.transactionsFailure?.let { failure ->
        item(key = "txn-failure") {
            NoteCard(title = "Could not load recent transactions", body = failure.headline(), tone = MasonTone.Error,
                action = { TextButton(onClick = onRetry) { Text("Try again") } })
        }
    }
    val q = query.trim()
    val rows = if (q.isEmpty()) state.transactions else state.transactions.filter { matchesTransaction(it, q) }

    when {
        state.transactions.isEmpty() && state.loadingTransactions -> item(key = "txn-loading") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Text(
                    "Reading the statement from ${state.host}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.transactions.isEmpty() && state.transactionsFailure == null -> item(key = "txn-empty") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EmptyState(
                    title = "No transactions yet",
                    body = if (state.stale) {
                        "The statement is not cached, so there is nothing to show without a connection. " +
                            "Pull to refresh when you are back on campus."
                    } else {
                        "This account has no posted transactions. Money is charged at the printer, not " +
                            "when a job is sent, so a queue is often empty of history until something prints."
                    },
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                )
                TextButton(onClick = onRetry) {
                    ButtonGlyph(Icons.Filled.Refresh)
                    Text("Try again")
                }
            }
        }

        rows.isEmpty() -> item(key = "txn-nomatch") {
            EmptyState(
                title = "Nothing matches \"$q\"",
                body = "This searches the statement already loaded. A document name, a printer, a " +
                    "date, or an amount.",
                icon = Icons.Filled.Search,
            )
        }

        else -> items(rows) { txn ->
            Column {
                MasonHairline()
                TransactionRow(state, txn)
            }
        }
    }
}

/** What a statement search looks at: what it was, where it printed, when, and how much. */
private fun matchesTransaction(txn: Transaction, q: String): Boolean =
    listOfNotNull(
        txn.description,
        txn.jobName,
        txn.printer,
        txn.purse,
        txn.reason,
        txn.transactionType,
        txn.subType,
        txn.time,
        txn.costCenters,
        txn.amount?.toString(),
    ).any { it.contains(q, ignoreCase = true) }

/**
 * One statement row (§3.10 `P:906–919`). The code badge shows the server's own transaction type —
 * `TF`, `CR`, `PF` — because that code is what the service desk reads back, and the words next to it
 * are the server's label, not a guess invented from the letter pair.
 */
@Composable
private fun TransactionRow(state: AppState, txn: Transaction) {
    val money = state.capabilities?.formats?.money(txn.amount)
    val inflow = txn.isCredit
    val zero = !inflow && txn.amount != null && txn.amount == 0.0
    val tone = when {
        inflow -> MasonTone.Ok
        zero -> MasonTone.Grant
        else -> MasonTone.Neutral
    }
    val (badgeBg, badgeFg) = MasonToneSurfaces(tone)
    val mason = currentMasonColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(badgeBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                transactionCode(txn),
                style = LocalMasonType.current.monoSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = badgeFg,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                transactionTitle(txn, state.capabilities?.transactionLabels.orEmpty()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            txn.detail.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatAccountTime(txn.at),
                style = LocalMasonType.current.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            money?.let { accountMinusSign(it) } ?: "—",
            style = LocalMasonType.current.monoLabel,
            color = when {
                inflow -> mason.ok
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------------------- settings rows

/**
 * The prototype's `listRow` (§3.10 `P:1615`): a 16 dp outlined row, 22 dp glyph, one line of
 * supporting copy, disclosure. Rows exist only where there is somewhere to go — the prototype's
 * "Default finishing options" and "Saved campuses" rows have no screen in this clone, so they are
 * absent rather than a button that says nothing happened.
 */
@Composable
internal fun AccountRow(
    icon: ImageVector,
    label: String,
    sub: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One honest line for the Cost centers row instead of the prototype's fictional count. */
// No "search the server for more". GMU answers every cost-centre search with the codes already on
// the account, so promising a directory here would be the same lie the picker used to tell.
private fun costCenterSub(caps: Capabilities?): String = when {
    caps == null -> "Reading what this server publishes"
    caps.usableCostCenters.isNotEmpty() ->
        "${caps.usableCostCenters.size} on this account, or type a code"
    caps.costCentersAllowed -> "Type a code to charge printing to"
    else -> "This server refuses cost centers"
}

// --------------------------------------------------------------------------- hidden by this server

/**
 * The refusal ledger (§3.10 `P:935–947`). This is the screen's argument for existing: every row is
 * a capability the server answered no to, the answer in the server's own spelling, and what the app
 * did about it. It is shown even when empty, because "nothing was removed" is also a fact the user
 * is entitled to.
 */
@Composable
private fun HiddenByServerCard(caps: Capabilities) {
    val rows = hiddenFromServer(caps)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Block, null, modifier = Modifier.size(20.dp))
                Text(
                    "HIDDEN BY THIS SERVER",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            Text(
                "Mason Print removes what the server refuses rather than showing a button that " +
                    "fails. This is what it removed, and what the server answered.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    "Nothing was removed. This server answered yes to everything the app asks about.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(row.answer, style = LocalMasonType.current.monoBlock)
                    Text(row.effect, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- log off

@Composable
private fun LogOffButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.error,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, modifier = Modifier.size(22.dp))
            Text("Log off", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LogOffDialog(host: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
        title = { Text("Log off?") },
        text = { Text(logOffNote(host) + " Your saved password and browser session are also removed.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Log off")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay signed in") } },
    )
}

/** §2.0.7 `logged_out`, with the host filled in from the live target rather than hard-coded. */
internal fun logOffNote(host: String): String =
    "Logging off asks $host to end the session and clears the cached queue and balance from this " +
        "phone. Saved campuses and trusted certificates stay."

/**
 * The sentence the Board wrote and every screen repeats: charging is a release-time event, and a
 * student who believes they have already paid will not send a second job.
 */
internal const val chargeTimingSentence: String =
    "You are charged when you release this at a printer, not now."

// --------------------------------------------------------------------------- pure logic

/** One entry in the refusal ledger: the thing, the server's answer, what the app did about it. */
internal data class HiddenCapability(val name: String, val answer: String, val effect: String)

/**
 * Which affordances this server took away. Every predicate reads a capability the settings document
 * actually published, so a server that says yes gets an empty ledger and the card says so.
 */
internal fun hiddenFromServer(caps: Capabilities): List<HiddenCapability> {
    val rows = mutableListOf<HiddenCapability>()
    if (!caps.canAddFunds) {
        rows += HiddenCapability(
            name = "Add funds",
            answer = if (caps.addFundsEnabled) "Add Funds: Allow, gateway: No" else "Add Funds: Deny",
            effect = "No top-up screen and no amount field. Account links to Mason Money instead.",
        )
    }
    if (caps.gateway == Gateway.NONE) {
        rows += HiddenCapability(
            name = "Credit card gateway",
            answer = "CreditCardGateway: No",
            effect = "No card fields exist anywhere in the app.",
        )
    }
    caps.uploadBlockReason?.let { reason ->
        rows += HiddenCapability(
            name = "Web upload",
            answer = if (caps.webUploadAllowed) "AllowUpload: false" else "WebUpload: false",
            effect = reason,
        )
    }
    if (!caps.qrReleaseEnabled) {
        rows += HiddenCapability(
            name = "Camera / QR release",
            answer = "CameraRelease: false",
            effect = "No scanner tab and no camera permission request. Pick the printer from the list.",
        )
    }
    if (!caps.costCentersAllowed) {
        rows += HiddenCapability(
            name = "Cost centers",
            answer = "CostCenters: false",
            effect = "No “Charge to” control. Every job comes out of your own balance.",
        )
    }
    return rows
}

/**
 * Purses in the order the server says they are emptied. `Priority` is the server's own field; a
 * missing one goes last rather than first, because a purse nobody told us the order of is not a
 * purse to spend before the ones we were told about.
 */
internal fun purseSpendOrder(purses: List<Purse>): List<Purse> =
    purses.sortedBy { it.priority ?: Long.MAX_VALUE }

/**
 * Purse spending order, in one sentence, using only the names the server published. The server
 * draws purses in the order its own `Priority` says; this app can report it and nothing else.
 */
internal fun purseOrderSentence(purses: List<Purse>): String {
    val ordered = purseSpendOrder(purses)
    return when {
        ordered.size >= 2 ->
            "The server empties ${ordered[0].name} before it touches ${ordered[1].name}, and it " +
                "reported that order itself. Mason Print cannot change it."
        ordered.size == 1 ->
            "This server publishes one purse, ${ordered[0].name}. There is no spending order to apply."
        else ->
            "This server published a total and no purses under it, so the balance above is the whole " +
                "of it."
    }
}

/** The 36 dp badge: the server's own short code when it has one, initials when it does not. */
internal fun transactionCode(txn: Transaction): String {
    val raw = txn.transactionType?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4 }
    if (raw != null) return raw.uppercase()
    val initials = listOfNotNull(txn.subType?.takeIf { it.isNotBlank() }, txn.transactionType?.takeIf { it.isNotBlank() })
        .mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() } }
        .joinToString("")
    return initials.uppercase().ifBlank { "?" }
}

/**
 * A row's title. `description` wins when the server wrote a sentence for the row — GMU often does
 * not: for a print charge it posts its page-counter transcript (`"I-94.pdf\n1 page of
 * 612x792,Color,Letter,Simplex"`, see [Transaction.descriptionHead]), whose first line is only a
 * file name. That is not a title, so a multi-line description gets the type vocabulary *plus* the
 * document the student recognises, and the transcript itself goes to the detail line. The rest is a
 * last resort, keyed on the type codes Uniprint actually posts, so an unknown code says
 * "Transaction" rather than a confident lie. [labels] is the server's own list indexed by
 * `TransactionType`, which is what makes a non-GMU server readable without hard-coding its words.
 */
internal fun transactionTitle(txn: Transaction, labels: List<String>): String {
    val server = txn.description?.trim()?.takeIf { it.isNotBlank() }
    if (server != null && txn.descriptionTail == null) return server
    val type = txn.transactionType?.trim()?.uppercase().orEmpty()
    val code = when {
        type in setOf("TF", "TRANSFERFUNDS", "TRANSFER") -> "Campus transfer in"
        type in setOf("PA", "PAYMENT", "ADD") -> "Funds added at a payment gateway"
        type in setOf("PF", "FEE") -> "Payment gateway fee"
        type in setOf("CR", "CHARGE", "PRINT") ->
            if (!txn.isCredit && txn.amount != null && txn.amount == 0.0) "Print charge paid by a grant" else "Print charge"
        else -> {
            val index = type.toIntOrNull()
            if (index != null && index >= 0 && index < labels.size) labels[index] else "Transaction"
        }
    }
    return listOfNotNull(
        code.takeIf { it.isNotBlank() },
        txn.descriptionHead?.takeIf { it.isNotBlank() && it != code },
        txn.subType?.trim()?.takeIf {
            it.isNotBlank() && it != txn.description && it != txn.descriptionHead && it != code
        },
    ).joinToString(" · ")
}

/** Monogram from whatever name the server sent: "A. Rahman" and "arahman3" both give "AR". */
internal fun accountInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
    if (words.isEmpty()) return "?"
    val initials = words.mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("")
    val full = if (initials.length >= 2) {
        initials
    } else {
        // One word: second letter of that word, so "arahman3" is "AR" and not "A?".
        val second = words[0].drop(1).firstOrNull { it.isLetterOrDigit() }
        initials + (second?.toString() ?: "")
    }
    return full.uppercase().ifBlank { "?" }
}

/** U+2212 for negatives: a hyphen inside tabular money looks like a typo next to the digits. */
internal fun accountMinusSign(rendered: String): String = rendered.replace('-', '\u2212')

private val accountDateTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** Posted time, or the honest word for not having one. */
private fun formatAccountTime(instant: Instant?): String =
    instant?.let { accountDateTimeFmt.format(it) } ?: "unknown time"
