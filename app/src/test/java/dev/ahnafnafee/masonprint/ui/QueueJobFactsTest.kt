package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.SupportedFinishing
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.*
import org.junit.Test

class QueueJobFactsTest {
    @Test fun `unreported options are not presented as defaults or restrictions`() {
        val job = PrintJob.from("""{"Location":"job","Stats":{"TotalColorPages":2}}""".toJsonObject())
        assertTrue(queuePrintSettings(job).isEmpty())
    }

    @Test fun `colour badge follows the print setting rather than source page counts`() {
        val job = PrintJob.from("""{
            "Location":"job", "Stats":{"TotalColorPages":2},
            "FinishingOptions":{"Mono":"Yes","Duplex":"Yes","Copies":"1"},
            "SupportedFinishingOptions":{"Color":false,"Duplex":true}
        }""".toJsonObject())
        val settings = queuePrintSettings(job)
        assertEquals(QueueSettingKind.Mono, settings.first().kind)
        assertEquals("colour", settings.first().restriction)
        assertNull(settings[1].restriction)
        assertFalse(settings.any { it.kind == QueueSettingKind.Copies })
    }

    @Test fun `explicit restrictions remain visible without values and do not warn on released history`() {
        val job = PrintJob.from("""{"Location":"job"}""".toJsonObject()).copy(
            supportedFinishing = SupportedFinishing(color = false, duplex = false, copies = false),
        )
        assertEquals(listOf("colour", "sides", "copies"), queuePrintSettings(job).map { it.restriction })
        assertTrue(queuePrintSettings(job.copy(pending = false)).none { it.restriction != null })
    }
}
