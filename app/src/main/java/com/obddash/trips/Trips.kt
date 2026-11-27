package com.obddash.trips

import android.os.SystemClock
import com.obddash.FuelType
import com.obddash.obd.Pid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Summary of one recorded trip. [id] is the start time in epoch milliseconds. */
data class Trip(
    val id: Long,
    val endMs: Long,
    val distanceKm: Double,
    val movingSeconds: Long,
    val maxSpeedKmh: Float,
    val maxRpm: Float,
    /** Estimated from MAF using the profile's fuel type; null if the car has no MAF sensor. */
    val fuelLiters: Double?,
    val demo: Boolean,
    /** Fuel type the estimate assumed, e.g. "Petrol". */
    val fuelLabel: String = "Petrol",
) {
    val durationSeconds: Long get() = (endMs - id) / 1000
    val avgSpeedKmh: Double get() = if (movingSeconds > 0) distanceKm / (movingSeconds / 3600.0) else 0.0
    val litersPer100Km: Double? get() = fuelLiters?.takeIf { distanceKm >= 0.5 }?.let { it / distanceKm * 100 }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("end", endMs).put("km", distanceKm).put("moving", movingSeconds)
        .put("maxSpeed", maxSpeedKmh.toDouble()).put("maxRpm", maxRpm.toDouble())
        .put("fuel", fuelLiters ?: JSONObject.NULL).put("demo", demo).put("fuelLabel", fuelLabel)

    companion object {
        fun fromJson(o: JSONObject) = Trip(
            id = o.getLong("id"),
            endMs = o.getLong("end"),
            distanceKm = o.getDouble("km"),
            movingSeconds = o.getLong("moving"),
            maxSpeedKmh = o.getDouble("maxSpeed").toFloat(),
            maxRpm = o.getDouble("maxRpm").toFloat(),
            fuelLiters = if (o.isNull("fuel")) null else o.getDouble("fuel"),
            demo = o.optBoolean("demo"),
            fuelLabel = o.optString("fuelLabel", "Petrol"),
        )
    }
}

/** Trip summaries live in trips.json; each trip's 1 Hz data log is a CSV file next to it. */
class TripStore(private val dir: File) {
    private val index = File(dir, "trips.json")

    init {
        dir.mkdirs()
    }

    fun csvFile(id: Long) = File(dir, "trip_$id.csv")

    fun load(): List<Trip> = runCatching {
        val arr = JSONArray(index.readText())
        List(arr.length()) { Trip.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList()).sortedByDescending { it.id }

    fun upsert(trip: Trip): List<Trip> = save(load().filterNot { it.id == trip.id } + trip)

    fun delete(id: Long): List<Trip> {
        csvFile(id).delete()
        return save(load().filterNot { it.id == id })
    }

    private fun save(trips: List<Trip>): List<Trip> {
        val arr = JSONArray()
        trips.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "trips.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) {
            index.writeText(arr.toString())
            tmp.delete()
        }
        return trips.sortedByDescending { it.id }
    }
}

/** Accumulates trip statistics and writes a 1 Hz CSV log while connected. */
class TripRecorder(private val store: TripStore, private val demo: Boolean, private val fuel: FuelType) {
    val id: Long = System.currentTimeMillis()

    var distanceKm = 0.0
        private set
    private var movingMs = 0L
    private var maxSpeed = 0f
    private var maxRpm = 0f
    private var fuelGrams = 0.0
    private var sawMaf = false
    private var lastUpdate = 0L
    /** Wall-clock time of the last reading, so a trip ends when data stopped, not when we gave up reconnecting. */
    private var lastDataWallMs = 0L
    private var lastSample = 0L
    private var rows = 0

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val writer = store.csvFile(id).bufferedWriter().apply {
        write("time,elapsed_s,speed_kmh,rpm,throttle_pct,load_pct,coolant_c,intake_c,maf_gs,fuel_level_pct,battery_v\n")
    }

    fun update(values: Map<Pid, Float>, voltage: Float?) {
        val now = SystemClock.elapsedRealtime()
        val speed = values[Pid.SPEED] ?: 0f
        if (lastUpdate != 0L) {
            val dtMs = (now - lastUpdate).coerceAtMost(5000) // don't integrate across stalls
            distanceKm += speed * dtMs / 3_600_000.0
            if (speed > 1f) movingMs += dtMs
            values[Pid.MAF]?.let {
                fuelGrams += it * dtMs / 1000.0 / fuel.afr
                sawMaf = true
            }
        }
        lastUpdate = now
        if (values.isNotEmpty()) lastDataWallMs = System.currentTimeMillis()
        maxSpeed = maxOf(maxSpeed, speed)
        values[Pid.RPM]?.let { maxRpm = maxOf(maxRpm, it) }

        if (now - lastSample >= 1000) {
            lastSample = now
            fun f(pid: Pid) = values[pid]?.let { "%.1f".format(Locale.US, it) } ?: ""
            val row = listOf(
                timeFormat.format(Date()),
                ((System.currentTimeMillis() - id) / 1000).toString(),
                f(Pid.SPEED), f(Pid.RPM), f(Pid.THROTTLE), f(Pid.LOAD), f(Pid.COOLANT),
                f(Pid.INTAKE_TEMP), f(Pid.MAF), f(Pid.FUEL_LEVEL),
                voltage?.let { "%.2f".format(Locale.US, it) } ?: "",
            ).joinToString(",")
            runCatching {
                writer.write(row)
                writer.write("\n")
                if (++rows % 10 == 0) writer.flush()
            }
        }
    }

    /** Fuel used so far, estimated from MAF; null if the car hasn't reported MAF. */
    val fuelLiters: Double? get() = if (sawMaf) fuelGrams / fuel.densityGPerL else null

    fun snapshot() = Trip(
        id = id,
        endMs = if (lastDataWallMs > 0) lastDataWallMs else System.currentTimeMillis(),
        distanceKm = distanceKm,
        movingSeconds = movingMs / 1000,
        maxSpeedKmh = maxSpeed,
        maxRpm = maxRpm,
        fuelLiters = fuelLiters,
        demo = demo,
        fuelLabel = fuel.label,
    )

    /** Closes the log. Returns null for trips too short to keep (under 30 s and under 50 m). */
    fun finish(): Trip? {
        runCatching { writer.close() }
        val trip = snapshot()
        return if (trip.durationSeconds < 30 && trip.distanceKm < 0.05) null else trip
    }
}
