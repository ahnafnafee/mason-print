@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.data.model.PrintJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    var file by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffectFor(job) { target ->
        val got = session.documentFor(target)
        file = got
        failed = got == null
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

            failed -> Box(Modifier.fillMaxSize().padding(bar), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Could not open this document",
                    body = "The server would not hand back the file. It can still be released at a " +
                        "printer. A preview is not needed to print it.",
                )
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

/** `LaunchedEffect` keyed on the job, without repeating the null dance at the call site. */
@Composable
private fun LaunchedEffectFor(job: PrintJob?, block: suspend (PrintJob) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(job?.location) {
        if (job != null) block(job)
    }
}

/** The one-line "1 sheet · 2 pages" summary, reused from the queue so both screens agree. */
private fun specLineFor(job: PrintJob): String = job.pageSummary.ifBlank { "Document" }

@Composable
private fun PdfPager(file: File, modifier: Modifier) {
    val pages = remember(file) { runCatching { PdfPages(file) }.getOrNull() }
    DisposableEffect(pages) { onDispose { pages?.close() } }

    if (pages == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = "This document will not display",
                body = "The file came back in a form this phone cannot render. It will still print.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = MasonScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "top") { Spacer(Modifier.height(4.dp)) }
        items(pages.pageCount) { index -> PdfPage(pages = pages, index = index) }
        item(key = "bottom") { Spacer(Modifier.height(MasonScrollSpacer)) }
    }
}

/** One page, decoded only once it is on screen. */
@Composable
private fun PdfPage(pages: PdfPages, index: Int) {
    val ratio = remember(pages, index) { pages.aspectRatio(index) }
    val bitmap by produceState<Bitmap?>(initialValue = null, pages, index) {
        value = pages.render(index, RenderWidthPx)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/**
 * A page is rendered at a fixed width rather than the measured one: it keeps the bitmap cache stable
 * while scrolling, and 1080 px is already past what a phone can show.
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

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** Page shape, so the placeholder occupies the right space before the bitmap exists. */
    fun aspectRatio(index: Int): Float = runCatching {
        renderer.openPage(index).use { page ->
            if (page.height == 0) DefaultRatio else page.width.toFloat() / page.height.toFloat()
        }
    }.getOrDefault(DefaultRatio)

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    // A PDF page is transparent where nothing is drawn; on a dark surface that reads
                    // as an unreadable page, so it is painted onto white like real paper.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.onFailure { MpLog.warn("preview", "page $index would not render", it) }.getOrNull()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private companion object {
        /** US Letter portrait, the overwhelming default on this campus. */
        const val DefaultRatio = 612f / 792f
    }
}
