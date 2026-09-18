package dev.ahnafnafee.masonprint.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Default page size for the job list. The web grid's own default is 15 rows on a desktop. */
const val JOB_PAGE_SIZE = 50

/**
 * The `/settings` document, kept raw and read by path.
 *
 * It is *not* a DTO on purpose: the sections are `"PrintCenter"`, `"Paypal"`, `"CyberSource"`,
 * `"Localisation"`, `"SecureRelease"`, `"Authentication"`, `"PaymentNotificationProcessor"`,
 * `"IPP Service"` — with spaces and capitals in the leaf keys (`"Add Funds"`, `"Maximum Allowed
 * Upload"`, `"Credit Card Gateway"`) — and the set of sections differs per deployment. Pinning it
 * to a class means a server upgrade drops fields; reading by path means it does not.
 *
 * The GMU document this was written against is checked in at
 * `pharos/research/webartifact/settings.json` (live, anonymous, API 4.11.24.1).
 */
class SettingsDocument(val raw: JsonObject) {

    fun section(vararg path: String): JsonObject? {
        var node: JsonObject? = raw
        for (segment in path) {
            node = node?.let { parent ->
                (parent.entries.firstOrNull { it.key.equals(segment, true) }?.value as? JsonObject)
            } ?: return null
        }
        return node
    }

    private fun value(vararg path: String): JsonElementish? {
        val parentPath = path.dropLast(1)
        val key = path.last()
        val node = section(*parentPath.toTypedArray()) ?: return null
        val element = node.entries.firstOrNull { it.key.equals(key, true) }?.value ?: return null
        return JsonElementish(element)
    }

    fun str(vararg path: String): String? = value(*path)?.string
    fun bool(vararg path: String): Boolean? = value(*path)?.boolean
    fun lng(vararg path: String): Long? = value(*path)?.long
    fun dbl(vararg path: String): Double? = value(*path)?.double

    val printCenter: JsonObject? get() = section("PrintCenter")
    val authentication: JsonObject? get() = section("Authentication")
    val secureRelease: JsonObject? get() = section("SecureRelease")
    val localisation: JsonObject? get() = section("Localisation")

    /**
     * `"Maximum Allowed Upload"`, quoted as a string on GMU (`"52428800"` = 50 MiB). This is the
     * value the upload screen quotes *before* sending, so the user learns about the 50 MB wall
     * while still holding the file instead of after a 40-second transfer.
     */
    val maxUploadBytes: Long? get() = lng("PrintCenter", "Maximum Allowed Upload")

    /** `FinishingOptions` as the server publishes it — `"Duplex":"Yes"`, `"PagesPerSide":"1"`. */
    fun defaultFinishing(): FinishingPayload? {
        val o = section("PrintCenter", "FinishingOptions") ?: return null
        val options = FinishingOptions.from(o)
        return FinishingPayload.from(options)
    }

    /**
     * `Localisation` — `{"DateFormat":"M/D/YYYY","TimeFormat":"h:mma","Currency Format":"$0.00"}`.
     *
     * This is why the clone formats money and dates from the server rather than from the phone's
     * locale: a student comparing the app against the web portal or a printed statement must see
     * the same string. The stock app used the device locale in its native forms and the server's
     * in the WebView, so the same balance rendered two ways on two screens of the same app.
     */
    fun formats(): ServerFormats = ServerFormats(
        dateFormat = str("Localisation", "DateFormat"),
        timeFormat = str("Localisation", "TimeFormat"),
        currencyFormat = str("Localisation", "Currency Format") ?: "$0.00",
    )

