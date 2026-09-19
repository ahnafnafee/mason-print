@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.ahnafnafee.masonprint.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.data.model.PrintJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Which job the preview screen is about.
 *
 * Process state in the same spirit as [ReleaseHandoff]: the queue sets it on the way in and the
 * screen reads the job back out of `AppState`, so nothing about a document is duplicated into a
 * second home that could go stale.
 */
internal object PreviewHandoff {
    var location: String? = null
}

/**
 * Look at a document before spending money on it.
 *
 * Rendered with the framework's own [PdfRenderer] — no library, and so no weight added to an APK
 * that was just cut from 18 MB to under 3. That is viable because Pharos converts on ingest, so
 * `GET {job}/content` answers `application/pdf` whatever was uploaded (docs/FINDINGS: `JobFormat:
 * Intermediate`, and every `/preview` shape answers 401 because the route does not exist).
 *
 * Pages render one at a time, off the main thread, and only as they scroll into view: a 200-page
 * course reader must not decode 200 bitmaps to show the first one.
 */
@Composable
internal fun PreviewScreen(state: AppState, session: Session, onBack: () -> Unit) {
    val job: PrintJob? = remember(state.jobs, PreviewHandoff.location) {
        state.jobs.firstOrNull { it.location == PreviewHandoff.location }
    }

    var file by remember(job?.location) { mutableStateOf<File?>(null) }
    var failed by remember(job?.location) { mutableStateOf(false) }

    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(job?.location, retry) {
        file = null
        failed = false
        if (job != null) {
            file = session.documentFor(job)
            failed = file == null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            job?.name ?: "Preview",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        job?.let {
                            Text(
                                specLineFor(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { bar ->
        val current = file
        when {
            job == null -> Box(Modifier.fillMaxSize().padding(bar), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "That document is gone",
                    body = "It is no longer in the queue on this server. It may have been released " +
                        "or deleted since this screen was opened.",
                )
            }

            failed -> Column(Modifier.fillMaxSize().padding(bar).padding(MasonScreenPadding),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    title = "Could not open this document",
                    body = "Check your connection and try downloading the preview again.",
                )
                TextButton(onClick = { retry++ }) { Text("Try again") }
            }

            current == null -> Box(Modifier.fillMaxSize().padding(bar), contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Text("Fetching the document", style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> PdfPager(file = current, modifier = Modifier.fillMaxSize().padding(bar))
        }
    }
}

/** The one-line "1 sheet · 2 pages" summary, reused from the queue so both screens agree. */
private fun specLineFor(job: PrintJob): String = specLine(job).ifBlank { "Document" }

@Composable
private fun PdfPager(file: File, modifier: Modifier) {
    val opened by produceState<Result<PdfPages>?>(null, file) {
        var resource: PdfPages? = null
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfPages(file).also { resource = it } }
            }
            value = result
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { resource?.close() }
        }
    }
    val pages = opened?.getOrNull()
    if (pages == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (opened == null) CircularProgressIndicator() else EmptyState(
                title = "This document will not display",
                body = "The downloaded file could not be rendered. Return to the queue to check its status.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = MasonScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "top") {
            Text(
                "Pinch or double-tap a page to zoom. Drag to move around; Fit resets the page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(pages.pageCount) { index -> PdfPage(pages = pages, index = index) }
        item(key = "bottom") { Spacer(Modifier.height(MasonScrollSpacer)) }
    }
}

/** Render visible pages at reading resolution, then sharpen a page when it is enlarged. */
@Composable
private fun PdfPage(pages: PdfPages, index: Int) {
    var zoom by remember(pages, index) { mutableStateOf(PreviewZoom()) }
    var viewport by remember(pages, index) { mutableStateOf(IntSize.Zero) }
    var rendered by remember(pages, index) { mutableStateOf<Result<Bitmap>?>(null) }
    var detailed by remember(pages, index) { mutableStateOf(false) }
    val enlarged = zoom.scale > 1f
    LaunchedEffect(pages, index, enlarged) {
        if (rendered == null) rendered = pages.render(index, RenderWidthPx)
        if (enlarged && !detailed && rendered?.isSuccess == true) {
            delay(180)
            val sharper = pages.render(index, RenderWidthPx * 2)
            if (sharper.isSuccess) {
                rendered = sharper
                detailed = true
            }
        }
    }
    val bitmap = rendered?.getOrNull()
    val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (612f / 792f)
    val transform = rememberTransformableState { centroid: Offset, scale: Float, pan: Offset, _: Float ->
        zoom = zoom.transformed(scale, pan, centroid, viewport)
    }
    val scope = rememberCoroutineScope()
    fun zoomTo(target: Float, centroid: Offset = Offset.Unspecified) {
        scope.launch {
            transform.transform(MutatePriority.UserInput) {
                zoom = zoom.transformed(target / zoom.scale, Offset.Zero, centroid, viewport)
            }
        }
    }
    fun panBy(pan: Offset): Boolean {
        scope.launch {
            transform.transform(MutatePriority.UserInput) {
                zoom = zoom.transformed(1f, pan, Offset.Unspecified, viewport)
            }
        }
        return true
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Page ${index + 1} of ${pages.pageCount}", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoomTo(zoom.scale / 1.5f) }, enabled = bitmap != null && enlarged) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out on page ${index + 1}")
                }
                Text("${(zoom.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { zoomTo(zoom.scale * 1.5f) }, enabled = bitmap != null && zoom.scale < MaxPreviewZoom) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in on page ${index + 1}")
                }
                TextButton(
                    onClick = { zoomTo(1f) },
                    enabled = bitmap != null && enlarged,
                    modifier = Modifier.semantics { contentDescription = "Fit page ${index + 1} to width" },
                ) { Text("Fit") }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()
                    .onSizeChanged {
                        viewport = it
                        zoom = zoom.constrained(it)
                    }
                    .transformable(
                        state = transform,
                        canPan = { zoom.canPan(it, viewport) },
                        lockRotationOnZoomPan = true,
                        enabled = bitmap != null,
                    )
                    .pointerInput(pages, index, bitmap != null) {
                        detectTapGestures(onDoubleTap = { point ->
                            if (bitmap != null) zoomTo(if (zoom.scale > 1f) 1f else 2.5f, point)
                        })
                    }
                    .semantics {
                        stateDescription = "${(zoom.scale * 100).roundToInt()} percent zoom"
                        if (enlarged) customActions = listOf(
                            CustomAccessibilityAction("Move left") { panBy(Offset(viewport.width / 2f, 0f)) },
                            CustomAccessibilityAction("Move right") { panBy(Offset(-viewport.width / 2f, 0f)) },
                            CustomAccessibilityAction("Move up") { panBy(Offset(0f, viewport.height / 2f)) },
                            CustomAccessibilityAction("Move down") { panBy(Offset(0f, -viewport.height / 2f)) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (rendered?.isFailure == true) {
                    Text("Page ${index + 1} could not be displayed", Modifier.padding(16.dp))
                } else if (bmp == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/**
 * Keep ordinary scrolling inexpensive; zoomed pages request double this resolution once, still
 * subject to [previewDimensions]' dimension and memory limits.
 */
private const val RenderWidthPx = 1080

/**
 * [PdfRenderer] over a downloaded file.
 *
 * `PdfRenderer` permits exactly one open page at a time and is not thread-safe, so every render is
 * serialised through a mutex — two pages scrolling into view at once is the ordinary case here, and
 * without the lock it throws.
 */
private class PdfPages(file: File) : AutoCloseable {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) {
        descriptor.close()
        throw error
    }
    private val lock = Any()
    private var closed = false
    val pageCount = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Result<Bitmap> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                check(!closed) { "Preview closed" }
                renderer.openPage(index).use { page ->
                    val (width, height) = previewDimensions(page.width, page.height, widthPx)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } catch (error: Exception) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }.onFailure { MpLog.warn("preview", "page $index would not render", it) }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { renderer.close() } finally { descriptor.close() }
        }
    }
}

/** Bound both dimensions and area, including unusually tall receipts or wide drawings. */
internal fun previewDimensions(width: Int, height: Int, requestedWidth: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && requestedWidth > 0)
    val scale = minOf(requestedWidth.toDouble() / width, 4096.0 / maxOf(width, height),
        kotlin.math.sqrt(4_000_000.0 / (width.toDouble() * height)))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
