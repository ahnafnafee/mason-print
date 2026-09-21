package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.core.ReleaseOutcome
import dev.ahnafnafee.masonprint.core.RefusedJob
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artifact a student copies to the phone for the service desk.
 *
 * What the desk actually asks, in the order they ask it: which printer, how many moved, what the
 * server said about the ones that did not, and the balance before and after. The reason lines are
 * the server's sentences byte-for-byte — a paraphrase over the phone is how "expired" becomes
 * "deleted" and the desk chases the wrong thing.
 */
class ReleaseReportTest {

    private fun nameFromLocation(loc: String) = loc.substringAfterLast('/')

    private val partial = ReleaseOutcome(
        printer = "Fusion 4570 (Slagle 2207)",
        fundingIntent = "My own balance",
        requested = 2,
        released = listOf("/PharosAPI/users/1/printjobs/91"),
        refused = listOf(
            RefusedJob(
                "scanned-receipt.pdf",
                "This document expired and is no longer held.",
                405,
            ),
        ),
        balanceBefore = "$12.00",
        balanceAfter = "$11.50",
    )

    @Test
    fun `a partial release carries the counts, the balances, and the server's sentence`() {
        val text = releaseReportText(partial, "https://mobileprint.gmu.edu/PharosAPI", "4.11.24.1", ::nameFromLocation)
        assertTrue(text, text.contains("Server: https://mobileprint.gmu.edu/PharosAPI (API 4.11.24.1)"))
        assertTrue(text, text.contains("Printer: Fusion 4570 (Slagle 2207)"))
        assertTrue(text, text.contains("Requested 2 · released 1 · refused 1"))
        assertTrue(text, text.contains("Balance: \$12.00 → \$11.50"))
        // Byte-for-byte, not summarised.
        assertTrue(text, text.contains("- scanned-receipt.pdf: This document expired and is no longer held."))
        // The released job is named, not identified by its URI — the desk has no queue to look it up in.
        assertTrue(text, text.contains("- 91"))
    }

    @Test
    fun `a refusal with no reason says so instead of inventing one`() {
        val noReason = partial.copy(
            refused = listOf(RefusedJob("thesis-ch3.pdf", null, null)),
        )
        val text = releaseReportText(noReason, "h", null, ::nameFromLocation)
        assertTrue(text, text.contains("- thesis-ch3.pdf: no reason given by the server"))
        // "Asked to charge", never "Charged to" — a release request is not a receipt (§7.1 #4).
        assertTrue(text, text.contains("Asked to charge: My own balance"))
        assertFalse(text, text.contains("Charged to:"))
    }

    @Test
    fun `no answer at all is labelled as that, with the transport headline quoted`() {
        val offline = partial.copy(
            released = emptyList(),
            refused = (0 until 2).map { RefusedJob("job-$it.pdf", null, null) },
            balanceBefore = null,
            balanceAfter = null,
            transport = PharosFailure.Offline,
        )
        val text = releaseReportText(offline, "h", null, ::nameFromLocation)
        assertTrue(text, text.contains("No answer from the server: Nothing answered at that address"))
        assertTrue(text, text.contains("Balance: — → —"))
        assertTrue(text, text.contains("unconfirmed 2"))
        assertFalse(text, text.contains("Refused:"))
        assertFalse(text, text.contains("Not charged"))
    }

    @Test
    fun `a grant-coded release records it as an intent`() {
        val grant = partial.copy(fundingIntent = "10111-M17041")
        val text = releaseReportText(grant, "h", null, ::nameFromLocation)
        assertTrue(text, text.contains("Asked to charge: 10111-M17041"))
    }
}
