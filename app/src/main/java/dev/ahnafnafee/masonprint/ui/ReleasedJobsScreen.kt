@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.printDocumentName
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.CancelVerdict
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.canAttemptCancellation
import dev.ahnafnafee.masonprint.core.unmatchedPrintTransactions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ReleasedJobsScreen(state: AppState, session: Session, router: Router) {
    LaunchedEffect(state.signedIn, state.host, state.user?.accountKey) { session.refreshReleaseHistory() }
    ReleasedJobsContent(state, onBack = { router.pop() }, onRefresh = session::refreshReleaseHistory,
        onCancel = session::attemptReleaseCancellation, onClear = session::clearReleaseHistory,
        onStatement = { router.push(Route.Statement) })
}

@Composable
internal fun ReleasedJobsContent(
    state: AppState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: (String) -> Unit,
    onClear: () -> Unit,
    onStatement: () -> Unit,
) {
    var clearing by rememberSaveable { mutableStateOf(false) }
    var cancelId by rememberSaveable { mutableStateOf<String?>(null) }
    val printTransactions = remember(state.releaseHistory, state.releaseTransactions) {
        unmatchedPrintTransactions(state.releaseHistory, state.releaseTransactions.orEmpty())
    }
    val cancellationAvailable = state.signedIn && state.busy == null && state.upload == null && state.cancellingReleaseId == null
    val canClearReceipts = !state.releasing && state.cancellingReleaseId == null
    Scaffold(topBar = {
        TopAppBar(title = { Text("Released jobs") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = onRefresh, enabled = state.signedIn && !state.checkingReleaseHistory) {
                Icon(Icons.Filled.Refresh, "Refresh released jobs")
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("See release receipts from this phone and recent print charges from your Pharos statement. Check the printer for output.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onStatement) { Text("View statement") }
            }
            if (state.checkingReleaseHistory) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking recent print activity…", style = MaterialTheme.typography.bodySmall)
            }
            state.releaseHistoryFailure?.let { failure -> item {
                FailureSummary(failure)
                TextButton(onClick = onRefresh, enabled = !state.checkingReleaseHistory) { Text("Retry") }
            } }
            if (state.releaseHistory.isEmpty() && printTransactions.isEmpty() && state.releaseTransactions != null &&
                !state.checkingReleaseHistory && state.releaseHistoryFailure == null) item {
                Text("No recent print activity", style = MaterialTheme.typography.titleMedium)
            }
            if (state.releaseHistory.isNotEmpty()) item { SectionLabel("Release receipts") }
            items(state.releaseHistory, key = { it.id }) { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.name, style = MaterialTheme.typography.titleMedium)
                        Text(record.printerName, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activityTime(Instant.ofEpochMilli(record.requestedAt)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(record.status, style = MaterialTheme.typography.labelLarge)
                        record.charge?.let { charge ->
                            val (bg, fg) = MasonToneSurfaces(if (charge.chargedTo?.contains("Cost Center", true) == true) MasonTone.Grant else MasonTone.Neutral)
                            Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PrintChargeDetails(state, charge.amount, charge.pages, charge.chargedTo)
                                    Text("Matched by document and release time", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (record.charge == null && record.billingCheckedAt != null) {
                            Text("No unique matching charge found yet.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        record.cancellation?.let { result ->
                            val tone = when (result.verdict) {
                                CancelVerdict.Accepted -> MasonTone.Neutral
                                CancelVerdict.Refused -> MasonTone.Warn
                                CancelVerdict.Unconfirmed -> MasonTone.Warn
                            }
                            val (bg, fg) = MasonToneSurfaces(tone)
                            Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(when {
                                        state.cancellingReleaseId == record.id -> "Requesting cancellation…"
                                        result.verdict == CancelVerdict.Accepted -> "Cancellation accepted"
                                        result.verdict == CancelVerdict.Refused -> "Cancellation refused"
                                        else -> "Cancellation unconfirmed"
                                    }, style = MaterialTheme.typography.labelLarge)
                                    if (state.cancellingReleaseId == record.id) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else {
                                        Text(result.message, style = MaterialTheme.typography.bodyMedium)
                                        if (result.verdict == CancelVerdict.Refused) {
                                            Text("If the job is still printing, use the printer’s cancel control if available.",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        if (canAttemptCancellation(state.releaseHistory, record)) OutlinedButton(
                            onClick = { cancelId = record.id }, enabled = cancellationAvailable,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (record.cancellation == null) "Try to cancel" else "Try to cancel again") }
                    }
                }
            }
            if (state.releaseHistory.isNotEmpty()) item {
                TextButton(onClick = { clearing = true }, enabled = canClearReceipts) { Text("Clear receipts") }
            }
            if (printTransactions.isNotEmpty()) {
                item { SectionLabel("Print charges from your statement") }
                items(printTransactions) { transaction ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(transaction.printDocumentName ?: "Print charge", style = MaterialTheme.typography.titleMedium)
                            (transaction.printer ?: transaction.device)?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            transaction.at?.let {
                                Text(activityTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PrintChargeDetails(state, transaction.amount, transaction.pages, transaction.chargedTo)
                        }
                    }
                }
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear release receipts?") },
        text = { Text("This removes local receipts and cancellation results. It does not cancel jobs or change your statement.") },
        confirmButton = { TextButton(onClick = { onClear(); clearing = false }, enabled = canClearReceipts) { Text("Clear receipts") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Keep receipts") } })
    state.releaseHistory.firstOrNull { it.id == cancelId }?.let { record ->
        AlertDialog(onDismissRequest = { cancelId = null }, title = { Text("Try to cancel this job?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(record.name, style = MaterialTheme.typography.titleMedium)
                    Text(record.printerName)
                    Text("GMU usually starts printing immediately, so cancellation may already be too late. The server decides and its response will appear on this receipt.")
                    Text("Cancellation does not guarantee a refund. Check your statement for billing changes.")
                }
            },
            confirmButton = { TextButton(onClick = { cancelId = null; onCancel(record.id) },
                enabled = cancellationAvailable && canAttemptCancellation(state.releaseHistory, record)) { Text("Try to cancel") } },
            dismissButton = { TextButton(onClick = { cancelId = null }) { Text("Keep job") } })
    }
}

@Composable
private fun PrintChargeDetails(state: AppState, amount: Double?, pages: Long?, chargedTo: String?) {
    val money = state.capabilities?.formats?.money(amount) ?: amount?.toString()
    val details = listOfNotNull(money, pages?.let { "$it page${if (it == 1L) "" else "s"}" })
    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
    chargedTo?.let { Text("Charged to $it", style = MaterialTheme.typography.bodySmall) }
}

private fun activityTime(time: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(time)
