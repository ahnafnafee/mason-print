package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.strCI
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Classic SignalR 2 long polling used by GMU, not the incompatible ASP.NET Core protocol. */
internal class PharosNotifications(http: OkHttpClient) {
    // Connection tokens are query parameters. Never pass this transport through the HTTP logger.
    private val transport = http.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
    }.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(130, TimeUnit.SECONDS).readTimeout(125, TimeUnit.SECONDS).build()

    suspend fun watch(target: PharosTarget, authHeader: () -> String?, onChanged: suspend () -> Unit) {
        var backoff = 5_000L
        while (currentCoroutineContext().isActive) {
            try {
                connect(target, authHeader, onChanged)
                backoff = 5_000L
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Manual refresh and analysis polling remain usable during server/network failures.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun connect(target: PharosTarget, authHeader: () -> String?, onChanged: suspend () -> Unit) {
        val hub = "[{\"name\":\"notificationhub\"}]"
        suspend fun read(path: String, token: String? = null, cursor: String? = null, groups: String? = null): JsonObject {
            val url = target.apiPath("signalr", path).newBuilder()
                .addQueryParameter("clientProtocol", "1.5").addQueryParameter("connectionData", hub)
                .apply {
                    if (token != null) { addQueryParameter("transport", "longPolling"); addQueryParameter("connectionToken", token) }
                    cursor?.let { addQueryParameter("messageId", it) }
                    groups?.let { addQueryParameter("groupsToken", it) }
                }.build()
            val request = Request.Builder().url(url).apply {
                authHeader()?.let { header("Authorization", it); header("X-Authorization", it) }
            }.build()
            val response = transport.newCall(request).awaitBytes(1024 * 1024)
            if (response.status != 200) throw IOException("Notification connection unavailable")
            return response.body.toString(Charsets.UTF_8).toJsonObject()
        }
        val negotiation = read("negotiate")
        val token = negotiation.strCI("ConnectionToken") ?: throw IOException("Notification token missing")
        var envelope = read("connect", token)
        val started = read("start", token)
        if (started.strCI("Response") != "started") throw IOException("Notification connection did not start")
        // Refresh after every reconnection to recover changes while disconnected.
        onChanged()
        var cursor: String? = null
        var groups: String? = null
        while (currentCoroutineContext().isActive) {
            cursor = envelope.strCI("C") ?: cursor
            groups = envelope.strCI("G") ?: groups
            if (hasQueueChange(envelope)) onChanged()
            if (envelope["D"]?.toString() == "1" || envelope["T"]?.toString() == "1") return
            delay(250)
            envelope = read("poll", token, cursor, groups)
        }
    }

    companion object {
        internal fun hasQueueChange(envelope: JsonObject): Boolean =
            (envelope["M"] as? JsonArray).orEmpty().any { value ->
                val event = value as? JsonObject ?: return@any false
                event.strCI("H").equals("notificationHub", true) && event.strCI("M")?.lowercase() in
                    setOf("addjob", "updatejob", "deletejob", "releasedjob", "activityupdate")
            }
    }
}
