package com.pump.obdlogger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.*


class SettingsActivity : AppCompatActivity() {

//    General settings
    private lateinit var rgTemp: RadioGroup
    private lateinit var rbTempC: RadioButton
    private lateinit var rbTempF: RadioButton

    private lateinit var rgDistance: RadioGroup
    private lateinit var rbKm: RadioButton
    private lateinit var rbMi: RadioButton

    private lateinit var btnSave: Button

    // end general settings

//    private lateinit var btnGeneral: Button
    private lateinit var btnPids: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        loadUnits()

//        btnGeneral = findViewById(R.id.btnGeneralSettings)
        btnPids = findViewById(R.id.btnPidSettings)

//        btnGeneral.setOnClickListener {
//            startActivity(Intent(this, GeneralSettingsActivity::class.java))
//        }

        btnPids.setOnClickListener {
            startActivity(Intent(this, PidSettingsActivity::class.java))
        }

        btnSave.setOnClickListener {
            saveUnits()
            Toast.makeText(this, "General settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val prefs by lazy {
        getSharedPreferences("obd_prefs", MODE_PRIVATE)
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
