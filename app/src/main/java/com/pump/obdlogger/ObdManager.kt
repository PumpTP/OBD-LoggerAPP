package com.pump.obdlogger

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.regex.Pattern

class ObdManager(private val ctx: Context) {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val promptByte: Byte = '>'.code.toByte()

    fun isConnected(): Boolean = (socket?.isConnected == true)

    fun connect(device: BluetoothDevice) {
        // Try secure first, then insecure as fallback (helps with cheap ELMs)
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

    private fun readUntilPrompt(timeoutMs: Long = 1200): String {
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
            .filter { it.isNotEmpty() && it != ">" && it.uppercase() != cmd.trim().uppercase() }
    }

    // ---- ELM init for Honda City GM2 (your car: CAN 29-bit, 500k, functional 7DF) ----
    suspend fun initElmForHonda() {
        val initSeq = listOf(
            "ATZ",     // reset
            "ATE0",    // echo off
            "ATL0",    // no linefeeds
            "ATS0",    // no spaces
            "ATH0",    // headers off
            "ATSP7",   // CAN 29-bit / 500 kbps
            "ATCAF1",  // CAN auto-format
            "ATSH7DF", // functional header
            "ATAL",    // allow long
            "ATST0A"   // timeout
        )
        for (cmd in initSeq) {
            sendRaw(cmd); delay(80)
        }
    }

    fun smokeTest(): Boolean {
        val resp = sendRaw("0100")
        return hasPositive(resp, "01", "00")
    }

    private fun hasPositive(lines: List<String>, svcHex: String, pidHex: String): Boolean {
        val targetSvc = Integer.parseInt(svcHex, 16) + 0x40  // 0x41 for 0x01
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
            val s = ln.uppercase()
            if (s.startsWith("41$pidHex")) return true // condensed
        }
        return false
    }

    private fun requestPid(pidHex: Int): IntArray? {
        val resp = sendRaw("01%02X".format(pidHex))
        return parsePositiveReply(resp, 0x41, pidHex)
    }

    private fun parsePositiveReply(lines: List<String>, posSvc: Int, pid: Int): IntArray? {
        val byteRegex = Pattern.compile("[0-9A-Fa-f]{2}")
        for (ln in lines) {
            val m = byteRegex.matcher(ln)
            val list = mutableListOf<Int>()
            while (m.find()) list.add(Integer.parseInt(m.group(), 16))
            for (i in 0 until list.size - 2) {
                if (list[i] == posSvc && list[i + 1] == pid) {
                    val rest = list.subList(i + 2, list.size)
                    return rest.toIntArray()
                }
            }
            // condensed like "410C1AF8"
            val s = ln.uppercase()
            val prefix = "41%02X".format(pid)
            if (s.startsWith(prefix)) {
                val tail = s.removePrefix(prefix)
                val mm = byteRegex.matcher(tail)
                val rest = mutableListOf<Int>()
                while (mm.find()) rest.add(Integer.parseInt(mm.group(), 16))
                if (rest.isNotEmpty()) return rest.toIntArray()
            }
        }
        return null
    }

    fun readRpm(): Double? {
        val data = requestPid(0x0C) ?: return null
        return if (data.size >= 2) ((data[0] * 256) + data[1]) / 4.0 else null
    }

    fun readCoolantC(): Double? {
        val data = requestPid(0x05) ?: return null
        return if (data.isNotEmpty()) (data[0] - 40).toDouble() else null
    }
}
