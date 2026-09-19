@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.BuildConfig
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.core.Session

/**
 * What the server said, on one screen, selectable and copyable.
 *
 * The stock app has no diagnostics at all and its `DebugTrace.LogMessage()` is an empty method, so
 * the only way to find out what happened was to run mitm — which is how FINDINGS.md was written
 * (docs/FINDINGS.md §10 S9). This screen is the cheap version of that instrument: it prints the
 * three things a support conversation actually turns on (API version, capabilities, certificate
 * fingerprints) plus whatever the app logged this session.
 */
@Composable
fun DiagnosticsScreen(
    state: AppState,
    graph: AppGraph,
    session: Session,
    onBack: () -> Unit,
) {
    val log by MpLog.entries.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Connection") {
                Row(Modifier.fillMaxWidth()) {
                    Label("Server"); Value(state.host.ifBlank { "—" })
                }
                Row(Modifier.fillMaxWidth()) {
                    Label("API version"); Value(state.apiVersion ?: graph.prefs.lastApiVersion ?: "unknown")
                }
                Row(Modifier.fillMaxWidth()) {
                    Label("User"); Value(state.user?.preferredName ?: graph.prefs.lastUserName ?: "—")
                }
                Row(Modifier.fillMaxWidth()) {
                    Label("Card"); Value(state.user?.cardId?.takeIf { it.isNotBlank() } ?: "—")
                }
                Row(Modifier.fillMaxWidth()) {
                    Label("App"); Value("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                }
            }

            Section("What this server allows") {
                val caps = state.capabilities
                if (caps == null) {
                    Value("Not yet read. Sign in, or use Connect to fetch /settings.")
                } else {
                    Bool("Web upload", caps.canUpload, caps.uploadBlockReason)
                    Bool("Preview before release", caps.previewAllowed)
                    Bool("Release at QR printers", caps.qrReleaseEnabled)
                    Bool("Add funds in app", caps.addFundsEnabled, "GMU: \"Add Funds\": Deny. Money arrives as campus credit")
                    Bool("Finishing changes", caps.finishingUpdateAllowed)
                    Value("Max upload: ${caps.maxUploadBytes?.let { "${it / 1024 / 1024} MB" } ?: "unknown"}")
                    Value("Gateway: ${caps.gateway}")
                    Value("SSO: ${caps.sso}")
                    Value("Charging: ${caps.chargingModel ?: "unknown"} · bank ${caps.bankName ?: "—"}")
                }
            }

            Section("Who pays") {
                val caps = state.capabilities
                val money: (Double) -> String = { amount ->
                    caps?.formats?.money(amount) ?: amount.toString()
                }
                Bool(
                    "Department cost centres",
                    caps?.costCentersAllowed == true,
                    "Privileges.Printing.PayForPrint.CostCenters. Where this is Deny the server is unlikely to accept a CostCenterCode, but the app still lets you type one because the privilege and the code list are maintained separately.",
                )
                Bool(
                    "Charge somebody else",
                    caps?.chargingUserChangeAllowed == true,
                    "Privileges.Printing.Administration.ChangeChargingUser. Gates the Owner key on a release body. This app never sends one.",
                )
                Row(Modifier.fillMaxWidth()) {
                    Label("Releasing as"); Value(state.fundingLabel)
                }
                val purses = state.user?.balance?.purses.orEmpty()
                if (purses.isEmpty()) {
                    Value("Server returned no separate purses. One balance total.")
                } else {
                    Text(
                        "Spending order is the server's `Priority`, lowest first:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    purses.sortedBy { it.priority ?: Long.MAX_VALUE }.forEach { purse ->
                        Row(Modifier.fillMaxWidth()) {
                            Label("#${purse.priority?.toString() ?: "?"}  ${purse.name}")
                            Value(money(purse.amount))
                        }
                    }
                }
                val centers = caps?.usableCostCenters.orEmpty()
                if (centers.isEmpty()) {
                    Value("`User.CostCenters` is empty for this account. A code can still be typed manually.")
                } else {
                    centers.forEach { center ->
                        Row(Modifier.fillMaxWidth()) {
                            Label(center.code)
                            Value(
                                buildString {
                                    append(center.description ?: "—")
                                    append(if (center.granted) "  (grant)" else "  (assigned)")
                                    if (!center.complete) append("  (closed)")
                                    if (!center.active) append("  (inactive)")
                                },
                            )
                        }
                    }
                }
            }

            Section("Secrets and certificates") {
                Bool("Encrypted at rest", graph.secrets.encryptedAtRest, if (graph.secrets.encryptedAtRest) null else "this device's keystore failed; storage is private but plaintext")
                val certs = remember { graph.trusts.all() }
                if (certs.isEmpty()) {
                    Value("No certificate approvals yet.")
                } else {
                    certs.forEach { cert ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cert.host, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "SHA-256 ${cert.shortFingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { graph.trusts.revoke(cert.host, cert.fingerprint) }) { Text("Revoke") }
                        }
                    }
                    Text(
                        "Revoking takes effect on the next handshake, not the connection already open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Session log") {
                Text(
                    "${log.size} entries this session · ${if (graph.prefs.diagnosticsEnabled) "response bodies captured" else "response bodies not captured"}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLog = true }) { Text("View") }
                    OutlinedButton(onClick = { graph.prefs.diagnosticsEnabled = !graph.prefs.diagnosticsEnabled }) {
                        Text(if (graph.prefs.diagnosticsEnabled) "Stop capturing bodies" else "Capture full bodies")
                    }
                    OutlinedButton(onClick = { MpLog.clear() }) { Text("Clear") }
                }
                if (showLog) {
                    SelectionContainer {
                        Text(
                            MpLog.dump().ifBlank { "(empty)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Section("Account") {
                Button(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out and forget this device")
                }
                Text(
                    "Sign-out calls the server's /logout. The vendor app only cleared its own preferences, so the Pharos session stayed alive server-side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Sign out?") },
            text = { Text("The saved password, session cookies, and cached queue are removed. Approved certificates are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    session.signOut()
                    onBack()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 10.dp),
    )
}

@Composable
private fun Value(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Bool(label: String, allowed: Boolean, note: String? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (allowed) "✓" else "✗", color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Label(label)
            Value(if (allowed) "allowed" else "not allowed")
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
