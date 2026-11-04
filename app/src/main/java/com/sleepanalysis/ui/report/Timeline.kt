package com.sleepanalysis.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.domain.scoring.SnoreScore

private fun tierColor(tier: String): Color = when (tier) {
    SnoreScore.IntensityTiers.EPIC -> Color(0xFFE85D75)
    SnoreScore.IntensityTiers.LOUD -> Color(0xFFF0A45B)
    SnoreScore.IntensityTiers.LIGHT -> Color(0xFF7FD1E8)
    else -> Color(0xFF5B8BA6)
}

private val otherNoiseColor = Color(0xFF8A94A0)

/**
 * Interactive night timeline: one horizontal strip, events as colored blocks. Tap selects the
 * nearest event (docs/MVP_SCOPE.md F5). Pure presentation — no clinical meaning is implied by
 * colors; they encode relative intensity only.
 */
@Composable
fun NightTimeline(
    events: List<AcousticEventEntity>,
    durationMs: Long,
    selectedEventId: Long?,
    onSelect: (AcousticEventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val laneHeight = 12.dp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.primary

    fun nearest(offsetMs: Long): AcousticEventEntity? = events.minByOrNull { event ->
        when {
            offsetMs < event.offsetMs -> event.offsetMs - offsetMs
            offsetMs > event.offsetMs + event.durationMs -> offsetMs - (event.offsetMs + event.durationMs)
            else -> 0L
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(events, durationMs) {
                detectTapGestures { tap ->
                    if (durationMs <= 0) return@detectTapGestures
                    val offsetMs = (tap.x / size.width * durationMs).toLong()
                    nearest(offsetMs)?.let(onSelect)
                }
            },
    ) {
        val laneY = (size.height - laneHeight.toPx()) / 2
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, laneY),
            size = Size(size.width, laneHeight.toPx()),
            cornerRadius = CornerRadius(laneHeight.toPx() / 2),
        )
        if (durationMs <= 0) return@Canvas
        for (event in events) {
            val x = event.offsetMs / durationMs.toFloat() * size.width
            val w = (event.durationMs / durationMs.toFloat() * size.width)
                .coerceAtLeast(2.dp.toPx())
            if (x > size.width) continue
            val color = if (event.type == EventType.SNORE) tierColor(event.intensityTier) else otherNoiseColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, laneY),
                size = Size(w, laneHeight.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            if (event.id == selectedEventId) {
                drawRoundRect(
                    color = highlight,
                    topLeft = Offset(x - 2.dp.toPx(), laneY - 2.dp.toPx()),
                    size = Size(w + 4.dp.toPx(), laneHeight.toPx() + 4.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke,
                )
            }
        }
    }
}

private val Stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)

/**
 * Hourly snoring bars (snore seconds per hour of the session). Y axis auto-scales to the
 * busiest hour; values are labeled nowhere near clinical metrics on purpose.
 */
@Composable
fun HourlyBars(
    hourlySnoreSeconds: List<Long>,
    modifier: Modifier = Modifier,
) {
    val maxSeconds = remember(hourlySnoreSeconds) { (hourlySnoreSeconds.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        if (hourlySnoreSeconds.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (hourlySnoreSeconds.size - 1)) / hourlySnoreSeconds.size
        val baselineY = size.height
        drawLine(trackColor, Offset(0f, baselineY), Offset(size.width, baselineY), strokeWidth = 2f)
        hourlySnoreSeconds.forEachIndexed { i, seconds ->
            val h = (seconds / maxSeconds.toFloat()) * (size.height - 8f)
            drawRect(
                color = barColor,
                topLeft = Offset(i * (barWidth + gap), baselineY - h),
                size = Size(barWidth.coerceAtLeast(1f), h),
            )
        }
    }
}
