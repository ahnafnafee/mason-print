package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.Capabilities
import dev.ahnafnafee.masonprint.data.model.Gateway
import dev.ahnafnafee.masonprint.data.model.PharosUser
import dev.ahnafnafee.masonprint.data.model.Purse
import dev.ahnafnafee.masonprint.data.model.SettingsDocument
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Account screen's pure logic: the refusal ledger, the purse-order sentence, and the words put
 * next to a transaction row.
 *
 * The ledger is tested against GMU's real `/settings` document rather than a hand-built one, because
 * the whole claim of the card is that it prints answers the server actually gave. GMU answers
 * `"Add Funds":"Deny"` and `"Credit Card Gateway":"No"` but `Web Upload: Allow` and
 * `EnableCameraScanner: true`, so the correct ledger has exactly two rows — and a screen that showed
 * five would be inventing refusals, which is the same failure as showing a button that fails.
 */
class AccountScreenTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/gmu/$name")) { "missing /gmu/$name" }
            .bufferedReader().use { it.readText() }

    private val gmu: Capabilities = SettingsDocument(fixture("settings-gmu.json").toJsonObject())
        .capabilities("4.11.24.1", PharosUser.from(fixture("logon-success.json").toJsonObject()))

    private fun txn(
        type: String? = null,
        sub: String? = null,
        amount: Double? = null,
        description: String? = null,
        purse: String? = null,
    ) = Transaction(
        identifier = null, cashier = null, purse = purse, user = null, time = null,
        subType = sub, transactionType = type, amount = amount, fee = null,
        description = description, costCenters = null, application = null, jobName = null,
        reason = null, server = null, printer = null, bank = null, offline = null,
        pages = null, sheets = null,
    )

    // --------------------------------------------------------------- the refusal ledger

    @Test
    fun `the ledger lists exactly what GMU refused, in the server's own spelling`() {
        val rows = hiddenFromServer(gmu)
        assertEquals(
            listOf("Add funds", "Credit card gateway"),
            rows.map { it.name },
        )
        assertEquals(listOf("Add Funds: Deny", "CreditCardGateway: No"), rows.map { it.answer })
        assertEquals(
            listOf(
                "No top-up screen and no amount field. Account links to Mason Money instead.",
                "No card fields exist anywhere in the app.",
            ),
            rows.map { it.effect },
        )
    }

    @Test
    fun `a refusal the server never made is not invented`() {
        // GMU says upload is allowed, the camera is on, and cost centres are permitted, so the card
        // must stay silent about all three. An app that lists them anyway tells the student something
        // is missing when nothing is.
        val names = hiddenFromServer(gmu).map { it.name }
        assertFalse(names.contains("Web upload"))
        assertFalse(names.contains("Camera / QR release"))
        assertFalse(names.contains("Cost centers"))
    }

    @Test
    fun `upload blocked by secure release quotes the server's own sentence, not a guess`() {
        val blocked = gmu.copy(uploadAllowedByRelease = false)
        val row = hiddenFromServer(blocked).first { it.name == "Web upload" }
        assertEquals("AllowUpload: false", row.answer)
        assertEquals(blocked.uploadBlockReason, row.effect)
        assertEquals("Secure Release does not allow document upload.", row.effect)
    }

    @Test
    fun `an unchecked web switch is reported as the switch, separately from the release gate`() {
        val row = hiddenFromServer(gmu.copy(webUploadAllowed = false)).first { it.name == "Web upload" }
        assertEquals("WebUpload: false", row.answer)
        assertEquals("This campus has web upload switched off.", row.effect)
    }

    @Test
    fun `add funds allowed without a gateway is its own honest answer`() {
        // Two independent switches. Saying `Add Funds: Deny` when the server said Allow would be a
        // lie about the server; the accurate row names the missing gateway instead.
        val allowed = gmu.copy(addFundsEnabled = true)
        val row = hiddenFromServer(allowed).first { it.name == "Add funds" }
        assertEquals("Add Funds: Allow, gateway: No", row.answer)
    }

    @Test
    fun `a server that answers yes to everything leaves an empty ledger`() {
        val permissive = gmu.copy(
            addFundsEnabled = true,
            gateway = Gateway.CYBERSOURCE,
            webUploadAllowed = true,
            uploadAllowedByRelease = true,
            qrReleaseEnabled = true,
            costCentersAllowed = true,
        )
        assertEquals(emptyList<String>(), hiddenFromServer(permissive).map { it.name })
    }

    @Test
    fun `the closed default produces a ledger and not a crash`() {
        val unknown = Capabilities.UNKNOWN
        val names = hiddenFromServer(unknown).map { it.name }
        assertTrue("an unknown server must read as everything refused", names.contains("Cost centers"))
        assertEquals(
            "No “Charge to” control. Every job comes out of your own balance.",
            hiddenFromServer(unknown).first { it.name == "Cost centers" }.effect,
        )
    }

    // ---------------------------------------------------------------------- purse order

    @Test
    fun `the spending order sentence uses the names and the priority the server published`() {
        val sentence = purseOrderSentence(
            listOf(
                Purse(name = "Semester Credit", amount = 40.0, priority = 2L),
                Purse(name = "Mason Money", amount = 2.65, priority = 1L),
            ),
        )
        assertEquals(
            "The server empties Mason Money before it touches Semester Credit, and it reported " +
                "that order itself. Mason Print cannot change it.",
            sentence,
        )
    }

    @Test
    fun `the numbered list follows the priority field, and an unreported priority goes last`() {
        val ordered = purseSpendOrder(
            listOf(
                Purse(name = "Guest Purse", amount = 1.0, priority = null),
                Purse(name = "Semester Credit", amount = 40.0, priority = 5L),
                Purse(name = "Mason Money", amount = 2.65, priority = 1L),
            ),
        )
        assertEquals(listOf("Mason Money", "Semester Credit", "Guest Purse"), ordered.map { it.name })
    }

    @Test
    fun `one purse means there is no spending order to explain`() {
        val sentence = purseOrderSentence(listOf(Purse(name = "Mason Money", amount = 2.65, priority = 1)))
        assertTrue(sentence.contains("Mason Money"))
        assertFalse(sentence.contains("before it touches"))
    }

    @Test
    fun `no purses says the total is the whole of it instead of naming an invented purse`() {
        val sentence = purseOrderSentence(emptyList())
        assertTrue(sentence.contains("no purses"))
        assertFalse(sentence.contains("Mason Money"))
    }

    // ------------------------------------------------------------------ transaction rows

    @Test
    fun `the badge carries the server's short code, which is what the service desk reads back`() {
        assertEquals("TF", transactionCode(txn(type = "TF")))
        assertEquals("CR", transactionCode(txn(type = "cr")))
        assertEquals("PF", transactionCode(txn(type = " PF ")))
        assertEquals("?", transactionCode(txn()))
    }

    @Test
    fun `a server that sends long type names gets initials instead of a truncated word`() {
        assertEquals("CT", transactionCode(txn(type = "Transfer Funds", sub = "CBORD")))
    }

    @Test
    fun `the server's own description wins over any wording this app could invent`() {
        val description = "Mason Money moved from your campus account into the print purse."
        assertEquals(description, transactionTitle(txn(type = "TF", description = description), emptyList()))
    }

    @Test
    fun `rows are named by their type code when the server sent no description`() {
        assertEquals("Campus transfer in", transactionTitle(txn(type = "TF", amount = 20.0), emptyList()))
        assertEquals(
            "Funds added at a payment gateway",
            transactionTitle(txn(type = "PA", amount = 10.0), emptyList()),
        )
        assertEquals("Payment gateway fee", transactionTitle(txn(type = "PF", amount = -0.5), emptyList()))
        assertEquals("Print charge", transactionTitle(txn(type = "CR", amount = -1.25), emptyList()))
    }

    @Test
    fun `a zero print charge is a grant, not a free job`() {
        // Uniprint prices a job at zero when a cost centre pays for it, so a `$0.00` row means
        // somebody else paid — which is the single most misread row on a student's statement.
        assertEquals(
            "Print charge paid by a grant",
            transactionTitle(txn(type = "CR", amount = 0.0), emptyList()),
        )
    }

    @Test
    fun `an unknown type falls back to the server's own label list rather than a hardcoded word`() {
        val labels = List(8) { "label$it" }
        assertEquals("label7", transactionTitle(txn(type = "7", amount = -1.0), labels))
        // Out of range, or not a number at all: no claim is made about what the row was.
        assertEquals("Transaction", transactionTitle(txn(type = "ZZ", amount = -1.0), labels))
        assertEquals("Transaction", transactionTitle(txn(type = "99", amount = -1.0), labels))
    }

    @Test
    fun `the subtype is appended rather than replacing the type`() {
        assertEquals("Print charge · Colour", transactionTitle(txn(type = "CR", amount = -1.25, sub = "Colour"), emptyList()))
    }

    // -------------------------------------------------------------------------- money

    @Test
    fun `money keeps the server's format and only swaps the hyphen for a real minus`() {
        // GMU's currency format string is one-section, so the server itself renders a negative as
        // `$-1.25`. The app is allowed to fix the glyph next to tabular figures and nothing else —
        // re-formatting the number would make the app disagree with the kiosk receipt.
        assertEquals("\$\u22121.25", accountMinusSign("\$-1.25"))
        assertEquals("\$0.00", accountMinusSign("\$0.00"))
        assertEquals("1.25 €", accountMinusSign("1.25 €"))
    }

    @Test
    fun `the charge timing sentence is the one the spec pins`() {
        assertEquals(
            "You are charged when you release this at a printer, not now.",
            chargeTimingSentence,
        )
    }

    // ---------------------------------------------------------------------- log off, name

    @Test
    fun `log off names the host and describes local cleanup`() {
        assertEquals(
            "Logging off asks mobileprint.gmu.edu to end the session and clears the cached queue " +
                "and balance from this phone. Saved campuses and trusted certificates stay.",
            logOffNote("mobileprint.gmu.edu"),
        )
    }

    @Test
    fun `the monogram comes from whatever the server called them`() {
        assertEquals("AR", accountInitials("Ayesha Rahman"))
        assertEquals("AR", accountInitials("A. Rahman"))
        assertEquals("AR", accountInitials("  ayesha   rahman  "))
        assertEquals("AR", accountInitials("arahman3"))
        assertEquals("AY", accountInitials("Ayesha"))
        assertEquals("?", accountInitials("?"))
        assertEquals("?", accountInitials("   "))
    }

    // ------------------------------------------------- GMU's transaction transcript (live)

    /**
     * Captured on-device from the signed-in statement of a real GMU account on 2026-09-18
     * (`phrarosprint/evidence/port-09-account.xml`). The first render of that screen printed a
     * literal `&#10;` and the same string twice, because GMU posts its page-counter transcript as
     * the row description and the row used the description for both lines.
     */
    private val gmuCharge = Transaction.from(
        """
        {"TransactionType":"CR","Amount":-0.11,
         "Description":"I-94.pdf&#10;1 page of 612x792,Color,Letter,Simplex",
         "JobName":"I-94.pdf","Printer":"FX-ENG4-LOBBY-5840","Purse":"General"}
        """.toJsonObject(),
    )

    @Test
    fun `the entity-encoded line feed in a GMU charge description is decoded, not displayed`() {
        assertEquals("I-94.pdf\n1 page of 612x792,Color,Letter,Simplex", gmuCharge.description)
        assertFalse(gmuCharge.description!!.contains("&#"))
    }

    @Test
    fun `the transcript splits into the document and the paper, and the row does not repeat itself`() {
        assertEquals("I-94.pdf", gmuCharge.descriptionHead)
        assertEquals("1 page of 612x792,Color,Letter,Simplex", gmuCharge.descriptionTail)
        // `JobName` is the same string as the first line, so it is dropped rather than printed twice.
        assertEquals(
            "1 page of 612x792,Color,Letter,Simplex · FX-ENG4-LOBBY-5840 · General",
            gmuCharge.detail,
        )
    }

    @Test
    fun `a transcript gets the type words and the document, never the raw page counter`() {
        assertEquals("Print charge · I-94.pdf", transactionTitle(gmuCharge, emptyList()))
        assertFalse(transactionTitle(gmuCharge, emptyList()).contains("612x792"))
    }

    @Test
    fun `a one-line server sentence still titles the row on its own`() {
        val sentence = "Funds transferred from CBORD Student Accounts"
        assertEquals(
            sentence,
            transactionTitle(txn(type = "CR", amount = -0.11, description = sentence), emptyList()),
        )
    }
}
