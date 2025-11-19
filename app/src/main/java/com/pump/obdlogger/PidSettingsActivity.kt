package com.pump.obdlogger

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class PidSettingsActivity : AppCompatActivity() {

    private lateinit var layoutPids: LinearLayout
    private lateinit var btnSave: Button

    private val prefs by lazy {
        getSharedPreferences("obd_prefs", MODE_PRIVATE)
    }

    data class PidOption(
        val pid: Int,
        val title: String
    )

    private val pidOptions = listOf(
        // Engine status / system info
        PidOption(0x01, "Monitor status since DTCs cleared"),
        PidOption(0x03, "Fuel system status"),
        PidOption(0x1F, "Run time since engine start"),
        PidOption(0x30, "Warm-ups since DTCs cleared"),
        PidOption(0x31, "Distance since DTCs cleared"),
        PidOption(0x21, "Distance with MIL on"),

        // Engine load / air / fuel
        PidOption(0x04, "Calculated engine load"),
        PidOption(0x06, "Short-term fuel trim (Bank 1)"),
        PidOption(0x0B, "MAP (manifold pressure)"),
        PidOption(0x0F, "Intake air temperature"),
        PidOption(0x10, "MAF (mass air flow)"),
        PidOption(0x43, "Absolute load"),
        PidOption(0x44, "Commanded equivalence ratio"),

        // Engine speed & timing
        PidOption(0x0C, "Engine RPM"),
        PidOption(0x0E, "Timing advance"),

        // Vehicle motion
        PidOption(0x0D, "Vehicle speed"),

        // Throttle / air control
        PidOption(0x11, "Throttle position"),
        PidOption(0x45, "Relative throttle position"),
        PidOption(0x4C, "Commanded throttle actuator"),

        // Environmental
        PidOption(0x33, "Barometric pressure"),
        PidOption(0x47, "Ambient air temperature"),

        // Oxygen / emissions
        PidOption(0x13, "O2 sensors present"),
        PidOption(0x15, "O2 B1S2 voltage + STFT"),
        PidOption(0x34, "O2 wide-range sensor"),
        PidOption(0x2E, "Commanded EGR"),

        // Electrical
        PidOption(0x42, "Control module voltage"),

        // Accelerator pedal
        PidOption(0x49, "Accel pedal D"),
        PidOption(0x4A, "Accel pedal E")
    )

    private val pidCheckBoxes = mutableMapOf<Int, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pid_settings)

        layoutPids = findViewById(R.id.layoutPids)
        btnSave = findViewById(R.id.btnSavePids)

        buildPidList()
        loadPidSelection()

        btnSave.setOnClickListener {
            savePidSelection()
            Toast.makeText(this, "PID settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildPidList() {
        layoutPids.removeAllViews()
        for (opt in pidOptions) {
            val cb = CheckBox(this).apply {
                text = "0x%02X – %s".format(opt.pid, opt.title)
                textSize = 14f
            }
            layoutPids.addView(cb)
            pidCheckBoxes[opt.pid] = cb
        }
    }

    private fun loadPidSelection() {
        val set = prefs.getStringSet("selected_pids", null)
        val parsed = set?.mapNotNull { it.toIntOrNull(16) }?.toSet()

        val defaultPids = setOf(
            0x0C, // RPM
            0x0D, // Speed
            0x05, // Coolant (if supported; you can adjust)
            0x0B, // MAP
            0x10, // MAF
            0x11  // Throttle
        )

        val active = if (parsed == null || parsed.isEmpty()) defaultPids else parsed

        for ((pid, cb) in pidCheckBoxes) {
            cb.isChecked = pid in active
        }
    }

    private fun savePidSelection() {
        val selected = pidCheckBoxes
            .filter { it.value.isChecked }
            .keys
            .map { "%02X".format(it) }
            .toSet()

        prefs.edit()
            .putStringSet("selected_pids", selected)
            .apply()
    }
}
