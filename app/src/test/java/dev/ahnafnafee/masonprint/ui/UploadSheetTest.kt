package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.core.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The arithmetic behind the send screen's three monospaced lines.
 *
 * Everything here is the part that can be wrong in a way a screenshot will not catch: a size line
 * whose numbers do not subtract, a percentage that outruns the total it is counting, a limit of `0`
 * rendered as a real allowance. The strings are asserted verbatim because the user reads them next
 * to a sentence quoted from the server, and an arithmetic line that contradicts the server's number
 * is the bug that sends a student to the service desk.
 */
class UploadSheetTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // -------------------------------------------------------------- size, and sizes that subtract --

    @Test
    fun `the GMU limit reads as fifty megabytes`() {
        assertEquals("50.0 MB", uploadSizeText(52_428_800L))
    }

    @Test
    fun `megabytes keep the decimal so the oversize line adds up`() {
        // 68.4 MiB and the 50 MiB limit, subtracting in the same units the server quoted.
        assertEquals("68.4 MB", uploadSizeText(71_722_598L))
        assertEquals(
            "68.4 MB selected · 50.0 MB allowed · 18.4 MB over",
            oversizeArithmetic(selectedBytes = 71_722_598L, limitBytes = 52_428_800L),
        )
    }

    @Test
    fun `sizes below a megabyte are not dressed up`() {
        assertEquals("512 B", uploadSizeText(512L))
        assertEquals("24 KB", uploadSizeText(24 * 1024L))
        assertEquals("1.5 GB", uploadSizeText((1.5 * 1073741824).toLong()))
    }

    @Test
    fun `nothing is computed when the file fits or a side is unknown`() {
        assertNull(oversizeArithmetic(selectedBytes = 1024L, limitBytes = 52_428_800L))
        assertNull(oversizeArithmetic(selectedBytes = 52_428_800L, limitBytes = 52_428_800L))
        assertNull(oversizeArithmetic(selectedBytes = null, limitBytes = 52_428_800L))
        assertNull(oversizeArithmetic(selectedBytes = 71_722_598L, limitBytes = null))
        // A server that publishes 0 has not published a limit; it must not read as "0.0 MB allowed".
        assertNull(oversizeArithmetic(selectedBytes = 71_722_598L, limitBytes = 0L))
    }

    @Test
    fun `the decimal separator is not the device's`() {
        // The line gets pasted into a ticket beside the server's US-formatted sentence. A German
        // locale turning this into "68,4 MB" beside "maximum upload size of 50 MB" reads as two
        // different files.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("50.0 MB", uploadSizeText(52_428_800L))
        assertEquals(
            "68.4 MB selected · 50.0 MB allowed · 18.4 MB over",
            oversizeArithmetic(selectedBytes = 71_722_598L, limitBytes = 52_428_800L),
        )
    }

    // ------------------------------------------------------------------- the streaming line ------

    @Test
    fun `the streaming line counts against the real total`() {
        val tenMegabytes = 10L shl 20
        assertEquals(
            "4.0 MB of 10.0 MB · streaming to the server",
            streamingDetail(0.4f, tenMegabytes),
        )
        assertEquals(
            "10.0 MB of 10.0 MB · streaming to the server",
            streamingDetail(1f, tenMegabytes),
        )
    }

    @Test
    fun `a fraction cannot outrun the file`() {
        // The writer thread reports progress; a callback rounding above 1.0 would otherwise print
        // "12.0 MB of 10.0 MB" and make the user think a second file went up.
        val tenMegabytes = 10L shl 20
        assertEquals(
            "10.0 MB of 10.0 MB · streaming to the server",
            streamingDetail(1.4f, tenMegabytes),
        )
        assertEquals(
            "0 B of 10.0 MB · streaming to the server",
            streamingDetail(-0.2f, tenMegabytes),
        )
    }

    @Test
    fun `no size means no total is claimed`() {
        // Some providers report no SIZE column. The percentage is still true; a total would not be.
        assertEquals(
            "62% · no size was reported, so there is no total to count against",
            streamingDetail(0.62f, null),
        )
        assertEquals(
            "62% · no size was reported, so there is no total to count against",
            streamingDetail(0.62f, -1L),
        )
    }

    // ---------------------------------------------------------------------- the file card -------

    @Test
    fun `the meta line says extension size and type, in that order`() {
        assertEquals(
            ".docx · 1.2 MB · application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            fileMetaText(
                fileName = "STAT-final-notes.docx",
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sizeBytes = 1_258_291L,
            ),
        )
    }

    @Test
    fun `a name without an extension and a size that never came are both said out loud`() {
        assertEquals(
            "no extension · size not reported · text/plain",
            fileMetaText(fileName = "notes", mimeType = "text/plain", sizeBytes = -1L),
        )
    }

    // ------------------------------------------------------------- what the server publishes -----

    @Test
    fun `the accepted types sentence uses the categories the server sent`() {
        assertEquals(
            "This server accepts files up to 50.0 MB, in PDF Document, Word Document, " +
                "and Publisher Document.",
            acceptedTypesSentence(
                maxUploadBytes = 52_428_800L,
                categories = listOf("PDF Document", "Word Document", "Publisher Document"),
            ),
        )
    }

    @Test
    fun `blank categories do not produce an empty list`() {
        assertEquals(
            "This server accepts files up to 50.0 MB. It did not publish a list of document types, " +
                "so an unfamiliar extension is decided by the server, not by this app.",
            acceptedTypesSentence(maxUploadBytes = 52_428_800L, categories = listOf("  ", "")),
        )
    }

    @Test
    fun `when the server published nothing the app says so instead of reciting the spec`() {
        assertEquals(
            "This server did not publish an upload limit or a list of document types, so its " +
                "refusal is the only authority on what it will take.",
            acceptedTypesSentence(maxUploadBytes = null, categories = emptyList()),
        )
        assertEquals(
            "This server did not publish an upload limit. It does publish the types it prints: " +
                "PDF Document and Image.",
            acceptedTypesSentence(maxUploadBytes = 0L, categories = listOf("PDF Document", "Image")),
        )
    }

    @Test
    fun `a list of two joins with and, a list of three takes the serial comma`() {
        assertEquals("", conjunction(emptyList()))
        assertEquals("Image", conjunction(listOf("Image")))
        assertEquals("Image and PDF Document", conjunction(listOf("Image", "PDF Document")))
        assertEquals(
            "Image, PDF Document, and Text Document",
            conjunction(listOf("Image", "PDF Document", "Text Document")),
        )
    }

    @Test
    fun `a job lookup is null safe about names`() {
        assertNull(jobNamed(emptyList(), "STAT-final-notes.docx"))
    }

    // ------------------------------------------------ what a batch says about its other files ------

    @Test
    fun `a single file pick has no siblings to report on`() {
        assertNull(siblingsStillSent(listOf(picked("a.pdf")), "a.pdf", listOf("a.pdf")))
    }

    @Test
    fun `when the rest of the pick failed too the app does not imply a partial success`() {
        assertEquals(
            "None of the 2 other files in this pick are in the queue either. " +
                "The list above names each one.",
            siblingsStillSent(
                listOf(picked("a.pdf"), picked("b.pdf"), picked("c.pdf")),
                "a.pdf",
                listOf("something-else.pdf"),
            ),
        )
    }

    @Test
    fun `only the siblings that actually arrived are counted`() {
        assertEquals(
            "1 of the 2 other files in this pick did reach the queue. The list above names each one.",
            siblingsStillSent(
                listOf(picked("a.pdf"), picked("b.pdf"), picked("c.pdf")),
                "a.pdf",
                // The queue's own spelling of the name: the match ignores case, because the server
                // echoes the name it was given and Android sometimes hands over a different case.
                listOf("B.PDF"),
            ),
        )
    }

    @Test
    fun `the row of a file still in flight never claims this send has arrived`() {
        // Verified on-device: `mp262-configurator.pdf` was already in the queue at $1.10 from an
        // earlier send while its second copy was still uploading, and the queue is matched by name.
        assertEquals(
            "A job called essay.pdf sent earlier is in the queue at \$1.10. " +
                "The copy going up now has not arrived yet.",
            sendingRowNote("essay.pdf", "\$1.10"),
        )
        assertEquals(
            "A job called essay.pdf sent earlier is already in the queue. " +
                "The copy going up now has not arrived yet.",
            sendingRowNote("essay.pdf", null),
        )
    }

    private fun picked(name: String) = PickedFile(name, "application/pdf", 1_024L)
}
