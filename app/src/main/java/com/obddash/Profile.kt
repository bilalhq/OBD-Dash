package com.obddash

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

/**
 * Fuel properties used to turn MAF air flow into fuel used:
 * fuel grams = air grams / [afr]; litres = grams / [densityGPerL].
 */
enum class FuelType(val label: String, val afr: Double, val densityGPerL: Double) {
    PETROL("Petrol", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
    HYBRID("Hybrid", 14.7, 745.0),
    LPG("LPG", 15.5, 540.0),
}

/** Driver + car details. Numbers are kept as text so the form can hold half-typed values. */
data class Profile(
    val driverName: String = "",
    val nickname: String = "",
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val plate: String = "",
    val vin: String = "",
    val fuel: FuelType = FuelType.PETROL,
    val engineLitres: String = "",
    val tankLitres: String = "",
    val redline: String = "",
) {
    /** "Corolla", or "Toyota Corolla" when there's no nickname; empty if nothing is set. */
    val carTitle: String
        get() = nickname.trim().ifBlank { listOf(make.trim(), model.trim()).filter { it.isNotEmpty() }.joinToString(" ") }

    /** "2018 · Petrol · 1.8 L" style subtitle. */
    val carSubtitle: String
        get() = listOfNotNull(
            year.trim().takeIf { it.isNotEmpty() },
            if (nickname.isNotBlank()) listOf(make, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null } else null,
            fuel.label,
            engineLitres.toFloatOrNull()?.let { "%.1f L".format(it) },
        ).joinToString(" · ")

    val initials: String
        get() = driverName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1).uppercase() }

    val isEmpty: Boolean get() = this == Profile()

    val tankLitresValue: Float? get() = tankLitres.toFloatOrNull()?.takeIf { it > 0 }

    /** Redline in rpm; defaults depend on fuel type (diesels rev lower). */
    val redlineRpm: Float
        get() = redline.toFloatOrNull()?.takeIf { it in 2000f..12000f }
            ?: if (fuel == FuelType.DIESEL) 4500f else 6500f

    /** Tachometer full scale: redline + headroom, rounded up to the next 1000. */
    val rpmGaugeMax: Float
        get() = (Math.ceil((redlineRpm + 1500.0) / 1000.0) * 1000.0).toFloat().coerceIn(4000f, 12000f)

    fun toJson(): JSONObject = JSONObject()
        .put("driverName", driverName).put("nickname", nickname).put("make", make).put("model", model)
        .put("year", year).put("plate", plate).put("vin", vin).put("fuel", fuel.name)
        .put("engineLitres", engineLitres).put("tankLitres", tankLitres).put("redline", redline)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            driverName = o.optString("driverName"),
            nickname = o.optString("nickname"),
            make = o.optString("make"),
            model = o.optString("model"),
            year = o.optString("year"),
            plate = o.optString("plate"),
            vin = o.optString("vin"),
            fuel = runCatching { FuelType.valueOf(o.optString("fuel")) }.getOrDefault(FuelType.PETROL),
            engineLitres = o.optString("engineLitres"),
            tankLitres = o.optString("tankLitres"),
            redline = o.optString("redline"),
        )
    }
}

class ProfileStore(private val prefs: SharedPreferences) {
    fun load(): Profile = prefs.getString(KEY, null)
        ?.let { runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull() }
        ?: Profile()

    fun save(p: Profile) {
        prefs.edit().putString(KEY, p.toJson().toString()).apply()
    }

    private companion object {
        const val KEY = "profile"
    }
}

/** What can be worked out from a VIN alone. */
data class VinInfo(val manufacturer: String?, val modelYear: Int?, val country: String?)

object Vin {
    private val VALID = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    fun isValid(vin: String) = VALID.matches(vin)

    fun decode(vin: String): VinInfo? {
        if (!isValid(vin)) return null
        val manufacturer = WMI[vin.take(3)] ?: WMI[vin.take(2)]
        return VinInfo(manufacturer, modelYear(vin), country(vin[0]))
    }

    /** Position 10 encodes the year on a 30-year cycle; pick the most recent plausible one. */
    private fun modelYear(vin: String): Int? {
        val i = "ABCDEFGHJKLMNPRSTVWXY123456789".indexOf(vin[9])
        if (i < 0) return null
        val nextYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        val recent = 2010 + i
        return if (recent <= nextYear) recent else 1980 + i
    }

    private fun country(c: Char): String? = when (c) {
        in '1'..'5' -> "North America"
        'J' -> "Japan"
        'K' -> "South Korea"
        'L' -> "China"
        'M' -> "India / South-East Asia"
        'S' -> "United Kingdom"
        'T' -> "Switzerland / Czechia"
        'V' -> "France / Spain"
        'W' -> "Germany"
        'Y' -> "Sweden / Finland"
        'Z' -> "Italy"
        else -> null
    }

    /** World manufacturer identifiers (first 3, or first 2, VIN characters) for common makes. */
    private val WMI = mapOf(
        "JT" to "Toyota", "4T" to "Toyota", "5T" to "Toyota", "2T" to "Toyota",
        "JH" to "Honda", "1HG" to "Honda", "2HG" to "Honda", "5FN" to "Honda",
        "JN" to "Nissan", "1N4" to "Nissan", "5N1" to "Nissan",
        "JM" to "Mazda", "JF" to "Subaru", "JS" to "Suzuki", "JA" to "Mitsubishi",
        "KMH" to "Hyundai", "5NP" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "5XY" to "Kia",
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "3VW" to "Volkswagen",
        "WAU" to "Audi", "WBA" to "BMW", "WBS" to "BMW M", "WBY" to "BMW",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz",
        "WP0" to "Porsche", "WP1" to "Porsche",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "VSS" to "SEAT", "TMB" to "Škoda",
        "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "ZFF" to "Ferrari",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "YV1" to "Volvo", "YS3" to "Saab",
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "WF0" to "Ford",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "1GN" to "Chevrolet", "1GT" to "GMC",
        "1C3" to "Chrysler", "1C4" to "Jeep", "1J4" to "Jeep", "1D7" to "Dodge", "2C3" to "Chrysler",
        "5YJ" to "Tesla", "7SA" to "Tesla",
    )
}
