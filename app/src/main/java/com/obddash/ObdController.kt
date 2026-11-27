package com.obddash

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import com.obddash.obd.Dtc
import com.obddash.obd.EcuNotRespondingException
import com.obddash.obd.Elm327
import com.obddash.obd.ObdParser
import com.obddash.obd.Pid
import com.obddash.obd.Readiness
import com.obddash.trips.Trip
import com.obddash.trips.TripRecorder
import com.obddash.trips.TripStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ConnState { Idle, Connecting, Connected, Error }

/** Running graphs: one sample every [HISTORY_STEP_MS], [HISTORY_POINTS] samples = 60 s window. */
const val HISTORY_STEP_MS = 250L
const val HISTORY_POINTS = 240
const val VOLTAGE_KEY = "VOLTAGE"
/** History/tile key for the current trip's average fuel economy (stored in km/L). */
const val FUEL_AVG_KEY = "FUEL_AVG"
/** History/tile key for instant fuel economy right now (stored in km/L). */
const val FUEL_INSTANT_KEY = "FUEL_INSTANT"
/** Tiles computed from the MAF fuel estimate, so they need a MAF sensor. */
val MAF_TILE_KEYS = setOf(FUEL_AVG_KEY, FUEL_INSTANT_KEY)
/** Instant economy is capped here: coasting with little air flow would otherwise read in the hundreds. */
const val INSTANT_KM_PER_L_MAX = 50.0

/** Most tiles the Android Auto screen can fit (2 × 3 full screen, 3 × 2 split screen). */
const val MAX_CAR_TILES = 6

/** Tiles the car screen shows until the user picks their own, in display order. */
val DEFAULT_CAR_TILES = listOf(
    VOLTAGE_KEY, Pid.COOLANT.name, Pid.THROTTLE.name, Pid.LOAD.name, Pid.INTAKE_TEMP.name, Pid.FUEL_LEVEL.name,
)

/** Every tile key the car screen can show: battery voltage plus the dashboard PIDs other than the two gauges. */
val CAR_TILE_KEYS: List<String> =
    listOf(VOLTAGE_KEY) + Pid.entries.filter { it.dashboard && it != Pid.RPM && it != Pid.SPEED }.map { it.name } +
        FUEL_AVG_KEY + FUEL_INSTANT_KEY

data class PairedDevice(val name: String, val address: String, val likelyObd: Boolean)

data class CodesState(
    val loading: Boolean = false,
    val read: Boolean = false,
    val milOn: Boolean? = null,
    val storedCount: Int? = null,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    /** Emissions readiness monitors; null if the car didn't answer PID 01 in full. */
    val readiness: Readiness? = null,
    val error: String? = null,
    val info: String? = null,
)

/** Android Auto dashboard colours. [AUTO] follows the car's day/night signal (headlights / light sensor). */
enum class CarTheme(val label: String) { AUTO("Auto"), DAY("Day"), NIGHT("Night") }

