package app.cirvue.select

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** What the user marked. All coordinates are in source image pixels. */
sealed interface Selection {
    val bounds: Rect

    data class Lasso(val points: List<Offset>) : Selection {
        override val bounds: Rect get() = boundsOf(points)
    }

    data class Box(override val bounds: Rect) : Selection

    data class Tap(val point: Offset, override val bounds: Rect) : Selection
}

/** Maps between view and image coordinates for an image drawn with `ContentScale.Fit`. */
data class FitTransform(val scale: Float, val offset: Offset) {
    fun toImage(view: Offset): Offset = (view - offset) / scale
    fun toView(image: Offset): Offset = image * scale + offset
    fun toView(image: Rect): Rect = Rect(toView(image.topLeft), toView(image.bottomRight))

    companion object {
        fun of(image: Size, view: Size): FitTransform {
            val scale = min(view.width / image.width, view.height / image.height)
            val offset = Offset((view.width - image.width * scale) / 2f, (view.height - image.height * scale) / 2f)
            return FitTransform(scale, offset)
        }
    }
}

object SelectionGeometry {
    /** Share of the image's short side used for a tap that hits no text. */
    const val TAP_SQUARE_FRACTION = 0.35f

    /** Smallest crop side, in image pixels. Below this, recognition has too little to work with. */
    const val MIN_CROP = 64f

    /**
     * The region for a tap: the smallest text box under the finger, grown by [slop], or a square
     * around the tap point, clamped to the image.
     */
    fun tapBounds(point: Offset, textBoxes: List<Rect>, image: Size, slop: Float): Rect {
        val hit = textBoxes
            .filter { it.inflate(slop).contains(point) }
            .minByOrNull { it.width * it.height }
        if (hit != null) return hit
        val side = min(image.width, image.height) * TAP_SQUARE_FRACTION
        return Rect(center = point, radius = side / 2f).intersect(Rect(Offset.Zero, image))
    }

    /** Pads [bounds] by [padFraction], enforces [MIN_CROP], and clamps it to the image. */
    fun cropRect(bounds: Rect, image: Size, padFraction: Float = 0.04f): Rect {
        val pad = max(bounds.width, bounds.height) * padFraction
        var r = bounds.inflate(pad)
        if (r.width < MIN_CROP) r = Rect(r.center.x - MIN_CROP / 2, r.top, r.center.x + MIN_CROP / 2, r.bottom)
        if (r.height < MIN_CROP) r = Rect(r.left, r.center.y - MIN_CROP / 2, r.right, r.center.y + MIN_CROP / 2)
        return r.intersect(Rect(Offset.Zero, image))
    }

    /** True if the gesture is too small to be a deliberate selection. */
    fun isTooSmall(bounds: Rect, minSide: Float): Boolean = max(bounds.width, bounds.height) < minSide
}

internal fun boundsOf(points: List<Offset>): Rect {
    if (points.isEmpty()) return Rect.Zero
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (p in points) {
        left = min(left, p.x); top = min(top, p.y)
        right = max(right, p.x); bottom = max(bottom, p.y)
    }
    return Rect(left, top, right, bottom)
}