    /**
     * Everything the UI is allowed to branch on. The rule for every field: **hide**, do not
     * disable. A greyed-out "Add Funds" on a campus where money only arrives from the card
     * printer is noise, and on GMU that is exactly what the stock app showed.
     */
    fun capabilities(apiVersion: String?, user: PharosUser?): Capabilities {
        val pc = printCenter
        val auth = authentication
        val release = secureRelease
        fun pcS(key: String) = pc?.strCI(key)
        fun pcB(key: String) = pc?.bool(key)
        fun authS(key: String) = auth?.strCI(key)
        fun relB(key: String) = release?.bool(key)
        fun relS(key: String) = release?.strCI(key)

        /*
         * `"Credit Card Gateway"` is only a Yes/No switch; *which* gateway is configured is
         * visible in which sibling section is populated (`Paypal.URL`, `CyberSource.URL`). GMU
         * has both sections present but the switch set to `"No"`, which is precisely why the
         * switch is tested first — reading section presence alone would offer a button that
         * cannot work.
         *
         * A section counts as configured when it has *any* endpoint or credential: a server that
         * publishes the URL but withholds the credential (the credential is server-side, so many
         * do) must not fall through to the wrong vendor. GMU publishes both `CyberSource.URL` and
         * `Paypal.URL`, so the order below is a preference, not a discovery — CyberSource first,
         * which is what its own portal redirects to. The `else` is not a guess of "no gateway":
         * with the switch on, money can be added and `POST /payment/transaction` will name the
         * endpoint, so the app offers the action without assuming a vendor.
         */
        val gatewayEnabled = pcB("Credit Card Gateway") ?: false
        fun anySet(section: String, vararg keys: String): Boolean {
            val o = this.section(section)
            return keys.any { !o?.strCI(it).isNullOrBlank() }
        }
        val gateway = when {
            !gatewayEnabled -> Gateway.NONE
            anySet("CyberSource", "Profile Id", "Access Key", "URL") -> Gateway.CYBERSOURCE
            anySet("Paypal", "URL", "API") -> Gateway.PAYPAL_WEB_ACCEPT
            anySet("Chase", "URL", "Merchant Id", "MerchantId") -> Gateway.CHASE
            else -> Gateway.CAMPUS_BILLING
        }
        val ssoType = authS("sso-type")
        return Capabilities(
            apiVersion = apiVersion,
            webUploadAllowed = pcB("Web Upload") ?: true,
            uploadAllowedByRelease = relB("Document Upload") ?: true,
            previewAllowed = relB("Document Preview") ?: false,
            qrReleaseEnabled = pcB("EnableCameraScanner") ?: false,
            maxUploadBytes = maxUploadBytes,
            addFundsEnabled = pcB("Add Funds") ?: false,
            finishingUpdateAllowed = pcB("Finishing Options Update") ?: true,
            gateway = gateway,
            sso = when {
                ssoType.isNullOrBlank() -> Sso.Local
                ssoType.equals("CAS", true) -> Sso.Cas(
                    idpLogonAttribute = authS("idp-logon-attribute") ?: "uid",
                    idpHost = authS("sso-server") ?: authS("idp-server"),
                )
                else -> Sso.Other(ssoType)
            },
            keepMeLoggedInAllowed = auth?.obj("User")?.bool("KeepMeLoggedIn") ?: pcB("KeepMeLoggedIn") ?: true,
            promptConfirmation = pcB("Prompt Confirmation Dialog") ?: true,
            displayPrintButton = pcB("Display Print Button") ?: true,
            chargingModel = pcS("Charging Model"),
            bankName = pcS("Bank"),
            transactionLabels = pcS("TransactionLabels")?.split(',')?.map { it.trim() }.orEmpty(),
            documentTypeCategories = relS("Document Types")?.split(',')?.map { it.trim() }.orEmpty(),
            mobilePluginVersion = release?.obj("MobilePrint")?.strCI("Plugin Version"),
            serverInterfaceVersion = release?.obj("MobilePrint")?.strCI("Server Interface Version"),
            guestAccountsAllowed = pcB("Guest Accounts") ?: false,
            costCentersAllowed = user?.privileges?.costCenters ?: false,
            formats = formats(),
            user = user,
        )
    }
}

/** A value read out of the settings document, already coerced to whatever the caller wants. */
private class JsonElementish(private val element: JsonElement) {
    val string: String? get() = asString(element)
    val boolean: Boolean? get() = asBoolean(element)
    val long: Long? get() = asLong(element)
    val double: Double? get() = asDouble(element)
}

private fun JsonObject?.strCIOrNull(key: String): String? = this?.strCI(key)

