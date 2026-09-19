package dev.ahnafnafee.masonprint.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PersistentCookieJarTest {
    private class Store : CookieSnapshotStore {
        var entries = emptyList<String>()
        override suspend fun load() = entries
        override suspend fun save(entries: List<String>) { this.entries = entries }
        override suspend fun clear() { entries = emptyList() }
    }

    @Test fun `domain cookies reach matching subdomains but host cookies do not`() = runBlocking {
        val jar = PersistentCookieJar(Store())
        jar.restore()
        val origin = "https://login.example.edu/".toHttpUrl()
        jar.saveFromResponse(origin, listOf(
            Cookie.Builder().name("session").value("shared").domain("example.edu").path("/").secure().build(),
            Cookie.parse(origin, "local=private; Path=/; Secure")!!,
        ))
        assertEquals(listOf("session"), jar.loadForRequest("https://print.example.edu/".toHttpUrl()).map { it.name })
        assertTrue(jar.loadForRequest("https://other.edu/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://print.example.edu/".toHttpUrl()).isEmpty())
    }

    @Test fun `same name and path on different domains coexist and expire independently`() = runBlocking {
        val jar = PersistentCookieJar(Store())
        jar.restore()
        val url = "https://print.example.edu/".toHttpUrl()
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().name("session").value("parent").domain("example.edu").path("/").build(),
            Cookie.parse(url, "session=host; Path=/")!!,
        ))
        assertEquals(setOf("parent", "host"), jar.loadForRequest(url).map { it.value }.toSet())
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=gone; Path=/; Max-Age=0")!!))
        assertEquals(listOf("parent"), jar.loadForRequest(url).map { it.value })
    }
    @Test fun `a save queued before sign-out cannot restore the old session`() = runBlocking {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val store = Store()
        val jar = PersistentCookieJar(store, CoroutineScope(dispatcher))
        jar.restore()
        val url = "https://print.example.edu/PharosAPI".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "token=secret; Path=/PharosAPI")!!))
        jar.clearAll()
        while (tasks.isNotEmpty()) tasks.remove().run()
        assertTrue(store.entries.isEmpty())
        assertTrue(jar.loadForRequest(url).isEmpty())
        val restored = PersistentCookieJar(store, CoroutineScope(dispatcher))
        restored.restore()
        assertTrue(restored.loadForRequest(url).isEmpty())
    }

    @Test fun `restore is idempotent and prioritizes the most specific path`() = runBlocking {
        val store = Store().apply {
            entries = listOf("https://print.example.edu/\ntoken=root; Path=/",
                "https://print.example.edu/PharosAPI\ntoken=api; Path=/PharosAPI")
        }
        val jar = PersistentCookieJar(store)
        jar.restore()
        jar.restore()
        assertEquals(listOf("api", "root"), jar.loadForRequest("https://print.example.edu/PharosAPI/jobs".toHttpUrl()).map { it.value })
    }

}
