package dev.ahnafnafee.masonprint.data.net

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class IppProtocolTest {
    @Test fun `language tagged job owner and title decode without language prefix`() {
        val value = "student"
        val bytes = ByteBuffer.allocate(2 + 2 + 2 + value.length).putShort(2).put("en".toByteArray())
            .putShort(value.length.toShort()).put(value.toByteArray()).array()
        for (tag in listOf(0x35, 0x36)) {
            assertEquals(value, IppProtocol.Attribute("owner", tag, bytes).text())
            assertNull(IppProtocol.Attribute("owner", tag, bytes.copyOf(bytes.size - 1)).text())
            assertNull(IppProtocol.Attribute("owner", tag, byteArrayOf(127, 127, 0, 0)).text())
        }
    }

    @Test fun `job groups and multi-value attributes retain boundaries`() {
        val bytes = ippReply(4, listOf(
            IppProtocol.Group(4, listOf(IppProtocol.Attribute.number("operations-supported", 8), IppProtocol.Attribute.number("", 10))),
            IppProtocol.Group(2, listOf(IppProtocol.Attribute.text("job-name", "First"))),
            IppProtocol.Group(2, listOf(IppProtocol.Attribute.text("job-name", "Second"))),
        ))
        val result = IppProtocol.response(bytes, 4)
        assertEquals(setOf(8, 10), result.groups[0].numbers("operations-supported"))
        assertEquals(listOf("First", "Second"), result.groups.drop(1).map { it.text("job-name") })
    }

    @Test fun `invalid response IDs truncation and oversized replies are refused`() {
        val bytes = ippReply(2, listOf(IppProtocol.Group(2, listOf(IppProtocol.Attribute.text("job-name", "test")))))
        assertThrows(IOException::class.java) { IppProtocol.response(bytes, 3) }
        for (length in 0 until bytes.size) assertThrows(IOException::class.java) { IppProtocol.response(bytes.copyOf(length), 2) }
        assertThrows(IOException::class.java) { IppProtocol.response(ByteArray(IppProtocol.MAX_RESPONSE + 1), 2) }
    }
}
