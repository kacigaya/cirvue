package app.cirvue.select

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {
    @Test fun letterboxedFitMapsImageAndViewCoordinates() {
        checkTransform(Size(400f, 200f), Size(300f, 300f), 0.75f, Offset(0f, 75f))
    }

    @Test fun pillarboxedFitMapsImageAndViewCoordinates() {
        checkTransform(Size(200f, 400f), Size(600f, 400f), 1f, Offset(200f, 0f))
    }

    @Test fun tapPicksSmallestTextBoxIncludingSlop() {
        val small = Rect(40f, 40f, 60f, 60f)
        val large = Rect(10f, 10f, 90f, 90f)
        assertEquals(small, SelectionGeometry.tapBounds(Offset(63f, 50f), listOf(large, small), Size(200f, 200f), 4f))
    }

    @Test fun tapWithoutTextHitUses35PercentOfShortSide() {
        for (image in listOf(Size(200f, 400f), Size(400f, 200f))) {
            assertEquals(
                Rect(65f, 65f, 135f, 135f),
                SelectionGeometry.tapBounds(Offset(100f, 100f), listOf(Rect(0f, 0f, 20f, 20f)), image, 4f),
            )
        }
    }

    @Test fun cropPadsBothAxesUsingLongerSide() {
        assertEquals(Rect(90f, 110f, 210f, 210f), SelectionGeometry.cropRect(Rect(100f, 120f, 200f, 200f), Size(400f, 400f), 0.1f))
    }

    @Test fun cropExpandsSmallSelectionTo64Pixels() {
        assertEquals(Rect(78f, 83f, 142f, 147f), SelectionGeometry.cropRect(Rect(100f, 100f, 120f, 130f), Size(400f, 400f), 0f))
    }

    @Test fun cropClampsAllEdgesToImage() {
        assertEquals(Rect(0f, 0f, 200f, 100f), SelectionGeometry.cropRect(Rect(-10f, -20f, 220f, 110f), Size(200f, 100f)))
    }

    @Test fun cropClampsMinimumSizeWhenImageIsSmallerThan64Pixels() {
        assertEquals(Rect(0f, 0f, 40f, 30f), SelectionGeometry.cropRect(Rect(19f, 14f, 21f, 16f), Size(40f, 30f)))
    }

    @Test fun boundsOfEmptyAndSinglePoint() {
        assertEquals(Rect.Zero, boundsOf(emptyList()))
        assertEquals(Rect(3f, -2f, 3f, -2f), boundsOf(listOf(Offset(3f, -2f))))
    }

    @Test fun lassoBoundsIncludeEveryPointRegardlessOfOrder() {
        val points = listOf(Offset(8f, 2f), Offset(-5f, 9f), Offset(4f, -3f), Offset(8f, 2f))
        val expected = Rect(-5f, -3f, 8f, 9f)
        assertEquals(expected, boundsOf(points))
        assertEquals(expected, Selection.Lasso(points).bounds)
        assertEquals(Rect.Zero, Selection.Lasso(emptyList()).bounds)
    }

    private fun checkTransform(image: Size, view: Size, scale: Float, offset: Offset) {
        val transform = FitTransform.of(image, view)
        assertEquals(scale, transform.scale, 0.0001f)
        assertEquals(offset, transform.offset)
        for (point in listOf(Offset.Zero, Offset(image.width, image.height), Offset(37f, 81f))) {
            val roundTrip = transform.toImage(transform.toView(point))
            assertEquals(point.x, roundTrip.x, 0.0001f)
            assertEquals(point.y, roundTrip.y, 0.0001f)
        }
        val viewPoint = Offset(47f, 129f)
        val roundTrip = transform.toView(transform.toImage(viewPoint))
        assertEquals(viewPoint.x, roundTrip.x, 0.0001f)
        assertEquals(viewPoint.y, roundTrip.y, 0.0001f)
        assertEquals(Rect(offset, image * scale), transform.toView(Rect(Offset.Zero, image)))
    }
}
