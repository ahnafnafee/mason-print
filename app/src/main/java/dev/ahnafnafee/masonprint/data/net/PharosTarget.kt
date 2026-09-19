package dev.ahnafnafee.masonprint.data.net

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Which server we are talking to, and the two user-scoped route prefixes it handed back.
 *
 * `{UserUri}` and `{UserLocation}` are different placeholders in the vendor's own route table —
 * `/printjobs`, `/transactions` and `/balance/gateway` hang off the first, `/balance` off the
 * second — so they are kept apart here instead of being collapsed into "the user URL".
 */
class PharosTarget private constructor(val root: HttpUrl) {

    @Volatile var userUri: HttpUrl? = null
        private set
    @Volatile var userLocation: HttpUrl? = null
        private set

    /** Captured from a response header when the body did not carry `ServerVersion`. */
    @Volatile var headerApiVersion: String? = null

    /** Keep the scheme and explicit port when restoring a configured server. */
    val savedAddress: String get() = root.newBuilder().encodedPath("/").build().toString().trimEnd('/')

    val host: String get() = root.host
    val displayHost: String get() = if (root.port == HttpUrl.defaultPort(root.scheme)) root.host else "${root.host}:${root.port}"

    fun apiPath(vararg segments: String): HttpUrl {
        val b = root.newBuilder()
        segments.forEach { seg ->
            // A `{id}` segment can itself be a URL (job Locations are), so a segment containing a
            // slash is added as path *segments*, not as one percent-encoded blob.
            if (seg.contains('/')) b.addEncodedPathSegments(seg.trimStart('/')) else b.addPathSegment(seg)
        }
        return b.build()
    }

    fun setUserUriFromValue(value: String) { resolve(value)?.let { userUri = it } }
    fun setUserLocationFromValue(value: String) { resolve(value)?.let { userLocation = it } }

    /**
     * Accepts an absolute URL, an absolute path, or a bare path — and always lands the result
     * **under the API base**, because that is the only place the ARR rule lives.
     *
     * GMU hands back `Location: /users/EXAMPLEuserUri000000A12` (captured live). Read as an
     * RFC-3986 absolute path that means `https://mobileprint.gmu.edu/users/…`, which IIS answers
     * with **404**; rooted under `/PharosAPI` instead it is 200. Verified side by side against
     * production, so this deliberately does *not* do plain reference resolution:
     *
     * ```
     * 200  https://mobileprint.gmu.edu/PharosAPI/users/EXAMPLEuserUri000000A12
     * 404  https://mobileprint.gmu.edu/users/EXAMPLEuserUri000000A12
     * ```
     *
     * An absolute URL on the *internal* origin (`MPSROSMOBP.mesa.gmu.edu`, see
     * `X-PHAROS-PRINT-CENTER-URI`) is re-rooted here too: the clone never talks to a hostname the
     * user never typed, which also keeps a single certificate and a single cookie scope.
     */
    fun resolve(value: String?): HttpUrl? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var path = v
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) {
            val absolute = runCatching { v.toHttpUrl() }.getOrNull() ?: return null
            if (absolute.host.equals(root.host, ignoreCase = true)) return absolute
            path = absolute.encodedPath
        }
        val base = root.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
        val bare = path.substringBefore('?').trimStart('/')
        val anchored = when {
            bare.isEmpty() -> base
            base.length > 1 && bare.startsWith(base.trimStart('/'), true) -> "/" + bare
            else -> base + bare
        }
        val query = path.substringAfter('?', "")
        return runCatching {
            root.newBuilder().encodedPath(anchored)
                .apply { if (query.isNotEmpty()) query(query) }
                .build()
        }.getOrNull()
    }

    fun forgetSession() {
        userUri = null
        userLocation = null
    }

    companion object {
        /**
         * Accepts whatever the user typed: `mobileprint.gmu.edu`, `https://host:8443`,
         * `https://host/PharosAPI`, or a pasted `/myprintcenter` URL from a help page.
         */
        fun parse(input: String): PharosTarget? {
            var raw = input.trim().removeSuffix("/")
            if (raw.isEmpty()) return null
            if (!raw.contains("://")) raw = "https://$raw"
            val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return null
            val path = url.encodedPath.trim('/')
            val apiPath = when {
                path.equals("PharosAPI", true) -> "PharosAPI"
                path.endsWith("/PharosAPI", true) -> "PharosAPI"
                path.isBlank() -> "PharosAPI"
                else -> "PharosAPI"   // /myprintcenter and friends all sit beside /PharosAPI
            }
            return PharosTarget(url.newBuilder().encodedPath("/$apiPath").query(null).fragment(null).build())
        }
    }
}

/**
 * Credentials, and the header they become.
 *
 * Pharos' logon filter wants `PHAROS-USER base64(urlEncode(user) + ":" + urlEncode(pass))`.
 * The URL-encoding happens *before* base64 (the stock app did it, GMU's bundle does it), so a
 * password containing `:` or `&` survives — a plain `Basic` header of the raw pair does not
 * round-trip through that filter on every deployment.
 */
data class Credentials(
    val username: String,
    val password: String,
    val rememberMe: Boolean = false,
) {
    val headerValue: String
        get() = "PHAROS-USER " + Base64.getEncoder()
            .encodeToString((pharosEncode(username) + ":" + pharosEncode(password)).toByteArray(Charsets.UTF_8))

    companion object {
        fun pharosEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/**
 * A document to upload, held as a *re-openable* handle rather than bytes.
 *
 * `content://` URIs from the share sheet can be read more than once, which is what makes a retry
 * after a transient failure possible without asking the user to pick the file again — the stock
 * app lost the pending upload on failure (`PendingFileUpload = null` in several error paths).
 */
class UploadSource(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    private val opener: () -> ParcelFileDescriptor,
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    fun toRequestBody(onProgress: (Float) -> Unit): RequestBody = object : RequestBody() {
        override fun contentType() = mimeType.toMediaType()

        override fun contentLength(): Long = if (sizeBytes > 0) sizeBytes else -1L

        // Re-openable, so OkHttp may retry. The opener is only called from OkHttp's writer
        // thread, which is also the only place a progress callback is allowed to fire.
        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            val total = if (sizeBytes > 0) sizeBytes else -1L
            var written = 0L
            var lastReport = 0L
            opener().use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        written += n
                        if (total > 0 && written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
        }
    }
}
