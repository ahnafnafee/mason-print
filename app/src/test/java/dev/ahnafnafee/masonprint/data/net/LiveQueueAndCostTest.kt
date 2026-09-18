package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.JobOperationResult
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.jobLocations
import dev.ahnafnafee.masonprint.data.model.obj
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first upload → queue → cost → delete round trip ever performed against GMU with a real account,
 * captured by `phrarosprint/analysis/probe.ps1` (see `evidence/probe-3.log`) and stored under
 * `src/test/resources/gmu/`.
 *
 * Everything before this class was written against responses from a *jobless* account, plus the
 * vendor bundle's model definitions. Together those two sources said a queue item looks like
 * `ModelPrintJob`, which has a `Pending` boolean, a `Cost`, and a `{Responses:[…]}` envelope on the
 * bulk endpoints. The live account disagreed with all three, and in each case the client's wrong guess
 * failed **silently** rather than as an exception:
 *
 * 1. There is no `Pending` key. `PrintState:"Queued"` is the only thing that marks a job as waiting,
 *    so a client that reads `Pending` gets `false` and hides every job in the queue.
 * 2. `POST /printjobs/cost` answers **HTTP 200** whose body is a bare array containing a per-job
 *    `Status:405`. HTTP 200 is not a price, and the reason ("Document still being processed") is the
 *    only explanation a student ever gets.
 * 3. `DELETE /printjobs` answers with the same bare-array shape, so a bulk call that failed per job
 *    would still be reported as "Deleted 1 job(s)".
 *
 * These tests pin the four shapes so the fix cannot regress into "works on my empty account".
 */
class LiveQueueAndCostTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) {
            "missing test fixture /gmu/$name (expected in app/src/test/resources/gmu/)"
        }.bufferedReader().use { it.readText() }

    private val queue: Page<PrintJob> =
        Page.from(fixture("jobs-page-with-job.json").toJsonObject(), PrintJob::from)

    private val job: PrintJob = queue.items.single()

    /** 615 bytes of hand-written PDF, uploaded by the probe; the server's converter is what named it. */
    @Test
    fun `a queue item is a real document with the server's own analysis attached`() {
        assertEquals(1L, queue.count ?: -1L)
        assertEquals("probe-job.pdf", job.name)
        assertEquals("PDF Document", job.documentType)
        assertEquals("Mobile Print Group", job.deviceGroup)
        assertEquals("Intermediate", job.jobFormat)
        // Set by Uniprint's converter, not by the client: the upload sent no ApplicationName at all.
        assertEquals("ACRORD32.EXE", job.applicationName)
        assertEquals("None", job.protectedBy)
        assertFalse(job.needsPassword)
        // Location is relative to the /PharosAPI virtual directory. Kept verbatim here; it is re-rooted
        // by PharosTarget.resolve(), which is the difference between a working queue and a 404.
        assertEquals(
            "/printjobs/1H9YlWIElWfCOUW_PIiR6k5DPcl7s8X_QyZY4XevL3KXTEbuaiXAY7-MAYl9im5R0",
            job.location,
        )
    }

    /**
     * The defect this whole file exists for. The bundle's model has `Pending`; GMU's JSON does not,
     * so `bool("Pending") ?: false` returned `false` for a job sitting in the queue, `isReleased`
     * became `true`, and the default queue filter showed "Nothing waiting to print" with a job in it.
     */
    @Test
    fun `a queued job is waiting even though GMU never sends Pending`() {
        assertTrue("PrintState:Queued must count as waiting", job.pending)
        assertFalse(job.isReleased)
        assertEquals("Queued", job.printState)
        // GMU sends `PrintState`; the bundle's `JobStatus`/`Pending` keys are simply not in the payload.
        assertNull(job.jobStatus)
        // Count is the total, so paging stops here rather than following the link GMU always sends.
        assertFalse(queue.hasMoreAfter(1))
    }

    @Test
    fun `only a state the server called finished counts as released`() {
        fun stateOf(json: String) = PrintJob.from(json.toJsonObject())

        assertFalse(stateOf("""{"Location":"/printjobs/a","PrintState":"Released"}""").pending)
        assertFalse(stateOf("""{"Location":"/printjobs/a","PrintState":"aborted"}""").pending)
        // An explicit boolean always wins, in both directions, for deployments that do send one.
        assertTrue(stateOf("""{"Location":"/printjobs/a","PrintState":"Released","Pending":true}""").pending)
        assertFalse(stateOf("""{"Location":"/printjobs/a","PrintState":"Queued","Pending":false}""").pending)
        // An unrecognised state stays visible: a hidden job can never be released, while a stale row
        // only costs the student one useless tap.
        assertTrue(stateOf("""{"Location":"/printjobs/a","PrintState":"Held"}""").pending)
        // And with neither field at all, showing the job is still the safer reading.
        assertTrue(stateOf("""{"Location":"/printjobs/a"}""").pending)
    }

    /** `AllowableActions` gates what the UI may offer; it is a comma string, not an array. */
    @Test
    fun `the server publishes which actions this job accepts`() {
        assertEquals(
            listOf("Read", "Update", "Delete", "Content", "Preview"),
            job.allowableActions,
        )
        // A job that has not been through the converter yet does not offer Preview.
        val fresh = PrintJob.from(fixture("upload-created.json").toJsonObject())
        assertEquals(listOf("Read", "Update", "Delete", "Content"), fresh.allowableActions)
    }

    /**
     * The probe's PDF converted but failed page counting, so costing never started and the job sits in
     * the queue with **zero** pages. That is a normal GMU outcome, not an error state, and it is why
     * "how many pages?" has to tolerate a job that does not know yet.
     */
    @Test
    fun `a job can sit in the queue knowing nothing about its pages`() {
        assertEquals(0L, job.stats.totalPages ?: -1L)
        assertEquals(0L, job.stats.sheets ?: -1L)
        assertEquals(0L, job.stats.sizeInKb ?: -1L)
        assertTrue(job.costUnknown)
        // "0 pages · 0 B" would tell the student their file is empty. It is not; the server has just
        // not counted it, or gave up. The card shows the pipeline state instead.
        assertTrue(job.awaitingPageCount)
        assertEquals("", job.pageSummary)
        // No Cost key on the live item at all: the price only ever comes from POST /printjobs/cost.
        assertNull(job.cost)
        // A counted job still gets its normal summary.
        val counted = PrintJob.from(
            """{"Location":"/printjobs/a","PrintState":"Queued","Stats":{"TotalPages":3,"TotalColorPages":1,"SizeInKb":120,"TotalSheets":3}}"""
                .toJsonObject(),
        )
        assertFalse(counted.awaitingPageCount)
        assertTrue(counted.pageSummary, counted.pageSummary.contains("3"))
    }

    /**
     * The queue row of a PDF the phone really uploaded (1,665,254 bytes, `mp262-configurator.pdf`,
     * 2026-09-18). GMU counted it correctly — 11 pages on 6 sheets, all black-and-white, priced
     * `$1.10` — and sent `SizeInKb: 0` for the file size in the same object. So a zero size means
     * "not measured", and printing it as `0 B` tells the student their 1.6 MB document arrived empty.
     */
    @Test
    fun `a real uploaded job reports its pages correctly and its size as nothing at all`() {
        val uploaded = PrintJob.from(
            """
            {"Location":"/printjobs/x","Name":"mp262-configurator.pdf","PrintState":"Queued",
             "Cost":"1.10",
             "Stats":{"TotalPages":11,"TotalSheets":6,"TotalBWPages":11,"TotalColorPages":0,"SizeInKb":0}}
            """.toJsonObject(),
        )
        assertEquals("6 sheets · 11 pages · 11 b&w", uploaded.pageSummary)
        assertFalse(uploaded.pageSummary, uploaded.pageSummary.contains("0 B"))
        // GMU sends the price as the JSON *string* "1.10"; the model reads it as a number so the
        // money formatter can do the rounding, and the screen re-applies the server's `"$0.00"`.
        assertEquals(1.10, uploaded.cost!!, 1e-9)
        assertFalse(uploaded.costUnknown)
        // A measured size is still shown when the server bothers to send one.
        val measured = PrintJob.from(
            """{"Location":"/printjobs/y","PrintState":"Queued",
               "Stats":{"TotalPages":1,"TotalSheets":1,"SizeInKb":120}}""".toJsonObject(),
        )
        assertTrue(measured.pageSummary, measured.pageSummary.contains("120 KB"))
    }

    @Test
    fun `the analysis pipeline explains why there is no price yet`() {
        val activity = job.activity
        assertNotNull(activity)
        assertEquals("Analysing", activity!!.name)
        assertEquals(
            listOf("Converting", "PageCounting", "Costing"),
            activity.steps.map { it.name },
        )
        assertTrue("PageCounting reported Failed", activity.failed)
        // Failed, not in-progress: the queue item still shows `Costing: Not yet started`, so a client
        // that infers "still working" from an unfinished step would wait forever for a price.
        assertFalse(activity.processing)
        assertEquals("PageCounting: Failed", activity.blocker)
        assertFalse(job.isProcessing)
        assertTrue(job.analysisFailed)
    }

    /** Immediately after upload the same resource is mid-flight, which is when a price is refused. */
    @Test
    fun `the upload response is the same resource a second earlier`() {
        val fresh = PrintJob.from(fixture("upload-created.json").toJsonObject())
        val freshActivity = requireNotNull(fresh.activity)
        assertTrue("Activity.State was \"Processing...\"", freshActivity.processing)
        assertFalse(fresh.analysisFailed)
        assertEquals("Converting: Not yet started", freshActivity.blocker)
        assertTrue(fresh.isProcessing)
        assertTrue(fresh.pending)
        // The upload reply omits CostCenterCode entirely; the queue item sends it back as "".
        assertNull(fresh.costCenterCode)
        assertEquals("", job.costCenterCode)
    }

    /** `SubmissionTimeDelta` is the server's own age, immune to the handset's clock being wrong. */
    @Test
    fun `the server hands over the age of the job directly`() {
        assertEquals(2.0000184, job.submissionAgeSeconds ?: 0.0, 1e-6)
        assertNotNull(job.submittedAt)
        // No expiry on a queued job, and the `0001-01-01T00:00:00` sentinel must read as "never".
        assertNull(job.expiresAt)
        val activity = requireNotNull(job.activity)
        val costing = activity.steps.last()
        assertEquals("Costing", costing.name)
        assertNull("a step that never started has no start time", costing.startedAt)
        assertNotNull("Converting did run, and the server says exactly when", activity.steps.first().startedAt)
    }

    /**
     * HTTP 200 with a 405 inside. The old parser called `toJsonObject()` on a body that starts with
     * `[`, the parse threw, the exception was swallowed, and the student saw a price of "?" with no
     * reason attached — for a job the server had explicitly refused to price.
     */
    @Test
    fun `a refusal to price arrives inside an http 200`() {
        val estimate = CostEstimate.from(fixture("cost-refused-200.json"))
        assertEquals("one line per job asked about", 1, estimate.lines.size)
        assertNull("no total is published when nothing was priced", estimate.total)
        assertEquals(1, estimate.refusals.size)
        val refusal = estimate.refusals.single()
        assertEquals(405, refusal.status)
        assertEquals("JobActionNotAllowedStillProcessing", refusal.errorCode)
        assertEquals(job.location, refusal.jobLocation)
        assertTrue(estimate.allRefused)
        // The vendor's sentence, HTML-unwrapped — GMU localises these through a text service, and the
        // student is reading the same words in the web portal.
        val message = requireNotNull(refusal.message)
        assertTrue(message, message.contains("still being processed"))
        assertFalse("HTML entities must not reach the screen", message.contains("&#39;"))
        assertFalse(message, message.contains("The operation could not be completed"))
    }

    /** The documented envelope still has to work: some deployments answer with `{TotalCost,Responses}`. */
    @Test
    fun `a priced answer is read from either envelope`() {
        val priced = CostEstimate.from(
            """{"TotalCost":0.5,"Responses":[{"Location":"/printjobs/a","Costing":{"Name":"essay.pdf","Cost":0.5}}]}""",
        )
        assertEquals(0.5, priced.total ?: 0.0, 1e-9)
        assertEquals(1, priced.lines.size)
        assertTrue(priced.refusals.isEmpty())
        assertFalse(priced.allRefused)
    }

    @Test
    fun `a bare array with no refusal inside is not read as a refusal`() {
        val estimate = CostEstimate.from("""[{"Location":"/printjobs/a","Status":200,"Cost":0.0}]""")
        assertEquals(1, estimate.lines.size)
        assertTrue("200 per job means priced", estimate.refusals.isEmpty())
        assertFalse(estimate.allRefused)
    }

    /** `DELETE /printjobs` answers `[{Location,Status}]`; the old reader turned that into "no detail". */
    @Test
    fun `a bulk delete reports what it did per job`() {
        val result = JobOperationResult.from(fixture("delete-response.json"))
        assertEquals(1, result.responses.size)
        assertTrue("Status 200 per job means deleted", result.failures.isEmpty())
        assertEquals(
            listOf("/printjobs/1H9YlWIElWfCOUW_PIiR6k5DPcl7s8X_QyZY4XevL3KXTEbuaiXAY7-MAYl9im5R0"),
            result.actedLocations,
        )
    }

    /**
     * The funding write answers the same way, and its `200` does not mean the department is paying:
     * live, `PATCH /printjobs/` with a real cost centre returned `Status:200` with
     * `CostCenterCode` still empty, because the job had not been costed yet. A client that reports
     * "Charged to 10111-M17041" from this response would have told the student their printout was on
     * the department and taken the money out of their purse.
     */
    @Test
    fun `the cost centre write is not proof the charge took`() {
        val patched = JobOperationResult.from(fixture("patch-response.json"))
        assertTrue(patched.failures.isEmpty())
        assertEquals(listOf(job.location), patched.actedLocations)
        val updated = patched.responses.single()
        // The refreshed job is nested under PrintJob — the same shape the vendor's SPA merges
        // (`Costing || PrintJob`), so a per-job row must never be read as a job itself.
        val refreshed = PrintJob.from(requireNotNull(updated.obj("PrintJob")))
        assertEquals("", refreshed.costCenterCode)
        // Still mid-flight here, which is why the cost call seconds later was refused.
        assertTrue(refreshed.isProcessing)
    }

    @Test
    fun `a bulk call that failed one job does not report total success`() {
        val mixed = JobOperationResult.from(
            """[{"Location":"/printjobs/a","Status":200},""" +
                """{"Location":"/printjobs/b","Status":405,"ErrorCode":"JobActionNotAllowed"}]""",
        )
        assertEquals(2, mixed.responses.size)
        assertEquals(1, mixed.failures.size)
        assertEquals(listOf("/printjobs/b"), mixed.failures.jobLocations())
        val envelope = JobOperationResult.from(
            """{"Responses":[{"Location":"/printjobs/a","Success":false,"Error":"nope"}],"User":{}}""",
        )
        assertEquals(1, envelope.failures.size)
    }

    /** Guards the rewrite of the bulk reader against losing the `{Responses,User}` shape. */
    @Test
    fun `release still hands back the refreshed user`() {
        val withUser = JobOperationResult.from(
            """{"Responses":[{"Location":"/printjobs/a","Status":200}],""" +
                """"User":{"Location":"/users/abc","Balance":{"Amount":"4.10"}}}""",
        )
        assertEquals(1, withUser.responses.size)
        assertEquals(listOf("/printjobs/a"), withUser.actedLocations)
        assertEquals("/users/abc", withUser.updatedUser?.location)
        assertEquals(4.10, withUser.updatedUser?.balance?.amount ?: 0.0, 1e-9)
    }
}
