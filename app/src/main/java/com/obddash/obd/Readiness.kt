package com.obddash.obd

/** One emissions self-test ("readiness monitor") and whether it has finished since codes were last cleared. */
data class Monitor(val name: String, val complete: Boolean, val continuous: Boolean)

/**
 * Mode 01 PID 01, "monitor status since DTCs cleared": check-engine light, stored code count,
 * and the readiness monitors an emissions inspection looks at.
 */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    /** Compression ignition (diesel): the non-continuous monitor bits mean different tests. */
    val diesel: Boolean,
    /** Only the monitors this car supports. */
    val monitors: List<Monitor>,
) {
    val incomplete: List<Monitor> get() = monitors.filterNot { it.complete }
    val completeCount: Int get() = monitors.count { it.complete }
    val allComplete: Boolean get() = monitors.isNotEmpty() && incomplete.isEmpty()

    companion object {
        private val CONTINUOUS = listOf("Misfire", "Fuel system", "Components")

        /** Bit 0 → 7 of bytes C (supported) and D (incomplete). Null = reserved bit. */
        private val SPARK = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        private val COMPRESSION = listOf(
            "NMHC catalyst", "NOx / SCR aftertreatment", null, "Boost pressure",
            null, "Exhaust gas sensor", "Particulate filter", "EGR / VVT system",
        )

        /**
         * Decodes one or more PID 01 replies (one per ECU that answered). Replies are merged:
         * a monitor counts as supported if any ECU supports it, and incomplete if any ECU
         * that supports it hasn't finished it. Returns null if no reply had all 4 bytes.
         */
        fun parse(replies: List<IntArray>): Readiness? {
            val full = replies.filter { it.size >= 4 }
            if (full.isEmpty()) return null
            val milOn = full.any { it[0] and 0x80 != 0 }
            val dtcCount = full.sumOf { it[0] and 0x7F }
            val diesel = full.any { it[1] and 0x08 != 0 }

            val monitors = mutableListOf<Monitor>()
            // Byte B: bits 0–2 supported, bits 4–6 incomplete.
            CONTINUOUS.forEachIndexed { bit, name ->
                val supporting = full.filter { it[1] and (1 shl bit) != 0 }
                if (supporting.isNotEmpty()) {
                    val complete = supporting.none { it[1] and (1 shl (bit + 4)) != 0 }
                    monitors += Monitor(name, complete, continuous = true)
                }
            }
            // Bytes C (supported) and D (incomplete).
            (if (diesel) COMPRESSION else SPARK).forEachIndexed { bit, name ->
                if (name == null) return@forEachIndexed
                val supporting = full.filter { it[2] and (1 shl bit) != 0 }
                if (supporting.isNotEmpty()) {
                    val complete = supporting.none { it[3] and (1 shl bit) != 0 }
                    monitors += Monitor(name, complete, continuous = false)
                }
            }
            return Readiness(milOn, dtcCount, diesel, monitors)
        }

        /** Parses a hex string like "81 07 65 04" (as stored in demo/raw values). */
        fun parseHex(hex: String): Readiness? {
            val bytes = hex.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }.toIntArray()
            return parse(listOf(bytes))
        }
    }
}
