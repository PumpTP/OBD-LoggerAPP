package com.pump.obdlogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pump.obdlogger.databinding.ActivityRealtimeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RealtimeActivity : AppCompatActivity() {

    private lateinit var vb: ActivityRealtimeBinding
    private var pollJob: Job? = null
    private var pids: IntArray = intArrayOf()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityRealtimeBinding.inflate(layoutInflater)
        setContentView(vb.root)

        pids = intent.getIntArrayExtra("pids") ?: intArrayOf(0x0C, 0x05)

        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }

        vb.btnClose.setOnClickListener { finish() }

        // simple, crash-proof placeholder UI
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                vb.container.removeAllViews()
                for (pid in pids) {
                    val tv = TextView(this@RealtimeActivity)
                    tv.textSize = 18f
                    tv.text = labelFor(pid) + ": (live page placeholder)"
                    vb.container.addView(tv)
                }
                delay(1000)
            }
        }
    }

    private fun labelFor(pid: Int): String = when (pid) {
        0x03 -> "Fuel System"
        0x04 -> "Load %"
        0x05 -> "Coolant °C"
        0x0A -> "Fuel Pressure kPa"
        0x0B -> "MAP kPa"
        0x06 -> "STFT %"
        0x0C -> "RPM"
        0x0D -> "Speed km/h"
        0x0E -> "Timing °"
        0x0F -> "IAT °C"
        0x10 -> "MAF g/s"
        0x11 -> "Throttle %"
        0x15 -> "O2 B1S2"
        0x45 -> "Rel Throttle %"
        0x47 -> "Ambient °C"
        0x4C -> "Cmd Throttle %"
        else -> "PID %02X".format(pid)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }
}
