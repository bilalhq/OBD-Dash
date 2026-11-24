package com.obddash.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.obddash.ConnState
import com.obddash.HISTORY_POINTS
import com.obddash.R
import com.obddash.UiState
import com.obddash.VOLTAGE_KEY
import com.obddash.obd.Pid
import com.obddash.ui.TileData
import com.obddash.ui.buildTiles
import com.obddash.ui.tickText
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Draws the dashboard onto the Android Auto surface. Same look as the phone UI. */
class CarDashRenderer(context: Context) {

    private class Palette(
        val bg: Int, val surface: Int, val surfaceHigh: Int, val border: Int, val track: Int,
        val accent: Int, val accent2: Int, val red: Int, val amber: Int, val text: Int, val muted: Int,
        /** Ambient background glow strengths (accent top-left, accent2 bottom-right). */
        val glowAlpha: Int, val glow2Alpha: Int,
        /** Gauge face: radial gradient centre → edge, plus an optional rim (0 = none). */
        val faceInner: Int, val faceOuter: Int, val faceRim: Int,
        /** Needle tip: halo alpha and core dot colour. */
        val tipHaloAlpha: Int, val tipCore: Int,
    )

    // Night: same palette as ui/Theme.kt
    private val night = Palette(
        bg = 0xFF07090D.toInt(), surface = 0xFF10141B.toInt(), surfaceHigh = 0xFF171C26.toInt(),
        border = 0x12FFFFFF, track = 0xFF1E2530.toInt(),
        accent = 0xFF22D3EE.toInt(), accent2 = 0xFF8B5CF6.toInt(), red = 0xFFFB4D6D.toInt(), amber = 0xFFFBBF24.toInt(),
        text = 0xFFF1F5F9.toInt(), muted = 0xFF8A94A6.toInt(),
        glowAlpha = 0x1A, glow2Alpha = 0x17,
        faceInner = 0xE6171C26.toInt(), faceOuter = 0x0010141B, faceRim = 0,
        tipHaloAlpha = 0x59, tipCore = 0xFFFFFFFF.toInt(),
    )

    // Day: light surfaces with deeper accents so they hold contrast in sunlight.
    private val day = Palette(
        bg = 0xFFF2F5F9.toInt(), surface = 0xFFE9EEF4.toInt(), surfaceHigh = 0xFFFFFFFF.toInt(),
        border = 0x1F0F172A, track = 0xFFD6DDE6.toInt(),
        accent = 0xFF0891B2.toInt(), accent2 = 0xFF7C3AED.toInt(), red = 0xFFE11D48.toInt(), amber = 0xFFD97706.toInt(),
        text = 0xFF0F172A.toInt(), muted = 0xFF526070.toInt(),
        glowAlpha = 0x14, glow2Alpha = 0x12,
        faceInner = 0xFFFFFFFF.toInt(), faceOuter = 0xFFE4EAF1.toInt(), faceRim = 0x260F172A,
        tipHaloAlpha = 0x80, tipCore = 0xFF0F172A.toInt(),
    )

    private var p = night

    /** Switches between the night (dark) and day (light) palettes. */
    fun setDark(dark: Boolean) {
        p = if (dark) night else day
    }

    // Shorthands for the current palette.
    private val bg get() = p.bg
    private val surface get() = p.surface
    private val surfaceHigh get() = p.surfaceHigh
    private val border get() = p.border
    private val track get() = p.track
    private val accent get() = p.accent
    private val accent2 get() = p.accent2
    private val red get() = p.red
    private val amber get() = p.amber
    private val textColor get() = p.text
    private val muted get() = p.muted

    // Bundled fonts (variable); fall back to system fonts if loading fails.
    private val displayBase: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull() ?: Typeface.DEFAULT
    private val bodyBase: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
    private val display = weighted(displayBase, 600)
    private val body = weighted(bodyBase, 400)
    private val bodyMedium = weighted(bodyBase, 600)

