package com.pump.obdlogger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DriverEvaluator {

    data class Sample(
        val timestampMs: Long,
        val speedKmh: Double?,
        val rpm: Double?,
        val throttlePct: Double?,
        val mafGps: Double?,
        val coolantC: Double?
    )

    // last scores (0..100)
    var accelScore: Double = 100.0
        private set
    var fuelScore: Double = 100.0
        private set
    var overallScore: Double = 100.0
        private set

    private var lastSpeed: Double? = null

    /** Feed a sample; returns Triple of (accel, fuel, overall) */
    fun onSample(s: Sample): Triple<Double, Double, Double> {
        val engineOff = ( (s.rpm ?: 0.0) < 400.0 ) && ( (s.mafGps ?: 0.0) < 1.0 )

        if (engineOff) {
            // recover gently to 100 when truly off
            accelScore = (accelScore + 0.5).coerceIn(0.0, 100.0)
            fuelScore  = (fuelScore + 0.5).coerceIn(0.0, 100.0)
        } else {
            // ---- Accel behaviour ----
            val prevV = lastSpeed
            if (prevV != null && s.speedKmh != null) {
                val dv = s.speedKmh - prevV
                if (dv > 3.5)  accelScore -= min(3.0, dv * 0.5)
                else           accelScore += 0.15
            } else {
                // No speed data? approximate harshness from throttle ramps
                val thr = s.throttlePct ?: 0.0
                if (thr > 65)       accelScore -= 1.0
                else if (thr > 45)  accelScore -= 0.5
                else                accelScore += 0.10
            }
            lastSpeed = s.speedKmh ?: lastSpeed

            // ---- Fuel behaviour ----
            val thr = s.throttlePct ?: 0.0
            val maf = s.mafGps ?: 0.0
            val speed = s.speedKmh ?: 0.0

            val fuelPenalty = when {
                speed < 5 && maf > 10 -> 1.2        // idling with high MAF (warmed up) wastes fuel
                thr > 70 && speed < 30 -> 1.5
                thr > 50               -> 0.8
                maf > 60               -> 1.0
                else                   -> -0.2      // recover slowly
            }
            fuelScore -= fuelPenalty

            accelScore = accelScore.coerceIn(0.0, 100.0)
            fuelScore  = fuelScore.coerceIn(0.0, 100.0)
        }

        overallScore = (0.5 * accelScore + 0.5 * fuelScore).coerceIn(0.0, 100.0)
        return Triple(accelScore, fuelScore, overallScore)
    }

}
