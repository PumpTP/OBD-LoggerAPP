package com.pump.obdlogger

object ObdShared {
    // basic live flags
    @Volatile var loggingActive: Boolean = false

    // “publish/subscribe” store for latest values
    private val latest = mutableMapOf<Int, Double?>()

    // PID keys we use in MainActivity / Realtime
    const val PID_ENGINE_RPM = 0x0C
    const val PID_VEHICLE_SPEED = 0x0D
    const val PID_COOLANT_TEMP = 0x05
    const val PID_MAF = 0x10
    const val PID_THROTTLE = 0x11

    // virtual “score” IDs
    const val PID_ACCEL_SCORE = 0xF101
    const val PID_FUEL_SCORE  = 0xF102
    const val PID_OVERALL     = 0xF103

    @Synchronized fun publish(pid: Int, value: Double?) {
        latest[pid] = value
    }
    @Synchronized fun read(pid: Int): Double? = latest[pid]
}