data class UiState(
    val conn: ConnState = ConnState.Idle,
    val message: String? = null,
    val deviceName: String? = null,
    val protocol: String? = null,
    val demo: Boolean = false,
    val values: Map<Pid, Float> = emptyMap(),
    val supported: Set<Pid> = Pid.entries.filter { it.dashboard }.toSet(),
    /** Supported PIDs with no numeric decoder, shown as raw bytes on the Sensors tab. */
    val rawSupported: List<Int> = emptyList(),
    val rawValues: Map<Int, String> = emptyMap(),
    val adapterVersion: String? = null,
    val voltage: Float? = null,
    val readingsPerSec: Float = 0f,
    val imperial: Boolean = false,
    val carTheme: CarTheme = CarTheme.AUTO,
    /** Tile keys (see [CAR_TILE_KEYS]) shown on the Android Auto screen, in order. */
    val carTiles: List<String> = DEFAULT_CAR_TILES,
    val bluetoothAvailable: Boolean = true,
    val bluetoothOn: Boolean = false,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val lastAddress: String? = null,
    val codes: CodesState = CodesState(),
    val trips: List<Trip> = emptyList(),
    val recordingTripId: Long? = null,
    val tripDistanceKm: Double = 0.0,
    /** Fuel used so far this trip (MAF estimate); null without a trip or a MAF sensor. */
    val tripFuelLiters: Double? = null,
    /** Recent samples keyed by Pid.name (or [VOLTAGE_KEY]); NaN marks a missing reading. */
    val history: Map<String, FloatArray> = emptyMap(),
    val profile: Profile = Profile(),
    /** VIN reported by the connected car (mode 09), if any. */
    val carVin: String? = null,
    val vinReading: Boolean = false,
) {
    /** Live average economy for the current trip in km/L; null until 0.5 km driven (too noisy before). */
    val tripKmPerLiter: Double?
        get() = tripFuelLiters?.takeIf { it > 0 && tripDistanceKm >= 0.5 }?.let { tripDistanceKm / it }

    /**
     * Economy right now in km/L: speed divided by fuel flow (MAF → L/h, same estimate as trips).
     * 0 when stopped; capped at [INSTANT_KM_PER_L_MAX]; null without speed or MAF readings.
     */
    val instantKmPerLiter: Double?
        get() {
            val maf = values[Pid.MAF] ?: return null
            val speed = values[Pid.SPEED] ?: return null
            if (speed < 1f) return 0.0
            val litersPerHour = maf * 3600.0 / profile.fuel.afr / profile.fuel.densityGPerL
            if (litersPerHour <= 0.0) return INSTANT_KM_PER_L_MAX
            return (speed / litersPerHour).coerceAtMost(INSTANT_KM_PER_L_MAX)
        }
}

