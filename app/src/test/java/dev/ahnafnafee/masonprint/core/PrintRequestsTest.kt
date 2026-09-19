package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.FinishingOptions
import dev.ahnafnafee.masonprint.data.model.FinishingPayload
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.encode
import dev.ahnafnafee.masonprint.data.model.putStr
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release/patch/cost bodies, pinned key by key.
 *
 * These three bodies are the whole reason the clone can be trusted with someone's money: which
 * funding key is present decides whose balance moves, and the endpoint distinguishes "absent" from
 * "empty" from "null" in ways that are invisible until a job is charged to the wrong account. Every
 * assertion here is against the vendor bundle's own construction (`script.min.js:1@2416520`, see
 * docs/FUNDING-MODELS.md §2.2), not against a preference.
 */
class PrintRequestsTest {

    private fun job(
        location: String,
        owner: String? = null,
        code: String? = null,
        protectedBy: String? = null,
        copies: Long = 1,
    ) = PrintJob.from(kotlinx.serialization.json.buildJsonObject {
        put("Location", location)
        put("Pending", true)
        owner?.let { put("Owner", it) }
        code?.let { put("CostCenterCode", it) }
        protectedBy?.let { put("ProtectedBy", it) }
        put(
            "FinishingOptions",
            kotlinx.serialization.json.buildJsonObject {
                put("Mono", true)
                put("Duplex", false)
                put("PagesPerSide", "1")
                put("Copies", copies.toString())
                put("DefaultPageSize", "Letter")
                put("PageRange", "")
            },
        )
    })

    private fun body(json: String) = json.toJsonObject()

    private fun jobsOf(json: String): List<JsonObject> =
        body(json).getValue("PrintJobs").jsonArray.map { it.jsonObject }

    private val payload = FinishingPayload.from(FinishingOptions.DEFAULT)

    // ------------------------------------------------------------------ release

    /**
     * The regression guard for the defect that made every release fail.
     *
     * `Owner` on this endpoint is a *redirect*: it asks the server to bill somebody other than the
     * job's owner, which Pharos gates behind `Printing.Administration.ChangeChargingUser` and
     * refuses on an ordinary student account. Because the server populates `Owner` on every job as
     * a plain description of who it belongs to, writing it back made every release a redirect to
     * yourself, and the server answered "Insufficient privileges to change charging user" no matter
     * which funding source was chosen. This client has no redirect feature, so the key never goes.
     */
    @Test
    fun `release never sends Owner, even for a job that has one`() {
        val mine = jobsOf(PrintRequests.release(listOf(job("loc/1", owner = "me")), "dev/1", "card-9")).single()
        assertFalse("Owner is a redirect, never a description", mine.containsKey("Owner"))

        val theirs = jobsOf(PrintRequests.release(listOf(job("loc/2", owner = "other student")), "dev/1", "")).single()
        assertFalse(theirs.containsKey("Owner"))
    }

    /**
     * The funding source the user picked is the only one that counts. Reusing the code stored on
     * the job let the wire disagree with the "Charged to" line the user was reading, which is how a
     * release reported "Asked to charge: My own balance" while billing a department.
     */
    @Test
    fun `release ignores the code stored on the job and honours the chosen source`() {
        val ownPurse = jobsOf(PrintRequests.release(listOf(job("loc/1", code = "10111-M17041")), "dev/1", "")).single()
        assertFalse("no funding key means charge the owner", ownPurse.containsKey("CostCenterCode"))
        assertFalse(ownPurse.containsKey("Owner"))

        val chosen = jobsOf(
            PrintRequests.release(listOf(job("loc/1")), "dev/1", "", costCenterCode = "10111-M17041"),
        ).single()
        assertEquals("10111-M17041", chosen.getValue("CostCenterCode").jsonPrimitive.content)
    }

    @Test
    fun `release omits both funding keys for an ordinary purse job`() {
        // Empty means "my purse" here, and the bundle omits the key rather than sending ""
        // (which is what PATCH does). Sending "" would be a different request.
        val j = jobsOf(PrintRequests.release(listOf(job("loc/1")), "dev/1", "")).single()
        assertFalse(j.containsKey("CostCenterCode"))
        assertFalse(j.containsKey("Owner"))
    }

    @Test
    fun `release override replaces the code the job already carried`() {
        val j = jobsOf(
            PrintRequests.release(listOf(job("loc/1", code = "OLD-CODE")), "dev/1", "", costCenterCode = "10111-M17041"),
        ).single()
        assertEquals("10111-M17041", j.getValue("CostCenterCode").jsonPrimitive.content)
    }

