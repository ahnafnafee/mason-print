package dev.ahnafnafee.masonprint.data.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A printer endpoint is configured explicitly; no device-name guessing or network discovery. */
@kotlinx.serialization.Serializable
data class PrinterConnection(val address: String, val username: String) {
    val endpoint: HttpUrl get() = transportUrl(address)
    val printerUri: String get() = endpoint.let { url ->
        val host = if (':' in url.host) "[${url.host}]" else url.host
        "${if (url.isHttps) "ipps" else "ipp"}://$host:${url.port}${url.encodedPath}"
    }

    companion object {
        fun transportUrl(address: String): HttpUrl {
            val original = address.trim()
            val mapped = when {
                original.startsWith("ipps://", true) -> "https://" + original.substringAfter("://")
                original.startsWith("ipp://", true) -> "http://" + original.substringAfter("://")
                else -> throw IllegalArgumentException("Enter the full ipp:// or ipps:// address, including its printer path.")
            }
            val url = mapped.toHttpUrlOrNull() ?: throw IllegalArgumentException("That printer address is not valid.")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Use an address without a username, password, query, or fragment."
            }
            require(url.encodedPath != "/") { "Include the printer path, such as /ipp/print." }
            // IPP and IPPS default to 631, independently of their HTTP transport scheme.
            val authority = original.substringAfter("://").substringBefore('/')
            val explicitPort = if (authority.startsWith('[')) authority.substringAfter(']').startsWith(':') else ':' in authority
            return if (explicitPort) url else url.newBuilder().port(631).build()
        }
    }
}

