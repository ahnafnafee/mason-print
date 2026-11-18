package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.BuildConfig

@Composable
internal fun StopPrintingGuide(
    printerName: String,
    printerMake: String?,
    host: String,
    showQuickStep: Boolean = false,
) {
    var open by rememberSaveable(printerName) { mutableStateOf(false) }
    val atMason = host.substringBefore(':').equals(BuildConfig.GMU_HOST, ignoreCase = true)
    val canon = printerMake.equals("Canon", ignoreCase = true) || (atMason && printerMake.isNullOrBlank())
    val quickStep = if (canon) {
        "If your job is printing, press Stop on the printer, select your document, then tap Cancel."
    } else {
        "On the printer’s panel, open the active print jobs, select your document, then tap Cancel."
    }

    Column {
        if (showQuickStep) Text(quickStep, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open = true }) { Text("How to stop at the printer") }
    }

    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Stop an active print job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(printerName, style = MaterialTheme.typography.titleSmall)
                Text(quickStep)
                if (canon) Text("You can also open Status Monitor → Copy/Print → Job Status, select your document, then tap Cancel.")
                Text("Cancel the active job to stop the remaining copies. The printer may stop partway through a copy.")
                Text(if (atMason) {
                    "If your job is missing or Cancel is unavailable, give the printer name to the staff at the printer or GMU Print Services."
                } else {
                    "If your job is missing or Cancel is unavailable, ask the staff at the printer for help."
                })
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
    )
}
