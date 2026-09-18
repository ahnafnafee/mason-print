package dev.ahnafnafee.masonprint.data.net

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Persistence seam for the cookie jar. Values are opaque strings (`"<url>\n<cookie>"`) so the
 * implementation can live behind EncryptedSharedPreferences without this layer knowing.
 */
interface CookieSnapshotStore {
    suspend fun load(): List<String>
    suspend fun save(entries: List<String>)
    suspend fun clear()
}

/**
 * A cookie jar that survives process death, because Pharos' session *is* its cookies.
 *
 * This is the fix for U3 in docs/FINDINGS.md: the stock app kept `X-PHAROS-USER-URI` and
 * `X-PHAROS-USER-TOKEN` in a memory-only `CookieCollection` (`UserAccountSettingManager`), while
 * the username/password sat in plaintext SharedPreferences. So it could "restore" a session by
 * re-sending credentials every launch — and if the server had rotated or revoked the token, the
 * user saw a generic error instead of a sign-in prompt.
 *
 * The cookies Pharos sets are `SameSite=Strict` and scoped to the API path, so the jar is keyed
 * by host+path exactly the way OkHttp's default in-memory jar does; the only difference is that
 * the snapshot is written through to storage and rehydrated at startup.
 */
class PersistentCookieJar(private val store: CookieSnapshotStore) : CookieJar {

    private val jar = ConcurrentHashMap<String, MutableList<Cookie>>()

    /** True once [restore] has run, so a save racing with startup cannot wipe the jar. */
    @Volatile private var restored = false

    suspend fun restore() {
        if (restored) return
        val entries = runCatching { store.load() }.getOrNull().orEmpty()
        for (entry in entries) {
            val nl = entry.indexOf('\n')
            if (nl <= 0) continue
            val url = runCatching { entry.substring(0, nl).toHttpUrl() }.getOrNull() ?: continue
            val cookie = runCatching { Cookie.parse(url, entry.substring(nl + 1)) }.getOrNull() ?: continue
            if (cookie.expiresAt < System.currentTimeMillis()) continue
            jar.getOrPut(key(url, cookie)) { mutableListOf() }.add(cookie)
        }
        restored = true
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        val kept = cookies.filter { !it.persistent || it.expiresAt > now }
        val hostList = jar.getOrPut(url.host) { mutableListOf() }
        synchronized(hostList) {
            hostList.removeAll { existing -> cookies.any { it.name == existing.name && it.path == existing.path } }
            hostList += kept
        }
        snapshotAsync()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Cookie>()
        jar.forEach { (host, list) ->
            if (host != url.host) return@forEach
            synchronized(list) {
                list.removeAll { it.expiresAt < now }
                candidates += list.filter { it.matches(url) }
            }
        }
        // Longest path first, which is what RFC 6265 §5.4 says browsers do and what Pharos'
        // `/PharosAPI`-scoped cookies depend on when a broader cookie also exists.
        return candidates.sortedByDescending { it.path.length }
    }

    suspend fun clearAll() {
        jar.clear()
        runCatching { store.clear() }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun snapshotAsync() {
        val entries = mutableListOf<String>()
        jar.forEach { (_, list) ->
            synchronized(list) {
                list.forEach { c ->
                    val url = urlFor(c) ?: return@forEach
                    entries += url.toString() + "\n" + c.toString()
                }
            }
        }
        scope.launch { runCatching { store.save(entries) } }
    }

    /**
     * OkHttp's `Cookie.toString()` round-trips through `Cookie.parse` only if the supplied URL
     * agrees with the cookie's domain/path, so the snapshot has to carry a URL that does.
     */
    private fun urlFor(cookie: Cookie): HttpUrl? = runCatching {
        val host = if (cookie.hostOnly) cookie.domain else cookie.domain.removePrefix(".")
        val scheme = if (cookie.secure) "https" else "http"
        HttpUrl.Builder().scheme(scheme).host(host)
            .addEncodedPathSegments(cookie.path.trimStart('/'))
            .build()
    }.getOrNull()

    private fun key(url: HttpUrl, cookie: Cookie) = url.host
}
