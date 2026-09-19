package dev.ahnafnafee.masonprint.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewDimensionsTest {
    @Test fun `letter page keeps a readable resolution and its proportions`() {
        val (width, height) = previewDimensions(612, 792, 1080)
        assertEquals(1080, width)
        assertEquals(1397, height)
    }

    @Test fun `long receipts and large drawings stay within the memory budget`() {
        for ((w, h) in listOf(100 to 100_000, 100_000 to 100, 100_000 to 100_000, 1 to Int.MAX_VALUE)) {
            val (width, height) = previewDimensions(w, h, 1080)
            assertTrue(width in 1..4096)
            assertTrue(height in 1..4096)
            assertTrue(width.toLong() * height <= 4_000_000)
        }
    }

    @Test fun `zoom detail increases resolution while preserving the bitmap budget`() {
        val base = previewDimensions(612, 792, 1080)
        val detail = previewDimensions(612, 792, 2160)
        assertTrue(detail.first > base.first)
        assertTrue(detail.second > base.second)
        assertTrue(detail.first.toLong() * detail.second <= 4_000_000)
    }
}
