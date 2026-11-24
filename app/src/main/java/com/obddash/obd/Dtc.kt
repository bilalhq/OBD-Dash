package com.obddash.obd

data class Dtc(val code: String) {
    val description: String get() = DtcParser.describe(code)
    val manufacturerSpecific: Boolean
        get() = code.length == 5 && if (code[0] == 'P') code[1] in "13" else code[1] in "12"
}

object DtcParser {

    private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'
    private val frameLine = Regex("^([0-9A-F]):(.*)$")

    /**
     * Parses a mode 03/07 reply. Handles:
     *  - non-CAN: one or more lines "43 AABB CCDD EEFF" (3 codes per line, padded with 0000)
     *  - CAN single frame: "43 NN AABB …" where NN is the code count
     *  - CAN multi-frame: "00A" / "0: 43 NN …" / "1: …"
     *  - several ECUs answering at once
     */
    fun parse(raw: String, responseMode: Int, isCan: Boolean): List<String> {
        val marker = "%02X".format(responseMode)
        val lines = raw.uppercase().split('\r', '\n').map { it.replace(" ", "").trim() }.filter { it.isNotEmpty() }

        val messages = mutableListOf<String>()
        var multi: StringBuilder? = null
        for (line in lines) {
            val frame = frameLine.find(line)
            when {
                frame != null -> {
                    val (index, data) = frame.destructured
                    if (index == "0") {
                        multi?.let { messages += it.toString() }
                        multi = StringBuilder(data)
                    } else {
                        multi?.append(data)
                    }
                }
                line.startsWith(marker) -> {
                    multi?.let { messages += it.toString() }
                    multi = null
                    messages += line
                }
                // anything else is a byte-count header, "NO DATA", "SEARCHING", etc.
            }
        }
        multi?.let { messages += it.toString() }

        val codes = linkedSetOf<String>()
        for (msg in messages) {
            if (!msg.startsWith(marker)) continue
            var hex = msg.substring(marker.length).takeWhile { it.isHex() }
            if (isCan) {
                val count = hex.take(2).toIntOrNull(16) ?: continue
                hex = hex.drop(2).take(count * 4)
            }
            hex.chunked(4).filter { it.length == 4 && it != "0000" }.forEach { codes += decode(it) }
        }
        return codes.toList()
    }

    /** "0133" -> "P0133", "C123" -> "U0123" etc. */
    fun decode(fourHex: String): String {
        val a = fourHex.substring(0, 2).toInt(16)
        val letter = "PCBU"[a shr 6]
        val digit = (a shr 4) and 0x3
        return "$letter$digit${fourHex[1]}${fourHex.substring(2)}"
    }

    fun describe(code: String): String {
        KNOWN[code]?.let { return it }
        if (code.length != 5) return "Unknown code"
        return when (code[0]) {
            'P' -> when (code[1]) {
                '0', '2' -> when (code[2]) {
                    '0', '1', '2' -> "Fuel and air metering"
                    '3' -> "Ignition system or misfire"
                    '4' -> "Auxiliary emission controls"
                    '5' -> "Vehicle speed, idle control or auxiliary inputs"
                    '6' -> "Computer or output circuit"
                    '7', '8', '9' -> "Transmission"
                    'A' -> "Hybrid propulsion"
                    else -> "Powertrain"
                }
                else -> "Manufacturer-specific powertrain code"
            }
            'C' -> "Chassis (ABS, steering, suspension)"
            'B' -> "Body (airbags, climate, lighting, etc.)"
            else -> "Network / module communication"
        }
    }

