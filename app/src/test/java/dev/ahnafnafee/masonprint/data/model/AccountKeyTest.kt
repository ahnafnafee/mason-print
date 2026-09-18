package dev.ahnafnafee.masonprint.data.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The key per-account preferences hang off. It has to be stable across a rename and identical for
 * the same person however they typed their login, or a student's remembered department silently
 * becomes a second, empty set of preferences.
 */
class AccountKeyTest {

    private fun user(identifier: String?, logonId: String?, alias: String? = null) =
        PharosUser(
            logonId = logonId, identifier = identifier, displayName = null, alias = alias,
            firstNames = null, lastName = null, email = null, cardId = null, userUri = null,
            location = null, balance = null, roles = emptyList(),
            privileges = Privileges.from(null), costCenters = emptyList(),
            raw = JsonObject(emptyMap()),
        )

    @Test fun `the server identifier wins, because it survives a rename`() {
        assertEquals("POYJ8x6E7Ias2Uj6cdAJZA2", user("POYJ8x6E7Ias2Uj6cdAJZA2", "aannafee").accountKey)
    }

    @Test fun `the sign-in id is the fallback, case-folded`() {
        assertEquals("aannafee", user(null, "aannafee").accountKey)
        assertEquals("aannafee", user(null, "AAnnafee").accountKey)
    }

    @Test fun `a user with nothing identifying still yields a usable key`() {
        assertEquals("096040", user(null, null, alias = "096040").accountKey)
        assertEquals("unknown", user(null, null).accountKey)
    }
}
