package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.PharosError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Every way a Pharos request can fail, in the terms the UI actually needs.
 *
 * The stock app collapsed all of these into one alert (or into the WebView's `Err_*` string
 * sniffing), which is why its most common user complaint is "it said it uploaded and there is
 * nothing in the queue". Here each case carries what the server said, so the UI can quote the
 * server instead of guessing.
 */
sealed interface PharosFailure {
    val retryable: Boolean get() = false

    /** 401/403 on an authenticated call — the session cookie died or the password changed. */
    data class Unauthenticated(val error: PharosError) : PharosFailure

    /** 403 from the *upload* path specifically: `PrintCenter.Web Upload = Deny`, or the account
     *  lacks `Printing.WebUpload`. Different remedy from a dead session, so a different type. */
    data class UploadDenied(val error: PharosError, val why: String?) : PharosFailure

    /**
     * 413 — carries the server's own limit so the dialog can say "over the 50 MB limit".
     *
     * [locallyGated] marks the same conclusion reached *before* the upload: this app measured the
     * file against the limit the server publishes and held it back. Same remedy, different author,
     * and the UI must not put this app's arithmetic inside "The server said:".
     */
    data class TooLarge(
        val limitBytes: Long?,
        val serverText: String?,
        val locallyGated: Boolean = false,
    ) : PharosFailure

    /** 415 — the extension is not in the deployment's allowlist. The 58-entry client list in the
     *  stock app is only a hint; this is the authoritative answer. */
    data class UnsupportedType(val extension: String?, val serverText: String?) : PharosFailure

    /** Anything else with a status attached. */
    data class Server(val error: PharosError) : PharosFailure {
        override val retryable: Boolean get() = (error.status ?: 0) >= 500
    }

    /** Route absent. Note: Pharos answers **401, not 404**, for unknown paths behind the auth
     *  filter, so this is only concluded when the response body/`ErrorCode` says so — never from
     *  the status alone. */
    data class NotFound(val path: String) : PharosFailure

    /** No usable network at all. The app keeps showing the cached snapshot when it can. */
    data object Offline : PharosFailure {
        override val retryable get() = true
    }

    /** Distinguishes "the server is thinking" from "the radio is off" — different advice. */
    data class Timeout(val writePhase: Boolean) : PharosFailure {
        override val retryable get() = true
    }

    /** Certificate we have not been shown before. The UI asks about *this* fingerprint. */
    data class TlsNotTrusted(val host: String, val fingerprint: String, val subject: String?) : PharosFailure

    /** The host is not a Pharos server, or not reachable as one. */
    data class NotAPharosServer(val detail: String?) : PharosFailure

    data class Unknown(val throwable: Throwable) : PharosFailure
}

/** Result of a single API interaction. Raw body kept where the caller may need it. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val status: Int, val body: String? = null) : ApiResult<T>
    data class Err(val failure: PharosFailure, val body: String? = null) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value), status, body)
    is ApiResult.Err -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

fun <T> ApiResult<T>.failureOrNull(): PharosFailure? = (this as? ApiResult.Err)?.failure

val <T> ApiResult<T>.isOk: Boolean get() = this is ApiResult.Ok

/**
 * Turn a transport-level exception into a [PharosFailure].
 *
 * `SocketTimeoutException` on the write half of an upload is the "600 MB scan is still going"
 * case; on the read half it is a stuck server. The stock app used one 30 s timeout for both and
 * reported either as a generic failure.
 *
 * A handshake failure is unwrapped to find [UntrustedCertificateException], because JSSE always
 * buries the real reason inside `SSLHandshakeException` — and "we have never seen this
 * certificate" needs a different conversation with the user than "the network is down".
 */
fun IOException.toPharosFailure(writePhase: Boolean): PharosFailure {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is UntrustedCertificateException) {
            return PharosFailure.TlsNotTrusted(cause.host, cause.fingerprint, cause.subject)
        }
        cause = cause.cause
    }
    return when (this) {
        is SocketTimeoutException -> PharosFailure.Timeout(writePhase)
        is UnknownHostException, is ConnectException, is SocketException -> PharosFailure.Offline
        is SSLException -> PharosFailure.TlsNotTrusted("", "unavailable", localizedMessage)
        else -> PharosFailure.Unknown(this)
    }
}
