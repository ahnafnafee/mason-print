@file:OptIn( androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ahnafnafee.masonprint.ui

import kotlinx.coroutines.withTimeoutOrNull

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.core.deviceTokenFromQr
import dev.ahnafnafee.masonprint.core.isPlausibleDeviceToken
import dev.ahnafnafee.masonprint.core.unreadableScanSentence
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The viewfinder for a release station's QR code — CLONE-PLAN §6 spike S4, Spec §3.7.
 *
 * CameraX preview + ML Kit's **bundled** decoder, which is the decision that matters: the model
 * ships inside the APK, so decoding needs no Play services and no first-run model download. A
 * print station sits in a corridor where the phone often has no usable signal, and a scanner that
 * had to fetch its model would fail in exactly the spot it exists for. The lookup the scan triggers
 * (`GET /devices/{id}`) still needs the network — but the camera never blames the network for
 * something that was its own fault.
 *
 * Two vendor defects this exists to close (FINDINGS §14 U5): the stock scanner is server-hidden *and*
 * unlabeled, and a denied camera permission leaves "Please enable camera permission" on screen with
 * no route out. Denial here is a state with a button that opens this app's system-settings page.
 *
 * Frames are decoded on the phone and thrown away. Nothing about the image leaves the device; the
 * only thing sent anywhere is the short code the user then chooses to look up, which is what the
 * permission is asked for in the copy below.
 *
 * @param onScan the raw text of the first code that yielded a plausible device token. Fires at most
 *   once per instance; the preview stays up and offers "Scan again" so a mis-targeted sticker is
 *   recoverable without leaving the screen.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun PrinterCodeScanner(
    onScan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val onCurrent by rememberUpdatedState(onScan)

    var permission by remember { mutableStateOf(cameraPermissionOf(context)) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var decoderFailed by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(false) }
    var misfire by remember { mutableStateOf<String?>(null) }
    val consumedFlag = remember { AtomicBoolean(false) }
    val lastMisfireAt = remember { AtomicLong(0L) }

    // "App settings" leaves the app, and the camera is very often granted there. Nothing else
    // re-reads the permission, so without this the viewfinder stays dead after coming back and the
    // student has to leave the tab and return — the dead end the stock app left its users in
    // (docs/FINDINGS.md U5: "camera denied → 'Please enable camera permission' with no way out").
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permission = cameraPermissionOf(context) }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView, so the corner marks drawn above it actually composite.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = if (granted) Permission.Granted else Permission.Denied
    }

    // One ask per visit, and only for a decision the user has never made: a permanent "don't ask
    // again" is answered by the settings card, not by a dialog that bounces shut instantly.
    LaunchedEffect(Unit) {
        if (permission == Permission.Unasked) {
            permission = Permission.Asked
            askPermission.launch(Manifest.permission.CAMERA)
            // "Asking" must never be where this tab comes to rest. Android answers a permanently
            // denied permission without ever drawing a dialog, and an answer lost across a
            // configuration change never reaches the callback at all — either way the student would
            // be left watching a spinner with no way out, which is the dead end this whole tab is
            // here to remove (docs/FINDINGS.md U5). So after a moment's patience the card appears;
            // a real dialog is modal and unaffected by what is drawn behind it.
            delay(AskPatienceMs)
            if (permission == Permission.Asked) permission = Permission.Denied
        }
    }

    var cameraAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(cameraAttempt) {
        cameraFailed = false
        val future = ProcessCameraProvider.getInstance(context)
        val ready = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                future.addListener({ if (cont.isActive) cont.resume(Unit) }, ContextCompat.getMainExecutor(context))
            }
            true
        } == true
        provider = if (ready) runCatching { future.get() }.getOrNull() else null
        cameraFailed = provider == null
    }

    val scanner = remember(cameraAttempt) {
        runCatching {
            BarcodeScanning.getClient(
                // QR only. A station sticker is a QR, and taking every symbology is how a phone in a
                // print room ends up reading the toner cartridge's DataMatrix instead.
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            // Creating the client is the one step of this screen that reaches into a native library
            // R8 has been rearranging, so its failure is logged rather than swallowed: on a release
            // build the class names in the message are meaningless without the frames underneath.
        }.onFailure { MpLog.warn("scanner", "barcode client could not be created", it) }.getOrNull()
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(scanner, analyzerExecutor) {
        onDispose {
            scanner?.close()
            analyzerExecutor.shutdown()
        }
    }

    LaunchedEffect(provider, permission, previewView, scanner, cameraAttempt) {
        val bound = provider ?: return@LaunchedEffect
        if (permission != Permission.Granted) return@LaunchedEffect
        if (scanner == null) {
            decoderFailed = true
            return@LaunchedEffect
        }
        decoderFailed = false
        val client = scanner
        val analysis = ImageAnalysis.Builder()
            // Keep only the newest frame: a queue of stale frames is how a scanner reports a code
            // the student has already moved on from.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy? ->
            if (proxy == null) return@setAnalyzer
            val frame = proxy.image
            if (frame == null || consumedFlag.get()) {
                proxy.close()
                return@setAnalyzer
            }
            // `client` is closed when the tab goes away, which can happen between one frame and the
            // next. A closed ML Kit client throws, and an uncaught throw on an analyzer thread kills
            // the app — so the call is guarded, and a frame that loses that race is simply dropped.
            val scanned = runCatching {
                client.process(InputImage.fromMediaImage(frame, proxy.imageInfo.rotationDegrees))
            }.getOrNull()
            if (scanned == null) {
                proxy.close()
                return@setAnalyzer
            }
            scanned
                .addOnSuccessListener { codes ->
                    val raw = codes.firstNotNullOfOrNull { it.rawValue }
                    val token = deviceTokenFromQr(raw)
                    if (raw != null && token != null && isPlausibleDeviceToken(token)) {
                        if (consumedFlag.compareAndSet(false, true)) {
                            misfire = null
                            consumed = true
                            onCurrent(raw)
                        }
                    } else if (raw != null) {
                        // Decoded, but not a printer. Frames arrive dozens of times a second, so
                        // one report per 1.5 s and no stopping: the student is usually a few
                        // centimetres from the right sticker, not lost.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMisfireAt.get() > MisfireIntervalMs) {
                            lastMisfireAt.set(now)
                            misfire = unreadableScanSentence(raw)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    MpLog.warn("scanner", "code reader unavailable", error)
                    decoderFailed = true
                    analysis.clearAnalyzer()
                }
                .addOnCompleteListener { proxy.close() }
        }
        val opened = runCatching {
            bound.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            bound.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
        camera = opened.getOrNull()
        cameraFailed = opened.isFailure
        try {
            awaitCancellation()
        } finally {
            analysis.clearAnalyzer()
            runCatching { bound.unbindAll() }
            camera = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                permission == Permission.Unasked || permission == Permission.Asked ->
                    ScannerWaiting("Asking the system for the camera")

                permission == Permission.Denied -> ScannerDenied(
                    onRetry = { askPermission.launch(Manifest.permission.CAMERA) },
                    onSettings = { context.openAppSettings() },
                )

                cameraFailed || decoderFailed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (decoderFailed) "The code reader is unavailable. Try again or choose a printer from the list."
                        else "The camera could not start. Try again or choose a printer from the list.",
                        textAlign = TextAlign.Center)
                    TextButton(onClick = { cameraAttempt++ }) { Text("Try camera again") }
                }

                provider == null -> ScannerWaiting("Waking the camera")

                else -> AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                )
            }

            if (permission == Permission.Granted && provider != null && !cameraFailed && !decoderFailed) {
                ViewfinderBrackets()
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    IconButton(
                        onClick = {
                            val next = !torchOn
                            torchOn = next
                            scope.launch { runCatching { camera?.cameraControl?.enableTorch(next) } }
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            contentDescription = if (torchOn) "Turn the light off" else "Turn the light on",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (consumed) {
                    ScannedStrip(onScanAgain = {
                        consumedFlag.set(false)
                        consumed = false
                        misfire = null
                    })
                } else {
                    ViewfinderHint(misfire ?: "Hold the code inside the corners")
                }
            }
        }
    }
}

