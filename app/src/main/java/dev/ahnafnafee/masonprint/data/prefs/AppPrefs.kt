package dev.ahnafnafee.masonprint.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dev.ahnafnafee.masonprint.data.net.TrustedCertificate
import dev.ahnafnafee.masonprint.data.net.TlsTrustStore

/**
 * Everything that is *not* a secret, in plain `SharedPreferences`.
 *
 * Deliberately synchronous and deliberately not DataStore: [TlsTrustStore.isTrusted] is called
 * from inside the JSSE handshake, which happens on OkHttp's thread with no coroutine context and
 * cannot await anything. The set is tiny (a hostname, a version string, a handful of approved
 * fingerprints), so the synchronous read costs nothing. Secrets stay in
 * [dev.ahnafnafee.masonprint.data.store.SecureStore].
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last server the user configured. Empty on first run, which routes to the connect screen. */
    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    /**
     * Cache-busting tick the stock app appended to the Print Center URL. Kept as a stored value so
     * a restored session reloads the SPA rather than reusing a WebView cache that no longer matches
     * its own session cookie (docs/FINDINGS.md §8.2).
     */
    var lastApiVersion: String?
        get() = prefs.getString(KEY_API_VERSION, null)
        set(value) = prefs.edit().putString(KEY_API_VERSION, value).apply()

    var lastUserName: String?
        get() = prefs.getString(KEY_LAST_USER, null)
        set(value) = prefs.edit().putString(KEY_LAST_USER, value).apply()

    /**
     * The account whose preferences to restore before the server has answered, so a warm start does
     * not briefly show the wrong funding source.
     */
    var lastAccount: String?
        get() = prefs.getString(KEY_LAST_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_LAST_ACCOUNT, value).apply()

    /**
     * The funding source an account last used: a cost-centre code, or null for their own balance.
     * Worth remembering because a student who charges to a department code types the same handful of
     * digits every single day.
     *
     * Keyed **per account** ([dev.ahnafnafee.masonprint.data.model.PharosUser.accountKey]) rather than stored
     * once: on a shared phone a single global default would charge one student's printing to another
     * student's department. It deliberately outlives sign-out, which is the whole point — logging
     * back in should land on the department you always use.
     */
    fun costCenterFor(account: String): String? = prefs.getString(KEY_COST_CENTER + account, null)

    fun setCostCenterFor(account: String, code: String?) {
        prefs.edit().apply {
            if (code == null) remove(KEY_COST_CENTER + account) else putString(KEY_COST_CENTER + account, code)
        }.apply()
    }

    /**
     * Cost-centre codes this account has charged to, most recent first, for reuse.
     *
     * The server publishes no directory of codes — a search answers with the ones already on the
     * account — so the only list that can remember what a department handed a student is on the
     * phone. Per account for the same reason the funding source is, and it outlives sign-out for
     * the same reason too: the code is as useful next semester as today.
     */
    fun savedCostCentersFor(account: String): List<SavedCostCenter> =
        prefs.getString(KEY_SAVED_COST_CENTERS + account, null)
            ?.split('\n')
            ?.mapNotNull { raw ->
                val sep = raw.indexOf('|')
                if (sep <= 0) return@mapNotNull null
                SavedCostCenter(
                    code = raw.substring(0, sep),
                    description = raw.substring(sep + 1).takeIf { it.isNotEmpty() },
                )
            }
            .orEmpty()

    fun setSavedCostCentersFor(account: String, list: List<SavedCostCenter>) {
        val encoded = list.take(SAVED_COST_CENTERS).joinToString("\n") { entry ->
            // `|` and newline are this format's own separators, and a description is display-only,
            // so stripping them there costs nothing and keeps the decode above honest.
            entry.code + "|" + (entry.description?.replace('\n', ' ')?.replace('|', '·') ?: "")
        }
        prefs.edit().putString(KEY_SAVED_COST_CENTERS + account, encoded).apply()
    }

    /** Moves [code] to the front, de-duplicating case-insensitively, and drops anything past the cap. */
    fun noteCostCenterUsed(account: String, code: String, description: String?) {
        if (code.isBlank()) return
        val next = listOf(SavedCostCenter(code, description)) +
            savedCostCentersFor(account).filterNot { it.code.equals(code, ignoreCase = true) }
        setSavedCostCentersFor(account, next)
    }

    /**
     * Printers this account has starred, by device `Location`.
     *
     * Per account and not per device, for the same reason the funding source is: on a shared phone
     * one student's regular printer is noise to the next. `Location` is the key because it is the
     * only identifier this API keeps stable; a label can be renamed by an administrator without
     * the station moving.
     */
    fun favouriteDevicesFor(account: String): Set<String> =
        prefs.getStringSet(KEY_FAVOURITE_DEVICES + account, emptySet()).orEmpty()

    fun setFavouriteDevicesFor(account: String, locations: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVOURITE_DEVICES + account, locations).apply()
    }

    /**
     * The printers this account last released at, most recent first, capped at [RECENT_DEVICES].
     *
     * Kept here rather than derived from the queue. The queue-derived version could only see
     * printers named by released jobs still in the loaded page, so the list a student relies on
     * emptied itself as their jobs aged out, which is exactly when they have been using the same
     * machine long enough for it to be worth remembering.
     *
     * A `StringSet` cannot hold an order, so this is one delimited string. `Location` values are
     * server paths like `/devices/70` and never contain a newline.
     */
    fun recentDevicesFor(account: String): List<String> =
        prefs.getString(KEY_RECENT_DEVICES + account, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Moves [location] to the front, de-duplicating, and drops anything past the cap. */
    fun noteDeviceUsed(account: String, location: String) {
        if (location.isBlank()) return
        val next = (listOf(location) + recentDevicesFor(account).filterNot { it == location })
            .take(RECENT_DEVICES)
        prefs.edit().putString(KEY_RECENT_DEVICES + account, next.joinToString("\n")).apply()
    }

    /**
     * Light, dark, or follow the phone. Stored for the device rather than the account: switching
     * accounts must not restyle the app, and it has to survive sign-out.
     */
    var themeMode: String?
        get() = prefs.getString(KEY_THEME, null)
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** Diagnostics is opt-in: a log buffer that keeps response bodies can hold document titles. */
    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTICS, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAGNOSTICS, value).apply()

    /**
     * The last printer list, JSON-encoded (a `List<Device>`). Printers are stable, so caching the
     * list lets the release screen paint instantly on a warm start instead of waiting on a fetch.
     * Not a secret — printer names and locations are public — and always superseded by a live load.
     */
    var cachedDevicesJson: String?
        get() = prefs.getString(KEY_DEVICES, null)
        set(value) = prefs.edit().putString(KEY_DEVICES, value).apply()

    // -- TLS approvals -------------------------------------------------------------

    /**
     * Approved certificates, encoded one-per-string so the set survives a prefs schema that has
     * no room for objects. `subject` may contain no `|` in practice (RFC 4514 uses `,` and `=`),
     * and it is only ever displayed, never parsed back into anything security-relevant — the
     * decision keys on host + fingerprint alone.
     */
    fun trustedCertificates(): List<TrustedCertificate> =
        prefs.getStringSet(KEY_TRUSTS, emptySet()).orEmpty().mapNotNull { raw ->
            val parts = raw.split('|')
            if (parts.size < 3) return@mapNotNull null
            TrustedCertificate(
                host = parts[0],
                fingerprint = parts[1],
                subject = parts[2].takeIf { it.isNotEmpty() && it != "-" },
                addedAtEpochMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            )
        }

    fun setTrustedCertificates(list: List<TrustedCertificate>) {
        val encoded = list.map {
            "${it.host}|${it.fingerprint}|${it.subject ?: "-"}|${it.addedAtEpochMs}"
        }.toSet()
        prefs.edit().putStringSet(KEY_TRUSTS, encoded).apply()
    }

    /** Sign-out destroys the session but must not silently forget a certificate the user approved. */
    fun forgetEverything() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "masonprint_prefs"
        const val KEY_HOST = "host"
        const val KEY_API_VERSION = "api_version"
        const val KEY_LAST_USER = "last_user"
        /** A prefix, not a key: the account id is appended (see [costCenterFor]). */
        const val KEY_COST_CENTER = "cost_center::"
        const val KEY_LAST_ACCOUNT = "last_account"
        const val KEY_THEME = "theme_mode"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_TRUSTS = "trusted_certs"
        const val KEY_DEVICES = "cached_devices"

        /** Prefixes, not keys: the account id is appended. */
        const val KEY_FAVOURITE_DEVICES = "fav_devices::"
        const val KEY_RECENT_DEVICES = "recent_devices::"

        /**
         * How many recent printers to keep. Short on purpose: the list exists to save a student
         * scrolling 302 rows for the machine they used yesterday, and a "recent" list long enough
         * to need its own scroll has stopped being one.
         */
        const val RECENT_DEVICES = 5

        /** Prefix, not a key: the account id is appended (see [savedCostCentersFor]). */
        const val KEY_SAVED_COST_CENTERS = "saved_cost_centers::"

        /**
         * How many saved codes to keep. The list exists so the same handful of codes a department
         * issues is one tap away; longer than this and it stops being shortcuts and starts being a
         * second directory.
         */
        const val SAVED_COST_CENTERS = 8
    }
}

