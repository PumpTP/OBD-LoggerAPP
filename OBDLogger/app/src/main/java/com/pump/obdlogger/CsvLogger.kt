package com.pump.obdlogger

import java.io.File
import java.io.FileWriter
import java.io.IOException

class CsvLogger(private val file: File) {
    private var writer: FileWriter? = null
    private var lastError: String? = null

    init {
        try {
            file.parentFile?.mkdirs()
            writer = FileWriter(file, /*append=*/true)
        } catch (e: IOException) {
            lastError = e.message
        }
    }

    fun writeHeader(columns: List<String>) {
        val w = writer ?: return
        try {
            if (file.length() == 0L) {
                w.append(columns.joinToString(",")).append("\n")
                w.flush()
            }
        } catch (e: IOException) {
            lastError = e.message
        }
    }

    @Synchronized
    fun writeRow(values: List<String>) {
        val w = writer ?: return
        try {
            w.append(values.joinToString(",")).append("\n")
            w.flush()
        } catch (e: IOException) {
            lastError = e.message
        }
    }

    fun error(): String? = lastError

    fun close() {
        try { writer?.flush() } catch (_: IOException) {}
        try { writer?.close() } catch (_: IOException) {}
        writer = null
    }
}
