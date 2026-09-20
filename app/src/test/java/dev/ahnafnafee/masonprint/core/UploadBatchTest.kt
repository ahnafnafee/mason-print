package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.PharosError
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.data.net.UploadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping of a multi-file pick.
 *
 * A batch is where an upload UI most easily starts lying: it has four files, one progress bar, and
 * one word ("Sent") available to describe all of them. Everything asserted here is a place where
 * that compression would produce a sentence that is not true — a file held back for its size counted
 * as refused, a percentage for one document read as the progress of four, a send that stopped at
 * file 2 of 5 reported as though the other three had been tried.
 *
 * Pure JVM, no Robolectric: the batch logic deliberately holds no Android types. `UploadSource`'s
 * opener is never called by any of it, which is exactly why a document can be planned, named, and
 * reported on without a content resolver in the room.
 */
class UploadBatchTest {

    /** A handle that would fail loudly if the batch logic ever tried to read the file itself. */
    private fun source(name: String, sizeBytes: Long): UploadSource =
        UploadSource(name, "application/pdf", sizeBytes) { throw AssertionError("the batch must not open $name") }

    private val mib = 1024 * 1024

    // ------------------------------------------------------------------ what may be sent at all --

    @Test
    fun `a file over the published limit is held back and the rest still go`() {
        val limit = 50L * mib
        val plan = planUpload(
            listOf(source("essay.pdf", 2L * mib), source("scan.pdf", 80L * mib), source("handout.pdf", 1L * mib)),
            limit,
        )
        assertEquals(listOf("essay.pdf", "handout.pdf"), plan.send.map { it.fileName })
        assertEquals(listOf("scan.pdf"), plan.overLimit.map { it.fileName })
        assertFalse(plan.nothingToSend)
    }

    @Test
    fun `the order the user picked in is the order the files go up`() {
        val sources = (1..4).map { source("doc$it.pdf", it.toLong()) }
        assertEquals(listOf("doc1.pdf", "doc2.pdf", "doc3.pdf", "doc4.pdf"), planUpload(sources, 9L * mib).send.map { it.fileName })
    }

    @Test
    fun `every file over the limit means nothing is sent, which is not the same as a refusal`() {
        val plan = planUpload(listOf(source("a.pdf", 60L * mib), source("b.pdf", 70L * mib)), 50L * mib)
        assertTrue(plan.nothingToSend)
        assertTrue(plan.send.isEmpty())
        assertEquals(2, plan.overLimit.size)
    }

    @Test
    fun `no published limit means nothing is held back`() {
        // GMU publishes `Maximum Allowed Upload`; a deployment that does not publish one still has a
        // server, and the server's 413 is a better authority than this app guessing.
        val sources = listOf(source("huge.pdf", 900L * mib))
        val plan = planUpload(sources, null)
        assertTrue(plan.send.isNotEmpty())
        assertTrue(plan.overLimit.isEmpty())
    }

    @Test
    fun `a limit of zero is no limit, not a prohibition`() {
        // `Maximum Allowed Upload: 0` has to read as "not stated". Treated as a real allowance, every
        // file would be held back and the app would refuse to print anything at all.
        val plan = planUpload(listOf(source("a.pdf", 1L * mib)), 0L)
        assertEquals(1, plan.send.size)
        assertTrue(plan.overLimit.isEmpty())
    }

    @Test
    fun `a size the provider never reported is sent, not held back`() {
        // Some providers answer the size query with nothing at all, which UploadFactory records as -1.
        // "unknown" is not evidence of "too big", so the server gets to decide.
        val plan = planUpload(listOf(source("mystery.pdf", -1L)), 1L * mib)
        assertEquals(listOf("mystery.pdf"), plan.send.map { it.fileName })
        assertTrue(plan.overLimit.isEmpty())
    }

    // ------------------------------------------------------------------ where in the batch are we --

    @Test
    fun `a single file has no position`() {
        assertNull(batchPosition(1, 1))
    }

    @Test
    fun `position is one-based for people and zero means nothing is transferring`() {
        assertEquals("2 of 3", batchPosition(2, 3))
        assertNull(batchPosition(0, 3))
    }

    @Test
    fun `the busy line names the file either way`() {
        assertEquals("Uploading essay.pdf", sendingLabel("essay.pdf", 1, 1))
        assertEquals("Sending 2 of 3 · essay.pdf", sendingLabel("essay.pdf", 2, 3))
    }

    // ------------------------------------------------------------------ what the bar is counting --

    @Test
    fun `the progress line of a single file does not pretend there is a batch`() {
        val detail = uploadProgressDetail(listOf(PickedFile("essay.pdf", "application/pdf", 1_670_000L)), 1, 0.4f)
        assertEquals("Uploading essay.pdf · 40%", detail)
    }

    @Test
    fun `the progress line of a batch says which file the bar belongs to`() {
        val files = listOf(
            PickedFile("a.pdf", "application/pdf", 1L),
            PickedFile("b.pdf", "application/pdf", 1L),
            PickedFile("c.pdf", "application/pdf", 1L),
        )
        val detail = uploadProgressDetail(files, 2, 1f)
        assertEquals("Finishing upload · file 2 of 3 · b.pdf", detail)
        assertFalse("Writing all bytes is not confirmation of queue arrival", detail.contains("added to queue"))
    }

