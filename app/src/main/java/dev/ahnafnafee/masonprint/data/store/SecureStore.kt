package dev.ahnafnafee.masonprint.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.data.net.CookieSnapshotStore
import dev.ahnafnafee.masonprint.data.net.Credentials

/**
 * Secrets at rest: the credential and the session cookies.
 *
 * The stock app kept its username and password in **plaintext** `SharedPreferences` with
 * `allowBackup=true` (docs/FINDINGS.md §12), so a cloud backup or an `adb backup` of a campus
 * account leaked the password in the clear. Here both live in `EncryptedSharedPreferences`
 * (AES-256-GCM, key in the Android keystore) and `xml/data_extraction_rules.xml` excludes this
 * file from backup and device transfer.
 *
 * `EncryptedSharedPreferences` is known to throw on a handful of OEM builds after a key
 * invalidation. Rather than crash on launch — which is how those devices end up with users who
 * cannot open the app at all — it degrades to private plaintext storage and *says so* in the log
 * and on the Diagnostics screen. The failure mode is a weaker-at-rest secret, not a dead app.
 */
class SecureStore(context: Context) : CookieSnapshotStore {

    private val tag = "SecureStore"

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        MpLog.warn(tag, "Encrypted storage unavailable; session will not be saved", it)
        context.getSharedPreferences(FILE_NAME + "_plain", Context.MODE_PRIVATE).also { fallback ->
            fallback.edit().clear().apply()
        }
    }

    /** False when the fallback above was taken; surfaced on the Diagnostics screen. */
    val encryptedAtRest: Boolean get() = prefs is EncryptedSharedPreferences

    var username: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var password: String?
        get() = prefs.getString(KEY_PASS, null)
        set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    var rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    /** The saved credential is only usable if the user asked to be kept signed in. */
    val hasSavedCredential: Boolean get() = rememberMe && !username.isNullOrBlank()

    /**
     * The credential to sign back in with, or null.
     *
     * `Credentials.headerValue` recomputes the `PHAROS-USER` header from these two strings, so a
     * restored session needs the password and not just a cookie: Pharos re-authenticates when its
     * session cookie ages out mid-use, and the stock app handled that by throwing the user back to
     * a blank login form because it had thrown the password away.
     */
    fun storedCredentials(): Credentials? = when {
        !hasSavedCredential -> null
        else -> Credentials(username.orEmpty(), password.orEmpty(), rememberMe = true)
    }

    fun saveCredentials(creds: Credentials) {
        if (!encryptedAtRest) return
        username = creds.username
        password = creds.password
        rememberMe = creds.rememberMe
    }

    override suspend fun load(): List<String> =
        if (encryptedAtRest) prefs.getStringSet(KEY_COOKIES, emptySet())?.toList().orEmpty() else emptyList()

    override suspend fun save(entries: List<String>) {
        if (!encryptedAtRest) return
        prefs.edit().putStringSet(KEY_COOKIES, entries.toSet()).apply()
    }

    /**
     * Logout has to destroy the credential *and* the jar. The stock app cleared only the user
     * record (`UserAccountSettingManager.RemoveUser`) and left Pharos' own session cookies alive
     * server-side — so the next person to touch the app could act on the previous account.
     */
    override suspend fun clear() {
        prefs.edit().clear().apply()
    }

    fun forgetCredentialOnly() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).remove(KEY_REMEMBER).apply()
    }

    private companion object {
        const val FILE_NAME = "masonprint_secure"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_REMEMBER = "remember"
        const val KEY_COOKIES = "cookie_jar"
    }
}
