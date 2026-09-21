package dev.ahnafnafee.masonprint.data.net

import com.sun.net.httpserver.HttpServer
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PharosNotificationsTest {
    @Test fun `only queue and release events from notification hub trigger refresh`() {
        for (method in listOf("AddJob", "UpdateJob", "DeleteJob", "ReleasedJob", "ActivityUpdate")) {
            assertTrue(PharosNotifications.hasQueueChange("""{"M":[{"H":"notificationHub","M":"$method"}]}""".toJsonObject()))
        }
        for (json in listOf("{}", """{"M":[null,1,{"H":"otherHub","M":"UpdateJob"}]}""",
            """{"M":[{"H":"notificationHub","M":"OtherEvent"}]}""")) {
            assertFalse(PharosNotifications.hasQueueChange(json.toJsonObject()))
        }
    }

    @Test fun `classic SignalR handshake polls with cursor fixed credentials and stops with its scope`() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<String>()
        val queries = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/PharosAPI/signalr") { exchange ->
            val path = exchange.requestURI.path.substringAfterLast('/')
            paths += path
            headers += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            queries += exchange.requestURI.rawQuery.orEmpty()
            val response = when (path) {
                "negotiate" -> """{"ConnectionToken":"token+/example"}"""
                "connect" -> """{"C":"cursor-1","G":"group-1","S":1,"M":[]}"""
                "start" -> """{"Response":"started"}"""
                else -> """{"C":"cursor-2","M":[{"H":"notificationHub","M":"ReleasedJob","A":[]}]}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val client = PharosClient(http, http)
            val original = Credentials("example-student", "example-secret")
            client.credentials = original
            val target = PharosTarget.parse("http://127.0.0.1:${server.address.port}")!!
            var changes = 0
            val watcher = launch {
                client.watchQueueChanges(target) {
                    changes++
                    client.credentials = Credentials("different-account", "different-secret")
                    if (changes == 2) throw CancellationException("screen closed")
                }
            }
            withTimeout(5_000) { watcher.join() }
            assertEquals(listOf("negotiate", "connect", "start", "poll"), paths.toList())
            assertTrue(headers.all { it == original.headerValue })
            assertTrue(queries.last().contains("messageId=cursor-1"))
            assertTrue(queries.last().contains("groupsToken=group-1"))
            assertTrue(queries.last().contains("transport=longPolling"))
            assertTrue(watcher.isCancelled)
        } finally { server.stop(0) }
    }
}