/** App-wide OBD connection and state, shared by the phone UI and the Android Auto screen. */
class ObdController(app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs = app.getSharedPreferences("obddash", Context.MODE_PRIVATE)
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val tripStore = TripStore(File(app.filesDir, "trips"))
    private val profileStore = ProfileStore(prefs)

    private val _state = MutableStateFlow(
        UiState(
            imperial = prefs.getBoolean(KEY_IMPERIAL, false),
            carTheme = prefs.getString(KEY_CAR_THEME, null)
                ?.let { name -> CarTheme.entries.firstOrNull { it.name == name } } ?: CarTheme.AUTO,
            carTiles = prefs.getString(KEY_CAR_TILES, null)
                ?.let { saved -> cleanCarTiles(saved.split(',')) } ?: DEFAULT_CAR_TILES,
            lastAddress = prefs.getString(KEY_LAST, null),
            bluetoothAvailable = adapter != null,
            trips = tripStore.load(),
            profile = profileStore.load(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: Job? = null
    private var codesJob: Job? = null
    private var elm: Elm327? = null
    private var recorder: TripRecorder? = null
    private var lastAutosave = 0L
    private var autoConnectTried = false
    private var demoCodesCleared = false

    // ---------------------------------------------------------------- devices

    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        val devices = try {
            adapter?.bondedDevices.orEmpty()
                .map { d ->
                    val name = d.name ?: d.address
                    PairedDevice(name, d.address, looksLikeObd(name))
                }
                .sortedWith(compareByDescending<PairedDevice> { it.likelyObd }.thenBy { it.name.lowercase() })
        } catch (ignored: SecurityException) {
            emptyList()
        }
        _state.update { it.copy(pairedDevices = devices, bluetoothOn = adapter?.isEnabled == true) }
    }

    /** Reconnects to the last used adapter once per app launch. */
    fun autoConnectOnce() {
        if (autoConnectTried) return
        autoConnectTried = true
        val s = _state.value
        val last = s.lastAddress ?: return
        if (s.conn == ConnState.Idle && s.pairedDevices.any { it.address == last }) connect(last)
    }

    /** Connects to the last used adapter unless already connected/connecting. Used by Android Auto. */
    fun connectLastIfIdle(): Boolean {
        val s = _state.value
        if (s.conn == ConnState.Connected || s.conn == ConnState.Connecting) return true
        val last = s.lastAddress ?: return false
        autoConnectTried = true
        connect(last)
        return true
    }

    fun toggleUnits() = setImperial(!_state.value.imperial)

    fun setImperial(imperial: Boolean) {
        prefs.edit().putBoolean(KEY_IMPERIAL, imperial).apply()
        _state.update { it.copy(imperial = imperial) }
    }

    fun setCarTheme(theme: CarTheme) {
        prefs.edit().putString(KEY_CAR_THEME, theme.name).apply()
        _state.update { it.copy(carTheme = theme) }
    }

    /** Auto → Day → Night → Auto, for the button on the car screen. */
    fun cycleCarTheme() {
        val next = _state.updateAndGet { it.copy(carTheme = CarTheme.entries[(it.carTheme.ordinal + 1) % CarTheme.entries.size]) }
        prefs.edit().putString(KEY_CAR_THEME, next.carTheme.name).apply()
    }

    /** Sets which tiles the car screen shows, in order. Unknown keys and extras past [MAX_CAR_TILES] are dropped. */
    fun setCarTiles(keys: List<String>) {
        val clean = cleanCarTiles(keys)
        prefs.edit().putString(KEY_CAR_TILES, clean.joinToString(",")).apply()
        _state.update { it.copy(carTiles = clean) }
    }

    private fun cleanCarTiles(keys: List<String>): List<String> =
        keys.filter { it in CAR_TILE_KEYS }.distinct().take(MAX_CAR_TILES)

    // ---------------------------------------------------------------- profile

    fun updateProfile(p: Profile) {
        profileStore.save(p)
        _state.update { it.copy(profile = p) }
    }

    /** Re-reads the VIN from the car (button on the profile screen). */
    fun readVinFromCar() {
        val s = _state.value
        if (s.conn != ConnState.Connected || s.vinReading) return
        if (s.demo) {
            _state.update { it.copy(carVin = DEMO_VIN) }
            return
        }
        val e = elm ?: return
        scope.launch { readVin(e, autofill = true) }
    }

    private suspend fun readVin(e: Elm327, autofill: Boolean) {
        _state.update { it.copy(vinReading = true) }
        val vin = try {
            e.readVin()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        _state.update { it.copy(carVin = vin, vinReading = false) }
        if (vin != null && autofill) autofillFromVin(vin)
    }

    /** Fills empty VIN / make / year fields from the car's VIN; never overwrites what the user typed. */
    private fun autofillFromVin(vin: String) {
        val p = _state.value.profile
        val info = Vin.decode(vin)
        val updated = p.copy(
            vin = p.vin.ifBlank { vin },
            make = p.make.ifBlank { info?.manufacturer.orEmpty() },
            year = p.year.ifBlank { info?.modelYear?.toString().orEmpty() },
        )
        if (updated != p) updateProfile(updated)
    }

    // ---------------------------------------------------------------- live data

    /** Number of reconnect attempts made since the link last dropped (0 = not reconnecting). */
    private var reconnectAttempt = 0

    fun connect(address: String) {
        stopSession()
        reconnectAttempt = 0
        startSession(address)
    }

    /**
     * Opens the adapter and polls until the link fails. If it fails after having worked
     * (a mid-drive Bluetooth drop, the adapter browning out during engine start, …) it retries
     * with backoff and keeps recording into the same trip, instead of ending the trip.
     */
    @SuppressLint("MissingPermission")
    private fun startSession(address: String) {
        val bt = adapter ?: return
        session = scope.launch {
            val device = bt.getRemoteDevice(address)
            val name = runCatching { device.name }.getOrNull() ?: address
            val reconnecting = reconnectAttempt > 0
            _state.update {
                it.copy(
                    conn = ConnState.Connecting,
                    message = if (reconnecting) "Connection lost. Reconnecting ($reconnectAttempt/$MAX_RECONNECTS)…"
                    else "Connecting to $name…",
                    deviceName = name,
                    demo = false, values = emptyMap(), voltage = null, protocol = null,
                    codes = if (reconnecting) it.codes else CodesState(),
                    rawValues = emptyMap(), rawSupported = emptyList(), adapterVersion = null,
                    carVin = if (reconnecting) it.carVin else null,
                )
            }
            var reachedConnected = false
            try {
                val e = Elm327.connect(bt, device).also { elm = it }
                _state.update { it.copy(message = "Talking to the car…") }
                val protocol = e.initialize()
                val supportedCodes = e.supportedPids()
                // If the car won't say what it supports, fall back to the dashboard set.
                val pids = if (supportedCodes.isEmpty()) Pid.entries.filter { it.dashboard }.toSet()
                else Pid.entries.filter { it.code in supportedCodes }.toSet()
                val rawCodes = supportedCodes
                    .filter { Pid.byCode[it] == null && !Pid.isBitmask(it) }
                    .sorted()

                prefs.edit().putString(KEY_LAST, address).apply()
                _state.update {
                    it.copy(
                        conn = ConnState.Connected, message = null, protocol = protocol,
                        supported = pids, rawSupported = rawCodes, adapterVersion = e.version,
                        lastAddress = address,
                    )
                }
                reachedConnected = true
                reconnectAttempt = 0
                // After a reconnect, carry on with the same trip and keep the graphs (they show a gap).
                if (recorder == null) startTrip(demo = false)
                if (historyJob == null) startHistory()
                // VIN in parallel with polling; the adapter mutex interleaves the requests.
                if (_state.value.carVin == null) launch { readVin(e, autofill = true) }
                pollLoop(e, pids, rawCodes)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                elm?.close()
                elm = null
                val wasRecovering = reconnectAttempt > 0
                if ((reachedConnected || wasRecovering) && t !is SecurityException && reconnectAttempt < MAX_RECONNECTS) {
                    reconnectAttempt++
                    _state.update {
                        it.copy(
                            conn = ConnState.Connecting, values = emptyMap(),
                            message = "Connection lost. Reconnecting ($reconnectAttempt/$MAX_RECONNECTS)…",
                        )
                    }
                    delay(if (reconnectAttempt <= 3) 3_000L else 10_000L)
                    startSession(address)
                } else {
                    reconnectAttempt = 0
                    historyJob?.cancel()
                    historyJob = null
                    finishTrip()
                    _state.update { it.copy(conn = ConnState.Error, message = friendlyError(t), values = emptyMap()) }
                }
            }
        }
    }

    /**
     * Three priority tiers so the dashboard stays responsive however many PIDs the car offers:
     * fast PIDs every cycle, two dashboard PIDs per cycle, one background PID per cycle.
     */
    private suspend fun pollLoop(e: Elm327, pids: Set<Pid>, rawCodes: List<Int>) {
        val fast = pids.filter { it.fast }
        val dashboard = pids.filter { !it.fast && it.dashboard }
        val background: List<Int> = (pids.filter { !it.dashboard }.map { it.code } + rawCodes).sorted()
        val values = HashMap<Pid, Float>()
        val raw = HashMap<Int, String>()
        var voltage: Float? = null
        var cycle = 0
        var dashIndex = 0
        var bgIndex = 0
        var responses = 0
        var windowStart = SystemClock.elapsedRealtime()
        var rate = 0f

        while (currentCoroutineContext().isActive) {
            val batch = ArrayList<Pid>(fast)
            repeat(min(2, dashboard.size)) { batch += dashboard[dashIndex++ % dashboard.size] }

            for (pid in batch) {
                ObdParser.value(pid, e.send(pid.command))?.let {
                    values[pid] = it
                    responses++
                }
            }
            if (background.isNotEmpty()) {
                val code = background[bgIndex++ % background.size]
                val pid = Pid.byCode[code]
                if (pid != null) {
                    ObdParser.value(pid, e.send(pid.command))?.let { values[pid] = it; responses++ }
                } else {
                    e.readRaw(code)?.let { raw[code] = it; responses++ }
                }
            }
            if (cycle++ % 8 == 0) ObdParser.voltage(e.send("ATRV"))?.let { voltage = it }

            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 1000) {
                rate = responses * 1000f / (now - windowStart)
                responses = 0
                windowStart = now
            }
            publish(values.toMap(), voltage, rate, raw.toMap())
        }
    }

    /** Simulated drive so the app can be tried without a car. */
    fun startDemo() {
        stopSession()
        demoCodesCleared = false
        _state.update {
            it.copy(
                conn = ConnState.Connected, demo = true, deviceName = "Demo", message = null,
                protocol = "Simulated data", voltage = 14.1f, codes = CodesState(),
                supported = Pid.entries.filter { p -> p.dashboard }.toSet() + DEMO_EXTRAS,
                rawSupported = DEMO_RAW.keys.sorted(), rawValues = DEMO_RAW, adapterVersion = "Demo adapter",
                carVin = DEMO_VIN,
            )
        }
        startTrip(demo = true)
        startHistory()
        session = scope.launch {
            val gearRatio = floatArrayOf(0f, 120f, 75f, 52f, 40f, 32f, 26f)
            var t = 0.0
            while (isActive) {
                t += 0.05
                val throttle = (35 + 30 * sin(t * 0.9) + 15 * sin(t * 2.3)).toFloat().coerceIn(0f, 100f)
                val speed = (70 + 55 * sin(t * 0.22) + 8 * sin(t * 0.9)).toFloat().coerceAtLeast(0f)
                val gear = when {
                    speed < 20 -> 1; speed < 40 -> 2; speed < 60 -> 3
                    speed < 80 -> 4; speed < 100 -> 5; else -> 6
                }
                val rpm = max(800f, speed * gearRatio[gear]) + throttle * 8
                val load = (throttle * 0.8f + 12).coerceAtMost(100f)
                val values = mapOf(
                    Pid.RPM to rpm,
                    Pid.SPEED to speed,
                    Pid.THROTTLE to throttle,
                    Pid.LOAD to load,
                    Pid.COOLANT to (88 + 3 * sin(t * 0.05)).toFloat(),
                    Pid.INTAKE_TEMP to (32 + 4 * sin(t * 0.03)).toFloat(),
                    Pid.MAP to (30 + load * 0.7f),
                    Pid.MAF to (rpm / 1000f * (2 + throttle / 12f)),
                    Pid.TIMING to (28 - throttle / 6f),
                    Pid.FUEL_LEVEL to (62 - (t * 0.01).toFloat()).coerceAtLeast(5f),
                    // Background sensors for the Sensors tab
                    Pid.STFT_B1 to (2.5 * sin(t * 3.1)).toFloat(),
                    Pid.LTFT_B1 to 3.1f,
                    Pid.O2_1 to (0.45 + 0.4 * sin(t * 6.0)).toFloat(),
                    Pid.O2_2 to (0.68 + 0.02 * sin(t * 0.5)).toFloat(),
                    Pid.BAROMETRIC to 101f,
                    Pid.AMBIENT_TEMP to 22f,
                    Pid.OIL_TEMP to (96 + 2 * sin(t * 0.04)).toFloat(),
                    Pid.CONTROL_VOLTAGE to (14.05 + 0.15 * sin(t * 0.4)).toFloat(),
                    Pid.COMMANDED_LAMBDA to if (throttle > 80) 0.92f else 1.0f,
                    Pid.RELATIVE_THROTTLE to throttle * 0.9f,
                    Pid.PEDAL_D to throttle * 0.85f + 15f,
                    Pid.CAT_TEMP_B1S1 to (520 + 120 * load / 100).toFloat(),
                    Pid.FUEL_RATE to (rpm / 1000f * (0.6f + throttle / 40f)),
                    Pid.RUN_TIME to (t * 1.0).toFloat() * 20f,
                    Pid.DISTANCE_SINCE_CLEAR to 1843f,
                    Pid.WARMUPS_SINCE_CLEAR to 37f,
                )
                publish(values, (14.1 + 0.15 * sin(t * 0.4)).toFloat(), 20f, DEMO_RAW)
                delay(50)
            }
        }
    }

    private fun publish(values: Map<Pid, Float>, voltage: Float?, rate: Float, raw: Map<Int, String>) {
        val r = recorder
        var trips: List<Trip>? = null
        if (r != null) {
            r.update(values, voltage)
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutosave >= AUTOSAVE_MS) {
                lastAutosave = now
                trips = runCatching { tripStore.upsert(r.snapshot()) }.getOrNull()
            }
        }
        _state.update {
            it.copy(
                values = values, voltage = voltage, readingsPerSec = rate, rawValues = raw,
                tripDistanceKm = r?.distanceKm ?: 0.0, tripFuelLiters = r?.fuelLiters, trips = trips ?: it.trips,
            )
        }
    }

    fun disconnect() {
        stopSession()
        _state.update {
            it.copy(
                conn = ConnState.Idle, message = null, demo = false, values = emptyMap(), voltage = null,
                protocol = null, history = emptyMap(), rawValues = emptyMap(), rawSupported = emptyList(),
                adapterVersion = null, carVin = null, vinReading = false,
            )
        }
    }

    // ---------------------------------------------------------------- running graphs

    private val history = HashMap<String, ArrayDeque<Float>>()
    private var historyJob: Job? = null

    /** Samples the latest values at a fixed rate so every graph has an even time axis. */
    private fun startHistory() {
        historyJob?.cancel()
        history.clear()
        _state.update { it.copy(history = emptyMap()) }
        historyJob = scope.launch {
            while (isActive) {
                delay(HISTORY_STEP_MS)
                val s = _state.value
                for (pid in Pid.entries) if (pid.dashboard) pushSample(pid.name, s.values[pid])
                pushSample(VOLTAGE_KEY, s.voltage)
                pushSample(FUEL_AVG_KEY, s.tripKmPerLiter?.toFloat())
                pushSample(FUEL_INSTANT_KEY, s.instantKmPerLiter?.toFloat())
                val snapshot = history.mapValues { (_, q) -> q.toFloatArray() }
                _state.update { it.copy(history = snapshot) }
            }
        }
    }

    private fun pushSample(key: String, value: Float?) {
        val q = history.getOrPut(key) { ArrayDeque(HISTORY_POINTS + 1) }
        q.addLast(value ?: Float.NaN)
        if (q.size > HISTORY_POINTS) q.removeFirst()
    }

    private fun stopSession() {
        historyJob?.cancel()
        historyJob = null
        codesJob?.cancel()
        codesJob = null
        session?.cancel()
        session = null
        elm?.close()
        elm = null
        finishTrip()
        _state.update { it.copy(codes = it.codes.copy(loading = false)) }
    }


    // ---------------------------------------------------------------- trips

    private fun startTrip(demo: Boolean) {
        val r = runCatching { TripRecorder(tripStore, demo, _state.value.profile.fuel) }.getOrNull() ?: return
        recorder = r
        lastAutosave = SystemClock.elapsedRealtime()
        _state.update { it.copy(recordingTripId = r.id, tripDistanceKm = 0.0, tripFuelLiters = null) }
    }

    private fun finishTrip() {
        val r = recorder ?: return
        recorder = null
        val trip = r.finish()
        val trips = runCatching {
            if (trip != null) tripStore.upsert(trip) else tripStore.delete(r.id)
        }.getOrDefault(_state.value.trips)
        _state.update { it.copy(trips = trips, recordingTripId = null, tripDistanceKm = 0.0, tripFuelLiters = null) }
    }

    fun deleteTrip(id: Long) {
        if (id == _state.value.recordingTripId) return
        val trips = runCatching { tripStore.delete(id) }.getOrDefault(_state.value.trips)
        _state.update { it.copy(trips = trips) }
    }

    fun tripCsv(id: Long): File? = tripStore.csvFile(id).takeIf { it.exists() && it.length() > 0 }

    // ---------------------------------------------------------------- trouble codes

    fun readCodes() {
        val s = _state.value
        if (s.conn != ConnState.Connected || s.codes.loading) return
        if (s.demo) {
            _state.update { it.copy(codes = demoCodes()) }
            return
        }
        val e = elm ?: return
        codesJob = scope.launch {
            setCodes { it.copy(loading = true, error = null, info = null) }
            try {
                val fresh = readAllCodes(e)
                setCodes { fresh }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                setCodes { it.copy(loading = false, error = "Couldn't read codes: ${friendlyError(t)}") }
            }
        }
    }

    fun clearCodes() {
        val s = _state.value
        if (s.conn != ConnState.Connected || s.codes.loading) return
        if (s.demo) {
            demoCodesCleared = true
            _state.update { it.copy(codes = demoCodes().copy(info = "Codes cleared.")) }
            return
        }
        val e = elm ?: return
        codesJob = scope.launch {
            setCodes { it.copy(loading = true, error = null, info = null) }
            try {
                val ok = e.clearDtcs()
                delay(1500) // give the ECU a moment before re-reading
                val fresh = readAllCodes(e)
                setCodes {
                    if (ok) fresh.copy(info = "Codes cleared. If a fault is still present, the code will come back.")
                    else fresh.copy(error = "The car refused to clear the codes. Try with the engine off and the ignition on.")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                setCodes { it.copy(loading = false, error = "Couldn't clear codes: ${friendlyError(t)}") }
            }
        }
    }

    private suspend fun readAllCodes(e: Elm327): CodesState {
        val readiness = e.readiness()
        val stored = e.readDtcs(0x03).map(::Dtc)
        val pending = e.readDtcs(0x07).map(::Dtc).filterNot { it in stored }
        return CodesState(
            read = true, milOn = readiness?.milOn, storedCount = readiness?.dtcCount ?: stored.size,
            stored = stored, pending = pending, readiness = readiness,
        )
    }

    private fun demoCodes() = if (demoCodesCleared) {
        // Clearing codes resets the non-continuous monitors, as on a real car.
        CodesState(read = true, milOn = false, storedCount = 0, readiness = Readiness.parseHex(DEMO_READINESS_CLEARED))
    } else {
        CodesState(
            read = true, milOn = true, storedCount = 1,
            stored = listOf(Dtc("P0420")), pending = listOf(Dtc("P0171")),
            readiness = Readiness.parseHex(DEMO_READINESS),
        )
    }

    private inline fun setCodes(transform: (CodesState) -> CodesState) {
        _state.update { it.copy(codes = transform(it.codes)) }
    }

    // ---------------------------------------------------------------- helpers

    private fun friendlyError(t: Throwable): String = when (t) {
        is SecurityException -> "Bluetooth permission was denied."
        is EcuNotRespondingException -> t.message!!
        is IOException -> "Lost connection to the adapter. Make sure it's plugged in, the ignition is on, " +
            "and no other app is connected to it."
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun looksLikeObd(name: String): Boolean {
        val n = name.lowercase()
        return listOf("obd", "elm", "vlink", "v-link", "icar", "vgate", "veepeak", "konnwei", "carista", "scan")
            .any { it in n }
    }

    private companion object {
        const val KEY_IMPERIAL = "imperial"
        const val KEY_CAR_THEME = "car_theme"
        const val KEY_CAR_TILES = "car_tiles"
        const val KEY_LAST = "last_device"
        const val AUTOSAVE_MS = 30_000L

        /** ~3 minutes of retries (3 × 3 s, then 10 s apart) before a dropped link ends the trip. */
        const val MAX_RECONNECTS = 20

        val DEMO_EXTRAS = setOf(
            Pid.STFT_B1, Pid.LTFT_B1, Pid.O2_1, Pid.O2_2, Pid.BAROMETRIC, Pid.AMBIENT_TEMP, Pid.OIL_TEMP,
            Pid.CONTROL_VOLTAGE, Pid.COMMANDED_LAMBDA, Pid.RELATIVE_THROTTLE, Pid.PEDAL_D, Pid.CAT_TEMP_B1S1,
            Pid.FUEL_RATE, Pid.RUN_TIME, Pid.DISTANCE_SINCE_CLEAR, Pid.WARMUPS_SINCE_CLEAR,
        )
        /** Petrol car, MIL on with 1 code; catalyst, evap, O2 + heater supported; evap not finished. */
        const val DEMO_READINESS = "81 07 65 04"
        /** After clearing: every non-continuous monitor back to incomplete. */
        const val DEMO_READINESS_CLEARED = "00 07 65 65"
        val DEMO_RAW = mapOf(0x01 to DEMO_READINESS, 0x03 to "02 00", 0x1C to "06", 0x51 to "01")

        /** Made-up but well-formed VIN (Toyota, 2018) for demo mode. */
        const val DEMO_VIN = "JTDBR32E0J0123456"
    }
}
