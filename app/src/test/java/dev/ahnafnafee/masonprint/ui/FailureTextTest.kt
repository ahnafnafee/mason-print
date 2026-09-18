package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.PharosError
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user reads when something goes wrong.
 *
 * The design rule this file defends: **the server's sentence wins, a canned string only when the
 * server said nothing.** The stock app could not do this — `ExpectedExcetpion` looked up a string
 * resource by exception class name, so every rejection read "Upload failed" (docs/FINDINGS.md §11).
 * Each test therefore supplies a real body and asserts the real words come out, plus the fallback
 * for the genuinely empty case.
 */
class FailureTextTest {

    private fun error(status: Int, body: String) = PharosError.from(status, body)

    private val gmuBadLogon = """
        {"Status":300,"UserMessage":"The operation could not be completed. &#39;Your username and
        password cannot be verified. Please try again.&#39;","ErrorCode":"TranslationNotFound",
        "DeveloperMessage":"Your username and password cannot be verified. Please try again.",
        "Request":"https://mobileprint.gmu.edu/PharosAPI/logon"}
    """.trimIndent().replace("\n", " ")

    @Test
    fun `a rejected sign-in shows the server's sentence and nothing else`() {
        val f = PharosFailure.Unauthenticated(error(300, gmuBadLogon))
        assertEquals("Your username and password cannot be verified. Please try again.", f.headline())
        assertFalse("no wrapper text may leak into the headline", f.headline().contains("could not be completed"))
        assertFalse("no HTML entities either", f.headline().contains("&#39;"))
    }

    @Test
    fun `the detail line keeps the status and the error code for the service desk`() {
        val detail = PharosFailure.Unauthenticated(error(300, gmuBadLogon)).detailLine()
        assertTrue(detail.orEmpty().contains("TranslationNotFound"))
        assertTrue(detail.orEmpty().contains("300"))
    }

    @Test
    fun `an empty body still yields a usable headline from the caller's fallback`() {
        val f = PharosFailure.Unauthenticated(error(401, ""))
        assertEquals("Your username or password was not accepted", f.headline())
    }

    @Test
    fun `too large quotes the server's own limit text alongside the byte limit`() {
        val server = "Document upload size of 52428800 bytes exceeded"
        val f = PharosFailure.TooLarge(52_428_800L, server)
        assertEquals("That file is larger than this server accepts", f.headline())
        val detail = f.detailLine()!!
        assertTrue("limit missing from: $detail", detail.contains("50 MB"))
        assertTrue(detail.contains(server))
    }

    @Test
    fun `unsupported type names the extension`() {
        val f = PharosFailure.UnsupportedType("exe", "Sorry, this file type is not supported")
        assertEquals("The queue will not print that file type", f.headline())
        assertTrue(f.detailLine()!!.startsWith(".exe"))
    }

    @Test
    fun `an unknown path explains the 401-not-404 quirk instead of looking like a typo`() {
        val f = PharosFailure.NotFound("PharosAPI/users/xyz/printjobs")
        assertTrue(f.headline().contains("PharosAPI/users/xyz/printjobs"))
        assertTrue(
            "Pharos answers 401 rather than 404 — the user needs to hear that, not 'not found'",
            f.detailLine()!!.contains("401"),
        )
    }

    @Test
    fun `a certificate we have not seen before shows the fingerprint the user must verify`() {
        val f = PharosFailure.TlsNotTrusted("mobileprint.gmu.edu", "AA:BB:CC:DD", null)
        assertEquals("Unrecognised certificate for mobileprint.gmu.edu", f.headline())
        assertTrue(f.detailLine()!!.contains("AA:BB:CC:DD"))
    }

    @Test
    fun `a host that is not a Pharos server is reported as that`() {
        val f = PharosFailure.NotAPharosServer("no X-PHAROS-API-VERSION header")
        assertEquals("That address is not a Pharos print server", f.headline())
        assertTrue(f.detailLine()!!.contains("X-PHAROS-API-VERSION"))
    }

    @Test
    fun `a timeout distinguishes the upload stalling from the server being slow`() {
        assertTrue(PharosFailure.Timeout(writePhase = true).headline().contains("upload"))
        assertTrue(PharosFailure.Timeout(writePhase = false).headline().contains("took too long"))
        assertNull("an upload timeout says nothing further — the bytes are still in flight", PharosFailure.Timeout(writePhase = true).detailLine())
    }

    @Test
    fun `the headline never contains raw JSON`() {
        val raw = """{"Error":{"Message":"boom"},"StackTrace":"at Pharos.Whatever()"}}"""
        listOf<PharosFailure>(
            PharosFailure.Server(error(500, raw)),
            PharosFailure.UploadDenied(error(403, raw), null),
        ).forEach {
            assertFalse("raw JSON leaked into ${it.headline()}", it.headline().contains("StackTrace"))
            assertFalse("raw JSON leaked into ${it.headline()}", it.headline().contains('{'))
        }
    }

    @Test
    fun `the no-answer failure uses the redesign's own words`() {
        // Spec `no_server`: "Nothing answered at that address". Pinned because this is one of the
        // nineteen strings the design spec fixes verbatim, and because the older, vaguer wording
        // ("Cannot reach the print server") blamed the server for what is usually a typo or a
        // missing VPN — the detail line has to name both of those.
        val f = PharosFailure.Offline
        assertEquals("Nothing answered at that address", f.headline())
        val detail = f.detailLine()!!
        assertTrue("advice must mention the spelling: $detail", detail.contains("spelling"))
        assertTrue("advice must mention the campus network or VPN: $detail", detail.contains("VPN"))
        assertTrue("must not pretend a response arrived: $detail", !detail.contains("timed out"))
    }

    @Test
    fun `statusForLog carries the code only where one exists`() {
        assertEquals(300, PharosFailure.Unauthenticated(error(300, gmuBadLogon)).statusForLog)
        assertEquals(413, PharosFailure.TooLarge(1L, null).statusForLog)
        assertEquals(415, PharosFailure.UnsupportedType("exe", null).statusForLog)
        assertNull(PharosFailure.Timeout(false).statusForLog)
    }
}
