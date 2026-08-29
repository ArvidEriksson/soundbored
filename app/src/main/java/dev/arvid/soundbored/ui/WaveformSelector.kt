package dev.arvid.soundbored.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private enum class Grab { START, END, WHOLE, FADE_IN, FADE_OUT }

/** Envelope value at a point in time — the drawn bars are scaled by exactly what you will hear. */
private fun gainAt(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    fadeInMs: Long,
    fadeOutMs: Long,
): Float {
    val offset = positionMs - startMs
    val length = endMs - startMs
    // Outside the selection there is nothing to fade: that audio is drawn as it really is.
    if (offset < 0 || offset > length || length <= 0) return 1f
    val rise = if (fadeInMs > 0 && offset < fadeInMs) offset.toFloat() / fadeInMs else 1f
    val remaining = length - offset
    val fall = if (fadeOutMs > 0 && remaining < fadeOutMs) remaining.toFloat() / fadeOutMs else 1f
    return minOf(rise, fall).coerceIn(0f, 1f)
}

/**
 * The waveform doubles as the interval picker: drag either edge to trim, drag the
 * middle to slide the whole selection along the track.
 *
 * [windowStartMs]/[windowEndMs] pick which slice of the track fills the width, which is what
 * turns the same component into the zoomed-in fine-tune strip.
 */
