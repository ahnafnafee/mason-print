package dev.ahnafnafee.masonprint.data.net

import com.sun.net.httpserver.HttpServer
import dev.ahnafnafee.masonprint.core.CancelVerdict
import dev.ahnafnafee.masonprint.core.PrintRequests
import dev.ahnafnafee.masonprint.core.cancellationVerdict
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ReleaseApiTest {
    @Test fun `recent charges use user ledger and original location cancellation decodes SRS refusal`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/PharosAPI/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestBody.bufferedReader().readText()}"
            auth += exchange.requestHeaders.getFirst("X-Authorization").orEmpty()
            val body = if (exchange.requestMethod == "DELETE") {
                requireNotNull(javaClass.getResourceAsStream("/gmu/released-cancel-refused.json")).use { it.readBytes() }
            } else """{"Items":[{"Identifier":"101","TransactionType":"Print","JobName":"Assignment.pdf","Time":"09/22/2026 06:11:47","ChargedTo":"Cost Center: EXAMPLE"}],"Count":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val client = PharosClient(http, http).apply { credentials = Credentials("example", "example-secret") }
            val target = PharosTarget.parse("http://127.0.0.1:${server.address.port}")!!
            target.setUserUriFromValue("/users/example")
            val ledger = client.transactions(target, 0, 100, newestFirst = true) as ApiResult.Ok
            assertEquals("Cost Center: EXAMPLE", ledger.value.items.single().chargedTo)
            assertNotNull(ledger.value.items.single().at)
            val deleted = client.deleteJobs(target, PrintRequests.deleteLocations(listOf("/printjobs/example"))) as ApiResult.Ok
            assertEquals(CancelVerdict.Refused, cancellationVerdict(deleted.value, "/printjobs/example", 1).verdict)
            assertEquals(listOf(
                "GET /PharosAPI/users/example/transactions?Skip=0&PageSize=100&OrderByDesc=Identifier ",
                "DELETE /PharosAPI/printjobs {\"PrintJobs\":[{\"Location\":\"/printjobs/example\"}]}"), requests.toList())
            assertTrue(auth.all { it == client.credentials!!.headerValue })
        } finally { server.stop(0) }
    }

    @Test fun `cancellation never replays after 503 retry after zero or redirect`() = runBlocking {
        for (status in listOf(503, 307)) {
            val paths = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.responseHeaders.add("Location", "/redirected")
                val body = "[]".toByteArray()
                exchange.sendResponseHeaders(if (paths.size == 1) status else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val http = OkHttpClient()
                val client = PharosClient(http, http)
                val target = PharosTarget.parse("http://127.0.0.1:${server.address.port}")!!
                assertTrue(client.deleteJobs(target, PrintRequests.deleteLocations(listOf("/printjobs/example"))) is ApiResult.Err)
                assertEquals("HTTP $status must not replay DELETE", listOf("/PharosAPI/printjobs"), paths.toList())
            } finally { server.stop(0) }
        }
    }
}
