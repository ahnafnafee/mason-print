package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.BuildConfig
import dev.ahnafnafee.masonprint.core.MpLog
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/** Everything TLS/transport-related the rest of the app is allowed to touch. */
class HttpComponents(
    val api: OkHttpClient,
    val upload: OkHttpClient,
    val sslSocketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
    val tofu: TofuTrustManager,
    val cookieJar: PersistentCookieJar,
)

object HttpFactory {

    /**
     * The server logs this string, and the service desk is the person who will read it when a
     * student's upload mysteriously fails. The stock app sent the Xamarin default
     * (`Xamarin.Android` + Mono version), which told Pharos support nothing about the app build.
     */
    val userAgent: String =
        "MasonPrint/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"

    /**
     * Plain REST timeouts are 30 s end to end. Uploads get a 10-minute write budget because a
     * 45 MB scan on campus Wi-Fi genuinely takes that long, and the stock app's single 30 s
     * timeout turned those into "upload failed" for no reason (CLONE-PLAN §4.4, U4).
     */
    fun build(
        cookieJar: PersistentCookieJar,
        trusts: TlsTrustStore,
        onApiVersion: (String?) -> Unit,
        logging: Boolean = BuildConfig.DEBUG,
    ): HttpComponents {
        val platform = platformTrustManager()
        val tofu = TofuTrustManager(platform, trusts)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tofu), SecureRandom())
        }

        val base = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(sslContext.socketFactory, platform)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                // The handshake happens inside `chain.proceed`, so the host has to be published
                // before it — TofuTrustManager needs it to key the approval list.
                trusts.setHost(chain.request().url.host)
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json, text/plain, */*")
                        // The server localises `UserMessage` from this; pinning it means the text
                        // we quote back to the user is text the service desk can also reproduce.
                        .header("Accept-Language", "en-US,en;q=0.8")
                        .build()
                )
            }
            .addNetworkInterceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                response.header("X-PHAROS-API-VERSION")?.let { onApiVersion(it) }
                response
            })

        if (logging) {
            // Body logging is deliberately HEADERS-only by default: an upload body is 45 MB and
            // the logcat ring buffer is the app's only diagnostic channel (MpLog).
            //
            // Redacted, because `Authorization: PHAROS-USER …` is a *reversible* Base64 of
            // `user:password` — logging it verbatim writes the student's real Mason password into
            // a buffer any adb client can read. That is not hypothetical: while driving the real
            // account through this very screen, the password showed up in `adb logcat -d` and had
            // to be scrubbed out of the captured evidence. (The vendor app logs nothing at all —
            // `DebugTrace.LogMessage` is an empty method — so at least its users keep that.)
            val li = HttpLoggingInterceptor(
                object : HttpLoggingInterceptor.Logger {
                    override fun log(message: String) = MpLog.debug("http", redact(message))
                },
            ).apply { level = HttpLoggingInterceptor.Level.HEADERS }
            base.addInterceptor(li)
        }

        val api = base.build()
        val upload = api.newBuilder()
            .writeTimeout(600, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        return HttpComponents(api, upload, sslContext.socketFactory, platform, tofu, cookieJar)
    }

    private val secretHeader = Regex(
        "^\\s*(Authorization|X-Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*:\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Keeps the header name and a six-character fingerprint — enough to tell "the credential was
     * attached" from "it wasn't", which is the only distinction that ever matters in a bug report —
     * and throws the value away.
     */
    internal fun redact(line: String): String {
        val m = secretHeader.matchEntire(line) ?: return line
        val value = m.groupValues[2]
        val name = m.groupValues[1]
        val fingerprint = value.takeWhile { !it.isWhitespace() && it != ';' }.take(6)
        return if (fingerprint.isEmpty()) "$name: (empty)" else "$name: $fingerprint… (${value.length} B hidden)"
    }

    /**
     * The platform trust manager, exactly as a stock `SSLContext` would use it — one store, which
     * is what CLONE-PLAN §4.4 demands after the stock app shipped with two (Mono `btls` for the
     * API, the Android store for the WebView) and let them disagree.
     */
    fun platformTrustManager(): X509TrustManager {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
