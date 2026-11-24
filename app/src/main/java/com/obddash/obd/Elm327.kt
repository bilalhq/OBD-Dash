package com.obddash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class EcuNotRespondingException : IOException(
    "The adapter is connected, but the car didn't answer. Turn the ignition ON (or start the engine) and try again."
)

/** Talks to an ELM327-compatible adapter over a Bluetooth Classic serial (SPP) socket. */
class Elm327 private constructor(private val socket: BluetoothSocket) : Closeable {

    private val input = socket.inputStream
    private val output = socket.outputStream
    private val lock = Mutex()

    /**
     * Complete replies (everything up to each '>' prompt), produced by [reader].
     * Closed with an IOException when the link dies, which makes [send] throw.
     */
    private val replies = Channel<String>(Channel.UNLIMITED)

    /**
     * Blocking reader on its own thread. It sleeps inside read() until bytes arrive, so waiting
     * for the adapter costs no CPU. (The previous approach polled available() every 4 ms, which
     * used enough CPU in the background for Android to kill the app mid-trip.)
     */
    private val reader = thread(isDaemon = true, name = "elm327-reader") {
        val buf = ByteArray(256)
        val sb = StringBuilder()
        var error: IOException = IOException("Adapter closed the connection")
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '>') {
                        replies.trySend(sb.toString())
                        sb.setLength(0)
                    } else {
                        sb.append(c)
                    }
                }
            }
        } catch (e: IOException) {
            error = IOException("Lost connection to the adapter", e)
        } finally {
            replies.close(error)
        }
    }

    /** Commands in a row that got no reply at all; a silent adapter is treated as a dropped link. */
    private var consecutiveTimeouts = 0

    /** Sends one command and returns everything up to the '>' prompt ("" on timeout). */
    suspend fun send(command: String, timeoutMs: Long = 1500): String = lock.withLock {
        // Discard replies left over from a previous timed-out command.
        while (replies.tryReceive().isSuccess) Unit

        withContext(Dispatchers.IO) {
            output.write("$command\r".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        val reply = withTimeoutOrNull(timeoutMs) { replies.receive() }
        if (reply == null) {
            if (++consecutiveTimeouts >= MAX_SILENT_COMMANDS) {
                throw IOException("The adapter stopped responding")
            }
            return@withLock ""
        }
        consecutiveTimeouts = 0
        reply.replace("SEARCHING...", "").trim()
    }

    /** Resets and configures the adapter, then waits for the car. Returns the protocol name. */
    suspend fun initialize(): String {
        send("ATZ", 4000)     // reset
        send("ATE0")          // echo off
        version = send("ATI").lines().lastOrNull { it.isNotBlank() }?.trim()
        send("ATL0")          // linefeeds off
        send("ATS0")          // spaces off
        send("ATH0")          // headers off
        send("ATAT1")         // adaptive timing
        send("ATSP0")         // auto-detect protocol
        val reply = send("0100", 12000) // first request triggers the protocol search
        if (ObdParser.dataBytes(reply, 0x00).isEmpty()) throw EcuNotRespondingException()
        // Protocol numbers 6–9 (and A–C on STN chips) are CAN, which formats DTC replies differently.
        val number = send("ATDPN").trim().removePrefix("A").take(1)
        isCan = number in setOf("6", "7", "8", "9", "A", "B", "C")
        return send("ATDP").removePrefix("AUTO,").trim().ifEmpty { "Unknown protocol" }
    }

    var isCan: Boolean = true
        private set

    /** Adapter identification from ATI, e.g. "ELM327 v1.5". */
    var version: String? = null
        private set

    /** Reads a mode-01 PID and returns its data bytes as hex ("00 07 E5 00"), or null if no answer. */
    suspend fun readRaw(code: Int): String? =
        ObdParser.dataBytes(send("01%02X".format(code)), code).firstOrNull()
            ?.joinToString(" ") { "%02X".format(it) }

    /** Check-engine light state and number of stored codes (mode 01 PID 01). */
    suspend fun milStatus(): Pair<Boolean, Int>? {
        val a = ObdParser.dataBytes(send("0101", 3000), 0x01).firstOrNull()?.firstOrNull() ?: return null
        return (a and 0x80 != 0) to (a and 0x7F)
    }

    /** Reads trouble codes: mode 0x03 = stored, 0x07 = pending. */
    suspend fun readDtcs(mode: Int): List<String> =
        DtcParser.parse(send("%02X".format(mode), 6000), mode + 0x40, isCan)

    /** Mode 09 PID 02: the Vehicle Identification Number, or null if the car doesn't report it. */
    suspend fun readVin(): String? = ObdParser.vin(send("0902", 6000))

    /** Mode 04: clears stored codes and turns the check-engine light off. */
    suspend fun clearDtcs(): Boolean = send("04", 6000).replace(" ", "").contains("44")

    /** Returns the set of mode-01 PID numbers the car says it supports. */
    suspend fun supportedPids(): Set<Int> {
        val result = mutableSetOf<Int>()
        for (base in 0x00..0x60 step 0x20) {
            if (base != 0 && base !in result) break
            result += ObdParser.supported(send("01%02X".format(base), 4000), base)
        }
        return result
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** ~15 s of total silence (10 × 1.5 s timeouts) before giving up on the link. */
        private const val MAX_SILENT_COMMANDS = 10

        @SuppressLint("MissingPermission")
        suspend fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): Elm327 =
            withContext(Dispatchers.IO) {
                // Discovery slows connections; cancelling needs BLUETOOTH_SCAN on 12+, so ignore failures.
                runCatching { adapter.cancelDiscovery() }
                val socket = openSocket(device)
                try {
                    ensureActive()
                } catch (c: CancellationException) {
                    socket.close()
                    throw c
                }
                Elm327(socket)
            }

        @SuppressLint("MissingPermission")
        private fun openSocket(device: BluetoothDevice): BluetoothSocket {
            val standard = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                standard.connect()
                return standard
            } catch (e: IOException) {
                runCatching { standard.close() }
                // Many cheap clones don't advertise SPP properly; RFCOMM channel 1 usually works.
                val fallback = try {
                    device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                } catch (ignored: Exception) {
                    throw e
                }
                try {
                    fallback.connect()
                    return fallback
                } catch (e2: IOException) {
                    runCatching { fallback.close() }
                    throw e2
                }
            }
        }
    }
}
