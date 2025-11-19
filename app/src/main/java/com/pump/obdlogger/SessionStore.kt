package com.pump.obdlogger

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("driver_sessions", Context.MODE_PRIVATE)

    fun save(s: DriverSession) {
        val arr = loadArray()
        arr.put(JSONObject().apply {
            put("id", s.id)
            put("startTimeSec", s.startTimeSec)
            put("endTimeSec", s.endTimeSec)
            put("csvPath", s.csvPath)
            put("accelScore", s.accelScore)
            put("fuelScore", s.fuelScore)
            put("overallScore", s.overallScore)
        })
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    fun list(): List<DriverSession> {
        val arr = loadArray()
        val out = mutableListOf<DriverSession>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += DriverSession(
                id = o.optString("id"),
                startTimeSec = o.optLong("startTimeSec"),
                endTimeSec = o.optLong("endTimeSec"),
                csvPath = o.optString("csvPath", null),
                accelScore = if (o.has("accelScore") && !o.isNull("accelScore")) o.getInt("accelScore") else null,
                fuelScore = if (o.has("fuelScore") && !o.isNull("fuelScore")) o.getInt("fuelScore") else null,
                overallScore = if (o.has("overallScore") && !o.isNull("overallScore")) o.getInt("overallScore") else null
            )
        }
        // Newest first
        return out.sortedByDescending { it.startTimeSec }
    }

    private fun loadArray(): JSONArray {
        val s = prefs.getString("sessions", "[]") ?: "[]"
        return runCatching { JSONArray(s) }.getOrElse { JSONArray() }
    }
}
