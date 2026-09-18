package dev.ahnafnafee.masonprint.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-side gate in front of a request the server will refuse anyway.
 *
 * GMU answers an unsupported type with **415 and a sentence** (`FileUpload_NotSupported_Generic`),
 * so rejecting locally is a courtesy to the user, not a security boundary. What matters is that the
 * list matches the server's: a false negative here silently removes a format the campus supports,
 * and a false positive costs a round trip. The entries below are pinned against the stock app's own
 * `AcceptedFileTypes` list (docs/FINDINGS.md §6.2), which is the same allowlist GMU's portal uses.
 */
class MimeTypesTest {

    @Test
    fun `the allowlist is the vendor's and is not empty`() {
        assertTrue("58 entries in the stock client", MimeTypes.supportedExtensions.size >= 50)
        assertTrue(MimeTypes.supportedExtensions.contains("pdf"))
        assertTrue(MimeTypes.supportedExtensions.contains("docx"))
        assertTrue(MimeTypes.supportedExtensions.contains("xlsx"))
        assertTrue(MimeTypes.supportedExtensions.contains("pptx"))
        assertTrue("the bundle accepts raw print formats too", MimeTypes.supportedExtensions.contains("txt"))
    }

    @Test
    fun `every listed extension maps to a real media type`() {
        MimeTypes.supportedExtensions.forEach { ext ->
            val mime = MimeTypes.forExtension(ext)
            assertNotNull("$ext has no media type", mime)
            assertTrue("$ext → $mime is not a type/subtype", mime!!.contains('/'))
            assertFalse("$ext maps to an empty type", mime.isBlank())
        }
    }

    @Test
    fun `pdf maps to application pdf whatever the spelling of the input`() {
        assertEquals("application/pdf", MimeTypes.forExtension("pdf"))
        assertEquals("application/pdf", MimeTypes.forExtension("PDF"))
        assertEquals("application/pdf", MimeTypes.forExtension(".pdf"))
        assertEquals("application/pdf", MimeTypes.forExtension("  pdf  ".trim()))
    }

    @Test
    fun `lookup is by extension only and an unknown one is refused`() {
        assertNull(MimeTypes.forExtension("exe"))
        assertNull(MimeTypes.forExtension("php"))
        assertNull(MimeTypes.forExtension(""))
        assertFalse(MimeTypes.isSupported("zip"))
        assertTrue(MimeTypes.isSupported(".pdf"))
    }

    @Test
    fun `file names are reduced to their extension, including dots in the stem`() {
        assertEquals("application/pdf", MimeTypes.forFile("Thesis (final) v3.PDF"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", MimeTypes.forFile("a.b.c.docx"))
        assertNull("no extension at all → nothing to match", MimeTypes.forFile("Makefile"))
        assertNull("a trailing dot is not an extension", MimeTypes.forFile("notes."))
    }

    @Test
    fun `the formats a student actually sends from a phone are all present`() {
        // Share-sheet reality: Files, Drive, Outlook, and camera apps hand over these.
        listOf("pdf", "docx", "xlsx", "pptx", "jpg", "png", "txt", "rtf", "csv").forEach {
            assertTrue("$it must be uploadable", MimeTypes.isSupported(it))
        }
    }
}