    private fun weighted(base: Typeface, weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

    private val gradientMatrix = Matrix()

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val areaPath = Path()

    private val tileOrder = listOf(
        VOLTAGE_KEY, Pid.COOLANT.name, Pid.THROTTLE.name, Pid.LOAD.name, Pid.INTAKE_TEMP.name, Pid.FUEL_LEVEL.name,
    )

    fun draw(c: Canvas, area: Rect, d: Float, s: UiState, rpm: Float?, speed: Float?, permissionMissing: Boolean) {
        c.drawColor(bg)
        // Ambient glows, same as the phone background.
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        fill.shader = RadialGradient(w * 0.1f, h * 0.05f, maxOf(w, h) * 0.6f, withAlpha(accent, p.glowAlpha), withAlpha(accent, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = RadialGradient(w * 0.95f, h * 0.9f, maxOf(w, h) * 0.6f, withAlpha(accent2, p.glow2Alpha), withAlpha(accent2, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        val r = RectF(area).apply { inset(16 * d, 12 * d) }
        if (r.width() <= 0 || r.height() <= 0) return

        if (s.conn != ConnState.Connected) {
            drawStatus(c, r, d, s, permissionMissing)
            return
        }

        val gap = 12 * d
        val minTileH = 92 * d
        val minTileW = 120 * d

        val gauges: RectF
        val tiles: RectF
        val cols: Int
        val rows: Int
        if (r.width() / r.height() >= 1.7f) {
            // Wide (full screen): gauges left, 2-column tile grid right.
            val tilesW = r.width() * 0.40f
            gauges = RectF(r.left, r.top, r.right - tilesW - gap, r.bottom)
            tiles = RectF(r.right - tilesW, r.top, r.right, r.bottom)
            cols = 2
            rows = ((tiles.height() + gap) / (minTileH + gap)).toInt().coerceIn(1, 3)
        } else {
            // Squarer (split screen next to media/maps): gauges across the top, tiles below.
            // Gauges take what's left after reserving at least one row of tiles.
            val gaugeH = min((r.width() - gap) / 2f, r.height() - minTileH - gap).coerceAtLeast(r.height() * 0.45f)
            gauges = RectF(r.left, r.top, r.right, r.top + gaugeH)
            tiles = RectF(r.left, gauges.bottom + gap, r.right, r.bottom)
            cols = ((tiles.width() + gap) / (minTileW + gap)).toInt().coerceIn(2, 3)
            rows = ((tiles.height() + gap) / (minTileH + gap)).toInt().coerceIn(1, 6 / cols)
        }

        val size = min((gauges.width() - gap) / 2f, gauges.height())
        val top = gauges.centerY() - size / 2
        val x1 = gauges.centerX() - (size * 2 + gap) / 2
        val x2 = x1 + size + gap
        val imperial = s.imperial

        drawGauge(
            c, RectF(x1, top, x1 + size, top + size), rpm,
            min = 0f, max = s.profile.rpmGaugeMax, step = 1000f, label = "Engine", unit = "rpm", divisor = 1000f,
            redline = s.profile.redlineRpm,
        )
        drawGauge(
            c, RectF(x2, top, x2 + size, top + size), speed?.let { if (imperial) it * 0.621371f else it },
            min = 0f, max = if (imperial) 160f else 240f, step = if (imperial) 20f else 40f,
            label = "Speed", unit = if (imperial) "mph" else "km/h", divisor = 1f, redline = null,
        )
        if (tiles.height() > 0) drawTiles(c, tiles, d, s, cols, rows)
    }

    // ------------------------------------------------------------------ status

    private fun drawStatus(c: Canvas, r: RectF, d: Float, s: UiState, permissionMissing: Boolean) {
        val message = when {
            permissionMissing -> "Open OBD Dash on your phone once to allow Bluetooth access."
            s.conn == ConnState.Connecting -> s.message ?: "Connecting…"
            s.conn == ConnState.Error -> s.message ?: "Couldn't connect to the adapter."
            s.lastAddress == null -> "Open OBD Dash on your phone and connect to your adapter once. " +
                "After that it connects automatically here. Or tap Demo."
            else -> "Not connected. Tap Connect, or Demo to try it out."
        }
        val dot = when (s.conn) {
            ConnState.Connecting -> amber
            ConnState.Error -> red
            else -> muted
        }

        text.textAlign = Paint.Align.CENTER
        text.typeface = weighted(displayBase, 700)
        text.textSize = 30 * d
        text.color = textColor
        text.letterSpacing = 0f
        val titleY = r.centerY() - 24 * d
        // "OBD" in the accent gradient, "Dash" in white
        val full = text.measureText("OBD Dash")
        val obdW = text.measureText("OBD ")
        val left = r.centerX() - full / 2
        text.textAlign = Paint.Align.LEFT
        text.shader = LinearGradient(left, 0f, left + obdW, 0f, accent, accent2, Shader.TileMode.CLAMP)
        c.drawText("OBD ", left, titleY, text)
        text.shader = null
        c.drawText("Dash", left + obdW, titleY, text)
        fill.color = withAlpha(dot, 0x38)
        c.drawCircle(left - 16 * d, titleY - 10 * d, 9 * d, fill)
        fill.color = dot
        c.drawCircle(left - 16 * d, titleY - 10 * d, 4.5f * d, fill)

        text.textAlign = Paint.Align.LEFT
        text.typeface = body
        text.textSize = 16 * d
        text.color = muted
        val width = min(r.width(), 520 * d).toInt()
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, text, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        c.save()
        c.translate(r.centerX() - width / 2f, titleY + 16 * d)
        layout.draw(c)
        c.restore()
    }

    // ------------------------------------------------------------------ gauge

    private fun drawGauge(
        c: Canvas, box: RectF, value: Float?,
        min: Float, max: Float, step: Float, label: String, unit: String, divisor: Float, redline: Float?,
    ) {
        val dim = box.width()
        val stroke = dim * 0.055f
        val radius = dim / 2f - stroke * 1.6f
        val cx = box.centerX()
        val cy = box.centerY()
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        fun angle(v: Float) = 135f + 270f * ((v.coerceIn(min, max) - min) / (max - min))

        val inRed = redline != null && value != null && value >= redline
        val startColor = if (inRed) amber else accent
        val endColor = if (inRed) red else accent2

        // Inner depth disc
        fill.shader = RadialGradient(cx, cy, radius * 0.95f, p.faceInner, p.faceOuter, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius * 0.95f, fill)
        fill.shader = null
        if (p.faceRim != 0) {
            line.shader = null
            line.color = p.faceRim
            line.strokeWidth = dim * 0.004f
            c.drawCircle(cx, cy, radius * 0.95f, line)
        }

        // Track
        arc.shader = null
        arc.alpha = 255
        arc.strokeWidth = stroke
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = track
        c.drawArc(oval, 135f, 270f, false, arc)

        // Red zone band outside the track
        if (redline != null) {
            val r2 = radius + stroke * 1.05f
            arc.strokeWidth = stroke * 0.22f
            arc.color = withAlpha(red, 0xD9)
            val a = angle(redline)
            c.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), a, 405f - a, false, arc)
        }

        // Gradient value arc + glow
        if (value != null) {
            val sweep = angle(value) - 135f
            if (sweep > 0.5f) {
                val shader = SweepGradient(
                    cx, cy, intArrayOf(startColor, endColor, endColor, startColor), floatArrayOf(0f, 0.75f, 0.9f, 1f),
                )
                gradientMatrix.setRotate(135f, cx, cy)
                shader.setLocalMatrix(gradientMatrix)
                arc.color = 0xFFFFFFFF.toInt()
                arc.shader = shader
                arc.strokeCap = Paint.Cap.ROUND
                for ((width, alpha) in listOf(3.4f to 18, 2.0f to 41, 1.0f to 255)) {
                    arc.strokeWidth = stroke * width
                    arc.alpha = alpha
                    c.drawArc(oval, 135f, sweep, false, arc)
                }
                arc.shader = null
                arc.alpha = 255

                // Lit tip
                val ta = Math.toRadians((135f + sweep).toDouble())
                val tx = cx + radius * cos(ta).toFloat()
                val ty = cy + radius * sin(ta).toFloat()
                fill.color = withAlpha(if (inRed) red else accent2, p.tipHaloAlpha)
                c.drawCircle(tx, ty, stroke * 1.1f, fill)
                fill.color = p.tipCore
                c.drawCircle(tx, ty, stroke * 0.32f, fill)
            }
        }

        // Ticks + labels
        val tickOuter = radius - stroke * 1.25f
        val majors = ((max - min) / step).roundToInt()
        text.textAlign = Paint.Align.CENTER
        text.typeface = bodyMedium
        text.letterSpacing = 0f
        for (i in 0..majors * 4) {
            val v = min + i * step / 4f
            val major = i % 4 == 0
            val isRed = redline != null && v >= redline
            val a = Math.toRadians(angle(v).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val len = if (major) stroke * 0.55f else stroke * 0.25f
            line.shader = null
            line.color = when {
                isRed -> withAlpha(red, if (major) 0xE6 else 0x80)
                major -> muted
                else -> withAlpha(muted, 0x4D)
            }
            line.strokeWidth = if (major) dim * 0.009f else dim * 0.005f
            c.drawLine(cx + ca * tickOuter, cy + sa * tickOuter, cx + ca * (tickOuter - len), cy + sa * (tickOuter - len), line)
            // Only the end-point labels: inner labels collide with the big readout.
            if (major && (i == 0 || i == majors * 4)) {
                text.textSize = dim * 0.047f
                text.color = muted
                val lr = tickOuter - len - dim * 0.06f
                c.drawText(tickText(v, divisor), cx + ca * lr, cy + sa * lr + text.textSize * 0.35f, text)
            }
        }

        // Readout
        text.typeface = display
        text.textSize = dim * 0.22f
        text.color = if (inRed) red else textColor
        c.drawText(value?.roundToInt()?.toString() ?: "--", cx, cy + dim * 0.05f, text)

        text.typeface = bodyMedium
        text.textSize = dim * 0.048f
        text.color = muted
        text.letterSpacing = 0.12f
        c.drawText(unit.uppercase(), cx, cy + dim * 0.14f, text)

        text.typeface = display
        text.textSize = dim * 0.05f
        text.color = withAlpha(textColor, 0xBF)
        text.letterSpacing = 0.15f
        c.drawText(label.uppercase(), cx, cy + radius * 0.86f + dim * 0.02f, text)
        text.letterSpacing = 0f
    }

    // ------------------------------------------------------------------ tiles

    private fun drawTiles(c: Canvas, area: RectF, d: Float, s: UiState, cols: Int, rows: Int) {
        val all = buildTiles(s)
        val chosen = (tileOrder.mapNotNull { k -> all.find { it.historyKey == k } } + all).distinct().take(cols * rows)
        val gap = 10 * d
        val tw = (area.width() - gap * (cols - 1)) / cols
        val th = (area.height() - gap * (rows - 1)) / rows
        chosen.forEachIndexed { i, t ->
            val col = i % cols
            val row = i / cols
            val left = area.left + col * (tw + gap)
            val top = area.top + row * (th + gap)
            drawTile(c, RectF(left, top, left + tw, top + th), d, t, s.history[t.historyKey])
        }
    }

    private fun drawTile(c: Canvas, r: RectF, d: Float, t: TileData, history: FloatArray?) {
        // Glass card: lifted gradient + hairline border (amber-tinted when warning)
        val corner = 16 * d
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, surfaceHigh, surface, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, corner, corner, fill)
        fill.shader = null
        if (t.warn) {
            fill.color = withAlpha(amber, 0x1A)
            c.drawRoundRect(r, corner, corner, fill)
        }
        line.shader = null
        line.color = if (t.warn) withAlpha(amber, 0x59) else border
        line.strokeWidth = 1 * d
        c.drawRoundRect(r, corner, corner, line)
        val px = 12 * d

        // Label with a small gradient dot
        val labelY = r.top + 18 * d
        val dotX = r.left + px + 3 * d
        if (t.warn) {
            fill.color = amber
        } else {
            fill.shader = LinearGradient(dotX - 3 * d, 0f, dotX + 3 * d, 0f, accent, accent2, Shader.TileMode.CLAMP)
        }
        c.drawCircle(dotX, labelY - 3.5f * d, 3 * d, fill)
        fill.shader = null

        text.textAlign = Paint.Align.LEFT
        text.typeface = bodyMedium
        text.textSize = 10 * d
        text.letterSpacing = 0.12f
        text.color = muted
        c.drawText(ellipsize(t.label.uppercase(), r.width() - px * 2 - 10 * d), r.left + px + 10 * d, labelY, text)
        text.letterSpacing = 0f

        val valueSize = min(24 * d, r.height() * 0.3f)
        text.typeface = display
        text.textSize = valueSize
        text.color = if (t.warn) amber else textColor
        val valueY = labelY + valueSize + 2 * d
        c.drawText(t.value, r.left + px, valueY, text)
        val vw = text.measureText(t.value)

        text.typeface = bodyMedium
        text.textSize = 12 * d
        text.color = muted
        c.drawText(t.unit, r.left + px + vw + 5 * d, valueY, text)

        val spark = RectF(r.left + px, valueY + 6 * d, r.right - px, r.bottom - 8 * d)
        if (spark.height() > 8 * d) {
            drawSparkline(c, spark, history, t.minSpan, if (t.warn) amber else accent, d)
        }
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    // ------------------------------------------------------------------ sparkline

    private fun drawSparkline(c: Canvas, r: RectF, points: FloatArray?, minSpan: Float, color: Int, d: Float) {
        line.shader = null
        line.color = track
        line.strokeWidth = 1 * d
        line.pathEffect = DashPathEffect(floatArrayOf(2 * d, 4 * d), 0f)
        c.drawLine(r.left, r.bottom, r.right, r.bottom, line)
        line.pathEffect = null
        if (points == null || points.size < 2) return

        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var finite = 0
        for (v in points) if (!v.isNaN()) { lo = minOf(lo, v); hi = maxOf(hi, v); finite++ }
        if (finite < 2) return
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val pad = (hi - lo) * 0.08f
        lo -= pad
        hi += pad

        val dotR = 3 * d
        val top = r.top + dotR
        val bottom = r.bottom
        val right = r.right - dotR
        val stepX = (right - r.left) / (HISTORY_POINTS - 1)
        val offset = HISTORY_POINTS - points.size
        fun x(i: Int) = r.left + (i + offset) * stepX
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

        linePath.reset()
        areaPath.reset()
        var penDown = false
        var lastX = 0f
        var lastY = 0f
        for (i in points.indices) {
            val v = points[i]
            if (v.isNaN()) {
                if (penDown) { areaPath.lineTo(lastX, bottom); areaPath.close() }
                penDown = false
                continue
            }
            val px = x(i)
            val py = y(v)
            if (!penDown) {
                linePath.moveTo(px, py)
                areaPath.moveTo(px, bottom)
                areaPath.lineTo(px, py)
                penDown = true
            } else {
                linePath.lineTo(px, py)
                areaPath.lineTo(px, py)
            }
            lastX = px
            lastY = py
        }
        if (penDown) { areaPath.lineTo(lastX, bottom); areaPath.close() }

        fill.shader = LinearGradient(0f, top, 0f, bottom, withAlpha(color, 0x38), withAlpha(color, 0), Shader.TileMode.CLAMP)
        c.drawPath(areaPath, fill)
        fill.shader = null

        // Accent graphs get the cyan→violet gradient; warnings stay solid.
        line.color = color
        line.shader = if (color == accent) {
            LinearGradient(r.left, 0f, r.right, 0f, accent, accent2, Shader.TileMode.CLAMP)
        } else {
            null
        }
        line.strokeWidth = 2 * d
        c.drawPath(linePath, line)
        line.shader = null
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