/** A cost-centre code saved on this phone for reuse, with the description the server gave, if any. */
data class SavedCostCenter(val code: String, val description: String?)

/**
 * [TlsTrustStore] over [AppPrefs].
 *
 * The in-memory copy is authoritative for reads (the handshake must not touch disk) and is
 * refreshed on every write; the prefs copy is loaded once at construction. Revoking from the
 * Account screen therefore takes effect on the next handshake, not the one in flight.
 */
class PrefsTlsTrustStore(private val prefs: AppPrefs) : TlsTrustStore {

    private val lock = Any()

    @Volatile private var certs: List<TrustedCertificate> = prefs.trustedCertificates()

    @Volatile private var host: String? = null

    override fun isTrusted(host: String, fingerprint: String): Boolean =
        certs.any { it.host.equals(host, true) && it.fingerprint.equals(fingerprint, true) }

    override fun currentHost(): String? = host

    override fun setHost(host: String) {
        this.host = host
    }

    override fun all(): List<TrustedCertificate> = certs

    override fun trust(cert: TrustedCertificate) = synchronized(lock) {
        if (!isTrusted(cert.host, cert.fingerprint)) {
            val next = certs + cert
            certs = next
            prefs.setTrustedCertificates(next)
        }
    }

    override fun revoke(host: String, fingerprint: String) = synchronized(lock) {
        val next = certs.filterNot { it.host.equals(host, true) && it.fingerprint.equals(fingerprint, true) }
        if (next.size != certs.size) {
            certs = next
            prefs.setTrustedCertificates(next)
        }
    }

    override fun clear() = synchronized(lock) {
        certs = emptyList()
        prefs.setTrustedCertificates(emptyList())
    }
}
