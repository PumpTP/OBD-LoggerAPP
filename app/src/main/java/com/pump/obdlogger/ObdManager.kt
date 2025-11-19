package com.pump.obdlogger

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

class ObdManager(private val ctx: Context) {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    var lastProtocol: String? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val promptByte: Byte = '>'.code.toByte()

    fun isConnected(): Boolean = (socket?.isConnected == true)

    fun connect(device: BluetoothDevice) {
        // Try secure first, then insecure fallback
        val secure = device.createRfcommSocketToServiceRecord(sppUuid)
        runCatching { secure.connect() }.onSuccess {
            socket = secure
        }.onFailure {
            val insecure = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            insecure.connect()
            socket = insecure
        }
        input = BufferedInputStream(socket!!.inputStream)
        output = BufferedOutputStream(socket!!.outputStream)
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    // Bump timeout slightly for flaky ELMs/ECUs
    private fun readUntilPrompt(timeoutMs: Long = 2200): String {
        val ins = input ?: return ""
        val start = System.currentTimeMillis()
        val buf = StringBuilder()
        val tmp = ByteArray(256)
        while (System.currentTimeMillis() - start < timeoutMs) {
            val avail = ins.available()
            if (avail > 0) {
                val n = ins.read(tmp, 0, minOf(avail, tmp.size))
                if (n > 0) {
                    buf.append(String(tmp, 0, n))
                    if (tmp.sliceArray(0 until n).any { it == promptByte }) break
                }
            }
            Thread.sleep(10)
        }
        return buf.toString()
    }

    private fun sendRaw(cmd: String, delayMs: Long = 80): List<String> {
        val outs = output ?: return emptyList()
        outs.write((if (cmd.endsWith("\r")) cmd else "$cmd\r").toByteArray())
        outs.flush()
        Thread.sleep(delayMs)
        val raw = readUntilPrompt()
        return raw
            .replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                        it != ">" &&
                        it.uppercase(Locale.US) != cmd.trim().uppercase(Locale.US)
            }
    }

    // ---- Existing Honda-specific init (kept for reference/back-compat) ----
    suspend fun initElmForHonda() {
        val initSeq = listOf(
            "ATZ", "ATE0", "ATL0", "ATS0", "ATH0",
            "ATSP7", "ATCAF1", "ATSH7DF", "ATAL", "ATST0A"
        )
        for (cmd in initSeq) {
            sendRaw(cmd)
            delay(80)
        }
        // Record the protocol used
        lastProtocol = "ISO 15765-4 (CAN 29-bit, 500 kbps)"
    }

    // ---- New: Auto protocol init for broad compatibility ----
    suspend fun initElmAuto(): Boolean {
        // Base hygiene
        val base = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATST0A")
        for (c in base) {
            sendRaw(c); delay(80)
        }

        // Try common stacks; stop on first 0100 success
        val tries = listOf(
            listOf("ATSP0"),                 // Auto
            listOf("ATSP6", "ATSH7DF", "ATCAF1"), // CAN 11/500 (Mini/BMW etc.)
            listOf("ATSP7", "ATSH7DF", "ATCAF1"), // CAN 29/500 (Honda/Toyota newer)
            listOf("ATSP3"),                 // KWP2000 fast
            listOf("ATSP5")                  // ISO 9141-2
        )
        for (seq in tries) {
            for (c in seq) {
                sendRaw(c); delay(80)
            }
            if (smokeTest()) return true
            // Some dongles choke on CAF1; try CAF0 before giving up this attempt group
            if (seq.contains("ATCAF1")) {
                sendRaw("ATCAF0"); delay(60)
                if (smokeTest()) return true
            }
        }
        return false
    }

    fun smokeTest(): Boolean {
        val resp = sendRaw("0100")
        return hasPositive(resp, "01", "0x00".removePrefix("0x"))
                || hasPositive(resp, "01", "00")
    }

    private fun hasPositive(lines: List<String>, svcHex: String, pidHex: String): Boolean {
        val targetSvc = Integer.parseInt(svcHex, 16) + 0x40
        val targetPid = Integer.parseInt(pidHex, 16)
        val byteRegex = Pattern.compile("[0-9A-Fa-f]{2}")
        for (ln in lines) {
            val m = byteRegex.matcher(ln)
            val bytes = mutableListOf<String>()
            while (m.find()) bytes.add(m.group())
            for (i in 0 until bytes.size - 1) {
                val b0 = Integer.parseInt(bytes[i], 16)
                val b1 = Integer.parseInt(bytes[i + 1], 16)
                if (b0 == targetSvc && b1 == targetPid) return true
            }
            val s = ln.uppercase(Locale.US)
            if (s.startsWith("41$pidHex")) return true
        }
        return false
    }

    /** Send 01xx and return the data bytes after the "41 xx" header. */
    private fun requestPidRaw(pidHex: Int): IntArray? {
        val resp = sendRaw("01%02X".format(pidHex))
        return parsePositiveReply(resp, 0x41, pidHex)
    }

