package com.obddash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obddash.CAR_TILE_KEYS
import com.obddash.CarTheme
import com.obddash.ConnState
import com.obddash.DEFAULT_CAR_TILES
import com.obddash.FUEL_AVG_KEY
import com.obddash.FuelType
import com.obddash.MAX_CAR_TILES
import com.obddash.Profile
import com.obddash.UiState
import com.obddash.VOLTAGE_KEY
import com.obddash.Vin
import com.obddash.obd.Pid

@Composable
fun ProfileScreen(
    s: UiState,
    onChange: (Profile) -> Unit,
    onImperial: (Boolean) -> Unit,
    onCarTheme: (CarTheme) -> Unit,
    onCarTiles: (List<String>) -> Unit,
    onReadVin: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val p = s.profile

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DashColors.Surface)
                        .border(1.dp, DashColors.Border, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DashColors.Text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("Profile", style = MaterialTheme.typography.titleLarge)
            }

            ProfileHeader(p)

            SectionLabel("Driver")
            Field("Your name", p.driverName, { onChange(p.copy(driverName = it)) }, capitalization = KeyboardCapitalization.Words)

            SectionLabel("Car")
            Field(
                "Nickname", p.nickname, { onChange(p.copy(nickname = it)) },
                placeholder = "e.g. Daily, The Beast", capitalization = KeyboardCapitalization.Words,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Make", p.make, { onChange(p.copy(make = it)) }, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
                Field("Model", p.model, { onChange(p.copy(model = it)) }, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    "Year", p.year, { v -> onChange(p.copy(year = v.filter { it.isDigit() }.take(4))) }, Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                )
                Field(
                    "Plate", p.plate, { onChange(p.copy(plate = it.uppercase().take(12))) }, Modifier.weight(1f),
                    capitalization = KeyboardCapitalization.Characters,
                )
            }

            VinCard(s, onChange, onReadVin)

            SectionLabel("Fuel")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelType.entries.forEach { f ->
                    Segment(f.label, selected = p.fuel == f, modifier = Modifier.weight(1f)) { onChange(p.copy(fuel = f)) }
                }
            }
            Text(
                "Used for fuel-used and economy estimates on trips.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
            )

            SectionLabel("Engine & tank")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    "Engine (L)", p.engineLitres, { onChange(p.copy(engineLitres = decimal(it))) }, Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal, placeholder = "1.8",
                )
                Field(
                    "Tank (L)", p.tankLitres, { onChange(p.copy(tankLitres = decimal(it))) }, Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal, placeholder = "50",
                )
            }
            Field(
                "Redline (rpm)", p.redline, { v -> onChange(p.copy(redline = v.filter { it.isDigit() }.take(5))) },
                keyboardType = KeyboardType.Number, placeholder = "%.0f (default)".format(p.copy(redline = "").redlineRpm),
            )
            Text(
                "Sets where the tachometer's red zone starts. Tank size adds litres remaining to the fuel tile.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
            )

            SectionLabel("Units")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment("Metric · km/h °C", selected = !s.imperial, modifier = Modifier.weight(1f)) { onImperial(false) }
                Segment("Imperial · mph °F", selected = s.imperial, modifier = Modifier.weight(1f)) { onImperial(true) }
            }

            SectionLabel("Android Auto theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarTheme.entries.forEach { t ->
                    Segment(t.label, selected = s.carTheme == t, modifier = Modifier.weight(1f)) { onCarTheme(t) }
                }
            }
            Text(
                "Auto switches the car dashboard to day or night colours when your headlights come on, " +
                    "following the car's own day/night setting. You can also change it from the car screen.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
            )

            SectionLabel("Android Auto tiles") {
                if (s.carTiles != DEFAULT_CAR_TILES) {
                    Text(
                        "Reset",
                        color = DashColors.Accent,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCarTiles(DEFAULT_CAR_TILES) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            CarTilesPicker(s, onCarTiles)
            Text(
                "Pick up to $MAX_CAR_TILES tiles for the car screen, shown beside the RPM and speed gauges in this order. " +
                    "Tap a tile to add or remove it; use the arrow to move it up. Smaller car screens show only the first few. " +
                    "With no tiles, the gauges fill the screen.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
            )

            Spacer(Modifier.height(4.dp))
            GradientButton("Done", onClose, Modifier.fillMaxWidth())
            Text(
                "Changes are saved automatically.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Chosen tiles first (numbered, in car order), then the rest. */
@Composable
private fun CarTilesPicker(s: UiState, onCarTiles: (List<String>) -> Unit) {
    val chosen = s.carTiles
    val full = chosen.size >= MAX_CAR_TILES
    val rows = chosen + CAR_TILE_KEYS.filter { it !in chosen }
    Column(Modifier.fillMaxWidth().glass().padding(vertical = 4.dp)) {
        rows.forEachIndexed { i, key ->
            val position = chosen.indexOf(key)
            val selected = position >= 0
            val enabled = selected || !full
            val pid = if (key == FUEL_AVG_KEY) Pid.MAF else Pid.entries.firstOrNull { it.name == key }
            val unsupported = s.conn == ConnState.Connected && pid != null && pid !in s.supported
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(DashColors.Border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        onCarTiles(if (selected) chosen - key else chosen + key)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Position badge, or an empty ring when not chosen.
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) Modifier.background(DashBrushes.Accent)
                            else Modifier.border(1.5.dp, DashColors.Border, CircleShape)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text(
                            "${position + 1}",
                            color = DashColors.Bg, fontFamily = DashFonts.Display, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        carTileLabel(key),
                        color = if (enabled) DashColors.Text else DashColors.Muted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (unsupported) {
                        Text(
                            if (key == FUEL_AVG_KEY) "Needs a MAF sensor, which this car doesn't report" else "Not reported by this car",
                            color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selected && position > 0) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                val reordered = chosen.toMutableList()
                                reordered.add(position - 1, reordered.removeAt(position))
                                onCarTiles(reordered)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", tint = DashColors.Muted)
                    }
                }
            }
        }
    }
}

private fun carTileLabel(key: String): String = when (key) {
    VOLTAGE_KEY -> "Battery voltage"
    FUEL_AVG_KEY -> "Trip fuel average"
    else -> Pid.entries.firstOrNull { it.name == key }?.label ?: key
}

@Composable
private fun ProfileHeader(p: Profile) {
    Row(Modifier.fillMaxWidth().glass().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 64)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.driverName.ifBlank { "Driver" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                p.carTitle.ifBlank { "Add your car below" },
                color = if (p.carTitle.isBlank()) DashColors.Muted else DashColors.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            if (p.carTitle.isNotBlank()) {
                Text(p.carSubtitle, color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VinCard(s: UiState, onChange: (Profile) -> Unit, onReadVin: () -> Unit) {
    val p = s.profile
    val connected = s.conn == ConnState.Connected
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VIN", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                s.vinReading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DashColors.Accent)
                connected -> Text(
                    "Read from car",
                    color = DashColors.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onReadVin).padding(6.dp),
                )
            }
        }
        Field(
            "Vehicle identification number", p.vin,
            { v -> onChange(p.copy(vin = v.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(17))) },
            capitalization = KeyboardCapitalization.Characters, monospace = true,
        )

        // What the car itself reported, if it differs from what's saved.
        val carVin = s.carVin
        if (carVin != null && carVin != p.vin) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Car reports", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text(carVin, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Text(
                    "Use",
                    color = DashColors.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onChange(p.copy(vin = carVin)) }.padding(8.dp),
                )
            }
        }

        val info = Vin.decode(p.vin)
        when {
            p.vin.isEmpty() -> Text(
                if (connected) "Tap “Read from car” or type it in." else "Connect to the car to read it automatically, or type it in.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            info == null -> Text(
                if (p.vin.length < 17) "${17 - p.vin.length} more characters" else "That doesn't look like a valid VIN.",
                color = DashColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.manufacturer?.let { Pill(it, DashColors.Accent) }
                    info.modelYear?.let { Pill("$it", DashColors.Accent2) }
                    info.country?.let { Pill(it, DashColors.Muted) }
                }
                val fill = p.copy(
                    make = p.make.ifBlank { info.manufacturer.orEmpty() },
                    year = p.year.ifBlank { info.modelYear?.toString().orEmpty() },
                )
                if (fill != p) {
                    Text(
                        "Fill make & year from VIN",
                        color = DashColors.Accent,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onChange(fill) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun Avatar(p: Profile, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(DashBrushes.Accent),
        contentAlignment = Alignment.Center,
    ) {
        val initials = p.initials
        if (initials.isEmpty()) {
            Icon(Icons.Default.Person, contentDescription = null, tint = DashColors.Bg, modifier = Modifier.size((sizeDp * 0.55f).dp))
        } else {
            Text(
                initials,
                color = DashColors.Bg,
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = (sizeDp * 0.38f).sp,
            )
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(if (selected) DashColors.Accent.copy(alpha = 0.14f) else DashColors.Surface, label = "segBg")
    val border by animateColorAsState(if (selected) DashColors.Accent.copy(alpha = 0.5f) else DashColors.Border, label = "segBorder")
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) DashColors.Accent else DashColors.Text.copy(alpha = 0.8f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = DashColors.Muted.copy(alpha = 0.6f)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = if (monospace) FontFamily.Monospace else DashFonts.Inter,
            color = DashColors.Text,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = DashColors.Border,
            focusedBorderColor = DashColors.Accent,
            unfocusedContainerColor = DashColors.Surface,
            focusedContainerColor = DashColors.Surface,
            unfocusedLabelColor = DashColors.Muted,
            focusedLabelColor = DashColors.Accent,
            cursorColor = DashColors.Accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Keeps digits and a single decimal point. */
private fun decimal(input: String): String {
    val cleaned = input.replace(',', '.').filter { it.isDigit() || it == '.' }
    val dot = cleaned.indexOf('.')
    return (if (dot < 0) cleaned else cleaned.substring(0, dot + 1) + cleaned.substring(dot + 1).replace(".", "")).take(6)
}
