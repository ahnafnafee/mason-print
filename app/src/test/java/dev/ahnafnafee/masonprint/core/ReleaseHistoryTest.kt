package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.PharosJson
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.parsePharosTime
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class ReleaseHistoryTest {
    private val time = Instant.parse("2026-09-22T06:11:47Z").toEpochMilli()
    private fun receipt(id: String = "one", at: Long = time) = ReleaseRecord(id, "/printjobs/example", "Assignment.pdf",
        "/devices/1", "Library printer", at, accepted = true)
    private fun transaction(id: String = "101", date: String = "2026-09-22T06:11:48Z") = Transaction.from(
        """{"Identifier":"$id","TransactionType":"Print","JobName":"Assignment.pdf&#10;1 page",
            "Time":"$date","Amount":"-0.04","ChargedTo":"Cost Center: EXAMPLE-101",
            "Device":"Library printer","Pages":1}""".toJsonObject())

    @Test fun `both ledger time formats match charges without claiming physical output`() {
        for (date in listOf("2026-09-22T06:11:48Z", "09/22/2026 06:11:48")) {
            val txn = transaction(date = date)
            val record = matchReleaseCharges(listOf(receipt()), listOf(txn), time + 2000).single()
            assertEquals("Print charge found", record.status)
            assertEquals("101", record.charge?.transactionId)
            assertEquals(-0.04, record.charge!!.amount!!, 0.0)
            assertEquals("Cost Center: EXAMPLE-101", record.charge.chargedTo)
            assertEquals(1L, record.charge.pages)
            assertTrue(txn.detail.contains("Cost Center: EXAMPLE-101"))
        }
    }
    @Test fun `missing or unsuitable ledger rows leave release accepted`() {
        val txn = transaction()
        for (rows in listOf(emptyList(), listOf(txn.copy(identifier = null)), listOf(txn.copy(time = "invalid")),
            listOf(txn.copy(transactionType = "Credit")), listOf(txn.copy(jobName = "Different.pdf")),
            listOf(txn.copy(device = "Another printer")), listOf(txn.copy(time = "2026-09-22T06:14:00Z")),
            listOf(txn.copy(time = "2026-09-22T06:11:00Z")))) {
            val result = matchReleaseCharges(listOf(receipt()), rows, time + 3000).single()
            assertNull(result.charge)
            assertEquals("Release accepted", result.status)
            assertEquals(time + 3000, result.billingCheckedAt)
        }
        assertEquals("Release unconfirmed", receipt().copy(accepted = false).status)
    }
    @Test fun `repeated names or multiple charges remain ambiguous`() {
        assertTrue(matchReleaseCharges(listOf(receipt(), receipt("two", time + 1000)), listOf(transaction()), time)
            .all { it.charge == null })
        assertNull(matchReleaseCharges(listOf(receipt()), listOf(transaction(), transaction("102")), time).single().charge)
    }
    @Test fun `existing matches remain and cannot lend their charge to another receipt`() {
        val matched = matchReleaseCharges(listOf(receipt()), listOf(transaction()), time).single()
        val next = matchReleaseCharges(listOf(matched, receipt("two", time + 1000)), listOf(transaction(), transaction("102")), time + 2000)
        assertEquals(matched.charge, next[0].charge)
        assertNull(next[1].charge)
        assertEquals(matched.charge, matchReleaseCharges(listOf(matched), emptyList(), time).single().charge)
    }
    @Test fun `description fallback and printer URI match when published`() {
        val txn = transaction().copy(jobName = null, description = "Assignment.pdf\n1 page", device = null, printer = "/devices/1")
        assertNotNull(matchReleaseCharges(listOf(receipt()), listOf(txn), time).single().charge)
    }
    @Test fun `old cached queue states are discarded`() {
        val record = PharosJson.decodeFromString<ReleaseRecord>("""{"id":"one","jobLocation":"/printjobs/example",
            "name":"Assignment.pdf","printerLocation":"/devices/1","printerName":"Library printer",
            "requestedAt":1,"accepted":true,"serverState":"Printed","checkedAt":2}""")
        assertEquals("Release accepted", record.status)
        assertNull(record.charge)
        assertNull(record.billingCheckedAt)
    }
    @Test fun `only latest receipt can attempt cancellation and accepted cancellation is final`() {
        val old = receipt()
        val recent = receipt("two", time + 1000)
        assertFalse(canAttemptCancellation(listOf(recent, old), old))
        assertTrue(canAttemptCancellation(listOf(recent, old), recent))
        val cancelled = recent.copy(cancellation = ReleaseCancellation(CancelVerdict.Accepted, "Accepted", time))
        assertFalse(canAttemptCancellation(listOf(cancelled), cancelled))
        assertEquals("Cancellation accepted", cancelled.status)
    }
    @Test fun `time parser handles offsets legacy and invalid dates independently of phone timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            for (date in listOf("2026-09-22T06:11:47Z", "2026-09-22T02:11:47-04:00", "2026-09-22T06:11:47", "09/22/2026 06:11:47")) {
                assertEquals(Instant.ofEpochMilli(time), parsePharosTime(date))
            }
            assertNull(parsePharosTime("02/30/2026 06:11:47"))
            assertNull(parsePharosTime("not a date"))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun `history is bounded ordered and replaces attempt acknowledgement`() {
        val old = (1..110).map { receipt("$it", it.toLong()) }
        val result = mergeReleaseHistory(old, listOf(receipt("110", 110).copy(accepted = false)))
        assertEquals(100, result.size)
        assertEquals("110", result.first().id)
        assertFalse(result.first().accepted)
        assertEquals("11", result.last().id)
    }
    @Test fun `storage separates server and account`() {
        assertNotEquals(printActivityKey("https://one", "student"), printActivityKey("https://two", "student"))
        assertNotEquals(printActivityKey("https://one", "student"), printActivityKey("https://one", "other"))
        assertEquals(printActivityKey("https://one", "student"), printActivityKey("https://one", "student"))
    }
}
