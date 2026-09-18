package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.CostCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local cost-centre filter is load-bearing because GMU ignores `?search=` server-side: every
 * query gets the byte-identical 274-byte list back (`evidence/probe-costcenters-costcenters.json`),
 * so the only place a keystroke can take effect is [filterCostCenters]. These are the folding rules
 * that make a half-typed code find its row — the separator in particular, since GMU's own
 * separator is the hyphen and the server rejects every other one at costing time
 * (`analysis/centre-sweep.ps1`), so a user typing a different separator while *searching* must
 * still be shown the one spelling that works.
 */
class CostCenterFilterTest {

    private val gmu = CostCenter(
        code = "10111-M17041",
        description = "10111-Computer Science Department",
        granted = false, // GMU's owned centre carries Grant:false
        complete = true,
        active = true,
    )
    private val folder = CostCenter(
        code = "10111",
        description = "10111-Computer Science",
        granted = false,
        complete = false,
        active = true,
    )
    private val closed = CostCenter(
        code = "22222-A99",
        description = "22222-Closed Grant",
        granted = true,
        complete = true,
        active = false,
    )
    private val all = listOf(gmu, folder, closed)

    @Test
    fun `a blank query is not a filter`() {
        assertSame(all, filterCostCenters(all, ""))
        assertSame(all, filterCostCenters(all, "   "))
    }

    @Test
    fun `half a code finds the hyphenated whole`() {
        assertEquals(listOf(gmu), filterCostCenters(all, "10111-M"))
        assertTrue(filterCostCenters(all, "M17041") == listOf(gmu))
    }

    @Test
    fun `a different separator while searching still finds the row`() {
        // The user may not know yet which separator GMU bills with; searching must not hide the
        // row the way the costing endpoint does.
        for (query in listOf("10111~M17041", "10111.M17041", "10111/M17041", "10111 M17041")) {
            assertEquals("query \"$query\"", listOf(gmu), filterCostCenters(all, query))
        }
    }

    @Test
    fun `matching is case-insensitive on both code and description`() {
        // Code half, lowercase probe:
        assertEquals(listOf(gmu), filterCostCenters(all, "m17041"))
        // Description half, a phrase only the chargeable leaf's description carries:
        assertEquals(listOf(gmu), filterCostCenters(all, "department"))
        // Both descriptions contain this phrase, so upper-case or not, two honest hits come back —
        // the folder is tagged not-chargeable by its card, not hidden by the filter.
        assertEquals(listOf(gmu, folder), filterCostCenters(all, "COMPUTER SCIENCE"))
    }

    @Test
    fun `the folder prefix matches both the folder and the chargeable leaf`() {
        // "10111" fails at *costing*, but as a *search* it is two honest hits; the card itself
        // marks the folder not-chargeable.
        assertEquals(listOf(gmu, folder), filterCostCenters(all, "10111"))
    }

    @Test
    fun `closed centres stay findable - the screen tags them closed rather than hiding them`() {
        assertEquals(listOf(closed), filterCostCenters(all, "a99"))
    }

    @Test
    fun `a query nothing matches yields the empty list, not the whole list`() {
        assertEquals(emptyList<CostCenter>(), filterCostCenters(all, "zzzz"))
    }
}
