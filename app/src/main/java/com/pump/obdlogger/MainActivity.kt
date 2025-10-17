package com.pump.obdlogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.pump.obdlogger.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val btAdapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    private var selectedDevice: BluetoothDevice? = null
    private var obd: ObdManager? = null
    private var pollJob: Job? = null
    private var startTs: Long = 0L
    private lateinit var csvLogger: CsvLogger
    private var currentLogFile: File? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        vb.btnRefresh.setOnClickListener { refreshDevices() }
        vb.btnConnect.setOnClickListener { connectToSelected() }
        vb.btnStart.setOnClickListener { startLogging() }
        vb.btnStop.setOnClickListener { stopLogging() }
        vb.btnShare.setOnClickListener { shareCsv() }

        requestBtPermsIfNeeded()
        refreshDevices()
    }

    private fun requestBtPermsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = mutableListOf<String>()
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT))
                need += Manifest.permission.BLUETOOTH_CONNECT
            if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshDevices() {
        val adapter = btAdapter ?: return toast("Bluetooth not available")
        if (!adapter.isEnabled) {
            toast("Enable Bluetooth first")
            return
        }
        val bonded = adapter.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            vb.spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No bonded devices"))
            selectedDevice = null
            return
        }
        val names = bonded.map { "${it.name} (${it.address})" }
        vb.spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        vb.spDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedDevice = bonded[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { selectedDevice = null }
        }
    }

    private fun connectToSelected() {
        val dev = selectedDevice ?: return toast("Pick your ELM327 device first")
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestBtPermsIfNeeded(); return
        }
        vb.tvStatus.text = "Status: Connecting to ${dev.name}…"
        lifecycleScope.launch {
            runCatching {
                if (obd?.isConnected() == true) obd?.close()
                obd = ObdManager(this@MainActivity).also { it.connect(dev) }
            }.onSuccess {
                vb.tvStatus.text = "Status: Connected to ${dev.name}"
            }.onFailure {
                vb.tvStatus.text = "Status: Connect failed: ${it.message}"
                toast("Connect failed: ${it.message}")
            }
        }
    }

    private fun startLogging() {
        val manager = obd ?: return toast("Connect first")

        // keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (pollJob != null) return

        val dir = File(getExternalFilesDir(null), "obd").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "rpm_temp_$ts.csv")
        currentLogFile = file

        csvLogger = CsvLogger(file)
        csvLogger.writeHeader(listOf("timestamp_s", "rpm", "coolant_C"))
        vb.tvLogPath.text = "Log: ${file.absolutePath}"

        startTs = System.currentTimeMillis()
        vb.btnStart.isEnabled = false

        pollJob = lifecycleScope.launch {
            vb.tvStatus.text = "Status: Initializing ELM…"
            manager.initElmForHonda()
            val ok = manager.smokeTest()
            if (!ok) {
                vb.tvStatus.text = "Status: ECU didn't acknowledge 0100"
                vb.btnStart.isEnabled = true
                return@launch
            }
            vb.tvStatus.text = "Status: Logging…"
            val hz = 5
            val periodMs = 1000L / hz

            while (true) {
                val t = (System.currentTimeMillis() - startTs).toDouble() / 1000.0
                val rpm = manager.readRpm()
                val ect = manager.readCoolantC()

                vb.tvElapsed.text = "t = ${"%.1f".format(t)} s"
                vb.tvRpm.text = "RPM: ${rpm?.toInt() ?: 0}"
                vb.tvCoolant.text = "Coolant: ${ect?.toInt() ?: 0} °C"

                csvLogger.writeRow(listOf("%.2f".format(t), rpm?.toString() ?: "", ect?.toString() ?: ""))
                csvLogger.error()?.let { err ->
                    vb.tvStatus.text = "Status: Write error: $err"
                }

                delay(periodMs)
            }
        }
    }

    private fun stopLogging() {
        pollJob?.cancel()
        pollJob = null
        vb.btnStart.isEnabled = true
        vb.tvStatus.text = "Status: Ready"
        if (::csvLogger.isInitialized) csvLogger.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun shareCsv() {
        val f = currentLogFile
        if (f == null || !f.exists()) {
            toast("No CSV yet. Start logging first.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share OBD CSV"))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
        obd?.close()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
