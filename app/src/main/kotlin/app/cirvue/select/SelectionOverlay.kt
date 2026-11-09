package app.cirvue.select

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.cirvue.Tool

private const val SCRIM_IDLE = 0.32f
private const val SCRIM_SELECTED = 0.55f

/**
 * The image with a scrim and a moving edge glow. Drawing makes a lasso, dragging in box mode
 * makes a rectangle, and a short touch is a tap. All results go to [onSelect] in image pixels.
 */
@Composable
fun SelectionOverlay(
    image: Bitmap,
    tool: Tool,
    selection: Selection?,
    textBoxes: List<Rect>,
    description: String,
    onSelect: (Selection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(image) { image.asImageBitmap() }
    val imageSize = remember(image) { Size(image.width.toFloat(), image.height.toFloat()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val transform = remember(viewSize, imageSize) {
        FitTransform.of(imageSize, Size(viewSize.width.toFloat(), viewSize.height.toFloat()))
    }

    val colors = MaterialTheme.colorScheme
    val glowColors = listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)
    val density = LocalDensity.current
    val strokeWidth = with(density) { 5.dp.toPx() }
    val minSelection = with(density) { 24.dp.toPx() }
    val tapSlop = with(density) { 8.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // Enter motion: the scrim fades in while the glow sweeps once around the edges.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(durationMillis = 600)) }
    val spin by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "glow-angle",
    )

    // The live gesture, in view coordinates.
    val trail = remember { mutableStateListOf<Offset>() }
    var boxStart by remember { mutableStateOf<Offset?>(null) }
    var boxEnd by remember { mutableStateOf<Offset?>(null) }

    // The settled selection morphs from the gesture bounds to its final rectangle.
    val shown = remember { Animatable(0f) }
    var from by remember { mutableStateOf(Rect.Zero) }
    val target = selection?.let { transform.toView(it.bounds) }
    LaunchedEffect(selection) {
        if (selection != null) {
            shown.snapTo(0f)
            shown.animateTo(1f, tween(durationMillis = 350))
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .semantics { contentDescription = description }
            .pointerInput(tool, transform, textBoxes) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    var dragging = false
                    trail.clear()
                    trail += start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!dragging && (change.position - start).getDistance() > touchSlop) {
                            dragging = true
                            if (tool == Tool.Box) boxStart = start
                        }
                        if (dragging) {
                            if (tool == Tool.Draw) trail += change.position else boxEnd = change.position
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                    }
                    val gesture = when {
                        !dragging -> null
                        tool == Tool.Draw -> boundsOf(trail)
                        else -> boundsOf(listOfNotNull(boxStart, boxEnd))
                    }
                    if (gesture == null || SelectionGeometry.isTooSmall(gesture, minSelection)) {
                        val point = transform.toImage(gesture?.center ?: start)
                        val bounds = SelectionGeometry.tapBounds(point, textBoxes, imageSize, tapSlop / transform.scale)
                        from = Rect(center = gesture?.center ?: start, radius = 1f)
                        onSelect(Selection.Tap(point, bounds))
                    } else {
                        from = gesture
                        onSelect(
                            if (tool == Tool.Draw) {
                                Selection.Lasso(trail.map(transform::toImage))
                            } else {
                                Selection.Box(Rect(transform.toImage(gesture.topLeft), transform.toImage(gesture.bottomRight)))
                            },
                        )
                    }
                    trail.clear()
                    boxStart = null
                    boxEnd = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(transform.offset.x.toInt(), transform.offset.y.toInt()),
                dstSize = IntSize((imageSize.width * transform.scale).toInt(), (imageSize.height * transform.scale).toInt()),
            )

            val cut = target?.let { lerp(from, it, shown.value) }
            val radius = CornerRadius(16.dp.toPx())
            val scrim = Color.Black.copy(alpha = enter.value * if (cut != null) SCRIM_SELECTED else SCRIM_IDLE)
            if (cut != null) {
                val hole = Path().apply { addRoundRect(RoundRect(cut, radius)) }
                clipPath(hole, ClipOp.Difference) { drawRect(scrim) }
                drawSelectionFrame(cut, radius, glowColors, strokeWidth)
            } else {
                drawRect(scrim)
                drawEdgeGlow(glowColors, spin, enter.value, strokeWidth)
            }

            if (trail.size > 1) drawTrail(trail, glowColors, strokeWidth)
            val a = boxStart
            val b = boxEnd
            if (a != null && b != null) {
                drawSelectionFrame(boundsOf(listOf(a, b)), radius, glowColors, strokeWidth)
            }
        }
    }
}

private fun DrawScope.drawEdgeGlow(colors: List<Color>, angle: Float, alpha: Float, width: Float) {
    val brush = rotatingSweep(colors, angle, center)
    val frame = Rect(width, width, size.width - width, size.height - width)
    val radius = CornerRadius(32.dp.toPx())
    drawRoundRect(brush, frame.topLeft, frame.size, radius, alpha = alpha * 0.18f, style = Stroke(width * 5f))
    drawRoundRect(brush, frame.topLeft, frame.size, radius, alpha = alpha * 0.85f, style = Stroke(width * 1.6f))
}

/** A sweep gradient turned by [angle] degrees, so the glow travels around the frame. */
private fun rotatingSweep(colors: List<Color>, angle: Float, center: Offset) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        SweepGradient(center.x, center.y, colors.map { it.toArgb() }.toIntArray(), null).apply {
            setLocalMatrix(Matrix().apply { setRotate(angle, center.x, center.y) })
        }
}

private fun DrawScope.drawTrail(points: List<Offset>, colors: List<Color>, width: Float) {
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        // Quadratic segments through midpoints smooth out touch jitter.
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            quadraticTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
        }
    }
    val brush = Brush.linearGradient(colors, start = points.first(), end = points.last())
    drawPath(path, brush, alpha = 0.25f, style = Stroke(width * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSelectionFrame(rect: Rect, radius: CornerRadius, colors: List<Color>, width: Float) {
    val brush = Brush.linearGradient(colors, start = rect.topLeft, end = rect.bottomRight)
    drawRoundRect(brush, rect.topLeft, rect.size, radius, alpha = 0.3f, style = Stroke(width * 3f))
    drawRoundRect(Color.White, rect.topLeft, rect.size, radius, style = Stroke(width * 0.6f))
}

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
