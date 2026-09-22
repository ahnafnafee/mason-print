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
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.CancelVerdict
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.canAttemptCancellation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ReleasedJobsScreen(state: AppState, session: Session, router: Router) {
    LaunchedEffect(state.host, state.user?.accountKey) { session.refreshReleaseHistory() }
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
    val cancellationAvailable = state.signedIn && state.busy == null && state.upload == null && state.cancellingReleaseId == null
    Scaffold(topBar = {
        TopAppBar(title = { Text("Released jobs") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            IconButton(onClick = onRefresh, enabled = state.signedIn && !state.checkingReleaseHistory && state.releaseHistory.isNotEmpty()) {
                Icon(Icons.Filled.Refresh, "Check recent charges")
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Charges confirm billing, not printed pages. GMU does not report a live queue after release.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onStatement) { Text("View statement") }
            }
            if (state.checkingReleaseHistory) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking recent charges…", style = MaterialTheme.typography.bodySmall)
            }
            state.releaseHistoryFailure?.let { failure -> item {
                FailureSummary(failure)
                TextButton(onClick = onRefresh, enabled = !state.checkingReleaseHistory) { Text("Retry charge check") }
            } }
            if (state.releaseHistory.isEmpty()) item {
                Text("No release receipts yet", style = MaterialTheme.typography.titleMedium)
                Text("Releases made in this app appear here. Your statement also includes charges from other print methods.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.releaseHistory, key = { it.id }) { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.name, style = MaterialTheme.typography.titleMedium)
                        Text(record.printerName, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activityTime(record.requestedAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(record.status, style = MaterialTheme.typography.labelLarge)
                        record.charge?.let { charge ->
                            val (bg, fg) = MasonToneSurfaces(if (charge.chargedTo?.contains("Cost Center", true) == true) MasonTone.Grant else MasonTone.Neutral)
                            Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val amount = state.capabilities?.formats?.money(charge.amount) ?: charge.amount?.toString()
                                    Text(listOfNotNull(amount, charge.pages?.let { "$it page${if (it == 1L) "" else "s"}" }).joinToString(" · "),
                                        style = MaterialTheme.typography.titleSmall)
                                    charge.chargedTo?.let { Text("Charged to $it", style = MaterialTheme.typography.bodySmall) }
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
                                    else Text(result.message, style = MaterialTheme.typography.bodyMedium)
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
                Text("Up to 100 receipts, cleared at sign-out. Charge checks cover up to 300 recent statement entries.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { clearing = true }, enabled = state.cancellingReleaseId == null) { Text("Clear receipts") }
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear release receipts?") },
        text = { Text("This removes local receipts and cancellation results. It does not cancel jobs or change your statement.") },
        confirmButton = { TextButton(onClick = { onClear(); clearing = false }, enabled = state.cancellingReleaseId == null) { Text("Clear receipts") } },
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

private fun activityTime(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
