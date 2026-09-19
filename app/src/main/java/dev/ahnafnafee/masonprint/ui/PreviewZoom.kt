package dev.ahnafnafee.masonprint.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/** Translation is in viewport pixels, with scaling about the page's centre. */
internal data class PreviewZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun transformed(
        zoomChange: Float,
        pan: Offset,
        centroid: Offset,
        viewport: IntSize,
    ): PreviewZoom {
        val nextScale = (scale * zoomChange).coerceIn(1f, MaxPreviewZoom)
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val focus = if (centroid.isSpecified) centroid - centre else Offset.Zero
        // Keep the document point beneath the fingers stationary as the scale changes.
        val nextOffset = focus - (focus - offset) * (nextScale / scale) + pan
        return PreviewZoom(nextScale, nextOffset).constrained(viewport)
    }

    fun constrained(viewport: IntSize): PreviewZoom {
        if (scale <= 1f) return PreviewZoom()
        val extra = (scale - 1f) / 2f
        val maxX = viewport.width * extra
        val maxY = viewport.height * extra
        return copy(offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY)))
    }

    /** At fit size or a page edge, leave a new vertical drag to the document's scrolling list. */
    fun canPan(pan: Offset, viewport: IntSize): Boolean {
        if (scale <= 1f) return false
        val next = copy(offset = offset + pan).constrained(viewport).offset
        return if (abs(pan.y) >= abs(pan.x)) next.y != offset.y else next.x != offset.x
    }
}

internal const val MaxPreviewZoom = 4f
