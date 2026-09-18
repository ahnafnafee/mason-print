package dev.ahnafnafee.masonprint.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline snapshot, pinned against the same captured GMU bodies the parser tests use.
 *
 * The contract that matters: what went in as *wire bodies* comes back out parseable by the same
 * parsers a live response goes through, so a cold start with no network renders a real queue and a
 * real balance, not an empty state with a spinner's leftovers. Plus the two failure shapes a disk
 * cache can produce — a truncated file and a cleared one — which must read as "no snapshot", never
 * as a crash or as an empty-but-valid queue.
 */
class SnapshotCacheTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) { "missing fixture $name" }
            .readBytes().decodeToString()

    /** A cache over its own throwaway file, so the tests never touch a real data dir. */
    private fun newCache(): Pair<SnapshotCache, File> {
        val file = File.createTempFile("queue-snapshot", ".json")
        file.delete()
        return SnapshotCache(file) to file
    }

    private fun gmuSnapshot(): SnapshotCache.Snapshot = SnapshotCache.Snapshot(
        savedAt = 1_758_000_000_000L,
        host = "mobileprint.gmu.edu",
        apiVersion = "4.11.24.1",
        jobsBody = fixture("jobs-page-with-costed-job.json"),
        userBody = fixture("logon-success.json"),
        // The live settings body arrives with a UTF-8 BOM; the cache stores what `encode()` wrote,
        // so strip it the same way before feeding the fixture through.
        settingsBody = fixture("settings-gmu.json").trimStart('\uFEFF'),
    )

    @Test
    fun roundTripRestoresJobsUserAndCapabilities() {
        val (cache, _) = newCache()
        cache.save(gmuSnapshot())

        val restored = requireNotNull(cache.load())
        assertEquals("mobileprint.gmu.edu", restored.snapshot.host)
        assertEquals("4.11.24.1", restored.snapshot.apiVersion)
        assertEquals(1_758_000_000_000L, restored.snapshot.savedAt)

        val page = requireNotNull(restored.jobsPage)
        assertTrue("the captured queue page has a job on it", page.items.isNotEmpty())
        assertNotNull(restored.user)
        val caps = requireNotNull(restored.capabilities)
        assertNotNull("money formats survive the round trip", caps.formats)
    }

    @Test
    fun truncatedFileIsNoSnapshotNotACrash() {
        val (cache, file) = newCache()
        file.writeText("""{"savedAt":1758000000000,"host":"mobileprint.gmu.edu","jobsBody":"{"I""")
        assertNull(cache.load())
    }

    @Test
    fun clearRemovesTheSnapshot() {
        val (cache, _) = newCache()
        cache.save(gmuSnapshot())
        cache.clear()
        assertNull(cache.load())
    }

    @Test
    fun absentFileIsNoSnapshot() {
        val (cache, _) = newCache()
        assertNull(cache.load())
    }
}
