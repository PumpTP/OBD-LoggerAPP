package com.pump.obdlogger
import java.io.Closeable
import java.io.File
import java.io.FileWriter

class CsvLogger(file: File) : Closeable {
    private val fw = FileWriter(file, /*append=*/false)
    private var lastLine: String? = null

    fun writeHeader(cols: List<String>) {
        val line = cols.joinToString(",")
        fw.append(line).append('\n')
        fw.flush()
        lastLine = line
    }

    fun writeRow(items: List<Any?>) {
        val line = items.joinToString(",") { it?.toString() ?: "" }
        if (line != lastLine) {               // ← de-dup identical consecutive rows
            fw.append(line).append('\n')
            fw.flush()
            lastLine = line
        }
    }

    override fun close() {
        fw.flush()
        fw.close()
    }
}
