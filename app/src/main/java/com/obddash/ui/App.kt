package com.obddash.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obddash.ConnState
import com.obddash.ObdApp
import com.obddash.ObdController
import com.obddash.PairedDevice
import com.obddash.UiState
import java.io.File

enum class Tab(val label: String, val icon: ImageVector) {
    Live("Live", Icons.Default.Home),
    Sensors("Sensors", Icons.AutoMirrored.Filled.List),
    Codes("Codes", Icons.Default.Warning),
    Trips("Trips", Icons.Default.Place),
}

@Composable
fun App(vm: ObdController = (LocalContext.current.applicationContext as ObdApp).controller) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()
    }
    // Notifications are optional (they make the "connected / recording" notice visible),
    // so they're requested alongside Bluetooth but don't gate the app.
    val requested = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions + Manifest.permission.POST_NOTIFICATIONS
        else permissions
    }
    fun granted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun allRequestedGranted() = requested.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var hasPermission by remember { mutableStateOf(granted()) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun batteryUnrestricted() = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    var batteryOk by remember { mutableStateOf(batteryUnrestricted()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = granted()
        if (hasPermission) vm.refreshDevices()
    }
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshDevices() }

    // Re-check whenever we come back (e.g. after pairing in system settings).
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        batteryOk = batteryUnrestricted()
        if (hasPermission) vm.refreshDevices()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!allRequestedGranted()) permissionLauncher.launch(requested)
    }
    LaunchedEffect(hasPermission, s.bluetoothOn, s.pairedDevices) {
        if (hasPermission && s.bluetoothOn && s.pairedDevices.isNotEmpty()) vm.autoConnectOnce()
    }

    Box(Modifier.fillMaxSize().ambientBackground()) {
        if (showProfile) {
            ProfileScreen(
                s = s,
                onChange = vm::updateProfile,
                onImperial = vm::setImperial,
                onCarTheme = vm::setCarTheme,
                onCarTiles = vm::setCarTiles,
                onReadVin = vm::readVinFromCar,
                onClose = { showProfile = false },
            )
            return@Box
        }
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = DashColors.Text,
            topBar = { TopBar(s, onProfile = { showProfile = true }, onDisconnect = vm::disconnect) },
            bottomBar = { if (!landscape) FloatingTabBar(tab, s) { tab = it } },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (landscape) TabRail(tab, s) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Crossfade(targetState = tab, animationSpec = tween(220), label = "tab") { current ->
                        when (current) {
                            Tab.Live -> if (s.conn == ConnState.Connected) {
                                Dashboard(s)
                            } else {
                                ConnectScreen(
                                    s = s,
                                    hasPermission = hasPermission,
                                    onRequestPermission = { permissionLauncher.launch(requested) },
                                    batteryOk = batteryOk,
                                    onFixBattery = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                    },
                                    onOpenAppSettings = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                    },
                                    onEnableBluetooth = {
                                        runCatching { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                    },
                                    onOpenSettings = {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                    },
                                    onConnect = vm::connect,
                                    onDemo = vm::startDemo,
                                    onCancel = vm::disconnect,
                                    onOpenProfile = { showProfile = true },
                                )
                            }
                            Tab.Sensors -> SensorsScreen(s)
                            Tab.Codes -> CodesScreen(s, onRead = vm::readCodes, onClear = vm::clearCodes)
                            Tab.Trips -> TripsScreen(
                                s,
                                onShare = { trip -> shareCsv(context, vm.tripCsv(trip.id)) },
                                onDelete = vm::deleteTrip,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- navigation

@Composable
private fun FloatingTabBar(selected: Tab, s: UiState, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t ->
                TabItem(t, t == selected, s, Modifier.weight(1f)) { onSelect(t) }
            }
        }
    }
}

