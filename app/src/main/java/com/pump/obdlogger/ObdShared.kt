package com.pump.obdlogger

import java.util.concurrent.ConcurrentHashMap

/** Shared state & last-known values when logging is active. */
object ObdShared {
    // Optional: expose the active manager for reuse (ONLY when not logging)
    @Volatile var obd: ObdManager? = null

    @Volatile private var _loggingActive: Boolean = false
    val loggingActive: Boolean get() = _loggingActive

    fun setLoggingActive(active: Boolean) { _loggingActive = active }

    // last numeric values by PID for viewer mode
    private val lastValues = ConcurrentHashMap<Int, Double>()

    /** Publish a numeric value for a PID (called by MainActivity loop). */
    fun publish(pid: Int, value: Double?) {
        if (value != null) lastValues[pid] = value
    }

    /** Read last numeric value (called by Realtime viewer). */
    fun read(pid: Int): Double? = lastValues[pid]

    /** Clear cached values (e.g., when stopping logging). */
    fun reset() { lastValues.clear() }
}
