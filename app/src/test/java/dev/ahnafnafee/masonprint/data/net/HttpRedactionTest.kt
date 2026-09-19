package dev.ahnafnafee.masonprint.data.net

import org.junit.Assert.*
import org.junit.Test

class HttpRedactionTest {
    @Test fun `secret headers retain no portion of their value`() {
        for (name in listOf("Cookie", "Set-Cookie", "Authorization", "X-Authorization", "Proxy-Authorization")) {
            assertEquals("$name: (redacted)", HttpFactory.redact("$name: abcdef-secret"))
        }
        assertEquals("Content-Type: application/json", HttpFactory.redact("Content-Type: application/json"))
    }
}
