package com.pump.obdlogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

    // Modern way (no deprecation warning)
    private val btAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private var selectedDevice: BluetoothDevice? = null
    private var obd: ObdManager? = null
    private var pollJob: Job? = null
    private var startTs: Long = 0L
    private lateinit var csvLogger: CsvLogger
    private var currentLogFile: File? = null
    private var selectedPids: List<Int> = emptyList()

    // ---- Permissions ----
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // If granted, we can safely touch Bluetooth now
        val granted = if (Build.VERSION.SDK_INT >= 31)
            (grants[Manifest.permission.BLUETOOTH_CONNECT] == true)
        else true

        if (granted) {
            refreshDevices()  // safe: permission is available now
        } else {
            toast("Bluetooth permission required")
            vb.tvStatus.text = "Status: Permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        vb.btnRefresh.setOnClickListener { ensureBtPermsThen { refreshDevices() } }
        vb.btnConnect.setOnClickListener { ensureBtPermsThen { connectToSelected() } }
        vb.btnStart.setOnClickListener { startLogging() }
        vb.btnStop.setOnClickListener { stopLogging() }
        vb.btnShare.setOnClickListener { shareCsv() }

        // Request permission first; DON'T touch Bluetooth yet.
        ensureBtPermsThen {
            refreshDevices() // called only after permission is granted
        }
    }

    // Request Android 12+ Bluetooth permission and run 'block' after it’s granted
    private fun ensureBtPermsThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                return
            }
        }
        block()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---- Bluetooth device list ----
    private fun refreshDevices() {
        val adapter = btAdapter ?: run { toast("Bluetooth not available"); return }
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Shouldn't happen because we gate this, but double-guard just in case
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if (!adapter.isEnabled) {
            toast("Enable Bluetooth first")
            return
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (se: SecurityException) {
            toast("Grant Bluetooth permission")
            return
        }

        if (bonded.isEmpty()) {
            vb.spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No bonded devices"))
            selectedDevice = null
            vb.tvStatus.text = "Status: No paired devices"
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
        vb.tvStatus.text = "Status: Pick your ELM327 and Connect"
    }

    private fun connectToSelected() {
        val dev = selectedDevice ?: run { toast("Pick your ELM327 device first"); return }
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
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

    // ---- PID selection helpers (IDs must match your layout ids) ----
    private fun buildSelectedPids(): List<Int> {
        val p = mutableListOf<Int>()
        // NOTE: ids must exist in activity_main.xml. If you renamed them, update here too.
        if (vb.cb03.isChecked) p += 0x03
        if (vb.cb04.isChecked) p += 0x04
        if (vb.cb05.isChecked) p += 0x05
        if (vb.cb0B.isChecked) p += 0x0B
        if (vb.cb06.isChecked) p += 0x06
        if (vb.cb0C.isChecked) p += 0x0C
        if (vb.cb0D.isChecked) p += 0x0D
        if (vb.cb0E.isChecked) p += 0x0E
        if (vb.cb0F.isChecked) p += 0x0F
        if (vb.cb10.isChecked) p += 0x10
        if (vb.cb11.isChecked) p += 0x11
        if (vb.cb15.isChecked) p += 0x15
        if (vb.cb45.isChecked) p += 0x45
        if (vb.cb47.isChecked) p += 0x47
        if (vb.cb4C.isChecked) p += 0x4C
        return p
    }

    private fun headerForPid(pid: Int): List<String> = when (pid) {
        0x03 -> listOf("fuel_status")
        0x04 -> listOf("calc_load_%")
        0x05 -> listOf("coolant_C")
        0x0B -> listOf("map_kPa")
        0x06 -> listOf("stft_b1_%")
        0x0C -> listOf("rpm")
        0x0D -> listOf("speed_kmh")
        0x0E -> listOf("timing_adv_deg")
        0x0F -> listOf("iat_C")
        0x10 -> listOf("maf_gps")
        0x11 -> listOf("throttle_%")
        0x15 -> listOf("o2_b1s2_V","o2_b1s2_stft_%")
        0x45 -> listOf("rel_throttle_%")
        0x47 -> listOf("ambient_C")
        0x4C -> listOf("cmd_throttle_%")
        else -> listOf("pid_%02X".format(pid))
    }

    private fun startLogging() {
        val manager = obd ?: run { toast("Connect first"); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pollJob != null) return

        selectedPids = buildSelectedPids()
        if (selectedPids.isEmpty()) { toast("Select at least one data item to log."); return }

        val dir = File(getExternalFilesDir(null), "obd").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "obd_log_$ts.csv")
        currentLogFile = file
        csvLogger = CsvLogger(file)

        val header = mutableListOf("timestamp_s")
        selectedPids.forEach { header += headerForPid(it) }
        csvLogger.writeHeader(header)

        vb.tvLogPath.text = "Log: ${file.absolutePath}"
        vb.btnStart.isEnabled = false
        startTs = System.currentTimeMillis()

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
                val t = (System.currentTimeMillis() - startTs) / 1000.0
                // live readouts (don’t crash if null)
                manager.readRpm()?.let { vb.tvRpm.text = "RPM: ${it.toInt()}" }
                manager.readCoolantC()?.let { vb.tvCoolant.text = "Coolant: ${it.toInt()} °C" }
                vb.tvElapsed.text = "t = ${"%.1f".format(t)} s"

                val row = mutableListOf("%.2f".format(t))
                selectedPids.forEach { pid -> row += decodeForCsv(manager, pid) }
                csvLogger.writeRow(row)
                csvLogger.error()?.let { err -> vb.tvStatus.text = "Status: Write error: $err" }

                delay(periodMs)
            }
        }
    }

    private fun Double.fmt0() = String.format(Locale.US, "%.0f", this)
    private fun Double.fmt1() = String.format(Locale.US, "%.1f", this)
    private fun Double.fmt2() = String.format(Locale.US, "%.2f", this)
    private fun Double.fmt3() = String.format(Locale.US, "%.3f", this)

    private fun decodeForCsv(manager: ObdManager, pid: Int): List<String> = when (pid) {
        0x03 -> listOf(manager.readFuelSystemStatusText() ?: "")
        0x04 -> listOf(manager.readCalcLoadPct()?.fmt2() ?: "")
        0x05 -> listOf(manager.readCoolantC()?.fmt0() ?: "")
        0x0B -> listOf(manager.readMapKpa()?.fmt0() ?: "")
        0x06 -> listOf(manager.readStftB1Pct()?.fmt2() ?: "")
        0x0C -> listOf(manager.readRpm()?.fmt0() ?: "")
        0x0D -> listOf(manager.readSpeedKmh()?.fmt0() ?: "")
        0x0E -> listOf(manager.readTimingAdvanceDeg()?.fmt1() ?: "")
        0x0F -> listOf(manager.readIatC()?.fmt0() ?: "")
        0x10 -> listOf(manager.readMafGps()?.fmt2() ?: "")
        0x11 -> listOf(manager.readThrottlePct()?.fmt1() ?: "")
        0x15 -> {
            val (v, stft) = manager.readO2B1S2() ?: return listOf("", "")
            listOf(v.fmt3(), stft.fmt2())
        }
        0x45 -> listOf(manager.readRelativeThrottlePct()?.fmt1() ?: "")
        0x47 -> listOf(manager.readAmbientC()?.fmt0() ?: "")
        0x4C -> listOf(manager.readCmdThrottlePct()?.fmt1() ?: "")
        else -> listOf("")
    }

    private fun stopLogging() {
        pollJob?.cancel()
        pollJob = null
        vb.btnStart.isEnabled = true
        vb.tvStatus.text = "Status: Saving…"
        if (::csvLogger.isInitialized) csvLogger.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        currentLogFile?.let { src ->
            runCatching { saveCopyToDownloads(src) }
                .onSuccess { vb.tvStatus.text = "Status: Saved to Downloads/OBD" }
                .onFailure { e -> vb.tvStatus.text = "Status: Save failed: ${e.message}" }
        } ?: run { vb.tvStatus.text = "Status: Ready" }
    }

    private fun saveCopyToDownloads(src: File): android.net.Uri {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OBD")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create Downloads entry")
        resolver.openOutputStream(uri, "w").use { out ->
            src.inputStream().use { it.copyTo(out!!) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
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