@Composable
private fun TabRail(selected: Tab, s: UiState, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .padding(start = 12.dp, top = 8.dp, bottom = 12.dp)
            .width(76.dp)
            .glass(RoundedCornerShape(26.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Tab.entries.forEach { t -> TabItem(t, t == selected, s, Modifier.fillMaxWidth()) { onSelect(t) } }
    }
}

@Composable
private fun TabItem(t: Tab, selected: Boolean, s: UiState, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) DashColors.Accent.copy(alpha = 0.14f) else Color.Transparent, label = "tabBg")
    val fg by animateColorAsState(if (selected) DashColors.Accent else DashColors.Muted, label = "tabFg")
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TabIcon(t, s, fg)
        Spacer(Modifier.height(3.dp))
        Text(
            t.label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun TabIcon(t: Tab, s: UiState, tint: Color) {
    val codeCount = s.codes.stored.size + s.codes.pending.size
    if (t == Tab.Codes && codeCount > 0) {
        BadgedBox(badge = { Badge(containerColor = DashColors.Amber, contentColor = DashColors.Bg) { Text(codeCount.toString()) } }) {
            Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp))
        }
    } else {
        Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun shareCsv(context: Context, file: File?) {
    if (file == null) {
        Toast.makeText(context, "This trip has no logged data yet.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "OBD Dash trip log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export trip log"))
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(s: UiState, onProfile: () -> Unit, onDisconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(brush = DashBrushes.Accent)) { append("OBD") }
                append(" Dash")
            },
            style = MaterialTheme.typography.titleLarge,
            color = DashColors.Text,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { StatusPill(s) }
        if (s.conn == ConnState.Connected || s.conn == ConnState.Connecting) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(DashColors.Surface)
                    .border(1.dp, DashColors.Border, CircleShape)
                    .clickable(onClick = onDisconnect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = DashColors.Muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        // Profile button: ringed avatar
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.5.dp, DashBrushes.Accent, CircleShape)
                .clickable(onClick = onProfile)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(s.profile, 34)
        }
    }
}

