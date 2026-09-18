package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PharosJson
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.dblCI
import dev.ahnafnafee.masonprint.data.model.obj
import dev.ahnafnafee.masonprint.data.model.objects
import dev.ahnafnafee.masonprint.data.model.objectsNested
import dev.ahnafnafee.masonprint.data.model.strCI
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second generation of live GMU captures: jobs that actually got a **price**.
 *
 * [LiveQueueAndCostTest] could only pin the *refusal* shape, because every job that probe uploaded was
 * a 615-byte hand-written PDF that GMU's own converter could not count (`PageCounting: Failed`), and an
 * uncounted job is never priced — which is why no cost fixture existed. Uploading a real, tool-produced
 * PDF instead (`mp262-relnote.pdf`, 12 pages; `mp262-configurator.pdf`, 11 pages) took the analysis to
 * `Completed` in about six seconds and produced the two things this file is about:
 *
 * 1. **A priced cost row.** The price is a *string* (`"Cost":"1.20"`) sitting inside a `Costing` object,
 *    and GMU never sends the `TotalCost` envelope the vendor model documents — so a client that reads
 *    only the envelope shows "Release 1 job · ?" for a selection it just priced at $1.20. 12 B&W pages
 *    at $0.10 each is the arithmetic GMU publishes, and 11 pages is `1.10`.
 * 2. **`AllowableActions` with `Release` in it** — which corrects the earlier conclusion in
 *    `pharos/research/PHAROS-GMU-FINDINGS.md` §4zz that the field cannot gate the release button. It
 *    can; the job that probe looked at simply had no price yet. A costed job reads
 *    `Read, Update, Delete, Content, Preview, Release, Cost, FinishingOptionsUpdatable`.
 *
 * Also pinned here: `Activity` collapses to `{"Steps":[],"Name":"N/A","State":"Completed"}` once the
 * analysis ends (an empty `Steps` is *finished*, not "still working"), and the cost-centre list GMU
 * publishes — one account, `Active`, **not** `Grant`ed, and `?search=` ignored server-side.
 */
class PricedJobTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) {
            "missing test fixture /gmu/$name (expected in app/src/test/resources/gmu/)"
        }.bufferedReader().use { it.readText() }

    /** Mirrors `PharosClient.costCenters`'s own parsing so the fixture guards the production path. */
    private fun costCenters(body: String): List<CostCenter> =
        when (val root = PharosJson.parseToJsonElement(body)) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> root.objects("Items").ifEmpty { root.objectsNested("CostCenters") }
            else -> emptyList()
        }.map(CostCenter::from)

    // ---------------------------------------------------------------- price

    /** `evidence/probe-cost.json`. The row that a job with a price comes back as. */
    @Test
    fun `a priced cost answer is totalled even though GMU sends no TotalCost envelope`() {
        val priced = CostEstimate.from(fixture("cost-priced-200.json"))
        assertEquals(1, priced.lines.size)
        assertTrue("Status:200 per row means priced, not refused", priced.refusals.isEmpty())
        assertFalse(priced.allRefused)
        // Without summing the rows this reads as no total at all, and the release verb loses its price.
        assertEquals(1.10, priced.total ?: 0.0, 1e-9)
        // …and the price itself is a JSON *string* under Costing, the SPA's `e.Costing || e.PrintJob` read.
        val costing = priced.lines.single().obj("Costing")
        assertNotNull("the price lives inside Costing, not on the row", costing)
        assertEquals(1.10, costing?.dblCI("Cost") ?: 0.0, 1e-9)
    }

    /** Half a selection priced, half still being analysed: there is no total worth printing. */
    @Test
    fun `a selection only partly priced has no total`() {
        val partly = CostEstimate.from(
            """[{"Location":"/printjobs/a","Status":200,"Costing":{"Cost":"1.10"}},
               |{"Location":"/printjobs/b","Status":405,"ErrorCode":"JobActionNotAllowedStillProcessing",
               | "UserMessage":"Document still being processed."}]""".trimMargin(),
        )
        assertEquals(2, partly.lines.size)
        assertEquals(1, partly.refusals.size)
        assertFalse("one job was priced, so this is not 'the server refused everything'", partly.allRefused)
        assertNull(partly.total)
    }

    /** `-1` is "this server will not price that job", which is not the same as free. */
    @Test
    fun `one uncostable job makes the whole selection unknown`() {
        val withUnknown = CostEstimate.from(
            """[{"Location":"/printjobs/a","Status":200,"Costing":{"Cost":"1.10"}},
               |{"Location":"/printjobs/b","Status":200,"Costing":{"Cost":-1}}]""".trimMargin(),
        )
        assertTrue(withUnknown.refusals.isEmpty())
        assertNull("a -1 anywhere means the total is unknown, not the sum of the rest", withUnknown.total)
    }

    // ------------------------------------------------- a job with a price

    private val costedPage: Page<PrintJob> =
        Page.from(fixture("jobs-page-with-costed-job.json").toJsonObject(), PrintJob::from)

    private val costed: PrintJob = costedPage.items.single()

    @Test
    fun `a job the server has priced is priced, not 'cost unknown'`() {
        assertEquals("mp262-relnote.pdf", costed.name)
        assertEquals(12L, costed.stats.totalPages ?: -1L)
        assertEquals(1.20, costed.cost ?: 0.0, 1e-9)
        assertFalse(costed.costUnknown)
        // The 12 pages are what $0.10 was multiplied by; the row must be able to say them.
        assertTrue(costed.pageSummary.contains("12 pages"))
        assertTrue(costed.pageSummary.contains("12 b&w"))
    }

    /**
     * The correction to §4zz. An uncosted job offers `Read, Update, Delete, Content, Preview` and
     * nothing else; once GMU has priced it, `Release` appears. So this list *is* a legitimate gate on
     * the release button — provided the client still falls back to `PrintState` for a job whose
     * analysis died, which [pendingJobIsStillWaiting] below keeps honest.
     */
    @Test
    fun `a priced job can be released, and the server says so in AllowableActions`() {
        assertTrue(costed.allowableActions.contains("Release"))
        assertTrue(costed.allowableActions.contains("Cost"))
        assertTrue(costed.allowableActions.contains("FinishingOptionsUpdatable"))
        assertEquals("Queued", costed.printState)
        assertTrue(costed.pending)
        assertFalse(costed.isReleased)
    }

    /**
     * `evidence/probe-job-settled.json`: after the analysis finishes, GMU replaces the step list
     * wholesale with `{"Steps":[],"Name":"N/A","State":"Completed"}`. A client that reads "no steps yet"
     * as "nothing has happened" spins a progress bar over a job that is ready to print.
     */
    @Test
    fun `an emptied Activity means finished, not still being processed`() {
        val settled = PrintJob.from(fixture("job-settled-costed.json").toJsonObject())
        assertNotNull(settled.activity)
        assertTrue("the finished activity carries no steps", settled.activity!!.steps.isEmpty())
        assertEquals("Completed", settled.activity?.state)
        assertFalse("no steps and State:Completed must not read as in-progress", settled.isProcessing)
        assertFalse(settled.analysisFailed)
        assertNull(settled.activity?.blocker)
        assertFalse(settled.awaitingPageCount)
        assertEquals(1.10, settled.cost ?: 0.0, 1e-9)
        /*
         * The pricing basis, straight off the wire: the page counter's own transcript, HTML-entity
         * escaped — `OK␍␊11 11 1␍␊PDF,612x792,Letter,Mono,Color,Simplex␍␊3␍␊page: 1 …Color…␍␊`. It counts
         * sheets *per category* (colour vs mono), which is why "11 pages" and "$1.10" agree only once
         * `FinishingOptions.Mono` is known. Not modelled on PrintJob yet; the estimate dialog is where
         * a student would want to see it.
         */
        val transcript = fixture("job-settled-costed.json").toJsonObject().strCI("RawPageCounterResult").orEmpty()
        assertTrue(transcript.startsWith("OK"))
        assertTrue(transcript.contains("11 11 1"))
        assertTrue(transcript.contains("Letter,Mono,Color,Simplex"))
    }

    // -------------------------------------------------------- cost centres

    /**
     * GMU's whole cost-centre namespace is one department account, published `Active` but with
     * `Grant:false` — i.e. it exists and is not granted to this user. `docs/FUNDING-MODELS.md` §2.7:
     * only an `Active` *and* usable account may be offered as "who pays", and this is the fixture that
     * stops the picker offering it as if it were free printing.
     */
    @Test
    fun `GMU publishes one cost centre and does not grant it`() {
        val centers = costCenters(fixture("costcenters-list.json"))
        val only = centers.single()
        assertEquals("10111-M17041", only.code)
        assertTrue(only.active)
        assertTrue(only.complete)
        assertFalse("Grant:false — the account is published, not given", only.granted)
        assertTrue(only.description.orEmpty().contains("Computer Science Department"))
    }

    /**
     * `GET {UserUri}/costcenters?search=10111` returned the byte-identical list, so the server does not
     * filter. A client that sends a query and trusts the answer will show every account under whatever
     * the student typed; the filtering has to happen here.
     */
    @Test
    fun `the cost centre search parameter is ignored by the server`() {
        assertEquals(
            costCenters(fixture("costcenters-list.json")).map { it.code },
            costCenters(fixture("costcenters-search.json")).map { it.code },
        )
    }
}
