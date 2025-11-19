package com.pump.obdlogger
//balls
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ===== UI (new layout) =====
    private lateinit var tvAppTitle: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvStatus: TextView

    private lateinit var tvCarName: TextView
    private lateinit var tvCarStatus: TextView
    private lateinit var tvOdometer: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvCoolant: TextView

    private lateinit var btnLiveData: Button
    private lateinit var btnDriveLogs: Button
    private lateinit var btnDiagnostics: Button
    private lateinit var btnCarProfile: Button
    private lateinit var btnSettings: Button
    private lateinit var btnConnection: Button

    // ===== Bluetooth / OBD =====
    private val btAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private var isConnected: Boolean = false
    private var selectedDevice: BluetoothDevice? = null
    private var selectedPids: Set<Int> = emptySet()
    private var obd: ObdManager? = null

    // ===== Logging =====
    private var pollJob: Job? = null
    private var startTsMs: Long = 0L
    private var lastWrittenSec: Long = -1L
    private lateinit var csvLogger: CsvLogger
    private var currentLogFile: File? = null
    private var evaluator: DriverEvaluator? = null
    private lateinit var failurePredictor: FailurePredictor

    private var enableDriverEval = true

    // Default PIDs (you no longer have checkboxes)
    private val DEFAULT_PIDS = setOf(
        0x0C, // RPM
        0x0D, // Speed
        0x05, // Coolant
        0x10, // MAF
        0x11, // Throttle
        0x0B, // MAP
        0x04  // Load
    )

    private val CORE_EVAL_PIDS = setOf(0x0C, 0x0D, 0x05, 0x10, 0x11)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshDevices() }

    // ===== Lifecycle =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadActiveCarToCard()
        val prefs = getSharedPreferences("obd_prefs", MODE_PRIVATE)
        val carName = prefs.getString("car_name", "HONDA City GM2")
        tvCarName.text = "Car: $carName"

        failurePredictor = FailurePredictor(this)

        // Click listeners for new UI
        btnConnection.setOnClickListener {
            val i = Intent(this, ConnectionActivity::class.java)
            startActivityForResult(i, 1001)
        }
        btnLiveData.setOnClickListener { openRealtime() }
        btnDriveLogs.setOnClickListener { showPastSessionsDialog() }
        btnCarProfile.setOnClickListener {
            val i = Intent(this, CarProfileActivity::class.java)
            startActivity(i)
        }


        btnSettings.setOnClickListener {
            val i = Intent(this, SettingsActivity::class.java)
            startActivity(i)
        }
        ensureBtPermsThen { refreshDevices() }
        tvStatus.text = "Status: Ready"
    }

    override fun onResume() {
        super.onResume()
        selectedPids = loadSelectedPidsFromPrefs()
        loadActiveCarToCard()   // refresh car name + odometer from Settings
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val addr = data?.getStringExtra("bt_addr")
            if (addr != null) {
                val dev = btAdapter?.getRemoteDevice(addr)
                selectedDevice = dev
                isConnected = true

                val name = dev?.name ?: "OBD Device"
                tvStatus.text = "Connected to $name"
                tvConnectionStatus.text = "Connected to $name"
                tvCarStatus.text = "Connected"
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopLogging() }
        runCatching { obd?.close() }
    }

    // ===== View bind =====
    private fun bindViews() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvStatus = findViewById(R.id.tvStatus)

        tvCarName = findViewById(R.id.tvCarName)
        tvCarStatus = findViewById(R.id.tvCarStatus)
        tvOdometer = findViewById(R.id.tvOdometer)
        tvBattery = findViewById(R.id.tvBattery)
        tvCoolant = findViewById(R.id.tvCoolant)

        btnLiveData = findViewById(R.id.btnLiveData)
        btnDriveLogs = findViewById(R.id.btnDriveLogs)
        btnDiagnostics = findViewById(R.id.btnDiagnostics)
        btnDiagnostics.visibility = View.GONE
        btnCarProfile = findViewById(R.id.btnCarProfile)
        btnSettings = findViewById(R.id.btnSettings)
        btnConnection = findViewById(R.id.btnConnection)
    }

    private fun loadSelectedPidsFromPrefs(): Set<Int> {
        val prefs = getSharedPreferences("obd_prefs", MODE_PRIVATE)
        val set = prefs.getStringSet("selected_pids", null) ?: return defaultPids()
        val parsed = set.mapNotNull { it.toIntOrNull(16) }.toSet()
        return if (parsed.isEmpty()) defaultPids() else parsed
    }

    private fun defaultPids(): Set<Int> = setOf(
        0x0C, // RPM
        0x0D, // Speed
        0x05, // You don't have 0x05; keep 0x0F + 0x47 instead if you want
        0x0B, // MAP
        0x10, // MAF
        0x11, // Throttle
        0x49, // Pedal D
        0x47, // Ambient
        0x42  // Voltage
    )


    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ===== Permissions =====
    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureBtPermsThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        block()
    }

    // ===== Device scan + connect =====
    private fun refreshDevices() {
        val adapter = btAdapter ?: return toast("No Bluetooth adapter")

        if (!adapter.isEnabled) {
            tvStatus.text = "Status: Bluetooth OFF"
            toast("Enable Bluetooth")
            return
        }

        val bonded = try { adapter.bondedDevices.toList() }
        catch (_: SecurityException) {
            toast("Bluetooth permission needed"); return
        }

        if (bonded.isEmpty()) {
            tvStatus.text = "Status: No paired OBD devices"
            return
        }

        // Auto-select the first OBD device
        selectedDevice = bonded.first()
        tvStatus.text = "Selected: ${selectedDevice!!.name}"
    }

    private suspend fun tryConnect(dev: BluetoothDevice): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (obd == null) obd = ObdManager(this@MainActivity)
                obd!!.connect(dev)
                obd!!.initElmAuto() || obd!!.smokeTest()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun ensureConnected(block: () -> Unit) {
        val dev = selectedDevice ?: return toast("No OBD device selected")
        tvStatus.text = "Connecting…"

        lifecycleScope.launch {
            val ok = tryConnect(dev)
            if (!ok) {
                tvStatus.text = "Connect failed"
            } else {
                tvStatus.text = "Connected"
                block()
            }
        }
    }

    // ===== Realtime =====
    private fun openRealtime() {
        if (!isConnected || selectedDevice == null) {
            toast("No OBD device connected")
            return
        }

        val intent = Intent(this, RealtimeActivity::class.java)
        intent.putExtra("pids", selectedPids.toIntArray())   // see PID section below
        intent.putExtra("bt_addr", selectedDevice!!.address)
        startActivity(intent)
    }
    // Load active car profile (name + odometer) from SharedPreferences
