package com.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.ConnState
import com.obddash.UiState
import com.obddash.obd.Pid

private data class SensorRow(
    val key: String,
    val name: String,
    /** e.g. "01 0C" — the mode/PID the reading came from; null for adapter rows. */
    val pid: String?,
    val value: String,
    val unit: String,
    val raw: Boolean = false,
)

private data class Section(val title: String, val rows: List<SensorRow>)

@Composable
fun SensorsScreen(s: UiState) {
    var filter by rememberSaveable { mutableStateOf("") }

    if (s.conn != ConnState.Connected) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.widthIn(max = 720.dp)) {
                DashCard {
                    Text("All sensors", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connect to your adapter on the Live tab (or start demo mode) to see every reading the car reports.",
                        color = DashColors.Muted,
                    )
                }
            }
        }
        return
    }

    val sections = buildSections(s).map { sec ->
        if (filter.isBlank()) sec
        else sec.copy(rows = sec.rows.filter {
            it.name.contains(filter, ignoreCase = true) || it.pid?.contains(filter, ignoreCase = true) == true
        })
    }.filter { it.rows.isNotEmpty() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "filter") {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter sensors") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.isNotEmpty()) {
                        IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear filter") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = DashColors.Track,
                    unfocusedContainerColor = DashColors.Surface,
                    focusedContainerColor = DashColors.Surface,
                ),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No sensors match \"$filter\".",
                    color = DashColors.Muted,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        sections.forEach { section ->
            item(key = "h-${section.title}") {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        section.title.uppercase(),
                        color = DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Pill("${section.rows.size}", DashColors.Accent)
                }
            }
            val last = section.rows.lastIndex
            items(section.rows.withIndex().toList(), key = { it.value.key }) { (i, row) ->
                val r = 14.dp
                val shape = RoundedCornerShape(
                    topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
                    bottomStart = if (i == last) r else 0.dp, bottomEnd = if (i == last) r else 0.dp,
                )
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    SensorRowView(row, Modifier.background(DashColors.Surface.copy(alpha = 0.92f), shape))
                    if (i != last) Spacer(Modifier.height(1.dp))
                }
            }
        }
        item(key = "footer") {
            Text(
                "RPM, speed and throttle refresh every cycle and the dashboard sensors refresh next. " +
                    "Everything else takes turns in the background, so those readings can be a few seconds old.",
                color = DashColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 720.dp).padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun SensorRowView(row: SensorRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, color = DashColors.Text, style = MaterialTheme.typography.bodyMedium)
            if (row.pid != null) {
                Text(row.pid, color = DashColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            row.value,
            color = if (row.value == "--") DashColors.Muted else DashColors.Text,
            fontFamily = if (row.raw) FontFamily.Monospace else DashFonts.Display,
            fontWeight = if (row.raw) FontWeight.Normal else FontWeight.SemiBold,
            fontSize = if (row.raw) 14.sp else 18.sp,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
        )
        if (row.unit.isNotEmpty()) {
            Text(
                row.unit,
                color = DashColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp).widthIn(min = 36.dp),
            )
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

private fun pidLabel(code: Int) = "01 %02X".format(code)

private fun buildSections(s: UiState): List<Section> {
    val adapter = listOfNotNull(
        s.adapterVersion?.let { SensorRow("adapter", "Adapter", null, it, "", raw = true) },
        s.protocol?.let { SensorRow("protocol", "Protocol", null, it, "", raw = true) },
        SensorRow(
            "vin", "Vehicle identification number", "09 02",
            s.carVin ?: if (s.vinReading) "reading…" else "--", "", raw = true,
        ),
        SensorRow("atrv", "Battery voltage (at adapter)", "AT RV", s.voltage?.let { "%.1f".format(it) } ?: "--", "V"),
        if (!s.demo) SensorRow("rate", "Readings per second", null, "%.1f".format(s.readingsPerSec), "") else null,
    )

    // Enum order keeps related sensors together (dashboard, fuel, O2, air, emissions, engine, counters).
    val sensors = Pid.entries.filter { it in s.supported }.map { pid ->
        val (value, unit) = Fmt.pidValue(pid, s.values[pid], s.imperial)
        SensorRow(pid.name, pid.label, pidLabel(pid.code), value, unit)
    }

    val raw = s.rawSupported.map { code ->
        SensorRow(
            "raw-$code",
            Pid.rawNames[code] ?: "PID %02X".format(code),
            pidLabel(code),
            s.rawValues[code] ?: "--",
            "",
            raw = true,
        )
    }

    return listOf(
        Section("Adapter", adapter),
        Section("Sensors", sensors),
        Section("Other data (raw bytes)", raw),
    )
}
