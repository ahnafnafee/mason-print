package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.core.PrinterJobMonitor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

internal fun ippReply(id: Int, groups: List<IppProtocol.Group>, status: Int = 0): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { out ->
        out.writeByte(2); out.writeByte(0); out.writeShort(status); out.writeInt(id)
        groups.forEach { group ->
            out.writeByte(group.tag)
            group.attributes.forEach { attribute ->
                val name = attribute.name.toByteArray()
                out.writeByte(attribute.tag); out.writeShort(name.size); out.write(name)
                out.writeShort(attribute.bytes.size); out.write(attribute.bytes)
            }
        }
        out.writeByte(3)
    }
    return buffer.toByteArray()
}

private class PrinterFixture {
    val connection = PrinterConnection("ipps://printer.example.edu:443/ipp/print", "student")
    val alias = "ipp://printer.example.edu:631/ipp/print"
    val job = PrinterJob(12, "ipp://printer.example.edu/jobs/12", alias, "student", "Assignment.pdf", 3,
        "urn:uuid:12345678-1234-1234-1234-123456789012", 20, 100)
    var latest = job
    var status = 0
    var cancelSupported = true
    var failCancel = false
    var failAfterCancel = false
    val requests = mutableListOf<Pair<Request, IppProtocol.Message>>()
    val client = IppClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val bytes = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        check(bytes[0] == 1.toByte() && bytes[1] == 1.toByte()) { "Printer only supports IPP 1.1" }
        val id = ByteBuffer.wrap(bytes, 4, 4).int
        val message = IppProtocol.response(bytes, id)
        requests += request to message
        if (message.status == IppProtocol.CANCEL_JOB && failCancel) throw IOException("response lost")
        if (failAfterCancel && message.status == IppProtocol.GET_PRINTER &&
            requests.any { it.second.status == IppProtocol.CANCEL_JOB }) throw IOException("refresh failed")
        val groups = when (message.status) {
            IppProtocol.GET_PRINTER -> listOf(IppProtocol.Group(4, listOf(
                IppProtocol.Attribute.text("printer-name", "Library printer", 0x42),
                IppProtocol.Attribute.text("printer-uri-supported", alias, 0x45),
                IppProtocol.Attribute.number("operations-supported", IppProtocol.GET_JOBS),
                IppProtocol.Attribute.number("", IppProtocol.GET_JOB),
                IppProtocol.Attribute.number("", if (cancelSupported) IppProtocol.CANCEL_JOB else IppProtocol.GET_PRINTER),
            )))
            IppProtocol.GET_JOB, IppProtocol.GET_JOBS -> listOf(group(latest))
            else -> emptyList()
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "application/ipp")
            .body(ippReply(id, groups, if (message.status == IppProtocol.CANCEL_JOB) status else 0)
                .toResponseBody("application/ipp".toMediaType())).build()
    }.build())

    private fun group(job: PrinterJob) = IppProtocol.Group(2, listOf(
        IppProtocol.Attribute.number("job-id", job.id),
        IppProtocol.Attribute.text("job-uri", job.uri, 0x45),
        IppProtocol.Attribute.text("job-printer-uri", job.printerUri, 0x45),
        IppProtocol.Attribute.text("job-originating-user-name", job.owner, 0x42),
        IppProtocol.Attribute.text("job-name", job.name, 0x42),
        IppProtocol.Attribute.number("job-state", job.state),
    ) + listOfNotNull(job.uuid?.let { IppProtocol.Attribute.text("job-uuid", it, 0x45) }))

    val cancels get() = requests.count { it.second.status == IppProtocol.CANCEL_JOB }
}

class IppClientTest {
    @Test fun `printer URI keeps IPP defaults explicit HTTP ports and IPv6`() {
        mapOf(
            "ipp://printer/ipp/print" to "ipp://printer:631/ipp/print",
            "ipps://printer/ipp/print" to "ipps://printer:631/ipp/print",
            "ipp://printer:80/ipp/print" to "ipp://printer:80/ipp/print",
            "ipps://printer:443/ipp/print" to "ipps://printer:443/ipp/print",
            "ipp://[::1]:80/ipp/print" to "ipp://[::1]:80/ipp/print",
        ).forEach { (input, expected) -> assertEquals(expected, PrinterConnection(input, "u").printerUri) }
        listOf("https://printer/ipp/print", "ipp://u:p@printer/ipp/print", "ipp://printer/", "ipp://printer/ipp/print?token=a",
            "ipp://printer/ipp/print#bad").forEach { assertThrows(IllegalArgumentException::class.java) { PrinterConnection.transportUrl(it) } }
    }