// and show it on the main card
    private fun loadActiveCarToCard() {
        val prefs = getSharedPreferences("obd_prefs", MODE_PRIVATE)

        // Which car slot is active? (1, 2, or 3)
        val activeSlot = prefs.getInt("active_car_slot", 1).coerceIn(1, 3)

        // Keys in SharedPreferences for this car
        val nameKey = "car_${activeSlot}_name"
        val odoKey = "car_${activeSlot}_odo"

        val defaultName = "Car $activeSlot"

        // Read stored values (or use defaults)
        val carName = prefs.getString(nameKey, defaultName) ?: defaultName
        val odo = prefs.getFloat(odoKey, 0f)

        // Update the TextViews from activity_main.xml
        tvCarName.text = "Car: $carName"
        tvOdometer.text = if (odo == 0f) {
            "-- km"
        } else {
            String.format("%.0f km", odo)
        }
    }




    private suspend fun readPidValue(pid: Int): Double? {
        return withContext(Dispatchers.IO) {
            try {
                when (pid) {
                    0x0C -> obd!!.readRpm()
                    0x0D -> obd!!.readSpeedKmh()
                    0x05 -> obd!!.readCoolantC()
                    0x10 -> obd!!.readMafGps()
                    0x11 -> obd!!.readThrottlePct()
                    0x0B -> obd!!.readMapKpa()
                    0x04 -> obd!!.readCalcLoadPct()
                    0x47 -> obd!!.readAmbientC()
                    0x4C -> obd!!.readCmdThrottlePct()
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ===== Logging (still needed for Drive Logs) =====
    private fun startLogging(pids: Set<Int>) {
        if (pollJob != null) return toast("Already logging")

        val dev = selectedDevice ?: return toast("No OBD device selected")
        lifecycleScope.launch {
            val ok = tryConnect(dev)
            if (!ok) return@launch toast("Connect failed")

            val allPids = pids + CORE_EVAL_PIDS
            lastWrittenSec = -1
            startTsMs = System.currentTimeMillis()

            val ts = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            currentLogFile = File(dir, "obd_$ts.csv")

            csvLogger = CsvLogger(currentLogFile!!)
            csvLogger.writeHeader(listOf("time_s") + pids.map { headerForPid(it) })

            evaluator = DriverEvaluator()

            pollJob = launch(Dispatchers.Main) {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - startTsMs) / 1000

                    val readings = mutableMapOf<Int, Double?>()
                    for (pid in allPids) {
                        readings[pid] = readPidValue(pid)
                    }

                    // ----- Predictive maintenance alert -----
                    val failure = failurePredictor.addSample(
                        floatArrayOf(
                            (readings[0x0C] ?: 0.0).toFloat(), // RPM
                            (readings[0x0D] ?: 0.0).toFloat(), // Speed
                            (readings[0x05] ?: 0.0).toFloat()  // Coolant
                        )
                    )

                    // Status line: show speed / rpm / fail score (if available)
                    tvStatus.text = if (failure != null) {
                        "Status: ${"%.0f".format(readings[0x0D] ?: 0.0)} km/h, " +
                                "${"%.0f".format(readings[0x0C] ?: 0.0)} rpm, " +
                                "fail=${"%.3f".format(failure[0])}"
                    } else {
                        "Status: ${"%.0f".format(readings[0x0D] ?: 0.0)} km/h, " +
                                "${"%.0f".format(readings[0x0C] ?: 0.0)} rpm"
                    }

                    // Top card car status: OK vs Alert
                    tvCarStatus.text = when {
                        failure == null -> "Monitoring…"
                        failure[0] >= 0.5f -> "Alert: possible issue detected"
                        else -> "Connected (healthy)"
                    }

                    // ----- CSV logging (unchanged) -----
                    if (elapsed != lastWrittenSec) {
                        val row = buildList {
                            add(elapsed)
                            for (pid in pids) add(readings[pid] ?: "")
                        }
                        csvLogger.writeRow(row)
                        lastWrittenSec = elapsed
                    }

                    delay(200)
                }
            }
        }
    }

    private fun stopLogging() {
        pollJob?.cancel()
        pollJob = null

        if (::csvLogger.isInitialized) csvLogger.close()

        toast("Logging stopped")
    }

    private fun headerForPid(pid: Int): String = when (pid) {
        0x0C -> "rpm"
        0x0D -> "speed_kmh"
        0x05 -> "coolant_c"
        0x10 -> "maf_gps"
        0x11 -> "throttle_pct"
        0x0B -> "map_kpa"
        0x04 -> "load_pct"
        else -> "pid_%02X".format(pid)
    }

    // ===== Drive Logs =====
    private fun showPastSessionsDialog() {
        val store = SessionStore(this)
        val list = store.list()

        if (list.isEmpty()) return toast("No past sessions")

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val items = list.map {
            val s = fmt.format(Date(it.startTimeSec * 1000))
            val e = fmt.format(Date(it.endTimeSec * 1000))
            "$s → $e (Score: ${it.overallScore ?: "—"})"
        }

        AlertDialog.Builder(this)
            .setTitle("Past Drive Logs")
            .setItems(items.toTypedArray()) { _, idx ->
                val session = list[idx]
                session.csvPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) {
                        val uri = FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            f
                        )
                        val i = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/csv")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(i, "Open CSV"))
                    } else toast("CSV not found")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
