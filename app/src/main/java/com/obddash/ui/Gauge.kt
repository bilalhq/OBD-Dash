package com.obddash.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f

/** "8k" for RPM-style gauges (divisor 1000), plain numbers otherwise. */
internal fun tickText(v: Float, divisor: Float): String {
    val n = (v / divisor).roundToInt()
    return if (divisor >= 1000f && n != 0) "${n}k" else n.toString()
}

/**
 * A 270° arc gauge: gradient value arc with a soft glow and a lit tip, fine ticks,
 * an outer red-zone band and a big digital readout.
 * [tickDivisor] scales the tick labels (e.g. 1000 shows RPM as 0…8).
 */
@Composable
fun ArcGauge(
    value: Float?,
    min: Float,
    max: Float,
    majorStep: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    tickDivisor: Float = 1f,
    redline: Float? = null,
) {
    val target = (value ?: min).coerceIn(min, max)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "gauge",
    )
    val measurer = rememberTextMeasurer()

    Canvas(modifier.aspectRatio(1f)) {
        val dim = size.minDimension
        val stroke = dim * 0.055f
        val radius = dim / 2f - stroke * 1.6f
        val c = center
        val arcTopLeft = Offset(c.x - radius, c.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        fun angleOf(v: Float) = START_ANGLE + SWEEP_ANGLE * ((v - min) / (max - min))
        fun pointAt(angleDeg: Float, r: Float): Offset {
            val a = Math.toRadians(angleDeg.toDouble())
            return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        }

        val inRed = redline != null && value != null && animated >= redline
        val startColor = if (inRed) DashColors.Amber else DashColors.Accent
        val endColor = if (inRed) DashColors.Red else DashColors.Accent2
        val tipColor = if (inRed) DashColors.Red else DashColors.Accent2

        // Inner depth: a faint lit disc behind the readout.
        drawCircle(
            Brush.radialGradient(
                listOf(DashColors.SurfaceHigh.copy(alpha = 0.9f), DashColors.Surface.copy(alpha = 0f)),
                center = c, radius = radius * 0.95f,
            ),
            radius = radius * 0.95f, center = c,
        )

        // Track
        drawArc(
            DashColors.Track, START_ANGLE, SWEEP_ANGLE, false, arcTopLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )

        // Red zone: thin band just outside the track.
        if (redline != null) {
            val a = angleOf(redline)
            val r2 = radius + stroke * 1.05f
            drawArc(
                DashColors.Red.copy(alpha = 0.85f), a, START_ANGLE + SWEEP_ANGLE - a, false,
                Offset(c.x - r2, c.y - r2), Size(r2 * 2, r2 * 2),
                style = Stroke(stroke * 0.22f, cap = StrokeCap.Round),
            )
        }

        // Value arc with a sweep gradient. Rotated so the gradient starts where the arc starts;
        // the last stop wraps back to the start colour so the round start cap isn't tinted.
        val sweep = angleOf(animated) - START_ANGLE
        if (value != null && sweep > 0.5f) {
            val brush = Brush.sweepGradient(
                0f to startColor, 0.75f to endColor, 0.9f to endColor, 1f to startColor,
                center = c,
            )
            rotate(START_ANGLE, pivot = c) {
                // Glow layers, widest and faintest first.
                drawArc(brush, 0f, sweep, false, arcTopLeft, arcSize, alpha = 0.07f, style = Stroke(stroke * 3.4f, cap = StrokeCap.Round))
                drawArc(brush, 0f, sweep, false, arcTopLeft, arcSize, alpha = 0.16f, style = Stroke(stroke * 2.0f, cap = StrokeCap.Round))
                drawArc(brush, 0f, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Lit tip
            val tip = pointAt(START_ANGLE + sweep, radius)
            drawCircle(tipColor.copy(alpha = 0.35f), stroke * 1.1f, tip)
            drawCircle(Color.White, stroke * 0.32f, tip)
        }

        // Ticks + labels (inside the arc)
        val tickOuter = radius - stroke * 1.25f
        val tickLabelStyle = TextStyle(
            color = DashColors.Muted, fontSize = (dim * 0.047f).toSp(),
            fontFamily = DashFonts.Inter, fontWeight = FontWeight.Medium,
        )
        val majors = ((max - min) / majorStep).roundToInt()
        for (i in 0..majors * 4) {
            val v = min + i * majorStep / 4f
            val major = i % 4 == 0
            val ang = angleOf(v)
            val len = if (major) stroke * 0.55f else stroke * 0.25f
            val red = redline != null && v >= redline
            drawLine(
                color = when {
                    red -> DashColors.Red.copy(alpha = if (major) 0.9f else 0.5f)
                    major -> DashColors.Muted
                    else -> DashColors.Muted.copy(alpha = 0.3f)
                },
                start = pointAt(ang, tickOuter),
                end = pointAt(ang, tickOuter - len),
                strokeWidth = if (major) dim * 0.009f else dim * 0.005f,
                cap = StrokeCap.Round,
            )
            // Only the end-point labels: inner labels collide with the big readout on small gauges.
            if (major && (i == 0 || i == majors * 4)) {
                val layout = measurer.measure(tickText(v, tickDivisor), tickLabelStyle)
                val p = pointAt(ang, tickOuter - len - dim * 0.06f)
                drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }

        // Readout
        val valueLayout = measurer.measure(
            value?.roundToInt()?.toString() ?: "--",
            TextStyle(
                color = if (inRed) DashColors.Red else DashColors.Text,
                fontSize = (dim * 0.22f).toSp(),
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        )
        drawText(
            valueLayout,
            topLeft = Offset(c.x - valueLayout.size.width / 2f, c.y - valueLayout.size.height * 0.62f),
        )
        val unitLayout = measurer.measure(
            unit.uppercase(),
            TextStyle(
                color = DashColors.Muted, fontSize = (dim * 0.048f).toSp(),
                fontFamily = DashFonts.Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
            ),
        )
        drawText(
            unitLayout,
            topLeft = Offset(c.x - unitLayout.size.width / 2f, c.y + valueLayout.size.height * 0.36f),
        )
        val labelLayout = measurer.measure(
            label.uppercase(),
            TextStyle(
                color = DashColors.Text.copy(alpha = 0.75f),
                fontSize = (dim * 0.05f).toSp(),
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            ),
        )
        drawText(
            labelLayout,
            topLeft = Offset(c.x - labelLayout.size.width / 2f, c.y + radius * 0.86f - labelLayout.size.height / 2f),
        )
    }
}
