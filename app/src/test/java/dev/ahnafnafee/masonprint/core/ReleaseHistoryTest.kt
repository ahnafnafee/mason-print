package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseHistoryTest {
    private fun receipt(id: String = "one", time: Long = 10) = ReleaseRecord(id, "/printjobs/a", "Assignment.pdf",
        "/devices/1", "Library printer", time, accepted = true)
    private fun job(state: String) = PrintJob.from("""{"Location":"/printjobs/a","PrintState":"$state"}""".toJsonObject())

    @Test fun `missing server record never means printed`() {
        val record = receipt()
        assertEquals(listOf(record), updateReleaseHistory(listOf(record), emptyList(), 50))
        assertEquals("Sent · completion unknown", record.status)
        assertEquals("Release unconfirmed", record.copy(accepted = false).status)
    }
    @Test fun `only latest attempt receives updates and terminal evidence is retained`() {
        val old = receipt()
        val recent = receipt("two", 20)
        val next = updateReleaseHistory(listOf(recent, old), listOf(job("Completed")), 30)
        assertEquals("Completed", next[0].serverState)
        assertEquals(old, next[1])
        assertEquals(next, updateReleaseHistory(next, listOf(job("Queued")), 40))
        assertTrue(receipt().copy(serverState = "Queued").status.startsWith("Still waiting to be released"))
    }
    @Test fun `history is bounded ordered and replaces an attempt acknowledgement`() {
        val old = (1..110).map { receipt("$it", it.toLong()) }
        val result = mergeReleaseHistory(old, listOf(receipt("110", 110).copy(accepted = false)))
        assertEquals(100, result.size)
        assertEquals("110", result.first().id)
        assertFalse(result.first().accepted)
        assertEquals("11", result.last().id)
    }
    @Test fun `receipt storage keys separate servers and accounts`() {
        assertNotEquals(printActivityKey("https://one", "student"), printActivityKey("https://two", "student"))
        assertNotEquals(printActivityKey("https://one", "student"), printActivityKey("https://one", "other"))
        assertEquals(printActivityKey("https://one", "student"), printActivityKey("https://one", "student"))
    }
}