@Composable
fun WaveformSelector(
    peaks: FloatArray,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    onMove: (Long) -> Unit,
    modifier: Modifier = Modifier,
    windowStartMs: Long = 0L,
    windowEndMs: Long = durationMs,
    fadeInMs: Long = 0L,
    fadeOutMs: Long = 0L,
    onFadeInChange: ((Long) -> Unit)? = null,
    onFadeOutChange: ((Long) -> Unit)? = null,
) {
    val selected = MaterialTheme.colorScheme.primary
    val unselected = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val selectionFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val handleColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.tertiary

    // Held in updated state so an in-flight drag never restarts the gesture detector.
    val currentStart by rememberUpdatedState(startMs)
    val currentEnd by rememberUpdatedState(endMs)
    val window by rememberUpdatedState(
        (windowEndMs - windowStartMs).coerceAtLeast(1L) to windowStartMs
    )
    val rangeChange by rememberUpdatedState(onRangeChange)
    val move by rememberUpdatedState(onMove)
    val currentFadeIn by rememberUpdatedState(fadeInMs)
    val currentFadeOut by rememberUpdatedState(fadeOutMs)
    val fadeInChange by rememberUpdatedState(onFadeInChange)
    val fadeOutChange by rememberUpdatedState(onFadeOutChange)
    var grab by remember { mutableStateOf(Grab.START) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    val (windowLength, windowStart) = window
                    val width = size.width.toFloat()
                    val startX = (currentStart - windowStart).toFloat() / windowLength * width
                    val endX = (currentEnd - windowStart).toFloat() / windowLength * width
                    val slop = 32.dp.toPx()
                    val nearStart = abs(offset.x - startX)
                    val nearEnd = abs(offset.x - endX)
                    // Only meaningful once a fade exists; at zero the handle sits under the
                    // edge handle, which keeps trimming the priority gesture.
                    val fadeInX = (currentStart + currentFadeIn - windowStart).toFloat() /
                        windowLength * width
                    val fadeOutX = (currentEnd - currentFadeOut - windowStart).toFloat() /
                        windowLength * width
                    val nearFadeIn = if (fadeInChange != null && currentFadeIn > 0L) {
                        abs(offset.x - fadeInX)
                    } else {
                        Float.MAX_VALUE
                    }
                    val nearFadeOut = if (fadeOutChange != null && currentFadeOut > 0L) {
                        abs(offset.x - fadeOutX)
                    } else {
                        Float.MAX_VALUE
                    }
                    grab = when {
                        nearStart <= slop && nearStart <= nearEnd -> Grab.START
                        nearEnd <= slop && nearEnd < nearFadeOut -> Grab.END
                        nearFadeOut <= slop && nearFadeOut <= nearFadeIn -> Grab.FADE_OUT
                        nearFadeIn <= slop -> Grab.FADE_IN
                        nearEnd <= slop -> Grab.END
                        offset.x > startX && offset.x < endX -> Grab.WHOLE
                        nearStart < nearEnd -> Grab.START
                        else -> Grab.END
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val (windowLength, _) = window
                    val deltaMs = (dragAmount.x / size.width * windowLength).toLong()
                    when (grab) {
                        Grab.START -> rangeChange(currentStart + deltaMs, currentEnd)
                        Grab.END -> rangeChange(currentStart, currentEnd + deltaMs)
                        Grab.WHOLE -> move(deltaMs)
                        // Dragging the fade marker left grows the fade, right shrinks it.
                        Grab.FADE_IN -> fadeInChange?.invoke(currentFadeIn + deltaMs)
                        Grab.FADE_OUT -> fadeOutChange?.invoke(currentFadeOut - deltaMs)
                    }
                },
            )
        }
    ) {
        val width = size.width
        val height = size.height
        val middle = height / 2f
        val count = peaks.size
        val windowLength = (windowEndMs - windowStartMs).coerceAtLeast(1L)

        if (count == 0 || durationMs <= 0L) {
            drawLine(
                color = unselected,
                start = Offset(0f, middle),
                end = Offset(width, middle),
                strokeWidth = 2f,
            )
            return@Canvas
        }

        fun xOf(timeMs: Long): Float = (timeMs - windowStartMs).toFloat() / windowLength * width

        val startX = xOf(startMs)
        val endX = xOf(endMs)

        drawRoundRect(
            color = selectionFill,
            topLeft = Offset(startX.coerceIn(0f, width), 0f),
            size = Size(
                (endX.coerceIn(0f, width) - startX.coerceIn(0f, width)).coerceAtLeast(1f),
                height,
            ),
            cornerRadius = CornerRadius(4f, 4f),
        )

        // Collapse however many buckets fall in the window down to one bar per ~2dp.
        val firstBucket = floor(windowStartMs.toDouble() / durationMs * count).toInt()
            .coerceIn(0, count - 1)
        val lastBucket = ceil(windowEndMs.toDouble() / durationMs * count).toInt()
            .coerceIn(firstBucket + 1, count)
        val bucketsInWindow = lastBucket - firstBucket
        val barCount = minOf(bucketsInWindow, (width / 2.dp.toPx()).toInt().coerceAtLeast(1))
        val barWidth = width / barCount
        val stroke = max(1.5f, barWidth * 0.6f)

        for (bar in 0 until barCount) {
            val from = firstBucket + bar * bucketsInWindow / barCount
            val to = (firstBucket + (bar + 1) * bucketsInWindow / barCount).coerceAtLeast(from + 1)
            var peak = 0f
            for (i in from until minOf(to, count)) peak = max(peak, peaks[i])

            val x = bar * barWidth + barWidth / 2f
            val barTimeMs = windowStartMs + (x / width * windowLength).toLong()
            val half = max(peak, 0.015f) * height * 0.45f * gainAt(
                positionMs = barTimeMs,
                startMs = startMs,
                endMs = endMs,
                fadeInMs = fadeInMs,
                fadeOutMs = fadeOutMs,
            )
            drawLine(
                color = if (x in startX..endX) selected else unselected,
                start = Offset(x, middle - half),
                end = Offset(x, middle + half),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        // The ramp lines make it obvious where the fade begins and how steep it is.
        if (fadeInMs > 0L) {
            drawLine(
                color = handleColor,
                start = Offset(startX, height),
                end = Offset(xOf(startMs + fadeInMs), 0f),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        if (fadeOutMs > 0L) {
            drawLine(
                color = handleColor,
                start = Offset(xOf(endMs - fadeOutMs), 0f),
                end = Offset(endX, height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }

        val handleWidth = 3.dp.toPx()
        listOf(startX, endX).forEach { x ->
            if (x >= -handleWidth && x <= width + handleWidth) {
                drawRoundRect(
                    color = handleColor,
                    topLeft = Offset(x - handleWidth / 2f, 0f),
                    size = Size(handleWidth, height),
                    cornerRadius = CornerRadius(handleWidth, handleWidth),
                )
            }
        }

        if (playheadMs >= 0L) {
            val x = xOf(playheadMs)
            if (x in 0f..width) {
                drawLine(
                    color = playheadColor,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}