data class PrinterJob(
    val id: Int,
    val uri: String,
    val printerUri: String,
    val owner: String,
    val name: String,
    val state: Int,
    val uuid: String?,
    val created: Int?,
    val printerUptime: Int?,
) {
    val active: Boolean get() = state in 3..6
    val status: String get() = when (state) {
        3 -> "Waiting to print"; 4 -> "Held at printer"; 5 -> "Printing"; 6 -> "Stopped at printer"
        7 -> "Cancelled"; 8 -> "Aborted"; 9 -> "Completed"; else -> "Status unknown"
    }
    val hasIdentity: Boolean get() = id > 0 && uri.isNotBlank() &&
        uuid?.matches(Regex("(?i)urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == true

    fun isSameJob(fresh: PrinterJob): Boolean = id == fresh.id && uri == fresh.uri &&
        printerUri == fresh.printerUri && owner == fresh.owner && hasIdentity && uuid.equals(fresh.uuid, true)
}

data class PrinterJobSnapshot(val printerName: String, val jobs: List<PrinterJob>, val canCancel: Boolean, val checkedAt: Long)

class IppFailure(val code: Int?, message: String) : IOException(message)

/** Separate transport: never sends Pharos cookies, campus credentials, or redirects a printer login. */
class IppClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()) {
    private val sequence = AtomicInteger(1)
    private val jobAttributes = listOf("job-id", "job-uri", "job-printer-uri", "job-originating-user-name",
        "job-name", "job-state", "job-uuid", "time-at-creation", "job-printer-up-time")

    suspend fun jobs(connection: PrinterConnection, password: String): PrinterJobSnapshot {
        val printer = capabilities(connection, password)
        val operations = printer.numbers("operations-supported")
        if (IppProtocol.GET_JOBS !in operations) throw IppFailure(null, "This printer does not offer a job list over IPP.")
        val reply = exchange(connection, password, IppProtocol.GET_JOBS, listOf(
            IppProtocol.Attribute.text("which-jobs", "not-completed"),
            IppProtocol.Attribute.bool("my-jobs", true), IppProtocol.Attribute.number("limit", 100),
        ) + requested(jobAttributes))
        val jobs = reply.groups.filter { it.tag == 2 }.mapNotNull(::job)
            .filter { belongsTo(it, connection, printer.texts("printer-uri-supported")) }
        return PrinterJobSnapshot(printer.text("printer-name") ?: connection.endpoint.host, jobs,
            IppProtocol.CANCEL_JOB in operations && IppProtocol.GET_JOB in operations, System.currentTimeMillis())
    }

    /** Re-read the exact job before a single cancellation attempt; no title-based matching or retry. */
    suspend fun cancel(connection: PrinterConnection, password: String, selected: PrinterJob) {
        val printer = capabilities(connection, password)
        val aliases = printer.texts("printer-uri-supported")
        val operations = printer.numbers("operations-supported")
        if (IppProtocol.CANCEL_JOB !in operations || IppProtocol.GET_JOB !in operations)
            throw IppFailure(null, "This printer does not offer cancellation through this connection.")
        if (!belongsTo(selected, connection, aliases) || !selected.hasIdentity || !selected.active)
            throw IppFailure(null, "This job cannot be safely identified for cancellation. Refresh the list.")
        val fresh = exchange(connection, password, IppProtocol.GET_JOB,
            listOf(IppProtocol.Attribute.number("job-id", selected.id)) + requested(jobAttributes))
            .groups.firstOrNull { it.tag == 2 }?.let(::job)
            ?: throw IppFailure(null, "The printer no longer reports this job. Refresh the list.")
        if (!belongsTo(fresh, connection, aliases) || !selected.isSameJob(fresh) || !fresh.active)
            throw IppFailure(null, "The job changed or finished. Refresh the list before cancelling.")
        exchange(connection, password, IppProtocol.CANCEL_JOB, listOf(IppProtocol.Attribute.number("job-id", fresh.id)))
    }

    private suspend fun capabilities(connection: PrinterConnection, password: String): IppProtocol.Group =
        exchange(connection, password, IppProtocol.GET_PRINTER,
            requested(listOf("printer-name", "operations-supported", "printer-uri-supported")))
            .groups.firstOrNull { it.tag == 4 } ?: throw IppFailure(null, "The printer did not report its capabilities.")

    internal fun belongsTo(job: PrinterJob, connection: PrinterConnection, aliases: List<String> = emptyList()): Boolean =
        connection.username.isNotBlank() && job.owner == connection.username &&
            (aliases + connection.address).any { alias -> runCatching {
                PrinterConnection.transportUrl(job.printerUri) == PrinterConnection.transportUrl(alias)
            }.getOrDefault(false) }

    private fun job(group: IppProtocol.Group): PrinterJob? {
        val id = group.number("job-id") ?: return null
        return PrinterJob(id, group.text("job-uri").orEmpty(), group.text("job-printer-uri").orEmpty(),
            group.text("job-originating-user-name").orEmpty(), group.text("job-name") ?: "Untitled document",
            group.number("job-state") ?: 0, group.text("job-uuid"), group.number("time-at-creation"),
            group.number("job-printer-up-time"))
    }

    private fun requested(names: List<String>) = names.mapIndexed { index, name ->
        IppProtocol.Attribute.text(if (index == 0) "requested-attributes" else "", name)
    }

    private suspend fun exchange(connection: PrinterConnection, password: String, operation: Int,
        attributes: List<IppProtocol.Attribute>): IppProtocol.Message {
        val url = connection.endpoint
        require(connection.username.isNotBlank()) { "Enter the username the printer uses for your jobs." }
        require(password.isEmpty() || url.isHttps) { "Use ipps:// to send a printer password securely." }
        val id = sequence.getAndIncrement()
        // RFC 8011: target printer-uri and job-id precede other operation attributes.
        val body = IppProtocol.request(operation, id, listOf(
            IppProtocol.Attribute.text("printer-uri", connection.printerUri, 0x45),
        ) + attributes.filter { it.name == "job-id" } +
            IppProtocol.Attribute.text("requesting-user-name", connection.username, 0x42) +
            attributes.filterNot { it.name == "job-id" })
        val request = Request.Builder().url(url).header("Accept", "application/ipp")
            .apply { if (password.isNotEmpty()) header("Authorization", Credentials.basic(connection.username, password)) }
            .post(body.toRequestBody("application/ipp".toMediaType())).build()
        val response = http.newCall(request).awaitBytes(IppProtocol.MAX_RESPONSE)
        if (response.status == 401 || response.status == 403)
            throw IppFailure(response.status, "The printer requires a permitted printer login.")
        if (response.status !in 200..299) throw IppFailure(response.status, "The printer returned HTTP ${response.status}.")
        if (response.contentType?.substringBefore(';')?.trim() != "application/ipp")
            throw IppFailure(null, "This address did not return an IPP response. Check the printer path.")
        val reply = IppProtocol.response(response.body, id)
        if (reply.status !in 0..0xff) throw IppFailure(reply.status, when (reply.status) {
            0x0401, 0x0402, 0x0403 -> "Your printer login is not allowed to perform this action."
            0x0406 -> "This job is no longer available at the printer."
            0x0404 -> "The job cannot be changed in its current state."
            else -> "The printer refused this operation (IPP ${reply.status.toString(16)})."
        })
        return reply
    }
}