    @Test fun `owned jobs submitted through a printer advertised alias remain visible`() = runBlocking {
        val f = PrinterFixture()
        val snapshot = f.client.jobs(f.connection, "")
        assertEquals("Assignment.pdf", snapshot.jobs.single().name)
        assertTrue(snapshot.canCancel)
        f.latest = f.job.copy(owner = "someone-else")
        assertTrue(f.client.jobs(f.connection, "").jobs.isEmpty())
        f.latest = f.job.copy(printerUri = "ipp://unrelated/ipp/print")
        assertTrue(f.client.jobs(f.connection, "").jobs.isEmpty())
    }

    @Test fun `cancel checks capabilities and UUID then sends one ordered job request to configured endpoint`() = runBlocking {
        val f = PrinterFixture()
        f.client.cancel(f.connection, "printer-only-secret", f.job)
        assertEquals(listOf(11, 9, 8), f.requests.map { it.second.status })
        assertEquals(1, f.cancels)
        f.requests.forEach { (request, message) ->
            assertEquals(f.connection.endpoint, request.url)
            assertNull(request.header("Cookie")); assertNull(request.header("X-Authorization"))
            assertEquals(okhttp3.Credentials.basic("student", "printer-only-secret"), request.header("Authorization"))
            if (message.status in listOf(8, 9)) assertEquals(
                listOf("attributes-charset", "attributes-natural-language", "printer-uri", "job-id", "requesting-user-name"),
                message.groups.first().attributes.take(5).map { it.name })
        }
    }

    @Test fun `cancel refuses changed UUID owner completed job and unsupported operation`() = runBlocking {
        val f = PrinterFixture()
        for (changed in listOf(f.job.copy(uuid = "urn:uuid:12345678-1234-1234-1234-123456789013"),
            f.job.copy(owner = "other"), f.job.copy(state = 9))) {
            f.latest = changed
            try { f.client.cancel(f.connection, "", f.job); fail("cancel should be refused") } catch (_: IppFailure) { }
        }
        f.cancelSupported = false
        try { f.client.cancel(f.connection, "", f.job); fail("cancel should be refused") } catch (_: IppFailure) { }
        assertEquals(0, f.cancels)
    }

    @Test fun `reused job numbers and creation seconds without UUID cannot authorize cancellation`() = runBlocking {
        val f = PrinterFixture()
        val beforeReboot = f.job.copy(uuid = null)
        val afterReboot = beforeReboot.copy(printerUptime = 200)
        assertFalse(beforeReboot.hasIdentity)
        assertFalse(beforeReboot.isSameJob(afterReboot))
        try { f.client.cancel(f.connection, "", beforeReboot); fail("missing UUID") } catch (_: IppFailure) { }
        assertEquals(0, f.cancels)
    }

    @Test fun `password over unencrypted IPP is rejected before sending`() = runBlocking {
        val f = PrinterFixture()
        try { f.client.jobs(f.connection.copy(address = f.alias), "secret"); fail("cleartext password") }
        catch (_: IllegalArgumentException) { }
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `accepted cancellation survives a failed refresh and cannot be repeated`() = runBlocking {
        val f = PrinterFixture()
        val monitor = PrinterJobMonitor(f.client)
        assertTrue(monitor.connect(f.connection, ""))
        f.failAfterCancel = true
        val selected = monitor.state.value.snapshot!!.jobs.single()
        monitor.cancel(selected)
        assertTrue(monitor.state.value.notice!!.contains("accepted"))
        assertTrue(monitor.state.value.error!!.contains("latest job status"))
        monitor.cancel(selected)
        assertEquals(1, f.cancels)
    }

    @Test fun `refusal and lost cancel response never report successful cancellation or retry automatically`() = runBlocking {
        for (lost in listOf(false, true)) {
            val f = PrinterFixture()
            val monitor = PrinterJobMonitor(f.client)
            monitor.connect(f.connection, "")
            f.failCancel = lost
            f.status = 0x0403
            monitor.cancel(monitor.state.value.snapshot!!.jobs.single())
            assertNull(monitor.state.value.notice)
            assertNotNull(monitor.state.value.actionError)
            monitor.refresh(clearActionError = false)
            assertNotNull(monitor.state.value.actionError)
            monitor.refresh()
            assertNull(monitor.state.value.actionError)
            assertEquals(1, f.cancels)
        }
    }

    @Test fun `a status refresh while confirmation is open does not silently discard cancellation`() = runBlocking {
        val f = PrinterFixture()
        val monitor = PrinterJobMonitor(f.client)
        monitor.connect(f.connection, "")
        val selected = monitor.state.value.snapshot!!.jobs.single()
        f.latest = f.job.copy(state = 5)
        monitor.refresh()
        monitor.cancel(selected)
        assertEquals(1, f.cancels)
        assertNotNull(monitor.state.value.notice)
    }
}