    @Test
    fun `a fraction cannot run past the bar it is describing`() {
        val files = listOf(PickedFile("a.pdf", "application/pdf", 1L))
        assertTrue(uploadProgressDetail(files, 1, 1.8f).startsWith("Finishing upload"))
        assertTrue(uploadProgressDetail(files, 1, -0.5f).contains("0%"))
    }

    @Test
    fun `an unknown file list still produces a sentence rather than a crash`() {
        // AppState is populated before the first progress callback lands; the bar can outlive the list.
        assertTrue(uploadProgressDetail(emptyList(), 0, 0.5f).startsWith("Uploading the document · 50%"))
    }

    // ------------------------------------------------------------------ which failures stop a batch --

    @Test
    fun `a refusal about one document does not stop the others`() {
        val perFile = listOf(
            PharosFailure.TooLarge(50L * mib, "Request body too large"),
            PharosFailure.UnsupportedType("dwg", "Unsupported file type"),
            PharosFailure.Server(errorEnvelope(500)),
            PharosFailure.Server(errorEnvelope(409)),
            PharosFailure.NotFound("PharosAPI/printjobs"),
        )
        perFile.forEach { assertFalse("$it should not stop the batch", it.stopsBatch) }
    }

    @Test
    fun `a failure about the connection or the account stops the batch`() {
        val shared = listOf(
            PharosFailure.Offline,
            PharosFailure.Timeout(writePhase = true),
            PharosFailure.TlsNotTrusted("mobileprint.gmu.edu", "AA:BB", "CN=GMU"),
            PharosFailure.Unauthenticated(errorEnvelope(300)),
            PharosFailure.UploadDenied(errorEnvelope(403), "This account cannot upload"),
            PharosFailure.NotAPharosServer("nginx"),
        )
        shared.forEach { assertTrue("$it should stop the batch", it.stopsBatch) }
    }

    private fun errorEnvelope(status: Int) =
        PharosError(status, "/PharosAPI/printjobs", null, null, null, null, null, null, bodyStatus = status)

    // ------------------------------------------------------------------ why files never went --

    @Test
    fun `one oversized file is named with its size`() {
        val sentence = oversizeSentence(listOf(source("scan.pdf", 80L * mib)), 50L * mib)
        assertEquals("scan.pdf is 80 MB; this server accepts 50 MB", sentence)
    }

    @Test
    fun `several oversized files are all named, because the user has to know which to fix`() {
        val sentence = oversizeSentence(listOf(source("a.pdf", 80L * mib), source("b.pdf", 61L * mib)), 50L * mib)
        assertEquals(
            "a.pdf (80 MB), b.pdf (61 MB) are over the 50 MB limit this server publishes, " +
                "so none of them were sent",
            sentence,
        )
    }

    @Test
    fun `a file with no reported size is not described as zero bytes`() {
        val sentence = oversizeSentence(listOf(source("mystery.pdf", -1L)), 50L * mib)
        assertEquals("mystery.pdf is an unknown size; this server accepts 50 MB", sentence)
    }

    // ------------------------------------------------------------------ the one honest sentence --

    @Test
    fun `nothing at all`() {
        assertEquals("Nothing was uploaded.", batchSummary(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `a whole batch that arrived`() {
        assertEquals("1 document added to queue", batchSummary(listOf("essay.pdf"), emptyList(), emptyList()))
        assertEquals("3 documents added to queue", batchSummary(listOf("a.pdf", "b.pdf", "c.pdf"), emptyList(), emptyList()))
    }

    @Test
    fun `an unconfirmed response does not assert the document never arrived`() {
        assertEquals("No uploads were confirmed. Check the queue.", batchSummary(emptyList(), listOf("a.pdf"), emptyList()))
    }

    @Test
    fun `partial success counts refused oversized and unattempted documents`() {
        assertEquals("2 of 5 documents added to queue",
            batchSummary(listOf("a.pdf", "b.pdf"), listOf("c.pdf"), listOf("d.pdf"), listOf("e.pdf")))
        assertEquals("No uploads were confirmed. Check the queue.",
            batchSummary(emptyList(), listOf("a.pdf"), listOf("b.pdf"), listOf("c.pdf")))
    }

    // ------------------------------------------------------------------ the value the UI can hold --

    @Test
    fun `a picked file carries the extension the type check needs`() {
        val file = PickedFile("Thesis FINAL.PDF", "application/pdf", 12L)
        assertEquals("pdf", file.extension)
        assertEquals("Thesis FINAL.PDF", file.name)
    }

    @Test
    fun `two resolutions of the same document are one row, which is why this is not UploadSource`() {
        val a = PickedFile("essay.pdf", "application/pdf", 5L)
        val b = PickedFile("essay.pdf", "application/pdf", 5L)
        assertEquals(a, b)
        assertEquals(listOf(a), listOf(a, b).distinct())
    }
}
