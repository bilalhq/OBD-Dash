package com.obddash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.UiState
import com.obddash.trips.Trip
import java.text.DateFormat
import java.util.Date

@Composable
fun TripsScreen(s: UiState, onShare: (Trip) -> Unit, onDelete: (Long) -> Unit) {
    var pendingDelete by remember { mutableStateOf<Trip?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("Trips", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Trips are recorded automatically while you're connected. Each one keeps a " +
                        "once-a-second data log you can export as a CSV file.",
                    color = DashColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (s.recordingTripId != null && s.trips.none { it.id == s.recordingTripId }) {
                DashCard(accent = DashColors.Red) {
                    Text("Recording a trip · ${Fmt.distance(s.tripDistanceKm, s.imperial)} so far")
                }
            }
            if (s.trips.isEmpty() && s.recordingTripId == null) {
                DashCard { Text("No trips yet. Connect to your car and drive, and trips will show up here.") }
            }
            s.trips.forEach { trip ->
                TripCard(
                    trip = trip,
                    imperial = s.imperial,
                    recording = trip.id == s.recordingTripId,
                    liveDistanceKm = s.tripDistanceKm,
                    onShare = { onShare(trip) },
                    onDelete = { pendingDelete = trip },
                )
            }
        }
    }

    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this trip?") },
            text = { Text("The trip summary and its data log will be removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(trip.id); pendingDelete = null }) {
                    Text("Delete", color = DashColors.Red)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    imperial: Boolean,
    recording: Boolean,
    liveDistanceKm: Double,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(trip.id))
    val distance = if (recording) liveDistanceKm else trip.distanceKm

    DashCard(accent = if (recording) DashColors.Red else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(started, fontWeight = FontWeight.SemiBold)
                Text(Fmt.duration(trip.durationSeconds), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            when {
                recording -> Pill("Recording", DashColors.Red)
                trip.demo -> Pill("Demo", DashColors.Muted)
            }
        }
        Row {
            Stat("Distance", Fmt.distance(distance, imperial), Modifier.weight(1f))
            Stat("Avg speed", Fmt.speed(trip.avgSpeedKmh, imperial), Modifier.weight(1f))
            Stat("Max speed", Fmt.speed(trip.maxSpeedKmh.toDouble(), imperial), Modifier.weight(1f))
        }
        Row {
            Stat("Max RPM", "%.0f".format(trip.maxRpm), Modifier.weight(1f))
            Stat("Fuel used*", Fmt.fuel(trip.fuelLiters, imperial), Modifier.weight(1f))
            Stat("Economy*", Fmt.economy(trip.litersPer100Km, imperial), Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "*estimated from air flow, assuming ${trip.fuelLabel.lowercase()}",
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShare) { Text("Export CSV") }
            if (!recording) TextButton(onClick = onDelete) { Text("Delete", color = DashColors.Muted) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(label.uppercase(), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, fontSize = 18.sp, fontFamily = DashFonts.Display, fontWeight = FontWeight.SemiBold)
    }
}
