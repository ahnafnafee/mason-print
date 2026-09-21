@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.PrinterJobMonitor
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.identityKey
import dev.ahnafnafee.masonprint.core.printActivityKey
import dev.ahnafnafee.masonprint.data.net.PrinterConnection
import dev.ahnafnafee.masonprint.data.net.PrinterJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object PrinterJobsHandoff {
    var deviceLocation: String = ""
    var printerName: String = ""
    fun clear() { deviceLocation = ""; printerName = "" }
}

@Composable
fun ReleasedJobsScreen(state: AppState, session: Session, router: Router) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(choosing) {
        if (choosing) {
            while (session.state.value.busy != null) delay(250)
            session.loadDevices()
        }
    }
    fun openPrinter(location: String, name: String) {
        PrinterJobsHandoff.deviceLocation = location
        PrinterJobsHandoff.printerName = name
        router.push(Route.PrinterJobs)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Released jobs") }, navigationIcon = {
            IconButton(onClick = { router.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { IconButton(onClick = { session.refresh() }, enabled = state.busy == null) {
            Icon(Icons.Filled.Refresh, "Refresh release status")
        } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Check the printer for jobs still waiting or printing. A release receipt alone does not confirm completion.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Check a printer") }
            }
            if (state.releaseHistory.isEmpty()) item {
                Text("No release receipts yet", style = MaterialTheme.typography.titleMedium)
                Text("Releases made in this app appear here. Jobs sent from elsewhere may still be visible through Check a printer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.releaseHistory, key = { it.id }) { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.name, style = MaterialTheme.typography.titleMedium)
                        Text(record.printerName, style = MaterialTheme.typography.bodyMedium)
                        Text(record.status, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activityTime(record.requestedAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { openPrinter(record.printerLocation, record.printerName) }) { Text("Check printer jobs") }
                    }
                }
            }
            if (state.releaseHistory.isNotEmpty()) item {
                Text("Up to 100 recent receipts, cleared when you sign out. Missing server records do not confirm completion.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { clearing = true }) { Text("Clear receipts") }
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear release receipts?") },
        text = { Text("This removes the receipts on this phone. It does not cancel print jobs.") },
        confirmButton = { TextButton(onClick = { session.clearReleaseHistory(); clearing = false }) { Text("Clear receipts") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Keep receipts") } })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Check a printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search printers") }, singleLine = true)
                if (state.busy == "Finding printers") LinearProgressIndicator(Modifier.fillMaxWidth())
                state.failure?.let { Text(it.headline(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                val devices = state.devices.filter { matchesPrinterQuery(it, stationOf(it, state.host), search) }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(devices, key = { it.location }) { device ->
                        TextButton(onClick = { choosing = false; openPrinter(device.location, device.label) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(device.label)
                                val station = stationOf(device, state.host)
                                Text(listOfNotNull(buildingName(station.building), stationRoom(station)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (devices.isEmpty() && state.busy != "Finding printers") item { Text("No matching printers. Try another search or refresh the list.") }
                }
                TextButton(onClick = { session.loadDevices() }, enabled = state.busy == null) { Text("Refresh printer list") }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } })
}

@Composable
fun PrinterJobsScreen(state: AppState, graph: AppGraph, onBack: () -> Unit) {
    val device = PrinterJobsHandoff.deviceLocation
    val key = printActivityKey(graph.target?.savedAddress ?: state.host, state.user?.accountKey.orEmpty()) + ":" + device
    val saved = remember(key) { graph.prefs.printerConnectionFor(key) }
    val monitor = remember(key) { PrinterJobMonitor() }
    val status by monitor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var settingsOpen by rememberSaveable(key) { mutableStateOf(saved == null) }
    var address by rememberSaveable(key) { mutableStateOf(saved?.address.orEmpty()) }
    var username by rememberSaveable(key) { mutableStateOf(saved?.username ?: state.user?.logonId.orEmpty()) }
    var password by remember(key) { mutableStateOf("") }
    var cancelling by remember { mutableStateOf<PrinterJob?>(null) }

    DisposableEffect(monitor) { onDispose { monitor.forgetPassword() } }
    LaunchedEffect(key) { if (saved != null) monitor.connect(saved, "") }
    LaunchedEffect(monitor, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(10_000); if (monitor.state.value.snapshot != null) monitor.refresh(clearActionError = false) }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Printer jobs") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { IconButton(onClick = { scope.launch { monitor.refresh() } },
            enabled = !status.busy && status.connection != null) { Icon(Icons.Filled.Refresh, "Refresh printer jobs") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(PrinterJobsHandoff.printerName, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { settingsOpen = !settingsOpen }, enabled = !status.busy) {
                    Text(if (settingsOpen) "Hide connection settings" else "Connection settings")
                }
            }
            if (settingsOpen) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connect to this printer", style = MaterialTheme.typography.titleMedium)
                        Text("Get its IPP address from the printer settings or print support. You may need campus Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(address, { address = it }, label = { Text("Printer IPP address") },
                            placeholder = { Text("ipps://printer.example.edu/ipp/print") }, singleLine = true,
                            enabled = !status.busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(username, { username = it }, label = { Text("Printer username") }, singleLine = true,
                            enabled = !status.busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            enabled = !status.busy, modifier = Modifier.fillMaxWidth())
                        Text("Use the printer's login. Passwords require ipps:// and are kept only while this screen is open.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            val connection = PrinterConnection(address.trim(), username.trim())
                            scope.launch {
                                if (monitor.connect(connection, password)) {
                                    graph.prefs.setPrinterConnectionFor(key, connection)
                                    settingsOpen = false
                                }
                            }
                        }, enabled = !status.busy && address.isNotBlank() && username.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Connect")
                        }
                    }
                }
            }
            if (status.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            status.error?.let { error -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            status.actionError?.let { error -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { scope.launch { monitor.refresh() } }, enabled = !status.busy) {
                            Text("Refresh printer jobs")
                        }
                    }
                }
            } }
            status.notice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodyMedium) } }
            status.snapshot?.let { snapshot ->
                item {
                    Text("${snapshot.printerName} · checked ${activityTime(snapshot.checkedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status.error != null) Text("Showing the last successful check.", style = MaterialTheme.typography.bodySmall)
                    Text("${status.connection?.username.orEmpty()} · updates every 10 seconds",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.jobs.isEmpty()) item {
                    Text("No pending jobs visible", style = MaterialTheme.typography.titleMedium)
                    Text("The printer returned no matching jobs for this login. A job may have finished or may not be exposed through this connection.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                items(snapshot.jobs, key = { it.id }) { job ->
                    val requested = job.identityKey() in status.cancellationRequested
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (requested && job.active) "Cancellation requested" else job.status,
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Job ${job.id} · ${job.owner}", style = MaterialTheme.typography.bodySmall)
                            if (job.active && snapshot.canCancel && job.hasIdentity) OutlinedButton(
                                onClick = { cancelling = job }, enabled = !status.busy && !requested && status.error == null && status.actionError == null,
                                modifier = Modifier.fillMaxWidth()) { Text("Cancel this job") }
                            else if (job.active) Text("Cancellation is unavailable for this job through this connection.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    cancelling?.let { job -> AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancel this print job?") },
        text = { Text("${job.name}\nJob ${job.id} at ${status.snapshot?.printerName.orEmpty()}.\n\nPages already printed cannot be taken back.") },
        confirmButton = { TextButton(
            onClick = { cancelling = null; scope.launch { monitor.cancel(job) } },
            enabled = !status.busy && status.error == null && status.actionError == null,
        ) { Text("Cancel job") } },
        dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep printing") } }) }
}

private fun activityTime(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
