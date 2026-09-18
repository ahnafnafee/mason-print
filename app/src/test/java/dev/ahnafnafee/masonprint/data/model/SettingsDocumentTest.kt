package dev.ahnafnafee.masonprint.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability derivation, against GMU's real `/settings?expanded=` document.
 *
 * This is the file that makes the clone portable: nothing here is configured per campus, everything
 * is read. Two of these assertions are the reason — GMU says `"Add Funds":"Deny"` and
 * `"Credit Card Gateway":"No"`, and a client that hardcodes "students can top up in app" is wrong
 * the moment it is installed somewhere that does, or somewhere like GMU where money can only arrive
 * from the campus bursar.
 */
class SettingsDocumentTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) { "missing /gmu/$name" }
            .bufferedReader().use { it.readText() }

    private val settings = SettingsDocument(fixture("settings-gmu.json").toJsonObject())
    private val user = PharosUser.from(fixture("logon-success.json").toJsonObject())
    private val caps = settings.capabilities("4.11.24.1", user)

    // ------------------------------------------------------------------ section lookups

    @Test
    fun `lookups ignore the server's inconsistent casing`() {
        // The document mixes `PrintCenter`, `SecureRelease`, `Authentication`; keys inside them mix
        // Title Case and camelCase (`sso-type` next to `Client Links`). Case-sensitive parsing is
        // the single most likely way to build a client that works only against one deployment.
        assertNotNull(settings.str("PrintCenter", "add funds"))
        assertNotNull(settings.str("printcenter", "ADD FUNDS"))
        assertEquals("Deny", settings.str("PrintCenter", "Add Funds"))
    }

    @Test
    fun `deny and no read as false, allow and yes read as true, and nothing is true by default only where the vendor says so`() {
        assertFalse(caps.addFundsEnabled)
        assertTrue(caps.finishingUpdateAllowed) // `"Finishing Options Update":"Allow"`
        assertTrue(caps.webUploadAllowed)       // `"Web Upload":"Allow"`
        assertTrue(caps.qrReleaseEnabled)       // `"EnableCameraScanner":true`
    }

    // --------------------------------------------------------------------------- gateway

    @Test
    fun `no gateway is switched on when the credit card gateway says No`() {
        assertEquals(Gateway.NONE, caps.gateway)
        assertFalse("`canAddFunds` needs the feature AND a way to pay", caps.canAddFunds)
    }

    @Test
    fun `a gateway is only claimed when its own keys are present, and a URL counts`() {
        val withPaypal = gatewayOf(
            """{"PrintCenter":{"Credit Card Gateway":"Yes","Add Funds":"Allow"},""" +
                """"Paypal":{"URL":"https://paypal.example/cgi-bin/webscr"}}""",
        )
        assertEquals(Gateway.PAYPAL_WEB_ACCEPT, withPaypal.gateway)
        assertTrue(withPaypal.canAddFunds)
        // Two independent switches: a configured gateway is not permission to add funds. GMU is the
        // mirror image — `"Add Funds":"Deny"` behind gateways that are configured but switched off.
        val gatewayButDenied = gatewayOf(
            """{"PrintCenter":{"Credit Card Gateway":"Yes","Add Funds":"Deny"},""" +
                """"Paypal":{"URL":"https://paypal.example/cgi-bin/webscr"}}""",
        )
        assertEquals(Gateway.PAYPAL_WEB_ACCEPT, gatewayButDenied.gateway)
        assertFalse("`Add Funds: Deny` must win over the gateway's presence", gatewayButDenied.canAddFunds)

        // The credentials live server-side, so a deployment may publish only the endpoint. Reading
        // credentials alone would misclassify this as campus billing and send the user to a route
        // that does not exist. GMU publishes both vendor sections at once, which is why the order
        // is a stated preference (CyberSource first) rather than discovery.
        assertEquals(
            Gateway.CYBERSOURCE,
            gatewayOf("""{"PrintCenter":{"Credit Card Gateway":"Yes"},"CyberSource":{"URL":"https://secureacceptance.cybersource.com/smartpay/pay"}}""").gateway,
        )
        assertEquals(
            Gateway.CYBERSOURCE,
            gatewayOf("""{"PrintCenter":{"Credit Card Gateway":"Yes"},"CyberSource":{"URL":"https://x"},"Paypal":{"URL":"https://y"}}""").gateway,
        )

        val saysYesButNamesNoVendor = gatewayOf("""{"PrintCenter":{"Credit Card Gateway":"Yes"}}""")
        assertEquals(
            "the switch is on, so money can be added; `POST /payment/transaction` names the real " +
                "endpoint, so the app offers the action instead of guessing a vendor",
            Gateway.CAMPUS_BILLING, saysYesButNamesNoVendor.gateway,
        )
        assertEquals(Gateway.NONE, gatewayOf("""{"PrintCenter":{"Credit Card Gateway":"No"},"CyberSource":{"URL":"https://x"}}""").gateway)
    }

    /** Capabilities from an inline settings document — used to probe the vendor-switch matrix. */
    private fun gatewayOf(json: String) = SettingsDocument(json.toJsonObject()).capabilities("4.11", null)

    // ------------------------------------------------------------------------------- sso

    @Test
    fun `GMU is CAS with the uid attribute and no override of the identity host`() {
        val sso = caps.sso
        assertTrue(sso is Sso.Cas)
        assertEquals("uid", (sso as Sso.Cas).idpLogonAttribute)
        assertNull("`sso-server` is absent, so the clone must not invent an IdP host", sso.idpHost)
    }

    @Test
    fun `a deployment with no Authentication section is treated as local passwords`() {
        val local = SettingsDocument(buildJsonObject { put("PrintCenter", buildJsonObject {}) }).capabilities("4.11", null)
        assertEquals(Sso.Local, local.sso)
    }

    @Test
    fun `anything other than CAS is recorded rather than guessed at`() {
        val other = SettingsDocument(
            buildJsonObject {
                put("Authentication", buildJsonObject { put("sso-type", "SAML") })
            },
        ).capabilities("4.11", null)
        assertTrue("an SSO type the clone does not implement must still be visible, not silently local", other.sso is Sso.Other)
    }

    // ----------------------------------------------------------------------------- money

    @Test
    fun `the upload ceiling and currency come from the document, not from a constant`() {
        assertEquals(52_428_800L, caps.maxUploadBytes)
        assertEquals("GMU", caps.chargingModel)
        assertEquals("GMU Print Center Bank", caps.bankName)
        // Currency lives under `Localisation`, not `PrintCenter` — reading the wrong section yields
        // the built-in `$0.00` and looks correct on every campus that happens to be American.
        assertEquals("$0.00", settings.str("Localisation", "Currency Format"))
        assertEquals("$0.00", caps.formats.currencyFormat)
        assertEquals("$1.25", caps.formats.money(1.25))
        assertEquals("$0.10", caps.formats.money(0.1))
        assertEquals("$0.00", caps.formats.money(0.0))
        assertEquals("—", caps.formats.money(null))
    }

    @Test
    fun `the currency pattern is read as symbol plus position, and no locale formatter is invented`() {
        fun fmt(pattern: String) = SettingsDocument(
            """{"Localisation":{"Currency Format":"$pattern"}}""".toJsonObject(),
        ).formats()
        assertEquals("$1.25", fmt("$0.00").money(1.25))
        assertEquals("1.25 €", fmt("0.00 €").money(1.25))     // symbol after, spaced
        assertEquals("£1.25", fmt("£0.00").money(1.25))
        assertEquals("Fr.1.25", fmt("Fr.0.00").money(1.25))   // multi-character symbol
        assertEquals("1.25 USD", fmt("0.00 USD").money(1.25)) // ISO code, grouped
        assertEquals("¥1,234.50", fmt("¥0,000.00").money(1234.5))
        assertEquals(
            "a pattern that groups must get grouping, and one that does not must not",
            "1,234,567.00", fmt("0,000,000.00").money(1234567.0),
        )
        assertEquals("1234567.00", fmt("0.00").money(1234567.0))
        assertEquals(
            "in `0,00` the comma is the decimal separator, not a group separator — reading it as " +
                "grouping would multiply every European balance by a thousand",
            "1,25 €", fmt("0,00 €").money(1.25),
        )
        assertEquals(
            "a single-section pattern puts the minus in front of the digits, because that is what " +
                ".NET does with `$0.00` and therefore what the portal shows",
            "$-1.25", fmt("$0.00").money(-1.25),
        )
        assertEquals("($1.25)", fmt("\$0.00;(\$0.00)").money(-1.25))
        assertEquals("$1.25", fmt("\$0.00;(\$0.00);-").money(1.25))
        assertEquals("-", fmt("\$0.00;(\$0.00);-").money(0.0))
    }

    // ---------------------------------------------------------------- privileges as input

    @Test
    fun `without a user there are no cost centre privileges, so no funding chooser`() {
        val anonymous = settings.capabilities("4.11.24.1", null)
        assertFalse("`User.Privileges` only exist once signed in", anonymous.costCentersAllowed)
        assertTrue(caps.costCentersAllowed)
    }

    @Test
    fun `the unknown deployment defaults to a closed app rather than a permissive one`() {
        val unknown = Capabilities.UNKNOWN
        assertFalse(unknown.canUpload)
        assertFalse(unknown.canAddFunds)
        assertFalse(unknown.costCentersAllowed)
        assertEquals(Gateway.NONE, unknown.gateway)
        assertEquals(Sso.Local, unknown.sso)
    }

    @Test
    fun `upload needs both the web switch and the release-side switch`() {
        assertTrue(caps.canUpload)
        val blocked = settings.capabilities("4.11.24.1", user).copy(uploadAllowedByRelease = false)
        assertFalse(blocked.canUpload)
        assertNotNull("uploadBlockReason must explain it to the user", blocked.uploadBlockReason)
    }
}
