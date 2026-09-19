@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The screen the prototype calls "How to add funds" (§3.12) — and at GMU, the honest answer is
 * that it cannot. `/PharosAPI/settings` answers `"Add Funds":"Deny"` with `"Credit Card Gateway":
 * "No"`, the vendor app carries no `com.android.vending.BILLING`, and money only ever arrives as
 * a CBORD/Bursar top-up that lands in the purse as a `TF` — Transfer Funds — transaction. So there
 * is no card form to build and no checkout to fake: this is a signpost with receipts. It shows the
 * balance, says where the money actually comes from, and lists the money-in rows the server
 * reports, so the next top-up visibly lands.
 *
 * The branch is capability-driven, not campus-driven: a server that does answer `Allow` with a
 * gateway gets the hosted-page story (the closer's sentence, and the Print Center handoff), never
 * an invented in-app form.
 */
@Composable
fun AddFundsScreen(
    state: AppState,
    session: Session,
    router: Router,
) {
    val caps = state.capabilities
    val canPayInApp = caps?.canAddFunds == true
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        if (state.transactions.isEmpty() && !state.loadingTransactions) session.loadTransactions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How to add funds") },
                navigationIcon = {
                    IconButton(onClick = { router.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
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
            // --- Balance, server-formatted, never re-formatted here. -------------------------
            BalanceCard(state)

            val overdrawn = state.user?.balance?.amount?.let { it < 0.0 } == true
            if (overdrawn) {
                NoteCard(
                    title = "Below zero",
                    body = "Your print balance is negative. You can add funds through your campus. The server decides whether your selected funding source can cover a release.",
                    icon = Icons.Filled.Error,
                    tone = MasonTone.Error,
                )
            }

            SectionGap()
            if (canPayInApp) {
                PayingServerCopy(onOpenGateway = { router.push(Route.PrintCenter) })
            } else if (state.host.substringBefore(':').let { it == "gmu.edu" || it.endsWith(".gmu.edu") }) {
                DenyingServerCopy(
                    addFundsSetting = when (caps?.addFundsEnabled) {
                        true -> "Allow"
                        false -> "Deny"
                        null -> "has not answered yet"
                    },
                    onOpenCampusPage = { uriHandler.openUri(MASON_MONEY_URL) },
                    onOpenPrintCenter = { router.push(Route.PrintCenter) },
                )
            } else {
                NoteCard(title = "Add funds through your campus",
                    body = "This server has no payment option available here. Open its Print Center or contact your campus print service for top-up instructions.",
                    action = { TextButton(onClick = { router.push(Route.PrintCenter) }) { Text("Open Print Center") } })
            }

            SectionGap()
            NoteCard(
                title = "Payments through your campus",
                body = "Enter payment details only on the campus payment page. Return here and refresh to check your balance.",
                icon = Icons.Filled.Lock,
                tone = MasonTone.Neutral,
            )

            SectionGap()
            MoneyInSection(
                state = state,
                onRefresh = {
                    session.loadTransactions()
                    session.refresh()
                },
            )

            Spacer(Modifier.height(MasonScrollSpacer))
        }
    }
}

/** The hero: the number the server last gave us, in the server's own money format. */
@Composable
private fun BalanceCard(state: AppState) {
    val caps = state.capabilities
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowAvatar(Icons.Filled.Savings, size = 56, tone = MasonTone.Neutral)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Print balance", style = MaterialTheme.typography.labelMedium)
            Text(
                state.balanceText ?: "—",
                style = LocalMasonType.current.money,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "You are charged when you release this at a printer, not now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val purses = state.user?.balance?.purses.orEmpty().filter { (it.amount ?: 0.0) != 0.0 }
    if (purses.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            purses.take(3).forEach { purse ->
                FactChip(
                    icon = Icons.Filled.AccountBalanceWallet,
                    label = "${purse.name} ${caps?.formats?.money(purse.amount) ?: purse.amount?.toString().orEmpty()}",
                    tone = MasonTone.Money,
                )
            }
        }
    }
}

/** The §3.12 copy for a server that refuses payments — GMU's answer, in its own words. */
@Composable
private fun DenyingServerCopy(
    addFundsSetting: String,
    onOpenCampusPage: () -> Unit,
    onOpenPrintCenter: () -> Unit,
) {
    Text("Mason Print cannot take a payment", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Add funds through the campus payment page, then return here to refresh your balance.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NoteCard(
        title = "What actually raises your balance",
        body = "Open Mason Money and follow the campus instructions for adding or transferring funds to printing.",
        icon = Icons.Filled.AccountBalanceWallet,
        tone = MasonTone.Money,
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onOpenCampusPage,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = MasonPillShape,
                ) {
                    Icon(Icons.Filled.Language, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Open Mason Money")
                }
                Text(
                    "gmu.edu/mason-money · opens in your browser",
                    style = LocalMasonType.current.monoSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    OutlinedButton(
        onClick = onOpenPrintCenter,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MasonPillShape,
    ) {
        Text("Open the Print Center instead")
    }
    Text(
        "Recent credits appear below after the print server receives them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The branch for a server that does allow funds — and it is still never an in-app form (§5.6). */
@Composable
private fun PayingServerCopy(onOpenGateway: () -> Unit) {
    Text("Add funds through your campus", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Open your campus Print Center and choose its add-funds option. Return here afterwards to check your balance.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onOpenGateway,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MasonPillShape,
    ) {
        Text("Open Print Center to add funds")
    }
    Text(
        "Payment options and processing times are set by your campus.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Money-in receipts, straight from the transactions endpoint — the top-up made visible. */
@Composable
private fun MoneyInSection(state: AppState, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Money in", Modifier.weight(1f))
        TextButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Refresh")
        }
    }
    val credits = remember(state.transactions) { state.transactions.filter { it.isCredit } }
    when {
        state.transactionsFailure != null -> NoteCard(
            title = "Could not load recent credits", body = state.transactionsFailure.headline(), tone = MasonTone.Error,
            action = { TextButton(onClick = onRefresh) { Text("Try again") } },
        )
        state.loadingTransactions && credits.isEmpty() -> Text(
            "Reading your transactions…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        credits.isEmpty() && !state.loadingTransactions -> EmptyState(
            title = "No recent credits",
            body = "No credits appear in the latest transactions loaded here. Refresh after adding funds to check for a new entry.",
            icon = Icons.Filled.Savings,
        )

        else -> {
            credits.take(5).forEach { txn -> TransactionRow(state, txn) }
            if (credits.size > 5) {
                Text(
                    "and ${credits.size - 5} more on the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(state: AppState, txn: Transaction) {
    val money = state.capabilities?.formats?.money(txn.amount) ?: txn.amount?.toString().orEmpty()
    val title = when {
        txn.transactionType.equals("TF", ignoreCase = true) -> "Transfer Funds"
        !txn.description.isNullOrBlank() -> txn.description
        !txn.transactionType.isNullOrBlank() -> txn.transactionType
        else -> "Money in"
    }
    val detail = listOfNotNull(
        txn.purse,
        txn.at?.let {
            TXN_TIME.format(it.atZone(ZoneId.systemDefault()))
        },
    ).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowAvatar(Icons.Filled.AccountBalanceWallet, tone = MasonTone.Money, size = 40)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) {
                MonoDetail(detail)
            }
        }
        MoneyText(money)
    }
}

/** The campus page that actually takes money for a GMU print account. */
private const val MASON_MONEY_URL = "https://www.gmu.edu/mason-money"

private val TXN_TIME: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