    @Test
    fun `release always sends Device and CardId, and Device may be null`() {
        val b = body(PrintRequests.release(listOf(job("loc/1")), null, null))
        assertTrue("`Device` must be present even when unknown", b.containsKey("Device"))
        assertEquals(JsonNull, b.getValue("Device"))
        assertEquals("", b.getValue("CardId").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `release sends Password only for a protected job it has a password for`() {
        val protected = job("loc/1", protectedBy = "Pin")
        val open = job("loc/2")
        val with = jobsOf(PrintRequests.release(listOf(protected), "dev/1", "", passwords = mapOf("loc/1" to "1234")))
        assertEquals("1234", with.single().getValue("Password").jsonPrimitive.content)

        val missing = jobsOf(PrintRequests.release(listOf(protected), "dev/1", "")).single()
        assertFalse("no password supplied → key omitted, not blank", missing.containsKey("Password"))

        val ignored = jobsOf(PrintRequests.release(listOf(open), "dev/1", "", passwords = mapOf("loc/2" to "1234"))).single()
        assertFalse("an unprotected job never carries a password", ignored.containsKey("Password"))
    }

    @Test
    fun `release always carries FinishingOptions, as an empty object when the job has none`() {
        val bare = PrintJob.from(kotlinx.serialization.json.buildJsonObject { put("Location", "loc/1") })
        val j = jobsOf(PrintRequests.release(listOf(bare), "dev/1", "")).single()
        assertTrue("the bundle always writes the key, `{}` when there is nothing to say", j.containsKey("FinishingOptions"))
        assertEquals(emptySet<String>(), (j.getValue("FinishingOptions") as JsonObject).keys)
    }

    // --------------------------------------------------------------------- patch

    @Test
    fun `update always carries a top-level CostCenterCode, empty meaning charge my purse`() {
        val b = body(PrintRequests.update(listOf(job("loc/1")), null, null, null))
        assertTrue(b.containsKey("CostCenterCode"))
        assertEquals("", b.getValue("CostCenterCode").jsonPrimitive.content)

        val charged = body(PrintRequests.update(listOf(job("loc/1")), null, null, "10111-M17041"))
        assertEquals("10111-M17041", charged.getValue("CostCenterCode").jsonPrimitive.content)
    }

    @Test
    fun `update sends FinishingOptions once when every selected job agrees`() {
        val b = body(PrintRequests.update(listOf(job("loc/1"), job("loc/2")), payload, null, null))
        assertTrue(b.containsKey("FinishingOptions"))
        jobsOf(b.encode()).forEach { assertFalse("redundant per-job copy", it.containsKey("FinishingOptions")) }
    }

    @Test
    fun `update moves FinishingOptions per job when the selection disagrees`() {
        val b = body(PrintRequests.update(listOf(job("loc/1", copies = 1), job("loc/2", copies = 3)), payload, null, null))
        assertFalse("no shared key when the jobs differ", b.containsKey("FinishingOptions"))
        jobsOf(b.encode()).forEach { assertTrue(it.containsKey("FinishingOptions")) }
    }

    @Test
    fun `update keeps a job's own code when no override is chosen`() {
        val j = jobsOf(PrintRequests.update(listOf(job("loc/1", code = "10111-M17041")), null, null, null)).single()
        assertEquals("10111-M17041", j.getValue("CostCenterCode").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------- upload

    @Test
    fun `upload meta data nests the finishing options and sends counts as strings`() {
        val body = PrintRequests.metaData(payload, null).toJsonObject()
        // `MetaData` is one object with `PrinterName` at the top and the options under
        // `FinishingOptions` — flattening it is invisible until the server silently ignores
        // the defaults and prints double-sided.
        val meta = body.getValue("FinishingOptions").jsonObject
        assertEquals("1", meta.getValue("PagesPerSide").jsonPrimitive.content)
        assertTrue("`Copies` as an integer makes 4.11.24.1 return 500", meta.getValue("Copies").jsonPrimitive.isString)
        assertFalse(body.containsKey("PrinterName"))
        assertEquals(
            "dev/316",
            PrintRequests.metaData(payload, "dev/316").toJsonObject().getValue("PrinterName").jsonPrimitive.content,
        )
    }

    @Test
    fun `patched finishing options use the Yes and No encoding while upload uses booleans`() {
        val o = payload.copy(mono = true, duplex = false).forJobUpdate()
        assertEquals("Yes", o.getValue("Mono").jsonPrimitive.content)
        assertEquals("No", o.getValue("Duplex").jsonPrimitive.content)
        // …while the upload part keeps real booleans. Both shapes are what the vendor sends, one
        // per call site: `MetaData` is parsed by the upload handler, `PATCH` by the job model.
        assertEquals(false, payload.forUpload().getValue("Mono").jsonPrimitive.boolean)
        assertEquals(true, payload.copy(mono = true).forUpload().getValue("Mono").jsonPrimitive.boolean)
        assertTrue("counts are strings in both shapes", payload.forUpload().getValue("Copies").jsonPrimitive.isString)
    }

    @Test
    fun `delete is a DELETE with a body carrying only locations`() {
        val json = PrintRequests.delete(listOf(job("loc/1", code = "X", owner = "y"), job("loc/2")))
        val list = json.toJsonObject().getValue("PrintJobs").jsonArray
        assertEquals(2, list.size)
        list.forEach { assertTrue("no funding keys leak into a delete", it.jsonObject.keys == setOf("Location")) }
        assertNull(json.toJsonObject()["CostCenterCode"])
    }
}
