package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `stillAwaitsCosting` is the whole decision behind defect 19.
 *
 * A document GMU has accepted is not priced yet: conversion, page counting and costing run on the
 * server and finish in roughly six seconds. The queue read that fires the instant `POST printjobs`
 * returns therefore always lands mid-analysis, and if nothing reads the queue a second time the row
 * keeps saying "the server is still counting this document" until the user restarts the app — which
 * is exactly what was reported from a Pixel ("on first load it stays in that state, it loads fine
 * after restarting").
 *
 * The three GMU bodies below are the real ones. They matter more than invented JSON here, because
 * the predicate has to tell apart a job that is *about* to get a price from one that never will:
 * `upload-created.json` is the poll case, `jobs-page-with-job.json` (`PageCounting: Failed`) is the
 * case that must not be polled at all, and `job-settled-costed.json` is finished — its `Steps` is an
 * empty array, which means done, not "not started".
 */
class StillAwaitsCostingTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("gmu/$name"))
            .bufferedReader().use { it.readText() }

    private fun job(body: String): PrintJob = PrintJob.from(body.toJsonObject())

    /** `jobs-page-with-job.json` is a queue *page*; the job is the single item in it. */
    private fun firstItem(name: String): PrintJob =
        Page.from(fixture(name).toJsonObject(), PrintJob::from).items.single()

    @Test
    fun theStateRightAfterAnUploadIsWorthAnotherLook() {
        val fresh = job(fixture("upload-created.json"))

        assertTrue("GMU says Activity.State = \"Processing...\"", fresh.isProcessing)
        assertTrue(fresh.costUnknown)
        assertTrue(stillAwaitsCosting(fresh))
    }

    @Test
    fun aJobThatHasBeenPricedAndFinishedIsNotWorthAnotherLook() {
        val settled = job(fixture("job-settled-costed.json"))

        // Empty Steps means the analysis is over, so nothing further will ever change on the server.
        assertTrue(settled.activity!!.steps.isEmpty())
        assertFalse(stillAwaitsCosting(settled))
    }

    /**
     * `PageCounting: Failed` is a normal GMU outcome, and the job keeps no price forever. Treating
     * "some step is unfinished" as "wait" — which is what an obvious implementation does — would poll
     * a job that is never going to answer, on every queue load, for as long as the document is held.
     */
    @Test
    fun aDocumentTheServerCouldNotCountIsNotWorthAnotherLook() {
        val uncountable = firstItem("jobs-page-with-job.json")

        assertTrue(uncountable.analysisFailed)
        assertTrue(uncountable.costUnknown)
        assertFalse(stillAwaitsCosting(uncountable))
    }

    @Test
    fun aReleasedJobWithNoPriceDoesNotHoldTheQueueOpen() {
        val released = job(
            """
            {"PrintState":"Released","Stats":{"TotalPages":3,"TotalSheets":3,"TotalBWPages":3,"SizeInKb":12},
             "Activity":{"Name":"Analysing","State":"Completed","Steps":[]},
             "Location":"/printjobs/abc"}
            """.trimIndent(),
        )

        assertFalse(released.pending)
        assertFalse(stillAwaitsCosting(released))
    }

    /**
     * The gap that produced the second half of the complaint: for a moment after the 201 the job has
     * no `Activity` block at all, so `isProcessing` is false and the row had no sentence to show. The
     * queue still has to look again, and the row needs its own words — which is why `JobsScreen` has a
     * branch for this predicate and not only for `isProcessing`.
     */
    @Test
    fun aJobTheServerHasNotWrittenActivityForYetIsStillWorthAnotherLook() {
        val barelyThere = job(
            """
            {"PrintState":"Queued","Stats":{"TotalPages":0,"TotalSheets":0,"TotalBWPages":0,"SizeInKb":0},
             "Location":"/printjobs/def"}
            """.trimIndent(),
        )

        assertTrue(barelyThere.activity == null)
        assertFalse("and yet the row must not claim the server is counting it", barelyThere.isProcessing)
        assertTrue(stillAwaitsCosting(barelyThere))
    }

    /**
     * A job carrying a cost centre GMU rejects is `-1` forever, so the predicate cannot exclude it and
     * the watcher's budget is what stops the requests. Asserted rather than assumed, because `-1`
     * arrives as a JSON number here and as a JSON string elsewhere.
     */
    @Test
    fun aRejectedCostCentreKeepsLookingUntilTheBudgetSaysStop() {
        val uncostable = job(
            """
            {"PrintState":"Queued","Cost":-1,
             "Activity":{"Name":"Analysing","State":"Completed","Steps":[{"Name":"Costing","State":"Completed"}]},
             "Location":"/printjobs/ghi"}
            """.trimIndent(),
        )

        assertTrue(uncostable.costUnknown)
        assertFalse(uncostable.analysisFailed)
        assertTrue(stillAwaitsCosting(uncostable))
    }

    /** The watcher's loop condition is `any`, not `all`: one fresh document re-reads the whole page. */
    @Test
    fun oneFreshDocumentInAPageOfFinishedOnesStillReReadsTheQueue() {
        val page = listOf(
            job(fixture("job-settled-costed.json")),
            job(fixture("jobs-page-with-job.json")),
            job(fixture("upload-created.json")),
        )

        assertTrue(page.any { stillAwaitsCosting(it) })
    }
}
