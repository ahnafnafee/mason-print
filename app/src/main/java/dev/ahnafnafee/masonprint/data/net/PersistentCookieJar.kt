package dev.ahnafnafee.masonprint.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Encrypted persistence seam; each entry carries the cookie's origin and wire value. */
interface CookieSnapshotStore {
    suspend fun load(): List<String>
    suspend fun save(entries: List<String>)
    suspend fun clear()
}

/** RFC domain/path matching, with serialized persistence so sign-out cannot resurrect a snapshot. */
class PersistentCookieJar(
    private val store: CookieSnapshotStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : CookieJar {
    private val lock = Any()
    private val cookies = linkedMapOf<Identity, Cookie>()
    private val persistence = Mutex()
    private var restored = false

    private data class Identity(val name: String, val domain: String, val path: String)
    private fun Cookie.identity() = Identity(name, domain, path)

    suspend fun restore() = persistence.withLock {
        if (synchronized(lock) { restored }) return@withLock
        val entries = try { store.load() } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
        synchronized(lock) {
            if (!restored) {
                for (entry in entries) {
                    val separator = entry.indexOf('\n')
                    if (separator <= 0) continue
                    val origin = entry.substring(0, separator).toHttpUrlOrNull() ?: continue
                    val cookie = Cookie.parse(origin, entry.substring(separator + 1)) ?: continue
                    if (cookie.expiresAt > System.currentTimeMillis()) {
                        cookies.putIfAbsent(cookie.identity(), cookie)
                    }
                }
                restored = true
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (cookie in cookies) {
                this.cookies.remove(cookie.identity())
                if (cookie.expiresAt > System.currentTimeMillis()) this.cookies[cookie.identity()] = cookie
            }
        }
        scope.launch { persist() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        cookies.values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        cookies.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    suspend fun clearAll() {
        synchronized(lock) {
            cookies.clear()
            restored = true
        }
        persistence.withLock { store.clear() }
    }

    // Read the current jar inside the persistence lock, never a snapshot queued before sign-out.
    internal suspend fun persist() = persistence.withLock {
        val entries = synchronized(lock) {
            cookies.values.filter { it.expiresAt > System.currentTimeMillis() }.map { cookie ->
                val origin = HttpUrl.Builder().scheme(if (cookie.secure) "https" else "http")
                    .host(cookie.domain).encodedPath(cookie.path).build()
                "$origin\n$cookie"
            }
        }
        try { store.save(entries) } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A failed disk write must not turn a valid live session into a crash.
        }
    }
}
