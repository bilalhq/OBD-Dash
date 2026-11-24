package com.obddash.obd

/**
 * Mode 01 PIDs the app can decode. Values are decoded into metric units;
 * the UI converts to imperial if requested.
 *
 * Polling priority: [fast] PIDs every cycle, [dashboard] PIDs next (they have tiles
 * and running graphs), everything else round-robins in the background for the Sensors tab.
 */
enum class Pid(
    val code: Int,
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val bytes: Int,
    val decimals: Int,
    val decode: (IntArray) -> Float,
    val fast: Boolean = false,
    val dashboard: Boolean = false,
) {
    // --- Dashboard ---------------------------------------------------------------
    RPM(0x0C, "RPM", "rpm", 0f, 8000f, 2, 0, { (it[0] * 256 + it[1]) / 4f }, fast = true, dashboard = true),
    SPEED(0x0D, "Speed", "km/h", 0f, 240f, 1, 0, { it[0].toFloat() }, fast = true, dashboard = true),
    THROTTLE(0x11, "Throttle", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }, fast = true, dashboard = true),
    LOAD(0x04, "Engine load", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }, dashboard = true),
    COOLANT(0x05, "Coolant", "°C", -40f, 130f, 1, 0, { it[0] - 40f }, dashboard = true),
    INTAKE_TEMP(0x0F, "Intake air", "°C", -40f, 80f, 1, 0, { it[0] - 40f }, dashboard = true),
    MAP(0x0B, "Manifold pressure", "kPa", 0f, 255f, 1, 0, { it[0].toFloat() }, dashboard = true),
    MAF(0x10, "Air flow", "g/s", 0f, 200f, 2, 1, { (it[0] * 256 + it[1]) / 100f }, dashboard = true),
    TIMING(0x0E, "Timing advance", "°", -20f, 50f, 1, 1, { it[0] / 2f - 64f }, dashboard = true),
    FUEL_LEVEL(0x2F, "Fuel level", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }, dashboard = true),

    // --- Fuel system -------------------------------------------------------------
    STFT_B1(0x06, "Short-term fuel trim (bank 1)", "%", -100f, 100f, 1, 1, { it[0] / 1.28f - 100f }),
    LTFT_B1(0x07, "Long-term fuel trim (bank 1)", "%", -100f, 100f, 1, 1, { it[0] / 1.28f - 100f }),
    STFT_B2(0x08, "Short-term fuel trim (bank 2)", "%", -100f, 100f, 1, 1, { it[0] / 1.28f - 100f }),
    LTFT_B2(0x09, "Long-term fuel trim (bank 2)", "%", -100f, 100f, 1, 1, { it[0] / 1.28f - 100f }),
    FUEL_PRESSURE(0x0A, "Fuel pressure", "kPa", 0f, 765f, 1, 0, { it[0] * 3f }),
    FUEL_RAIL_REL(0x22, "Fuel rail pressure (vs. manifold)", "kPa", 0f, 5178f, 2, 1, { (it[0] * 256 + it[1]) * 0.079f }),
    FUEL_RAIL_GAUGE(0x23, "Fuel rail pressure (gauge)", "kPa", 0f, 655350f, 2, 0, { (it[0] * 256 + it[1]) * 10f }),
    FUEL_RAIL_ABS(0x59, "Fuel rail pressure (absolute)", "kPa", 0f, 655350f, 2, 0, { (it[0] * 256 + it[1]) * 10f }),
    ETHANOL(0x52, "Ethanol fuel", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    INJECTION_TIMING(0x5D, "Fuel injection timing", "°", -210f, 302f, 2, 1, { (it[0] * 256 + it[1]) / 128f - 210f }),
    FUEL_RATE(0x5E, "Engine fuel rate", "L/h", 0f, 3277f, 2, 2, { (it[0] * 256 + it[1]) / 20f }),
    COMMANDED_LAMBDA(0x44, "Commanded air-fuel ratio", "λ", 0f, 2f, 2, 3, { (it[0] * 256 + it[1]) * 2f / 65536f }),

    // --- Oxygen sensors ----------------------------------------------------------
    O2_1(0x14, "O2 sensor 1 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_2(0x15, "O2 sensor 2 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_3(0x16, "O2 sensor 3 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_4(0x17, "O2 sensor 4 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_5(0x18, "O2 sensor 5 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_6(0x19, "O2 sensor 6 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_7(0x1A, "O2 sensor 7 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),
    O2_8(0x1B, "O2 sensor 8 voltage", "V", 0f, 1.275f, 1, 3, { it[0] / 200f }),

    // --- Air, throttle & pedal ---------------------------------------------------
    BAROMETRIC(0x33, "Barometric pressure", "kPa", 0f, 255f, 1, 0, { it[0].toFloat() }),
    AMBIENT_TEMP(0x46, "Ambient air temperature", "°C", -40f, 215f, 1, 0, { it[0] - 40f }),
    ABSOLUTE_LOAD(0x43, "Absolute load", "%", 0f, 25700f, 2, 0, { (it[0] * 256 + it[1]) * 100f / 255f }),
    RELATIVE_THROTTLE(0x45, "Relative throttle position", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    THROTTLE_B(0x47, "Absolute throttle position B", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    THROTTLE_C(0x48, "Absolute throttle position C", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    PEDAL_D(0x49, "Accelerator pedal position D", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    PEDAL_E(0x4A, "Accelerator pedal position E", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    PEDAL_F(0x4B, "Accelerator pedal position F", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    COMMANDED_THROTTLE(0x4C, "Commanded throttle actuator", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    RELATIVE_PEDAL(0x5A, "Relative accelerator pedal", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),

    // --- Emissions ---------------------------------------------------------------
    COMMANDED_EGR(0x2C, "Commanded EGR", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    EGR_ERROR(0x2D, "EGR error", "%", -100f, 100f, 1, 1, { it[0] / 1.28f - 100f }),
    EVAP_PURGE(0x2E, "Commanded evaporative purge", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    CAT_TEMP_B1S1(0x3C, "Catalyst temperature (bank 1, sensor 1)", "°C", -40f, 6514f, 2, 0, { (it[0] * 256 + it[1]) / 10f - 40f }),
    CAT_TEMP_B2S1(0x3D, "Catalyst temperature (bank 2, sensor 1)", "°C", -40f, 6514f, 2, 0, { (it[0] * 256 + it[1]) / 10f - 40f }),
    CAT_TEMP_B1S2(0x3E, "Catalyst temperature (bank 1, sensor 2)", "°C", -40f, 6514f, 2, 0, { (it[0] * 256 + it[1]) / 10f - 40f }),
    CAT_TEMP_B2S2(0x3F, "Catalyst temperature (bank 2, sensor 2)", "°C", -40f, 6514f, 2, 0, { (it[0] * 256 + it[1]) / 10f - 40f }),

    // --- Engine ------------------------------------------------------------------
    OIL_TEMP(0x5C, "Engine oil temperature", "°C", -40f, 210f, 1, 0, { it[0] - 40f }),
    CONTROL_VOLTAGE(0x42, "Control module voltage", "V", 0f, 65.5f, 2, 2, { (it[0] * 256 + it[1]) / 1000f }),
    HYBRID_BATTERY(0x5B, "Hybrid battery remaining", "%", 0f, 100f, 1, 0, { it[0] * 100f / 255f }),
    DEMAND_TORQUE(0x61, "Driver's demand torque", "%", -125f, 130f, 1, 0, { it[0] - 125f }),
    ACTUAL_TORQUE(0x62, "Actual engine torque", "%", -125f, 130f, 1, 0, { it[0] - 125f }),
    REFERENCE_TORQUE(0x63, "Engine reference torque", "Nm", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() }),

    // --- Counters ----------------------------------------------------------------
    RUN_TIME(0x1F, "Run time since engine start", "s", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() }),
    DISTANCE_MIL_ON(0x21, "Distance with check-engine light on", "km", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() }),
    WARMUPS_SINCE_CLEAR(0x30, "Warm-ups since codes cleared", "", 0f, 255f, 1, 0, { it[0].toFloat() }),
    DISTANCE_SINCE_CLEAR(0x31, "Distance since codes cleared", "km", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() }),
    TIME_MIL_ON(0x4D, "Time run with check-engine light on", "min", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() }),
    TIME_SINCE_CLEAR(0x4E, "Time since codes cleared", "min", 0f, 65535f, 2, 0, { (it[0] * 256 + it[1]).toFloat() });

    val command: String get() = "01%02X".format(code)

    companion object {
        val byCode: Map<Int, Pid> = entries.associateBy { it.code }

        /** Names for supported PIDs that aren't numeric sensors; shown as raw bytes. */
        val rawNames: Map<Int, String> = mapOf(
            0x01 to "Monitor status since codes cleared",
            0x02 to "Freeze frame trouble code",
            0x03 to "Fuel system status",
            0x12 to "Commanded secondary air status",
            0x13 to "O2 sensors present",
            0x1C to "OBD standard",
            0x1D to "O2 sensors present (4 banks)",
            0x1E to "Auxiliary input status",
            0x41 to "Monitor status this drive cycle",
            0x4F to "Maximum values (ratio, voltage, current, MAP)",
            0x50 to "Maximum air flow rate",
            0x51 to "Fuel type",
            0x5F to "Emission requirements",
        )

        /** "Supported PIDs" bitmask requests (0x00, 0x20, 0x40…) aren't sensors. */
        fun isBitmask(code: Int) = code % 0x20 == 0
    }
}
