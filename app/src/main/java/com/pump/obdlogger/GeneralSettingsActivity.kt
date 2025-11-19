package com.pump.obdlogger

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class GeneralSettingsActivity : AppCompatActivity() {

    private lateinit var rgTemp: RadioGroup
    private lateinit var rbTempC: RadioButton
    private lateinit var rbTempF: RadioButton

    private lateinit var rgDistance: RadioGroup
    private lateinit var rbKm: RadioButton
    private lateinit var rbMi: RadioButton

    private lateinit var btnSave: Button

    private val prefs by lazy {
        getSharedPreferences("obd_prefs", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        loadUnits()

        btnSave.setOnClickListener {
            saveUnits()
            Toast.makeText(this, "General settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindViews() {
        rgTemp = findViewById(R.id.rgTempUnit)
        rbTempC = findViewById(R.id.rbTempC)
        rbTempF = findViewById(R.id.rbTempF)

        rgDistance = findViewById(R.id.rgDistanceUnit)
        rbKm = findViewById(R.id.rbKm)
        rbMi = findViewById(R.id.rbMi)

        btnSave = findViewById(R.id.btnSaveGeneral)
    }

    private fun loadUnits() {
        val tempUnit = prefs.getString("unit_temp", "C") ?: "C"
        val distUnit = prefs.getString("unit_distance", "km") ?: "km"

        if (tempUnit == "F") rbTempF.isChecked = true else rbTempC.isChecked = true
        if (distUnit == "mi") rbMi.isChecked = true else rbKm.isChecked = true
    }

    private fun saveUnits() {
        val tempUnit = if (rbTempF.isChecked) "F" else "C"
        val distUnit = if (rbMi.isChecked) "mi" else "km"

        prefs.edit()
            .putString("unit_temp", tempUnit)
            .putString("unit_distance", distUnit)
            .apply()
    }
}
