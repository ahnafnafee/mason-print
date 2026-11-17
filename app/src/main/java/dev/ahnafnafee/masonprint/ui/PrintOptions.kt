package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.FinishingEdits
import dev.ahnafnafee.masonprint.data.model.PrintJob

@Composable
internal fun PrintOptionsDialog(
    jobs: List<PrintJob>,
    canEdit: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (FinishingEdits) -> Unit,
) {
    var edits by remember(jobs.map { it.location }) { mutableStateOf(FinishingEdits()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    jobs.singleOrNull()?.let { it.name ?: "Untitled document" } ?: "${jobs.size} documents",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PrintOptionControls(jobs, edits, canEdit, enabled) { edits = it }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(edits) }, enabled = canEdit && enabled && edits.changesAny(jobs)) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun PrintOptionControls(
    jobs: List<PrintJob>,
    edits: FinishingEdits,
    canEdit: Boolean,
    enabled: Boolean,
    onChange: (FinishingEdits) -> Unit,
) {
    val copiesValues = jobs.map { it.finishing?.copies }
    val sidesValues = jobs.map { it.finishing?.duplex }
    val colorValues = jobs.map { it.finishing?.mono }
    val copies = copiesValues.distinct().singleOrNull()
    val duplex = sidesValues.distinct().singleOrNull()
    val mono = colorValues.distinct().singleOrNull()
    val copiesLocked = jobs.none { it.supportedFinishing.copies != false }
    val sidesLocked = jobs.none { it.supportedFinishing.duplex != false }
    val colorLocked = jobs.none { it.supportedFinishing.color != false }
    val displayedCopies = edits.copies ?: copies
    val canChange = canEdit && enabled

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Copies", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OutlinedIconButton(
                    onClick = { onChange(edits.copy(copies = ((displayedCopies ?: 2L) - 1).coerceAtLeast(1L).takeUnless { it == copies })) },
                    enabled = canChange && !copiesLocked && (displayedCopies == null || displayedCopies > 1L),
                ) { Icon(Icons.Filled.Remove, "Fewer copies") }
                Text(
                    displayedCopies?.toString() ?: if (copiesValues.any { it != null }) "Mixed" else "—",
                    Modifier.widthIn(min = 48.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedIconButton(
                    onClick = { onChange(edits.copy(copies = ((displayedCopies ?: 0L) + 1).coerceAtMost(99L).takeUnless { it == copies })) },
                    enabled = canChange && !copiesLocked && (displayedCopies == null || displayedCopies < 99L),
                ) { Icon(Icons.Filled.Add, "More copies") }
            }
            if (canEdit && copiesLocked) PrintOptionNote("The print server sets the copy count for these documents.")
        }
        PrintOptionChoice(
            label = "Sides",
            first = "One-sided",
            second = "Double-sided",
            selected = edits.duplex ?: duplex,
            mixed = sidesValues.distinct().size > 1,
            enabled = canChange && !sidesLocked,
            locked = canEdit && sidesLocked,
            onSelect = { onChange(edits.copy(duplex = it.takeUnless { it == duplex })) },
        )
        PrintOptionChoice(
            label = "Color mode",
            first = "Color",
            second = "Black & white",
            selected = edits.mono ?: mono,
            mixed = colorValues.distinct().size > 1,
            enabled = canChange && !colorLocked,
            locked = canEdit && colorLocked,
            onSelect = { onChange(edits.copy(mono = it.takeUnless { it == mono })) },
        )

        if (!canEdit) PrintOptionNote("This server keeps the print options set at upload.")
        jobs.forEach { job ->
            val restricted = edits.unsupportedFor(job)
            if (restricted.isNotEmpty()) {
                PrintOptionNote("${job.name ?: "Document"} keeps its current ${restricted.joinToString(" and ")}.")
            }
        }
    }
}

@Composable
private fun PrintOptionChoice(
    label: String,
    first: String,
    second: String,
    selected: Boolean?,
    mixed: Boolean,
    enabled: Boolean,
    locked: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (selected == null && mixed) "$label · Mixed" else label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == false, onClick = { onSelect(false) }, label = { Text(first) }, enabled = enabled)
            FilterChip(selected = selected == true, onClick = { onSelect(true) }, label = { Text(second) }, enabled = enabled)
        }
        if (locked) PrintOptionNote("Set by the print server for these documents.")
    }
}

@Composable
private fun PrintOptionNote(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
