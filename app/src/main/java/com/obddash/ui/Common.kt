package com.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.obd.Pid
import java.util.Locale

val CardShape = RoundedCornerShape(22.dp)

/** Glass-style card: lifted gradient surface with a hairline border. */
fun Modifier.glass(shape: Shape = CardShape, tint: Color? = null): Modifier =
    this
        .clip(shape)
        .background(DashBrushes.Card)
        .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.10f)) else Modifier)
        .border(1.dp, if (tint != null) tint.copy(alpha = 0.35f) else DashColors.Border, shape)

/** Soft colour glows behind the whole app so the black isn't flat. */
fun Modifier.ambientBackground(): Modifier = this
    .background(DashColors.Bg)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(DashColors.Accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.05f),
                radius = size.maxDimension * 0.6f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(DashColors.Accent2.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.9f),
                radius = size.maxDimension * 0.6f,
            )
        )
    }

@Composable
fun DashCard(accent: Color? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(tint = accent).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Primary call-to-action with the signature gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) DashBrushes.Accent else SolidColor(DashColors.Track))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) DashColors.Bg else DashColors.Muted,
            fontFamily = DashFonts.Display,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

/** Secondary button: glass outline. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = DashColors.Text) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(DashColors.SurfaceHigh.copy(alpha = 0.6f))
            .border(1.dp, DashColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Small uppercase section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            color = DashColors.Muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Unit conversion and formatting shared by the screens. */
object Fmt {
    private const val KM_TO_MI = 0.621371

    fun distance(km: Double, imperial: Boolean) =
        if (imperial) "%.1f mi".format(km * KM_TO_MI) else "%.1f km".format(km)

    fun speed(kmh: Double, imperial: Boolean) =
        if (imperial) "%.0f mph".format(kmh * KM_TO_MI) else "%.0f km/h".format(kmh)

    fun fuel(liters: Double?, imperial: Boolean) = when {
        liters == null -> "--"
        imperial -> "%.2f gal".format(liters * 0.264172)
        else -> "%.2f L".format(liters)
    }

    fun economy(litersPer100Km: Double?, imperial: Boolean) = when {
        litersPer100Km == null || litersPer100Km <= 0 -> "--"
        imperial -> "%.1f mpg".format(235.215 / litersPer100Km)
        else -> "%.1f km/L".format(100.0 / litersPer100Km)
    }

    /** Formats a decoded PID value, converting to imperial where it applies. Returns (value, unit). */
    fun pidValue(pid: Pid, raw: Float?, imperial: Boolean): Pair<String, String> {
        if (pid.unit == "s") {
            // Run time reads better as h:mm:ss
            val text = raw?.toLong()?.let { String.format(Locale.US, "%d:%02d:%02d", it / 3600, (it % 3600) / 60, it % 60) }
            return (text ?: "--") to ""
        }
        val (unit, conv) = when {
            imperial && pid.unit == "°C" -> "°F" to { v: Float -> v * 9f / 5f + 32f }
            imperial && pid.unit == "km/h" -> "mph" to { v: Float -> v * KM_TO_MI.toFloat() }
            imperial && pid.unit == "km" -> "mi" to { v: Float -> v * KM_TO_MI.toFloat() }
            else -> pid.unit to { v: Float -> v }
        }
        return (raw?.let { "%.${pid.decimals}f".format(conv(it)) } ?: "--") to unit
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%d h %02d min", h, m)
            m > 0 -> "$m min"
            else -> "$seconds s"
        }
    }
}
