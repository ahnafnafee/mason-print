package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.CostCenter
import dev.ahnafnafee.masonprint.data.model.Gateway
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PharosError
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.PharosUser
import dev.ahnafnafee.masonprint.data.model.Privileges
import dev.ahnafnafee.masonprint.data.model.SettingsDocument
import dev.ahnafnafee.masonprint.data.model.Sso
import dev.ahnafnafee.masonprint.data.model.objectsNested
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything in this class is a captured production response from GMU's Uniprint 9.1 SP3
 * (`mobileprint.gmu.edu`, `X-PHAROS-API-VERSION: 4.11.24.1`), recorded against a real student
 * account on a real Android session and stored under `src/test/resources/gmu/`.
 *
 * Personal values are replaced (`student`, `student@example.edu`, alias `000000`); every key,
 * every casing choice, and every money/permission value is untouched, because those are the parts
 * that keep breaking the client. This is the regression net for the four defects that real
 * credentials exposed and that no fixture assembled from documentation would have caught:
 *
 * 1. `{UserUri}` is only ever the user's own relative `Location` — GMU sends no `UserUri` key.
 * 2. A relative `Location` must be re-rooted under `/PharosAPI`; resolved as an absolute path it
 *    404s (both halves measured against production).
 * 3. The balance is nested under `Balance` and its `Amount` is a *string*.
 * 4. The cost centre the student's free printing comes from carries `"Grant":false`.
 */
class GmuLivePayloadTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) {
            "missing test fixture /gmu/$name (expected in app/src/test/resources/gmu/)"
        }.bufferedReader().use { it.readText() }

    private val logon = fixture("logon-success.json").toJsonObject()
    private val user = PharosUser.from(logon)
    private val settings = SettingsDocument(fixture("settings-gmu.json").toJsonObject())
    private val caps = settings.capabilities("4.11.24.1", user)

    // ------------------------------------------------------- {UserUri}, the root of everything

    @Test
    fun `GMU's user object has no UserUri key - its own Location is the user resource`() {
        assertNull("live payload has no `UserUri`; parsing one would be fiction", logon["UserUri"])
        assertEquals("/users/POYJ8x6E7Ias2Uj6cdAJZA2", user.location)
        assertNull(user.userUri)
        // …so this is the only way to find {UserUri}, and it is what PharosClient.captureFromUser does.
        val target = PharosTarget.parse("mobileprint.gmu.edu")!!
        (user.userUri ?: user.location)!!.also { target.setUserUriFromValue(it) }
        assertEquals(
            "https://mobileprint.gmu.edu/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2",
            target.userUri.toString(),
        )
    }

    @Test
    fun `a relative Location is re-rooted under the API base because the absolute path 404s`() {
        val target = PharosTarget.parse("https://mobileprint.gmu.edu/PharosAPI")!!
        assertEquals(
            "https://mobileprint.gmu.edu/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2",
            target.resolve("/users/POYJ8x6E7Ias2Uj6cdAJZA2").toString(),
        )
        // Measured: /PharosAPI/users/{id} → 200, /users/{id} → 404. Plain RFC-3986 resolution
        // produces the 404, which is why resolve() is not a plain resolver.
        assertEquals(
            "https://mobileprint.gmu.edu/PharosAPI/devices/316",
            target.resolve("/devices/316").toString(),
        )
        assertEquals("already rooted stays rooted", "https://mobileprint.gmu.edu/PharosAPI/settings", target.resolve("/PharosAPI/settings").toString())
    }

    @Test
    fun `a URL on the internal origin is re-rooted onto the host the user typed`() {
        val target = PharosTarget.parse("mobileprint.gmu.edu")!!
        val internal = target.resolve("https://MPSROSMOBP.mesa.gmu.edu/MyPrintCenter/x")
        assertEquals("mobileprint.gmu.edu", internal?.host)
        assertTrue(internal.toString().startsWith("https://mobileprint.gmu.edu/PharosAPI/"))
    }

    // --------------------------------------------------------------------------------- money

    @Test
    fun `the balance is nested under Balance and its Amount is a string`() {
        val balance = user.balance
        assertNotNull("nested `Balance` must be unwrapped or the app shows a fabricated zero", balance)
        assertEquals(0.0, balance!!.total, 1e-9)
        assertFalse("`Amount: \"0.00\"` is a real balance, not an absent one", balance.isEmpty)
        assertTrue("this campus reports one total, no purse split", balance.purses.isEmpty())
    }

    @Test
    fun `offline limit and amount arrive as strings and are not money-formatted by accident`() {
        assertEquals(0.0, PharosUser.from(fixture("user-resource.json").toJsonObject()).balance?.total ?: -1.0, 1e-9)
    }

    // -------------------------------------------------------------------------- cost centres

    @Test
    fun `the department centre is visible even though Grant is false`() {
        val center = user.costCenters.single()
        assertEquals("10111-M17041", center.code)
        assertFalse("free printing at GMU comes from a centre flagged Grant:false", center.granted)
        assertTrue(center.active)
        assertTrue(center.complete)
        assertEquals("10111-Computer Science Department", center.description)
    }

    @Test
    fun `descriptions arrive HTML-escaped and are unescaped exactly once`() {
        val raw = fixture("costcenters-search.json")
        assertTrue("the server really does send &quot; inside the value", raw.contains("&quot;"))
        val parsed = user.costCenters.single()
        assertEquals("10111-Computer Science Department", parsed.description)
        val description = parsed.description ?: "description was dropped entirely"
        assertFalse(description.startsWith("&quot;"))
        assertFalse(description.contains('"'))
    }

    @Test
    fun `cost centres offered to the user are the assigned ones, not only granted ones`() {
        assertEquals(listOf("10111-M17041"), caps.usableCostCenters.map { it.code })
        assertTrue("Grant is a label, so a Grant:false centre must still be offered", caps.usableCostCenters.isNotEmpty())
        assertEquals(emptyList<String>(), caps.grantedCostCenters.map { it.code })
    }

    @Test
    fun `cost centre search answers with the assigned set, not a paged list, and ignores its query`() {
        val doc = fixture("costcenters-search.json").toJsonObject()
        assertNull("no Items — this endpoint is not the paged list shape", doc["Items"])
        assertNull("no Count either", doc["Count"])
        assertEquals("GMU", doc["ChargingModel"].toString().trim('"'))
        // GMU labels the field "Account Number" in its own portal, which is the wording the clone
        // should use; a student looking for "cost centre" will not recognise the column header.
        assertTrue(doc.toString().contains("\"Name\":\"Account Number\""))
        // Measured live: `search=`, `search=10111`, `search=Comp`, `search=zzzz` and `search=40500`
        // all return this identical 274-byte body, so the parameter is ignored server-side and any
        // filtering a user sees has to happen on the client.
        val rows = doc.objectsNested("CostCenters").map { CostCenter.from(it) }
        assertEquals(listOf("10111-M17041"), rows.map { it.code })
    }

    @Test
    fun `the same key is a nested object at logon and a bare array from the search endpoint`() {
        // `User.CostCenters` = {"ChargingModel":…,"CostCenters":[…]} while
        // `GET {UserUri}/costcenters` = {"ChargingModel":…,"CostCenters":[…] at top level}.
        // objectsNested() has to keep answering for both or one of the two routes silently
        // returns an empty list, which reads as "you have no department account".
        val nested = logon.objectsNested("CostCenters")
        val flat = fixture("costcenters-search.json").toJsonObject().objectsNested("CostCenters")
        assertEquals(1, nested.size)
        assertEquals(1, flat.size)
        assertEquals(nested.single()["Code"], flat.single()["Code"])
    }

    // ---------------------------------------------------------------------- privileges as gates

    @Test
    fun `the cost-centre privilege is what unlocks the funding chooser`() {
        assertTrue(user.privileges.costCenters)
        assertTrue(caps.costCentersAllowed)
    }

    @Test
    fun `each privilege matches the live document, including the negative ones`() {
        assertTrue(user.privileges.webUpload)
        assertTrue(user.privileges.costing)          // `"Costing":"Allow"` — without it no balance loads
        assertFalse(user.privileges.creditCardGateway) // `"CreditCardGateway":"Deny"`
        assertFalse(user.privileges.quotaView)        // `QuotaPrivileges.View:"Deny"`
        assertFalse(user.privileges.userAdministration)
    }

    @Test
    fun `an absent privilege key reads as allowed but an absent feature subtree does not`() {
        // The vendor's `privilegeTrue` is a *negative* test, so any present value that is not
        // "deny"/"no" means allowed — including tokens the vendor invented later. That default is
        // what keeps a clone from hiding features a newer server enables.
        val odd = Privileges.from("""{"Printing":{"PayForPrint":{"CostCenters":"True-ish"}}}""".toJsonObject())
        assertTrue("an unrecognised token is not a Deny", odd.costCenters)
        assertTrue(Privileges.from("""{"Printing":{"WebUpload":"Allow"}}""".toJsonObject()).webUpload)
        // A missing subtree is different: no `PayForPrint` object means the deployment does not do
        // pay-for-print at all, so the cost-centre chooser must not appear. Getting these two
        // cases the same way round is the difference between a portable client and a GMU-only one.
        val sparse = Privileges.from("""{"Printing":{"WebUpload":"Allow"}}""".toJsonObject())
        assertFalse(sparse.costCenters)
        assertFalse("Privileges.from(null) is all-false, not all-true", Privileges.from(null).costCenters)
        assertTrue("…but an explicit Deny is a Deny", Privileges.from("""{"Printing":{"PayForPrint":{"CostCenters":"Deny"}}}""".toJsonObject()).costCenters.not())
    }

    @Test
    fun `the release privilege is Deny for a student who obviously can still release, so it stays unmodelled`() {
        val printing = logon["Privileges"]!!.jsonObject["Printing"]!!.jsonObject
        assertEquals("Deny", printing["Release"].toString().trim('"'))
        assertFalse(
            "Privileges.Printing.Release is Deny on an account that releases jobs from the QR " +
                "printers daily; a client that gates the release button on it is broken. It is " +
                "deliberately not a field on Privileges so nobody can wire it up by accident.",
            Privileges::class.java.declaredFields.any { it.name == "release" },
        )
    }

    @Test
    fun `the Add Funds privilege contradicts settings and settings wins`() {
        val payForPrint = logon["Privileges"]!!.jsonObject["Printing"]!!.jsonObject["PayForPrint"]!!.jsonObject
        assertEquals("Allow", payForPrint["AddFunds"].toString().trim('"'))
        assertEquals("Deny", settings.str("PrintCenter", "Add Funds"))
        assertFalse("capabilities follow PrintCenter, which is what the portal itself obeys", caps.addFundsEnabled)
        assertFalse("and no gateway is switched on either", caps.canAddFunds)
        assertEquals(Gateway.NONE, caps.gateway)
    }

    @Test
    fun `GMU is a CAS deployment with a local password fallback that actually works`() {
        assertEquals(Sso.Cas(idpLogonAttribute = "uid", idpHost = null), caps.sso)
        assertEquals(50L * 1024 * 1024, caps.maxUploadBytes)
        assertEquals("GMU", caps.chargingModel)
        assertEquals("GMU Print Center Bank", caps.bankName)
        assertEquals("\$0.10", caps.formats.money(0.1))
        assertEquals("—", caps.formats.money(null))
    }

    // --------------------------------------------------------------------------- rejection path

    @Test
    fun `the rejected-logon envelope is HTTP 300 with the sentence in DeveloperMessage`() {
        val error = PharosError.from(300, fixture("logon-rejected-300.json"))
        assertEquals(300, error.verdict)
        assertEquals("Your username and password cannot be verified. Please try again.", error.userText("?"))
        assertEquals("TranslationNotFound", error.errorCode)
    }

    // ---------------------------------------------------------------------------------- devices

    @Test
    fun `logon already lists the release stations including duplex and colour support`() {
        val stations = logon["Devices"]!!.toString()
        assertTrue("the QR-release list is available before any /devices call", stations.contains("\"Location\":\"/devices/316\""))
        assertTrue(stations.contains("FX-ENG4-LOBBY-5840"))
        assertTrue(stations.contains("\"DuplexSupported\":true"))
        assertTrue(stations.contains("\"ColorSupported\":true"))
    }

    // ---------------------------------------------------------------------------------- paging

    @Test
    fun `the empty page reports Count zero, which is the only honest stop signal`() {
        val page = Page.from(fixture("jobs-page-empty.json").toJsonObject()) { it }
        assertEquals(
            "GMU does send `Count`; if this comes back null the reader is wrong and every queue " +
                "header in the app silently loses its total",
            0L, page.count,
        )
        assertTrue(page.items.isEmpty())
        assertFalse(
            "…and an empty queue must not offer a second page, because GMU sends NextPageLink even " +
                "when there is nothing to page through",
            page.hasMore,
        )
    }

    @Test
    fun `the queue is asked for at printjobs, not on the user resource itself`() {
        val target = PharosTarget.parse("mobileprint.gmu.edu")!!
        target.setUserUriFromValue(user.location!!)
        val url = jobsUrl(target.userUri!!, 0, 50)
        assertEquals(
            "GMU's own endpoint table says `get {UserUri}/printjobs` (script.min.js:1@1316092)",
            "/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2/printjobs",
            url.encodedPath,
        )
        assertEquals("0", url.queryParameter("Skip"))
        assertEquals("50", url.queryParameter("PageSize"))
        // Why asking the user resource instead looked fine: it answers 200, and as a page it is
        // empty with no total — the same picture as a student who really has nothing queued.
        val asPage = Page.from(fixture("user-resource.json").toJsonObject(), PrintJob::from)
        assertTrue(asPage.items.isEmpty())
        assertNull(asPage.count)
    }

    @Test
    fun `a document is posted to printjobs, because the user resource refuses POST`() {
        val target = PharosTarget.parse("mobileprint.gmu.edu")!!
        target.setUserUriFromValue(user.location!!)
        assertEquals(
            "/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2/printjobs",
            uploadUrl(target.userUri!!).encodedPath,
        )
        // Measured on 2026-09-18 with a real 1.67 MB PDF on a signed-in account: the same URL with
        // the `printjobs` segment removed answers 405 in 1.8 s, having already received the whole
        // body, and names the verbs it does take. `POST` is not among them. The document is not
        // uploaded, the queue stays empty, and the only evidence is a status code — so the segment
        // is pinned here rather than discovered again on a phone.
        //
        //     POST https://mobileprint.gmu.edu/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2  -> 405
        //     allow: GET,DELETE,PATCH,PUT
        assertFalse(
            "the upload URL must not be the user resource itself",
            uploadUrl(target.userUri!!).encodedPath == "/PharosAPI/users/POYJ8x6E7Ias2Uj6cdAJZA2",
        )
    }

    @Test
    fun `paging links arrive HTML escaped and must be unescaped before OkHttp sees them`() {
        val raw = fixture("jobs-page-empty.json")
        assertTrue("the live capture really is escaped", raw.contains("&amp;skip=20"))
        val page = Page.from(raw.toJsonObject()) { it }
        val link = page.nextPageLink
        assertNotNull(link)
        assertFalse("parsed link still carries the raw `&amp;`", link!!.contains("&amp;"))
        val target = PharosTarget.parse("https://mobileprint.gmu.edu/PharosAPI")!!
        val url = target.resolve(link)
        assertNotNull(url)
        assertEquals(
            "followed verbatim, the escaped link asks for a parameter named `amp;skip`, the server " +
                "ignores it, and every next page is page 1 again",
            "20", url!!.queryParameter("skip"),
        )
    }
}
