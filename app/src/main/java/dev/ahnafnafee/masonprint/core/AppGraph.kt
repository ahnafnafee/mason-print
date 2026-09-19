package dev.ahnafnafee.masonprint.core

import android.app.Application
import android.content.Context
import dev.ahnafnafee.masonprint.BuildConfig
import dev.ahnafnafee.masonprint.MasonPrintApp
import dev.ahnafnafee.masonprint.data.net.PharosClient
import dev.ahnafnafee.masonprint.data.net.PharosTarget
import dev.ahnafnafee.masonprint.data.net.PersistentCookieJar
import dev.ahnafnafee.masonprint.data.prefs.AppPrefs
import dev.ahnafnafee.masonprint.data.prefs.PrefsTlsTrustStore
import dev.ahnafnafee.masonprint.data.net.HttpComponents
import dev.ahnafnafee.masonprint.data.net.HttpFactory
import dev.ahnafnafee.masonprint.data.store.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The whole dependency graph, built by hand.
 *
 * CLONE-PLAN §5 named Hilt. It was dropped deliberately: the object graph is six nodes deep, all
 * of them constructed once, and every reason to want a DI container here — scoped lifetimes,
 * multibindings, a test override of the transport — is answered by a `by lazy` block and a
 * constructor. The same reasoning dropped Retrofit: this API's contract is a set of transport
 * quirks (see `PharosClient`), and an annotation layer over it hides exactly the things that need
 * to be visible.
 */
class AppGraph(val app: Application) {

    val prefs: AppPrefs by lazy { AppPrefs(app) }

    /** Cookies + credentials at rest. The stock app kept these in plaintext SharedPreferences. */
    val secrets: SecureStore by lazy { SecureStore(app) }

    /** Approved certificate fingerprints. Public so the TLS prompt and Diagnostics can write it. */
    val trusts: PrefsTlsTrustStore by lazy { PrefsTlsTrustStore(prefs) }

    val cookieJar: PersistentCookieJar by lazy { PersistentCookieJar(secrets) }

    val http: HttpComponents by lazy {
        HttpFactory.build(
            cookieJar = cookieJar,
            trusts = trusts,
            // Published from every response header, so the version is known before the first
            // authenticated call and can be shown in Diagnostics — the stock app only learned it
            // when a request failed.
            onApiVersion = { version -> if (version != null) prefs.lastApiVersion = version },
            logging = BuildConfig.DEBUG,
        )
    }

    val api: PharosClient by lazy { PharosClient(http.api, http.upload) }

    /**
     * The offline snapshot of the queue + balance + settings, written after every successful
     * refresh and read back on cold boot. Cleared with the session in [forgetSession].
     */
    val snapshot: SnapshotCache by lazy { SnapshotCache(app) }

    /** App-scoped work scope: uploads and refreshes outlive the screen that started them. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val session: Session by lazy { Session(this) }

    /** Sign-out. Wipes the session, the credential, and the cached queue; keeps the TLS approvals. */
    suspend fun forgetSession() {
        api.credentials = null
        target?.forgetSession()
        cookieJar.clearAll()
        secrets.forgetCredentialOnly()
        snapshot.clearAsync()
    }

    /** The server in use, or null until the user configures one. */
    @Volatile var target: PharosTarget? = null
        private set

    fun useTarget(target: PharosTarget) {
        this.target = target
        prefs.host = target.savedAddress
    }

    /** Rebuild everything that caches per-host state after the server changes. */
    fun resetForNewServer() {
        target = null
    }

    companion object {
        /** Reach the graph from anywhere that has a Context, without a DI framework. */
        fun of(context: Context): AppGraph = (context.applicationContext as MasonPrintApp).graph
    }
}