@Composable
private fun StatusPill(s: UiState) {
    val (color, text) = when (s.conn) {
        ConnState.Connected -> DashColors.Green to
            (if (s.demo) "Demo" else s.profile.carTitle.ifBlank { s.deviceName ?: "Connected" })
        ConnState.Connecting -> DashColors.Amber to "Connecting"
        ConnState.Error -> DashColors.Red to "Error"
        ConnState.Idle -> DashColors.Muted to "Offline"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(DashColors.Surface)
            .border(1.dp, DashColors.Border, RoundedCornerShape(50))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = DashColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GlowDot(color: Color) {
    Box(Modifier.size(14.dp).background(color.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

// ---------------------------------------------------------------- connect screen

@Composable
private fun ConnectScreen(
    s: UiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnect: (String) -> Unit,
    onDemo: () -> Unit,
    onCancel: () -> Unit,
    onOpenProfile: () -> Unit,
    batteryOk: Boolean,
    onFixBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hero(s)

            if (!batteryOk) {
                DashCard(accent = DashColors.Amber) {
                    Text("Keep trips recording", style = MaterialTheme.typography.titleMedium, color = DashColors.Amber)
                    Text(
                        "Android may stop OBD Dash while you use other apps, which cuts trips short. " +
                            "Allow it to run in the background. On OPPO, Realme and OnePlus phones, also turn on " +
                            "“Allow background activity” in the app's battery settings.",
                        color = DashColors.Text.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Allow", onFixBattery, Modifier.weight(1f))
                        GhostButton("App settings", onOpenAppSettings, Modifier.weight(1f))
                    }
                }
            }

            if (s.profile.carTitle.isBlank()) {
                Row(
                    Modifier.fillMaxWidth().glass(tint = DashColors.Accent2).clickable(onClick = onOpenProfile).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(s.profile, 40)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Add driver & car info", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Name your car, set fuel type, tank size and redline.",
                            color = DashColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Set up", color = DashColors.Accent2, style = MaterialTheme.typography.labelLarge)
                }
            }

            when (s.conn) {
                ConnState.Connecting -> DashCard(accent = DashColors.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(26.dp), strokeWidth = 3.dp, color = DashColors.Amber, trackColor = DashColors.Track,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(s.message ?: "Connecting…", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(
                            "Cancel",
                            color = DashColors.Muted,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(8.dp),
                        )
                    }
                }
                ConnState.Error -> DashCard(accent = DashColors.Red) {
                    Text("Couldn't connect", style = MaterialTheme.typography.titleMedium, color = DashColors.Red)
                    Text(s.message ?: "", color = DashColors.Text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    s.lastAddress?.let { addr ->
                        GhostButton("Try again", onClick = { onConnect(addr) }, color = DashColors.Red)
                    }
                }
                else -> {}
            }

            when {
                !s.bluetoothAvailable -> DashCard { Text("This device doesn't have Bluetooth.") }
                !hasPermission -> DashCard {
                    Text("Allow Bluetooth", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "OBD Dash needs the Nearby devices permission to talk to your OBD-II adapter.",
                        color = DashColors.Muted,
                    )
                    GradientButton("Grant permission", onRequestPermission, Modifier.fillMaxWidth())
                }
                !s.bluetoothOn -> DashCard {
                    Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                    Text("Turn it on to find your adapter.", color = DashColors.Muted)
                    GradientButton("Turn on Bluetooth", onEnableBluetooth, Modifier.fillMaxWidth())
                }
                else -> DeviceList(s, onConnect, onOpenSettings)
            }

            Spacer(Modifier.height(4.dp))
            GhostButton("Try demo mode", onDemo, Modifier.fillMaxWidth(), color = DashColors.Accent)

            Row(
                Modifier.fillMaxWidth().glass().padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Pill("Tip", DashColors.Accent2)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Plug the adapter into the OBD-II port (usually under the steering wheel), turn the ignition on, " +
                        "then pair it in Android Bluetooth settings. The PIN is usually 1234 or 0000.",
                    color = DashColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Hero(s: UiState) {
    Row(
        Modifier.fillMaxWidth().glass().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val first = s.profile.driverName.trim().substringBefore(' ')
            val car = s.profile.carTitle
            Text(
                when {
                    s.conn == ConnState.Connecting -> "Connecting…"
                    first.isNotEmpty() -> "Hi, $first"
                    else -> "Connect your car"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = DashColors.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (car.isNotEmpty()) "Pick your adapter below to connect to your $car."
                else "Pick your OBD-II adapter below for live gauges, sensor data, trouble codes and trip logs.",
                color = DashColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(16.dp))
        HeroRing(Modifier.size(76.dp))
    }
}

/** Decorative mini gauge in the hero card. */
@Composable
private fun HeroRing(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val r = size.minDimension / 2 - stroke
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(r * 2, r * 2)
        drawArc(DashColors.Track, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val brush = Brush.sweepGradient(
            0f to DashColors.Accent, 0.75f to DashColors.Accent2, 0.9f to DashColors.Accent2, 1f to DashColors.Accent,
            center = center,
        )
        rotate(135f, pivot = center) {
            drawArc(brush, 0f, 190f, false, tl, sz, alpha = 0.18f, style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
            drawArc(brush, 0f, 190f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        drawCircle(Color.White, stroke * 0.3f, center)
    }
}

@Composable
private fun DeviceList(s: UiState, onConnect: (String) -> Unit, onOpenSettings: () -> Unit) {
    SectionLabel("Paired adapters") {
        Text(
            "Pair new",
            color = DashColors.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenSettings).padding(6.dp),
        )
    }
    if (s.pairedDevices.isEmpty()) {
        DashCard {
            Text("No paired devices yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pair your OBD-II adapter in Bluetooth settings, then come back here.",
                color = DashColors.Muted,
            )
            GradientButton("Open Bluetooth settings", onOpenSettings, Modifier.fillMaxWidth())
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.pairedDevices.forEach { d -> DeviceRow(d, s, onConnect) }
    }
}

@Composable
private fun DeviceRow(d: PairedDevice, s: UiState, onConnect: (String) -> Unit) {
    val enabled = s.conn != ConnState.Connecting
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(shape)
            .clickable(enabled = enabled) { onConnect(d.address) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: gradient for likely adapters, muted for everything else.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (d.likelyObd) DashBrushes.Accent
                    else Brush.linearGradient(listOf(DashColors.Track, DashColors.SurfaceHigh))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.name.take(1).uppercase(),
                color = if (d.likelyObd) DashColors.Bg else DashColors.Muted,
                fontFamily = DashFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                d.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (d.likelyObd) DashColors.Text else DashColors.Text.copy(alpha = 0.8f),
            )
            Text(d.address, color = DashColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        if (d.address == s.lastAddress) Pill("Last used", DashColors.Green)
        else if (d.likelyObd) Pill("OBD", DashColors.Accent)
    }
}
