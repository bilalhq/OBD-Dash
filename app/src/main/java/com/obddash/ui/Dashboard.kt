package com.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.FUEL_AVG_KEY
import com.obddash.FUEL_INSTANT_KEY
import com.obddash.UiState
import com.obddash.VOLTAGE_KEY
import com.obddash.obd.Pid

data class TileData(
    val label: String,
    val value: String,
    val unit: String,
    /** Key into UiState.history for the running graph. */
    val historyKey: String,
    /** Smallest y-range the graph will zoom into (in the sensor's raw metric units). */
    val minSpan: Float,
    val warn: Boolean = false,
)

@Composable
fun Dashboard(s: UiState) {
    val tiles = buildTiles(s)
    val speed = s.values[Pid.SPEED]?.let { if (s.imperial) it * KMH_TO_MPH else it }
    val rpm = s.values[Pid.RPM]
    val redline = s.profile.redlineRpm
    val rpmInRed = rpm != null && rpm >= redline

    val rpmGauge: @Composable (Modifier) -> Unit = { m ->
        GaugeWithGraph(
            history = s.history[Pid.RPM.name],
            minSpan = 500f,
            graphColor = if (rpmInRed) DashColors.Red else DashColors.Accent,
            modifier = m,
        ) {
            ArcGauge(
                value = rpm, min = 0f, max = s.profile.rpmGaugeMax, majorStep = 1000f,
                label = "Engine", unit = "rpm", tickDivisor = 1000f, redline = redline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val speedGauge: @Composable (Modifier) -> Unit = { m ->
        GaugeWithGraph(
            history = s.history[Pid.SPEED.name],
            minSpan = 10f,
            graphColor = DashColors.Accent,
            modifier = m,
        ) {
            ArcGauge(
                value = speed, min = 0f,
                max = if (s.imperial) 160f else 240f,
                majorStep = if (s.imperial) 20f else 40f,
                label = "Speed", unit = if (s.imperial) "mph" else "km/h",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (maxWidth > maxHeight) {
            // Landscape: gauges on the left, tiles on the right.
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rpmGauge(Modifier.weight(1f))
                speedGauge(Modifier.weight(1f))
                Column(
                    Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    TileGrid(tiles, s)
                    StatusLine(s)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rpmGauge(Modifier.weight(1f))
                    speedGauge(Modifier.weight(1f))
                }
                TileGrid(tiles, s)
                StatusLine(s)
            }
        }
    }
}

@Composable
private fun GaugeWithGraph(
    history: FloatArray?,
    minSpan: Float,
    graphColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    gauge: @Composable () -> Unit,
) {
    Column(
        modifier.glass().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) { gauge() }
        Sparkline(
            points = history,
            minSpan = minSpan,
            color = graphColor,
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 6.dp),
        )
        Text(
            "LAST 60 S",
            color = DashColors.Muted.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TileGrid(tiles: List<TileData>, s: UiState, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { StatTile(it, s.history[it.historyKey], Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(t: TileData, history: FloatArray?, modifier: Modifier = Modifier) {
    Column(
        modifier.glass(tint = if (t.warn) DashColors.Amber else null).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (t.warn) SolidColor(DashColors.Amber) else DashBrushes.Accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t.label.uppercase(),
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                t.value,
                fontSize = 30.sp,
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                color = if (t.warn) DashColors.Amber else DashColors.Text,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                t.unit,
                color = DashColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Sparkline(
            points = history,
            minSpan = t.minSpan,
            color = if (t.warn) DashColors.Amber else DashColors.Accent,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}

@Composable
private fun StatusLine(s: UiState) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.protocol?.let { StatusChip(it, DashColors.Muted) }
        if (!s.demo) StatusChip("%.1f readings/s".format(s.readingsPerSec), DashColors.Accent)
        if (s.recordingTripId != null) StatusChip("● Trip ${Fmt.distance(s.tripDistanceKm, s.imperial)}", DashColors.Red)
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DashColors.Surface)
            .border(1.dp, DashColors.Border, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val KMH_TO_MPH = 0.621371f

internal fun buildTiles(s: UiState): List<TileData> {
    val tiles = mutableListOf<TileData>()
    val v = s.voltage
    tiles += TileData(
        label = "Battery",
        value = v?.let { "%.1f".format(it) } ?: "--",
        unit = "V",
        historyKey = VOLTAGE_KEY,
        minSpan = 0.5f,
        warn = v != null && (v < 11.8f || v > 15f),
    )
    for (pid in Pid.entries) {
        if (!pid.dashboard || pid == Pid.RPM || pid == Pid.SPEED || pid !in s.supported) continue
        val raw = s.values[pid]
        val (value, unit) = Fmt.pidValue(pid, raw, s.imperial)
        // With a tank size in the profile, the fuel tile also shows what's left in litres/gallons.
        val tank = s.profile.tankLitresValue
        val label = if (pid == Pid.FUEL_LEVEL && tank != null && raw != null) {
            val litres = tank * raw / 100f
            "${pid.label} · " + if (s.imperial) "%.1f gal".format(litres * 0.264172f) else "%.0f L".format(litres)
        } else {
            pid.label
        }
        tiles += TileData(
            label = label,
            value = value,
            unit = unit,
            historyKey = pid.name,
            minSpan = (pid.max - pid.min) * 0.05f,
            warn = (pid == Pid.COOLANT && raw != null && raw > 105f) ||
                (pid == Pid.FUEL_LEVEL && raw != null && raw < 12f),
        )
    }
    // Instant economy and trip average, from the same MAF fuel estimate as the Trips tab,
    // so only with a MAF sensor.
    if (Pid.MAF in s.supported) {
        tiles += TileData(
            label = "Instant economy",
            value = s.instantKmPerLiter?.let { "%.1f".format(if (s.imperial) it * KM_PER_L_TO_MPG else it) } ?: "--",
            unit = if (s.imperial) "mpg" else "km/L",
            historyKey = FUEL_INSTANT_KEY,
            minSpan = 2f,
        )
        val kmL = s.tripKmPerLiter
        tiles += TileData(
            label = "Trip fuel avg",
            value = kmL?.let { "%.1f".format(if (s.imperial) it * KM_PER_L_TO_MPG else it) } ?: "--",
            unit = if (s.imperial) "mpg" else "km/L",
            historyKey = FUEL_AVG_KEY,
            minSpan = 1f,
        )
    }
    return tiles
}

/** km/L → US mpg (same factor as Fmt.economy's 235.215 / L/100km). */
private const val KM_PER_L_TO_MPG = 2.35215
