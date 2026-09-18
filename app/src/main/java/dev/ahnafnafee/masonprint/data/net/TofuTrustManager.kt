package dev.ahnafnafee.masonprint.data.net

import java.security.cert.CertificateException
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use, layered on top of the platform trust store rather than replacing it.
 *
 * The stock app's WebView did `OnReceivedSslError → Proceed()`, which silently accepted anything
 * (docs/FINDINGS.md §10). That is wrong in both directions: it makes a campus-CA certificate
 * invisible *and* makes a hostile intercept invisible. This manager keeps normal validation and,
 * only when normal validation fails, asks whether *this exact leaf certificate* for *this exact
 * host* was already approved by the user.
 *
 * Campus print servers are routinely served from internally-issued CAs that no Android device
 * ships with (CLONE-PLAN risk R7), so a hard pin would lock students out; a prompt that names the
 * fingerprint and persists the decision keeps the security property without the lockout.
 *
 * The decision list is readable and revocable in the Account screen — the stock app never told
 * the user anything about the certificate it had accepted.
 */
class TofuTrustManager(
    private val delegate: X509TrustManager,
    private val trusts: TlsTrustStore,
) : X509TrustManager {

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
            return
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            val host = trusts.currentHost()
            val fp = leaf.fingerprint()
            if (host != null && trusts.isTrusted(host, fp)) return
            throw UntrustedCertificateException(
                host = host ?: "unknown host",
                fingerprint = fp,
                subject = leaf.subjectX500Principal.name,
                cause = e,
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    companion object {
        /** SHA-256 of the DER encoding — the same notation `adb` and browsers show. */
        fun X509Certificate.fingerprint(): String = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: CertificateEncodingException) {
            "unavailable"
        }
    }
}

/** Thrown upward so the UI can show the fingerprint and offer to trust it. */
class UntrustedCertificateException(
    val host: String,
    val fingerprint: String,
    val subject: String?,
    cause: Throwable?,
) : CertificateException("Untrusted certificate for $host ($fingerprint)", cause)

/** What the user agreed to. Persisted; shown with the host so a swap is visible. */
data class TrustedCertificate(
    val host: String,
    val fingerprint: String,
    val subject: String?,
    val addedAtEpochMs: Long,
) {
    val shortFingerprint: String get() = fingerprint.take(23) + "…"
}

/**
 * Storage seam for the TOFU list. Synchronous reads because the JSSE trust manager is called
 * from inside the TLS handshake and has no coroutine context; implementations keep an in-memory
 * copy and flush asynchronously.
 */
interface TlsTrustStore {
    fun isTrusted(host: String, fingerprint: String): Boolean
    fun currentHost(): String?
    fun setHost(host: String)
    fun all(): List<TrustedCertificate>
    fun trust(cert: TrustedCertificate)
    fun revoke(host: String, fingerprint: String)
    fun clear()
}

/** In-memory, write-through implementation of [TlsTrustStore]. */
class InMemoryTlsTrustStore(private val sink: (List<TrustedCertificate>) -> Unit = {}) : TlsTrustStore {
    private val certs = java.util.concurrent.CopyOnWriteArrayList<TrustedCertificate>()

    @Volatile private var host: String? = null

    fun load(list: List<TrustedCertificate>) {
        certs.clear()
        certs += list
    }

    override fun isTrusted(host: String, fingerprint: String): Boolean =
        certs.any { it.host.equals(host, true) && it.fingerprint.equals(fingerprint, true) }

    override fun currentHost(): String? = host
    override fun setHost(host: String) { this.host = host }
    override fun all(): List<TrustedCertificate> = certs.toList()

    override fun trust(cert: TrustedCertificate) {
        if (!isTrusted(cert.host, cert.fingerprint)) {
            certs.add(cert)
            sink(all())
        }
    }

    override fun revoke(host: String, fingerprint: String) {
        certs.removeAll { it.host.equals(host, true) && it.fingerprint.equals(fingerprint, true) }
        sink(all())
    }

    override fun clear() {
        certs.clear()
        sink(all())
    }
}