enum class Gateway { NONE, PAYPAL_WEB_ACCEPT, CYBERSOURCE, CHASE, CAMPUS_BILLING }

sealed interface Sso {
    /** Local Pharos accounts: the app's own username/password form is the right UI. */
    data object Local : Sso

    /**
     * GMU's case: `{"sso-type":"CAS","idp-logon-attribute":"uid"}`. The identity lives at
     * `login.gmu.edu`, so the app must be honest that it is handing the credential to the IdP, and
     * must ask for the *IdP* username (`uid`), which is not always the same string as the print
     * account name the student sees in the portal.
     */
    data class Cas(val idpLogonAttribute: String, val idpHost: String?) : Sso

    data class Other(val type: String) : Sso
}

/** Server-provided presentation formats, so the app and the web portal never disagree. */
data class ServerFormats(val dateFormat: String?, val timeFormat: String?, val currencyFormat: String) {
    /**
     * Render an amount with the deployment's currency pattern (`"$0.00"` → `$1.25`, a European
     * deployment's `"0.00 €"` → `1.25 €`, `"0,000,000.00"` grouped). The pattern is read as
     * *symbol, position, grouping and separator* — never handed to a locale-aware formatter,
     * because the phone's locale and the deployment's are different questions, and the student
     * compares this number against the web portal, not against their keyboard.
     */
    fun money(amount: Double?): String {
        if (amount == null) return "—"
        /*
         * A .NET custom format carries up to three `;`-separated sections: positive, negative, zero.
         * GMU's `$0.00` has only one, and .NET answers a negative amount by putting the minus
         * directly in front of the digits — so the portal genuinely prints `$-1.25`, not `-$1.25`.
         * Reproducing that quirk is the point: a balance the app draws differently from the portal
         * reads like a different balance.
         */
        val sections = currencyFormat.trim().split(';')
        if (amount == 0.0 && sections.size > 2 && sections[2].none { it == '0' || it == '#' || it.isDigit() }) {
            return sections[2].trim() // a deployment that shows zero as `-` means "no balance"
        }
        val ownSection = amount < 0 && sections.size > 1
        val pattern = (if (ownSection) sections[1] else sections[0]).trim()
        // The skeleton starts at the first digit placeholder; a `.` or `,` before it belongs to the
        // symbol, because `Fr.0.00` is a Swiss pattern and not two numbers.
        val first = pattern.indexOfFirst { it == '0' || it == '#' || it.isDigit() }
        if (first < 0) return "%.2f".format(amount)
        val prefix = pattern.take(first)
        val rest = pattern.substring(first)
        val splitAt = rest.indexOfFirst { !it.isNumberish() }.let { if (it < 0) rest.length else it }
        val body = formatAmount(if (ownSection) -amount else amount, rest.take(splitAt))
        // A pattern with no symbol at all ("0,000.00") must not gain one: inventing `$` on a
        // deployment that deliberately prints bare numbers would be a wrong currency claim.
        return prefix + body + rest.drop(splitAt)
    }

    /** Split a .NET-style numeric skeleton into sign, grouping and separator, then apply them. */
    private fun formatAmount(amount: Double, skeleton: String): String {
        val lastSep = maxOf(skeleton.lastIndexOf('.'), skeleton.lastIndexOf(','))
        // A separator is the decimal separator only if a fraction follows it; in `0,000.00` the
        // commas group, in `0,00 €` the comma separates — the pattern says which, the locale does not.
        val fractionDigits = if (lastSep >= 0) skeleton.length - lastSep - 1 else 0
        val decimal = if (lastSep >= 0 && fractionDigits in 1..2) skeleton[lastSep] else '.'
        val group = skeleton.firstOrNull { (it == ',' || it == '.') && it != decimal }
        val rendered = "%.${fractionDigits.coerceIn(0, 2)}f".format(kotlin.math.abs(amount))
        val intPart = rendered.substringBefore('.')
        val fracPart = rendered.substringAfter('.', "")
        val grouped = if (group == null || intPart.length <= 3) intPart
        else intPart.reversed().chunked(3).joinToString(group.toString()).reversed()
        val body = if (fracPart.isEmpty()) grouped else grouped + decimal + fracPart
        return (if (amount < 0) "-" else "") + body
    }