/** How often one mis-targeted sticker is allowed to be reported, given the frame rate. */
private const val MisfireIntervalMs = 1500L

/** How long to show "Asking the system for the camera" before falling back to the settings card. */
private const val AskPatienceMs = 2_000L

/**
 * Camera permission, as the screen needs to distinguish it.
 *
 * [Asked] exists because the system distinguishes "no answer yet" from "just said no": relaunching a
 * request the user refused a second ago produces the permanent denial, and the app should ask once
 * per visit rather than nag its way to a dead end.
 */
private enum class Permission { Unasked, Asked, Granted, Denied }

private fun cameraPermissionOf(context: Context): Permission =
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        Permission.Granted
    } else {
        Permission.Unasked
    }

/**
 * This app's page in system settings.
 *
 * `ACTION_APPLICATION_DETAILS_SETTINGS` needs `NEW_TASK` from a Compose context. The route out is
 * the point: the stock app's denied-camera string is a dead end, and a student who cannot get past
 * it does not retry the app, they use the web portal (FINDINGS §14 U5).
 */
private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

@Composable
private fun ScannerWaiting(message: String) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScannerDenied(onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Block, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        Text("The camera is off", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scanning uploads no picture. The code is read on the phone and only the code is sent, " +
                "to your own print server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // FlowRow so "Allow the camera" and "App settings" wrap instead of crushing each other on
        // a card too narrow to hold both — same fix as the not-found card on the release screen.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(onClick = onRetry) { Text("Allow the camera") }
            TextButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("App settings")
            }
        }
    }
}

/**
 * The four corner marks. Brand gold is budgeted for exactly this and nothing else (Spec §2.0.3),
 * which is why the marks are drawn rather than a stock overlay: the one place gold earns its keep
 * is the thing telling you where to point.
 */
@Composable
private fun ViewfinderBrackets() {
    val gold = currentMasonColors.brandGold
    Canvas(Modifier.fillMaxSize().padding(30.dp)) {
        val arm = 32.dp.toPx()
        val stroke = 4.dp.toPx()
        fun corner(startX: Float, startY: Float, dx: Float, dy: Float) {
            val sx = if (dx > 0f) stroke / 2f else size.width - stroke / 2f
            val sy = if (dy > 0f) stroke / 2f else size.height - stroke / 2f
            drawLine(gold, Offset(sx, sy), Offset(sx + dx * arm, sy), stroke, StrokeCap.Round)
            drawLine(gold, Offset(sx, sy), Offset(sx, sy + dy * arm), stroke, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(1f, 0f, -1f, 1f)
        corner(0f, 1f, 1f, -1f)
        corner(1f, 1f, -1f, -1f)
    }
}

/**
 * The one line of camera copy. It has to be readable over a live camera feed of an unknown
 * corridor, so it sits on a surface rather than floating on the preview.
 */
@Composable
private fun BoxScope.ViewfinderHint(message: String) {
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun BoxScope.ScannedStrip(onScanAgain: () -> Unit) {
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Code read.", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onScanAgain) { Text("Scan again") }
        }
    }
}
