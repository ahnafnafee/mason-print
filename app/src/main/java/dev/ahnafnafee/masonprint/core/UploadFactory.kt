package dev.ahnafnafee.masonprint.core

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.ahnafnafee.masonprint.data.net.UploadSource
import dev.ahnafnafee.masonprint.data.upload.MimeTypes
import java.util.concurrent.atomic.AtomicReference

/**
 * Turning a share-sheet `Uri` into something uploadable.
 *
 * The stock app resolved the name and MIME type in `MainActivity.OnNewIntent` and then read the
 * whole stream into a `byte[]` (docs/FINDINGS.md §7.2). Here the `Uri` itself is captured and each
 * attempt opens a fresh file descriptor, which is what makes "retry without picking the file again"
 * possible — the failure mode that produced the stock app's
 * "it said it uploaded and there is nothing in the queue".
 */
object UploadFactory {

    /** Null when the `Uri` cannot be opened for reading at all (revoked permission, deleted file). */
    fun fromUri(context: Context, uri: Uri): UploadSource? {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val declared = querySize(context, uri)

        val opener: () -> android.os.ParcelFileDescriptor = {
            // Kept in a field so a close race during cancellation cannot leave the descriptor
            // unreachable while OkHttp is still writing.
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.FileNotFoundException("Cannot open $uri")
        }

        // A failed probe (no descriptor at all) means there is nothing to upload; report that
        // rather than queuing a document that will fail at write time.
        val probe = runCatching { opener() }.getOrNull() ?: return null
        val size = if (declared > 0) declared else runCatching { probe.statSize }.getOrDefault(-1L)
        runCatching { probe.close() }

        val extension = name.substringAfterLast('.', "")
        val mime = MimeTypes.forExtension(extension)
            ?: context.contentResolver.getType(uri)
            ?: "application/octet-stream"

        return UploadSource(
            fileName = sanitize(name),
            mimeType = mime,
            sizeBytes = size,
            opener = { opener() },
        )
    }

    private fun displayName(context: Context, uri: Uri): String? = when (uri.scheme) {
        "content" -> {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
            } catch (e: Exception) {
                null
            } finally {
                cursor?.close()
            }
        }

        else -> uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /** Pharos puts the name straight into a `Content-Disposition` header and into the job title. */
    private fun sanitize(name: String): String =
        name.replace('"', '_').replace('\\', '_').replace('/', '_').replace(Regex("[\r\n]"), "").trim()
            .ifEmpty { "document" }
}

/**
 * Progress that survives being called from OkHttp's writer thread while Compose reads it on the
 * main thread. Throttled upstream by [UploadSource] to one callback per 256 KB.
 */
class UploadProgress {
    private val ref = AtomicReference(0f)
    val fraction: Float get() = ref.get()
    fun set(value: Float) { ref.set(value.coerceIn(0f, 1f)) }
}
