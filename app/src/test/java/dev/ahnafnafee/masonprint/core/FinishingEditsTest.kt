package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.*
import org.junit.Test

class FinishingEditsTest {
    private fun job(color: String = "false", mono: String = "Yes", copies: Int = 1) = PrintJob.from(
        """{
            "Location":"/printjobs/${color.hashCode()}", "Name":"Document",
            "SupportedFinishingOptions":{"Color":$color,"Duplex":true,"Copies":true},
            "FinishingOptions":{"Mono":"$mono","Duplex":"Yes","Copies":"$copies",
                "PagesPerSide":"2","DefaultPageSize":"A4","PageRange":"1-3"}
        }""".toJsonObject(),
    )

    @Test fun `colour and sides apply to each supported field in a mixed document batch`() {
        val pdf = job()
        val photo = job(color = "true")
        val edit = FinishingEdits(mono = false, duplex = false)
        val requested = listOf(pdf, photo).map(edit::optionsFor)
        assertTrue("The server advertises this PDF as colour-ineligible", requested[0].mono)
        assertFalse(requested[0].duplex)
        assertFalse(requested[1].mono)
        assertFalse(requested[1].duplex)
        assertEquals(listOf("colour"), edit.unsupportedFor(pdf))
        assertTrue(edit.unsupportedFor(photo).isEmpty())
    }

    @Test fun `a sides-only edit preserves each documents colour copies and page setup`() {
        val originals = listOf(job(mono = "Yes", copies = 2), job(color = "true", mono = "No", copies = 4))
        val requested = originals.map(FinishingEdits(duplex = false)::optionsFor)
        assertEquals(listOf(true, false), requested.map { it.mono })
        assertEquals(listOf(2L, 4L), requested.map { it.copies })
        requested.forEach {
            assertFalse(it.duplex)
            assertEquals(2L, it.pagesPerSide)
            assertEquals("A4", it.defaultPageSize)
            assertEquals("1-3", it.pageRange)
        }
    }

    @Test fun `missing capability flags still allow edits and string flags are respected`() {
        val unknown = PrintJob.from("""{"Location":"job"}""".toJsonObject())
        assertFalse(FinishingEdits(mono = false).optionsFor(unknown).mono)
        val stringFlag = job(color = "\"False\"")
        assertEquals(false, stringFlag.supportedFinishing.color)
        assertTrue(FinishingEdits(mono = false).optionsFor(stringFlag).mono)
    }

    @Test fun `asking for an existing restricted value is not a rejected change`() {
        assertTrue(FinishingEdits(mono = true).unsupportedFor(job()).isEmpty())
    }

    @Test fun `unsupported sides and copies do not prevent a colour edit`() {
        val restricted = job(color = "true").copy(
            supportedFinishing = dev.ahnafnafee.masonprint.data.model.SupportedFinishing(
                color = true, duplex = false, copies = false,
            ),
        )
        val edits = FinishingEdits(mono = false, duplex = false, copies = 3)
        val requested = edits.optionsFor(restricted)
        assertFalse(requested.mono)
        assertTrue(requested.duplex)
        assertEquals(1L, requested.copies)
        assertEquals(listOf("sides", "copies"), edits.unsupportedFor(restricted))
    }
}
