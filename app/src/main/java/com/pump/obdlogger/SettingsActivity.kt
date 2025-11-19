package com.pump.obdlogger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnGeneral: Button
    private lateinit var btnPids: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnGeneral = findViewById(R.id.btnGeneralSettings)
        btnPids = findViewById(R.id.btnPidSettings)

        btnGeneral.setOnClickListener {
            startActivity(Intent(this, GeneralSettingsActivity::class.java))
        }

        btnPids.setOnClickListener {
            startActivity(Intent(this, PidSettingsActivity::class.java))
        }
    }
}
