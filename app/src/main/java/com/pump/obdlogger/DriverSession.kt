package com.pump.obdlogger

data class DriverSession(
    val id: String,
    val startTimeSec: Long,
    val endTimeSec: Long,
    val csvPath: String? = null,
    val accelScore: Int? = null,
    val fuelScore: Int? = null,
    val overallScore: Int? = null
)