    private fun Char.isNumberish() = this == '0' || this == '#' || isDigit() || this == '.' || this == ','
}

/**
 * What this deployment lets the app do. Derived, never configured: the whole point of reading it
 * from `/settings` + `User.Privileges` is that the next campus to deploy Pharos needs no change
 * here. CLONE-PLAN §4.5.
 */
data class Capabilities(
    val apiVersion: String?,
    val webUploadAllowed: Boolean,
    val uploadAllowedByRelease: Boolean,
    val previewAllowed: Boolean,
    val qrReleaseEnabled: Boolean,
    val maxUploadBytes: Long?,
    val addFundsEnabled: Boolean,
    val finishingUpdateAllowed: Boolean,
    val gateway: Gateway,
    val sso: Sso,
    val keepMeLoggedInAllowed: Boolean,
    val promptConfirmation: Boolean,
    val displayPrintButton: Boolean,
    val chargingModel: String?,
    val bankName: String?,
    val transactionLabels: List<String>,
    val documentTypeCategories: List<String>,
    val mobilePluginVersion: String?,
    val serverInterfaceVersion: String?,
    val guestAccountsAllowed: Boolean,
    /**
     * Whether this user may charge a job to something other than their own balance.
     *
     * `Privileges.Printing.PayForPrint.CostCenters` — the same flag GMU's own Print Center uses to
     * decide whether to render the cost-centre field at all. It is the permission that makes
     * "my department pays for this" a thing a student can do, so it gates the funding chooser
     * rather than the list of codes (a department code is often not in `User.CostCenters` until
     * it has been used once, which is why free text stays available).
     */
    val costCentersAllowed: Boolean,
    val formats: ServerFormats,
    val user: PharosUser?,
) {
    val canUpload: Boolean get() = webUploadAllowed && uploadAllowedByRelease
    val canAddFunds: Boolean get() = addFundsEnabled && gateway != Gateway.NONE

    /** Purses other than the total, in the server's own spending order. Empty when unreported. */
    val purses: List<Purse> get() = user?.balance?.purses ?: emptyList()

    /**
     * Cost centres this account may charge to. `Grant` is **not** the filter: GMU's live payload
     * hands back the one centre the student owns — `10111-M17041`, "10111-Computer Science
     * Department" — with `"Grant":false`, so filtering on it hid the centre that actually makes
     * printing free. Everything still `Active` is offered; `Grant` survives as a label.
     * Free-text entry never depends on this list.
     */
    val usableCostCenters: List<CostCenter> get() = user?.costCenters?.filter { it.active } ?: emptyList()

    /** The subset the server marked as a grant. A badge, not a gate. */
    val grantedCostCenters: List<CostCenter> get() = usableCostCenters.filter { it.granted }

    /** Human sentence for the "why can't I…?" disclosure on the Account screen. */
    val uploadBlockReason: String?
        get() = when {
            !webUploadAllowed -> "This campus has web upload switched off."
            !uploadAllowedByRelease -> "Secure Release does not allow document upload."
            else -> null
        }

    companion object {
        /** Safe default before the first `/settings` read: show nothing extra, break nothing. */
        val UNKNOWN = Capabilities(
            apiVersion = null, webUploadAllowed = false, uploadAllowedByRelease = false,
            previewAllowed = false, qrReleaseEnabled = false, maxUploadBytes = null,
            addFundsEnabled = false, finishingUpdateAllowed = false, gateway = Gateway.NONE,
            sso = Sso.Local, keepMeLoggedInAllowed = true, promptConfirmation = true,
            displayPrintButton = true, chargingModel = null, bankName = null,
            transactionLabels = emptyList(), documentTypeCategories = emptyList(),
            mobilePluginVersion = null, serverInterfaceVersion = null,
            guestAccountsAllowed = false, costCentersAllowed = false,
            formats = ServerFormats(null, null, "$0.00"), user = null,
        )
    }
}
