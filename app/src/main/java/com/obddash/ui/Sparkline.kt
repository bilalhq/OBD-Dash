package com.obddash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.obddash.HISTORY_POINTS

/**
 * Running graph of the last [HISTORY_POINTS] samples. Newest sample is at the right edge,
 * so the line grows in from the right after connecting. NaN samples break the line.
 *
 * The y-axis auto-scales to the window, but never tighter than [minSpan] so sensor
 * noise on a steady value doesn't get blown up into a jagged line.
 */
@Composable
fun Sparkline(
    points: FloatArray?,
    minSpan: Float,
    color: Color,
    modifier: Modifier = Modifier,
    background: Color = DashColors.Surface,
) {
    Canvas(modifier) {
        val strokePx = 2.dp.toPx()
        val dotR = 3.dp.toPx()
        val top = dotR + strokePx
        val bottom = size.height - strokePx / 2
        val right = size.width - dotR - 1.5.dp.toPx()

        // Recessive dotted baseline so the graph has an anchor even before data arrives.
        drawLine(
            DashColors.Track, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
        )

        if (points == null || points.size < 2) return@Canvas
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var finiteCount = 0
        for (v in points) if (!v.isNaN()) { lo = minOf(lo, v); hi = maxOf(hi, v); finiteCount++ }
        if (finiteCount < 2) return@Canvas
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val pad = (hi - lo) * 0.08f
        lo -= pad
        hi += pad

        val stepX = right / (HISTORY_POINTS - 1)
        val offset = HISTORY_POINTS - points.size
        fun x(i: Int) = (i + offset) * stepX
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

        val line = Path()
        val area = Path()
        var penDown = false
        var lastX = 0f
        var lastY = 0f
        for (i in points.indices) {
            val v = points[i]
            if (v.isNaN()) {
                if (penDown) {
                    area.lineTo(lastX, bottom)
                    area.close()
                }
                penDown = false
                continue
            }
            val px = x(i)
            val py = y(v)
            if (!penDown) {
                line.moveTo(px, py)
                area.moveTo(px, bottom)
                area.lineTo(px, py)
                penDown = true
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
            lastX = px
            lastY = py
        }
        if (penDown) {
            area.lineTo(lastX, bottom)
            area.close()
        }

        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = top, endY = bottom),
        )
        // Accent graphs get the signature cyan→violet gradient; warnings stay solid.
        val lineBrush = if (color == DashColors.Accent) {
            Brush.horizontalGradient(listOf(DashColors.Accent, DashColors.Accent2))
        } else {
            SolidColor(color)
        }
        drawPath(line, lineBrush, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
