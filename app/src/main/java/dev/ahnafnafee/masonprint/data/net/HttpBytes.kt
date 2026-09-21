package dev.ahnafnafee.masonprint.data.net

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpBytes(val status: Int, val contentType: String?, val body: ByteArray)

/** Read a bounded body on OkHttp's worker; cancellation also interrupts a body still arriving. */
internal suspend fun Call.awaitBytes(limit: Int): HttpBytes = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    val source = it.body.source()
                    source.request(limit.toLong() + 1)
                    if (source.buffer.size > limit) throw IOException("The response exceeds the supported size.")
                    HttpBytes(it.code, it.body.contentType()?.toString(), source.readByteArray())
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