    private val KNOWN: Map<String, String> = buildMap {
        put("P0010", "Intake camshaft position actuator circuit (bank 1)")
        put("P0011", "Intake camshaft timing over-advanced (bank 1)")
        put("P0016", "Crankshaft / camshaft position correlation (bank 1)")
        put("P0030", "O2 sensor heater control circuit (bank 1, sensor 1)")
        put("P0100", "Mass air flow sensor circuit")
        put("P0101", "Mass air flow sensor range/performance")
        put("P0102", "Mass air flow sensor circuit low")
        put("P0103", "Mass air flow sensor circuit high")
        put("P0106", "Manifold pressure sensor range/performance")
        put("P0107", "Manifold pressure sensor circuit low")
        put("P0108", "Manifold pressure sensor circuit high")
        put("P0110", "Intake air temperature sensor circuit")
        put("P0112", "Intake air temperature sensor circuit low")
        put("P0113", "Intake air temperature sensor circuit high")
        put("P0115", "Engine coolant temperature sensor circuit")
        put("P0116", "Engine coolant temperature sensor range/performance")
        put("P0117", "Engine coolant temperature sensor circuit low")
        put("P0118", "Engine coolant temperature sensor circuit high")
        put("P0120", "Throttle position sensor circuit")
        put("P0121", "Throttle position sensor range/performance")
        put("P0122", "Throttle position sensor circuit low")
        put("P0123", "Throttle position sensor circuit high")
        put("P0125", "Insufficient coolant temperature for closed-loop fuel control")
        put("P0128", "Thermostat: coolant below regulating temperature")
        put("P0130", "O2 sensor circuit (bank 1, sensor 1)")
        put("P0131", "O2 sensor circuit low voltage (bank 1, sensor 1)")
        put("P0132", "O2 sensor circuit high voltage (bank 1, sensor 1)")
        put("P0133", "O2 sensor slow response (bank 1, sensor 1)")
        put("P0134", "O2 sensor no activity (bank 1, sensor 1)")
        put("P0135", "O2 sensor heater circuit (bank 1, sensor 1)")
        put("P0136", "O2 sensor circuit (bank 1, sensor 2)")
        put("P0137", "O2 sensor circuit low voltage (bank 1, sensor 2)")
        put("P0138", "O2 sensor circuit high voltage (bank 1, sensor 2)")
        put("P0141", "O2 sensor heater circuit (bank 1, sensor 2)")
        put("P0155", "O2 sensor heater circuit (bank 2, sensor 1)")
        put("P0171", "System too lean (bank 1)")
        put("P0172", "System too rich (bank 1)")
        put("P0174", "System too lean (bank 2)")
        put("P0175", "System too rich (bank 2)")
        put("P0200", "Injector circuit")
        for (c in 1..8) put("P020$c", "Injector circuit, cylinder $c")
        put("P0217", "Engine overheat condition")
        put("P0230", "Fuel pump primary circuit")
        put("P0234", "Turbo/supercharger overboost")
        put("P0299", "Turbo/supercharger underboost")
        put("P0300", "Random/multiple cylinder misfire detected")
        for (c in 1..8) put("P030$c", "Cylinder $c misfire detected")
        put("P0325", "Knock sensor 1 circuit (bank 1)")
        put("P0327", "Knock sensor 1 circuit low (bank 1)")
        put("P0335", "Crankshaft position sensor A circuit")
        put("P0340", "Camshaft position sensor A circuit (bank 1)")
        for ((i, c) in "ABCDEFGH".withIndex()) put("P035${i + 1}", "Ignition coil $c primary/secondary circuit")
        put("P0400", "EGR flow malfunction")
        put("P0401", "EGR flow insufficient")
        put("P0402", "EGR flow excessive")
        put("P0411", "Secondary air injection: incorrect flow")
        put("P0420", "Catalyst efficiency below threshold (bank 1)")
        put("P0430", "Catalyst efficiency below threshold (bank 2)")
        put("P0440", "Evaporative emission system malfunction")
        put("P0441", "EVAP system: incorrect purge flow")
        put("P0442", "EVAP system: small leak detected")
        put("P0443", "EVAP purge control valve circuit")
        put("P0446", "EVAP vent control circuit")
        put("P0449", "EVAP vent valve/solenoid circuit")
        put("P0455", "EVAP system: large leak detected (check fuel cap)")
        put("P0456", "EVAP system: very small leak detected")
        put("P0500", "Vehicle speed sensor A")
        put("P0505", "Idle air control system")
        put("P0506", "Idle RPM lower than expected")
        put("P0507", "Idle RPM higher than expected")
        put("P0562", "System voltage low")
        put("P0563", "System voltage high")
        put("P0600", "Serial communication link")
        put("P0601", "Control module memory checksum error")
        put("P0606", "Control module processor fault")
        put("P0700", "Transmission control system (check transmission module codes)")
        put("P0705", "Transmission range sensor circuit")
        put("P0715", "Transmission input/turbine speed sensor circuit")
        put("P0720", "Transmission output speed sensor circuit")
        put("P0730", "Incorrect gear ratio")
        put("P0740", "Torque converter clutch circuit")
        put("P0750", "Shift solenoid A")
        put("U0100", "Lost communication with engine control module")
        put("U0101", "Lost communication with transmission control module")
        put("U0121", "Lost communication with ABS module")
        put("U0140", "Lost communication with body control module")
    }
}
