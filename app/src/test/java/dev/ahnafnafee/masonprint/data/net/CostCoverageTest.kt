package dev.ahnafnafee.masonprint.data.net

import org.junit.Assert.*
import org.junit.Test

class CostCoverageTest {
    @Test fun `a partial or unrelated quote cannot enable the whole selection`() {
        val quote = CostEstimate.from("""[{"Location":"a","Status":200,"Costing":{"Cost":"0.10"}}]""")
        assertTrue(quote.covers(setOf("a")))
        assertFalse(quote.covers(setOf("a", "b")))
        assertFalse(quote.covers(setOf("b")))
        assertFalse(quote.covers(emptySet()))
    }

    @Test fun `a refusal or unknown price cannot be presented as a complete quote`() {
        val partial = CostEstimate.from("""[{"Location":"a","Status":200,"Costing":{"Cost":"0.10"}}, {"Location":"b","Status":405}]""")
        val unknown = CostEstimate.from("""[{"Location":"a","Status":200,"Costing":{"Cost":"-1"}}]""")
        assertFalse(partial.covers(setOf("a", "b")))
        assertFalse(unknown.covers(setOf("a")))
    }

    @Test fun `free quotes still enable release`() {
        val quote = CostEstimate.from("""[{"Location":"a","Status":200,"Costing":{"Cost":"0.00"}}]""")
        assertTrue(quote.covers(setOf("a")))
    }
}
