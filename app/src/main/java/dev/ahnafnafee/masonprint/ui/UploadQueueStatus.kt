package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.uploadProgressDetail
import dev.ahnafnafee.masonprint.data.model.humanBytes
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/** Upload is an operation on the queue, not a separate destination. */
@Composable
internal fun UploadQueueStatus(state: AppState, preparing: Boolean, onDismiss: () -> Unit) {
    if (preparing || state.upload != null) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (preparing) "Opening documents…" else uploadProgressDetail(state.uploadFiles, state.uploadIndex, state.uploadFraction),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (preparing || state.uploadFraction <= 0f || state.uploadFraction >= 1f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { state.uploadFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        return
    }
    val names = state.uploadNotSent
    if (names.isEmpty()) return
    var expanded by remember(names, state.uploadFailure) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = currentMasonColors.warnContainer,
        contentColor = currentMasonColors.onWarnContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (names.size == 1) "1 document needs attention" else "${names.size} documents need attention",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss upload notice") }
            }
            if (names.size == 1 && !expanded) {
                Text(names.first(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(uploadIssueHint(state.uploadFailure), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            if (expanded) {
                names.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.uploadFailure?.let { failure ->
                    Text(
                        state.uploadFailedFile?.name?.let { "$it: ${failure.headline()}" }
                            ?: "Last upload issue: ${failure.headline()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    failure.detailLine()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** Do not turn a lost response into a promise that re-uploading is safe. */
internal fun uploadIssueHint(failure: PharosFailure?): String = when (failure) {
    is PharosFailure.Timeout, is PharosFailure.Offline ->
        "Check the queue before uploading again; a document may have arrived."
    is PharosFailure.TooLarge -> failure.limitBytes?.takeIf { it > 0 }?.let {
        "The file limit is ${humanBytes(it)}. Check Details for the affected documents."
    } ?: "A document exceeds the file size limit. Check Details before trying again."
    is PharosFailure.UnsupportedType -> "A file type was not accepted. Try saving that document as a PDF."
    is PharosFailure.UploadDenied -> "Your account cannot upload documents to this server."
    is PharosFailure.Unauthenticated -> "Sign in again before uploading."
    else -> "Check the queue and the details below before uploading these files again."
}
