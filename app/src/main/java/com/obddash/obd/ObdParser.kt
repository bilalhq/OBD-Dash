package com.obddash.obd

/** Parses raw ELM327 text responses. */
object ObdParser {

    private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'

    /**
     * Returns the data bytes following "41 <pid>" for every ECU line that answered.
     * Works with spaces on or off, echo on or off, and multi-ECU replies.
     */
    fun dataBytes(raw: String, pid: Int): List<IntArray> {
        val marker = "41%02X".format(pid)
        return raw.uppercase()
            .split('\r', '\n')
            .mapNotNull { line ->
                val compact = line.replace(" ", "")
                val i = compact.indexOf(marker)
                if (i < 0) return@mapNotNull null
                val hex = compact.substring(i + marker.length).takeWhile { it.isHex() }
                if (hex.length < 2) return@mapNotNull null
                IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            }
    }

    fun value(pid: Pid, raw: String): Float? {
        val bytes = dataBytes(raw, pid.code).firstOrNull { it.size >= pid.bytes } ?: return null
        return pid.decode(bytes)
    }

    /** Decodes a "supported PIDs" bitmask reply (0100, 0120, 0140…). */
    fun supported(raw: String, base: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (bytes in dataBytes(raw, base)) {
            if (bytes.size < 4) continue
            for (bit in 0 until 32) {
                if ((bytes[bit / 8] shr (7 - bit % 8)) and 1 == 1) result += base + bit + 1
            }
        }
        return result
    }

    /**
     * Parses a mode 09 PID 02 (VIN) reply. Handles:
     *  - CAN multi-frame: "014" / "0: 49 02 01 31 47 31" / "1: 4A 43 …" / "2: …"
     *  - older protocols: five lines "49 02 0N AA BB CC DD"
     * Returns the 17-character VIN, or null if the reply doesn't contain one.
     */
    fun vin(raw: String): String? {
        val hex = StringBuilder()
        for (line in raw.uppercase().split('\r', '\n')) {
            var l = line.replace(" ", "").trim()
            if (l.isEmpty()) continue
            l = l.replace(Regex("^[0-9A-F]:"), "")          // CAN frame index
            if (!l.all { it.isHex() }) continue                // NO DATA, SEARCHING, etc.
            if (l.startsWith("4902")) l = l.drop(6)            // "49 02" + count/sequence byte
            else if (l.length <= 3) continue                   // CAN byte-count header
            hex.append(l)
        }
        val text = hex.chunked(2).filter { it.length == 2 }
            .map { it.toInt(16).toChar() }
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .joinToString("")
        return text.takeLast(17).takeIf { it.length == 17 }
    }

    /** Parses ATRV output such as "12.6V". */
    fun voltage(raw: String): Float? =
        Regex("""(\d{1,2}(?:\.\d+)?)\s*V""").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
}
