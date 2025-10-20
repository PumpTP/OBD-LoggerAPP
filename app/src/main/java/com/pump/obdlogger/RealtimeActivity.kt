package com.pump.obdlogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class RealtimeActivity : AppCompatActivity() {

    private var pollJob: Job? = null
    private var pids: IntArray = intArrayOf()
    private var btAddr: String? = null
    private var obd: ObdManager? = null

    // simple UI elements (no XML)
    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Build the UI programmatically ----
        val root = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusText = TextView(this).apply {
            textSize = 16f
            text = "Status: —"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        headerRow.addView(statusText)
        headerRow.addView(btnClose)

        val divider = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 12; bottomMargin = 12 }
            setBackgroundColor(0xFFCCCCCC.toInt())
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        outer.addView(headerRow)
        outer.addView(divider)
        outer.addView(container)
        root.addView(outer)
        setContentView(root)
        // ---------------------------------------

        pids = intent.getIntArrayExtra("pids") ?: intArrayOf(0x0C, 0x05)
        btAddr = intent.getStringExtra("bt_addr")

        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }

        // Build rows once
        container.removeAllViews()
        val rows = mutableMapOf<Int, TextView>()
        for (pid in pids) {
            val row = TextView(this).apply {
                textSize = 18f
                setPadding(8, 12, 8, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = "${labelFor(pid)}: --"
            }
            container.addView(row)
            rows[pid] = row
        }

        // Start polling
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            val device = resolveDevice(btAddr)
            if (device == null) {
                toast("No Bluetooth device address. Go back and connect first.")
                return@launch
            }

            obd = ObdManager(this@RealtimeActivity)
            try {
                obd!!.connect(device)
            } catch (e: Exception) {
                toast("Connect failed: ${e.message}")
                return@launch
            }

            statusText.text = "Status: Initializing…"
            val okInit = runCatching { obd!!.initElmAuto() }.getOrDefault(false)
            if (!okInit) {
                statusText.text = "Status: No ECU response"
                toast("No ECU response on common protocols")
                return@launch
            }

            val okSmoke = obd!!.smokeTest()
            if (!okSmoke) {
                statusText.text = "Status: ECU didn't acknowledge 0100"
                toast("ECU didn't acknowledge 0100")
                return@launch
            }

            statusText.text = "Status: Live"
            val periodMs = 200L // ~5 Hz

            while (isActive) {
                for (pid in pids) {
                    val valueText = readOne(pid) ?: "--"
                    rows[pid]?.text = "${labelFor(pid)}: $valueText"
                }
                delay(periodMs)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        runCatching { obd?.close() }
    }

    // Resolve BluetoothDevice from address
    private fun resolveDevice(addr: String?): BluetoothDevice? {
        if (addr.isNullOrBlank()) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return try { adapter.getRemoteDevice(addr) } catch (_: IllegalArgumentException) { null }
    }

    // Labels
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

    // Read a single PID using ObdManager’s decoders
    private fun readOne(pid: Int): String? = when (pid) {
        0x03 -> obd?.readFuelSystemStatusText()
        0x04 -> obd?.readCalcLoadPct()?.fmt(2)
        0x05 -> obd?.readCoolantC()?.fmt(0)
        0x0A -> null
        0x0B -> obd?.readMapKpa()?.fmt(0)
        0x06 -> obd?.readStftB1Pct()?.fmt(2)
        0x0C -> obd?.readRpm()?.fmt(0)
        0x0D -> obd?.readSpeedKmh()?.fmt(0)
        0x0E -> obd?.readTimingAdvanceDeg()?.fmt(1)
        0x0F -> obd?.readIatC()?.fmt(0)
        0x10 -> obd?.readMafGps()?.fmt(2)
        0x11 -> obd?.readThrottlePct()?.fmt(1)
        0x15 -> {
            val v = obd?.readO2B1S2() ?: return null
            String.format(Locale.US, "%.3f V, %.2f %%", v.first, v.second)
        }
        0x45 -> obd?.readRelativeThrottlePct()?.fmt(1)
        0x47 -> obd?.readAmbientC()?.fmt(0)
        0x4C -> obd?.readCmdThrottlePct()?.fmt(1)
        else -> null
    }

    private fun Double.fmt(dp: Int): String = when (dp) {
        0 -> String.format(Locale.US, "%.0f", this)
        1 -> String.format(Locale.US, "%.1f", this)
        2 -> String.format(Locale.US, "%.2f", this)
        else -> String.format(Locale.US, "%.3f", this)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
