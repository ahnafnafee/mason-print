package dev.ahnafnafee.masonprint.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PreviewZoomTest {
    private val viewport = IntSize(400, 600)

    @Test fun `zoom keeps the touched document point beneath the fingers`() {
        val focus = Offset(100f, 200f)
        val zoom = PreviewZoom().transformed(2f, Offset.Zero, focus, viewport)
        assertEquals(2f, zoom.scale, 0.001f)
        assertEquals(Offset(100f, 100f), zoom.offset)
        // The point started at (-100, -100) relative to the viewport centre.
        assertEquals(focus, Offset(200f, 300f) + Offset(-100f, -100f) * zoom.scale + zoom.offset)
    }

    @Test fun `a second pinch preserves its anchor and includes finger translation`() {
        val zoom = PreviewZoom(2f, Offset(40f, -30f))
            .transformed(1.5f, Offset(10f, 20f), Offset(250f, 350f), viewport)
        assertEquals(3f, zoom.scale, 0.001f)
        assertEquals(Offset(45f, -50f), zoom.offset)
    }

    @Test fun `zoom limits use the actual scale change and fit clears the pan`() {
        val start = PreviewZoom(3f, Offset(30f, 60f))
        val maximum = start.transformed(10f, Offset.Zero, Offset.Unspecified, viewport)
        assertEquals(4f, maximum.scale, 0.001f)
        assertEquals(Offset(40f, 80f), maximum.offset)
        assertEquals(PreviewZoom(), maximum.transformed(0.01f, Offset(-90f, 100f), Offset.Zero, viewport))
    }

    @Test fun `panning cannot expose blank space beyond any page edge`() {
        for (x in listOf(-10_000f, 10_000f)) {
            for (y in listOf(-10_000f, 10_000f)) {
                val zoom = PreviewZoom(2f).transformed(1f, Offset(x, y), Offset.Unspecified, viewport)
                assertEquals(if (x < 0) -200f else 200f, zoom.offset.x, 0.001f)
                assertEquals(if (y < 0) -300f else 300f, zoom.offset.y, 0.001f)
            }
        }
    }

    @Test fun `fit size and outward edge drags remain available for document scrolling`() {
        assertFalse(PreviewZoom().canPan(Offset(0f, -50f), viewport))
        val bottom = PreviewZoom(2f, Offset(0f, -300f))
        assertFalse(bottom.canPan(Offset(1f, -50f), viewport))
        assertTrue(bottom.canPan(Offset(0f, 50f), viewport))
        assertTrue(bottom.canPan(Offset(50f, 1f), viewport))
        assertFalse(PreviewZoom(2f, Offset(200f, 0f)).canPan(Offset(50f, 1f), viewport))
    }

    @Test fun `a resized viewport brings the page back inside its new bounds`() {
        val zoom = PreviewZoom(2f, Offset(200f, -300f)).constrained(IntSize(200, 300))
        assertEquals(Offset(100f, -150f), zoom.offset)
    }
}
