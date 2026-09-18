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
        assertEquals("EXAMPLEuserUri000000A12", user("EXAMPLEuserUri000000A12", "jdoe").accountKey)
    }

    @Test fun `the sign-in id is the fallback, case-folded`() {
        assertEquals("jdoe", user(null, "jdoe").accountKey)
        assertEquals("jdoe", user(null, "JDoe").accountKey)
    }

    @Test fun `a user with nothing identifying still yields a usable key`() {
        assertEquals("000000", user(null, null, alias = "000000").accountKey)
        assertEquals("unknown", user(null, null).accountKey)
    }
}