    private fun parsePositiveReply(lines: List<String>, posSvc: Int, pid: Int): IntArray? {
        val byteRegex = Pattern.compile("[0-9A-Fa-f]{2}")
        var first: IntArray? = null
        var from7E8: IntArray? = null

        for (ln in lines) {
            // Extract all byte-like tokens
            val m = byteRegex.matcher(ln)
            val list = mutableListOf<Int>()
            while (m.find()) list.add(Integer.parseInt(m.group(), 16))

            // Try generic scan
            for (i in 0 until list.size - 2) {
                if (list[i] == posSvc && list[i + 1] == pid) {
                    val rest = list.subList(i + 2, list.size).toIntArray()
                    if (first == null) first = rest
                }
            }

            // If headers are visible, prefer engine ECU 7E8
            val upper = ln.uppercase(Locale.US)
            val prefix = "41%02X".format(pid)
            if (upper.contains("7E8") && upper.contains(prefix)) {
                val idx = upper.indexOf(prefix)
                val tail = upper.substring(idx + prefix.length)
                val mm = byteRegex.matcher(tail)
                val rest = mutableListOf<Int>()
                while (mm.find()) rest.add(Integer.parseInt(mm.group(), 16))
                if (rest.isNotEmpty()) from7E8 = rest.toIntArray()
            }

            // Also accept plain "41xx..." lines
            if (from7E8 == null && first == null && upper.startsWith(prefix)) {
                val tail = upper.removePrefix(prefix)
                val mm = byteRegex.matcher(tail)
                val rest = mutableListOf<Int>()
                while (mm.find()) rest.add(Integer.parseInt(mm.group(), 16))
                if (rest.isNotEmpty()) first = rest.toIntArray()
            }
        }
        return from7E8 ?: first
    }

    // ===== Supported PID cache (01 00/20/40) =====
    private val supported01 = mutableSetOf<Int>()

    fun refreshSupportedPids01() {
        supported01.clear()
        addSupportedFromBitmap(0x00)
        addSupportedFromBitmap(0x20)
        addSupportedFromBitmap(0x40)
    }

    fun isPidSupported01(pid: Int): Boolean = supported01.contains(pid)

    private fun addSupportedFromBitmap(base: Int) {
        val data = requestPidRaw(base) ?: return
        if (data.size < 4) return
        for (i in 0 until 32) {
            val byteIdx = i / 8
            val bit = 7 - (i % 8)
            val set = ((data[byteIdx] shr bit) and 0x01) == 1
            if (set) {
                val pid = base + 1 + i
                supported01 += pid
            }
        }
    }

    // ===== Decoders =====
    fun readFuelSystemStatusText(): String? {
        val d = requestPidRaw(0x03) ?: return null
        if (d.isEmpty()) return null
        val a = d[0]
        return when (a) {
            0x01 -> "Open loop"
            0x02 -> "Closed loop"
            0x04 -> "Open loop (engine load)"
            0x08 -> "Open loop (system fault)"
            0x10 -> "Closed loop (O2/sensors)"
            else -> "0x%02X".format(a)
        }
    }

    fun readCalcLoadPct(): Double? =
        requestPidRaw(0x04)?.let { if (it.isNotEmpty()) it[0] * 100.0 / 255.0 else null }

    fun readCoolantC(): Double? =
        requestPidRaw(0x05)?.let { if (it.isNotEmpty()) (it[0] - 40).toDouble() else null }

    fun readMapKpa(): Double? =
        requestPidRaw(0x0B)?.let { if (it.isNotEmpty()) it[0].toDouble() else null }

    fun readStftB1Pct(): Double? =
        requestPidRaw(0x06)?.let { if (it.isNotEmpty()) (it[0] - 128) / 1.28 else null }

    fun readRpm(): Double? =
        requestPidRaw(0x0C)?.let { if (it.size >= 2) ((it[0] * 256) + it[1]) / 4.0 else null }

    fun readSpeedKmh(): Double? =
        requestPidRaw(0x0D)?.let { if (it.isNotEmpty()) it[0].toDouble() else null }

    fun readTimingAdvanceDeg(): Double? =
        requestPidRaw(0x0E)?.let { if (it.isNotEmpty()) (it[0] / 2.0) - 64.0 else null }

    fun readIatC(): Double? =
        requestPidRaw(0x0F)?.let { if (it.isNotEmpty()) (it[0] - 40).toDouble() else null }

    fun readMafGps(): Double? =
        requestPidRaw(0x10)?.let { if (it.size >= 2) ((it[0] * 256) + it[1]) / 100.0 else null }

    fun readThrottlePct(): Double? =
        requestPidRaw(0x11)?.let { if (it.isNotEmpty()) it[0] * 100.0 / 255.0 else null }

    fun readO2B1S2(): Pair<Double, Double>? {
        val d = requestPidRaw(0x15) ?: return null
        if (d.size < 2) return null
        val v = d[0] / 200.0
        val stft = (d[1] - 128) / 1.28
        return Pair(v, stft)
    }

    fun readRelativeThrottlePct(): Double? =
        requestPidRaw(0x45)?.let { if (it.isNotEmpty()) it[0] * 100.0 / 255.0 else null }

    fun readAmbientC(): Double? =
        requestPidRaw(0x47)?.let { if (it.isNotEmpty()) (it[0] - 40).toDouble() else null }

    fun readCmdThrottlePct(): Double? =
        requestPidRaw(0x4C)?.let {
            if (it.isNotEmpty()) it[0] * 100.0 / 255.0 else null
        }
}
