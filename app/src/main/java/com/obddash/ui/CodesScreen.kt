package com.obddash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.ConnState
import com.obddash.UiState
import com.obddash.obd.Dtc
import com.obddash.obd.Monitor
import com.obddash.obd.Readiness

@Composable
fun CodesScreen(s: UiState, onRead: () -> Unit, onClear: () -> Unit) {
    val c = s.codes
    val connected = s.conn == ConnState.Connected
    var confirmClear by remember { mutableStateOf(false) }

    // Read automatically the first time the tab is opened while connected.
    LaunchedEffect(connected) {
        if (connected && !c.read && !c.loading) onRead()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) {
                DashCard {
                    Text("Trouble codes", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connect to your adapter on the Live tab (or start demo mode) to read and clear trouble codes.",
                        color = DashColors.Muted,
                    )
                }
            } else {
                StatusCard(
                    milOn = c.milOn, storedCount = c.storedCount, read = c.read, loading = c.loading,
                    onRead = onRead, onClear = { confirmClear = true },
                )

                if (c.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Reading from the car…", color = DashColors.Muted)
                    }
                }
                c.error?.let { DashCard(accent = DashColors.Red) { Text(it) } }
                c.info?.let { DashCard(accent = DashColors.Green) { Text(it) } }

                if (c.read && !c.loading) {
                    if (c.stored.isEmpty() && c.pending.isEmpty()) {
                        DashCard(accent = DashColors.Green) {
                            Text("No trouble codes", fontWeight = FontWeight.SemiBold, color = DashColors.Green)
                            Text("The car isn't reporting any stored or pending faults.")
                        }
                    } else {
                        CodeSection("Stored", "Confirmed faults. These turn on the check-engine light.", c.stored)
                        CodeSection("Pending", "Faults seen once that haven't been confirmed yet.", c.pending)
                    }
                    c.readiness?.takeIf { it.monitors.isNotEmpty() }?.let { ReadinessCard(it) }
                }
            }

            Text(
                "Descriptions are the generic SAE meanings. Manufacturer-specific codes (for example P1xxx) " +
                    "mean different things on different makes, so look those up for your car.",
                color = DashColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear trouble codes?") },
            text = {
                Text(
                    "This turns off the check-engine light and resets the emissions readiness monitors. " +
                        "It doesn't fix the underlying problem, and the car may need several drives before it " +
                        "can pass an emissions inspection. It's best done with the engine off and the ignition on."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear codes", color = DashColors.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(
    milOn: Boolean?,
    storedCount: Int?,
    read: Boolean,
    loading: Boolean,
    onRead: () -> Unit,
    onClear: () -> Unit,
) {
    DashCard(accent = if (milOn == true) DashColors.Amber else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CHECK ENGINE LIGHT", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                Text(
                    when (milOn) { true -> "ON"; false -> "OFF"; null -> "—" },
                    fontSize = 36.sp,
                    fontFamily = DashFonts.Display,
                    fontWeight = FontWeight.Bold,
                    color = when (milOn) { true -> DashColors.Amber; false -> DashColors.Green; null -> DashColors.Text },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("STORED CODES", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                Text(
                    storedCount?.toString() ?: "—",
                    fontSize = 36.sp,
                    fontFamily = DashFonts.Display,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(
                if (read) "Read again" else "Read codes", onRead, Modifier.weight(1f), enabled = !loading,
            )
            GhostButton(
                "Clear codes",
                onClick = { if (!loading && read) onClear() },
                modifier = Modifier.weight(1f),
                color = if (!loading && read) DashColors.Red else DashColors.Muted,
            )
        }
    }
}

@Composable
private fun ReadinessCard(r: Readiness) {
    val notReady = r.incomplete.size
    DashCard(accent = if (r.allComplete) DashColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EMISSIONS READINESS", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${r.completeCount} / ${r.monitors.size}",
                        fontSize = 36.sp,
                        fontFamily = DashFonts.Display,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("complete", color = DashColors.Muted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            if (r.allComplete) Pill("All complete", DashColors.Green)
            else Pill("$notReady not ready", DashColors.Amber)
        }
        Column {
            r.monitors.forEachIndexed { i, m ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DashColors.Border))
                MonitorRow(m)
            }
        }
        Text(
            "These are the car's own emissions self-tests${if (r.diesel) " (diesel set)" else ""}. " +
                "Clearing codes or disconnecting the battery resets them, and they finish again after a few days " +
                "of normal driving. Inspection rules differ by region, and some allow one or two incomplete monitors.",
            color = DashColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MonitorRow(m: Monitor) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (m.complete) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = DashColors.Green, modifier = Modifier.size(20.dp))
        } else {
            Box(Modifier.size(20.dp).padding(2.dp).border(2.dp, DashColors.Amber, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name)
            if (m.continuous) Text("Runs continuously", color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (m.complete) "Complete" else "Not ready",
            color = if (m.complete) DashColors.Green else DashColors.Amber,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun CodeSection(title: String, subtitle: String, codes: List<Dtc>) {
    if (codes.isEmpty()) return
    Column(Modifier.padding(top = 4.dp)) {
        Text("$title (${codes.size})", style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    codes.forEach { DtcRow(it) }
}

@Composable
private fun DtcRow(d: Dtc) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.code,
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                style = androidx.compose.ui.text.TextStyle(brush = DashBrushes.Accent),
                modifier = Modifier.width(84.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.description)
                if (d.manufacturerSpecific) Pill("Manufacturer-specific", DashColors.Muted)
            }
        }
    }
}
