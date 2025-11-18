package com.pump.obdlogger

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
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
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

    // ===== UI refs (match activity_main.xml) =====
    private lateinit var tvStatus: TextView
    private lateinit var spDevices: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSessions: Button
    private lateinit var swDriverEval: Switch
    private lateinit var btnShare: Button
    private lateinit var btnRealtime: Button
    private lateinit var tvElapsed: TextView
    private lateinit var tvLogPath: TextView

    // ===== Checkboxes =====
    private lateinit var cb0C: CheckBox
    private lateinit var cb05: CheckBox
    private lateinit var cb03: CheckBox
    private lateinit var cb04: CheckBox
    private lateinit var cb0B: CheckBox
    private lateinit var cb06: CheckBox
    private lateinit var cb0D: CheckBox
    private lateinit var cb0E: CheckBox
    private lateinit var cb0F: CheckBox
    private lateinit var cb10: CheckBox
    private lateinit var cb11: CheckBox
    private lateinit var cb15: CheckBox
    private lateinit var cb45: CheckBox
    private lateinit var cb47: CheckBox
    private lateinit var cb4C: CheckBox

    // ===== Bluetooth / OBD =====
    private val btAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter
    private var selectedDevice: BluetoothDevice? = null
    private var obd: ObdManager? = null

    // ===== Logging =====
    private var pollJob: Job? = null
    private var startTsMs: Long = 0L
    private var lastWrittenSec: Long = -1L
    private lateinit var csvLogger: CsvLogger
    private var currentLogFile: File? = null
    private var evaluator: DriverEvaluator? = null

    private lateinit var failurePredictor: FailurePredictor

    private var enableDriverEval: Boolean = true

    // PIDs: poll vs log
    private var polledPids: Set<Int> = emptySet()   // user + core (for evaluation)
    private var loggedPids: List<Int> = emptyList() // exactly what user wants to display/realtime
    private val CORE_EVAL_PIDS = setOf(0x0C, 0x0D, 0x05, 0x10, 0x11) // rpm, speed, coolant, maf, throttle

    // ===== Permissions =====
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // On grant, just refresh the device list
        refreshDevices()
    }

    // ===== Lifecycle =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        failurePredictor = FailurePredictor(this)

        enableDriverEval = swDriverEval.isChecked
        swDriverEval.setOnCheckedChangeListener { _, isChecked -> enableDriverEval = isChecked }

        btnRefresh.setOnClickListener { ensureBtPermsThen { refreshDevices() } }
        btnConnect.setOnClickListener { ensureBtPermsThen { connectToSelected() } }
        btnStart.setOnClickListener { startLogging() }
        btnStop.setOnClickListener { stopLogging() }
        btnShare.setOnClickListener { shareCsv() }
        btnRealtime.setOnClickListener { openRealtime() }
        btnSessions.setOnClickListener { showPastSessionsDialog() }

        ensureBtPermsThen { refreshDevices() }
        setUiConnected(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopLogging() }
        runCatching { obd?.close() }
        setUiConnected(false)
    }

    // ===== View helpers =====
    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        spDevices = findViewById(R.id.spDevices)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnConnect = findViewById(R.id.btnConnect)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSessions = findViewById(R.id.btnSessions)
        swDriverEval = findViewById(R.id.swDriverEval)
        btnShare = findViewById(R.id.btnShare)
        btnRealtime = findViewById(R.id.btnRealtime)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvLogPath = findViewById(R.id.tvLogPath)

        cb0C = findViewById(R.id.cb0C)
        cb05 = findViewById(R.id.cb05)
        cb03 = findViewById(R.id.cb03)
        cb04 = findViewById(R.id.cb04)
        cb0B = findViewById(R.id.cb0B)
        cb06 = findViewById(R.id.cb06)
        cb0D = findViewById(R.id.cb0D)
        cb0E = findViewById(R.id.cb0E)
        cb0F = findViewById(R.id.cb0F)
        cb10 = findViewById(R.id.cb10)
        cb11 = findViewById(R.id.cb11)
        cb15 = findViewById(R.id.cb15)
        cb45 = findViewById(R.id.cb45)
        cb47 = findViewById(R.id.cb47)
        cb4C = findViewById(R.id.cb4C)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setUiConnected(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnStart.isEnabled = connected
        btnRealtime.isEnabled = connected
        btnShare.isEnabled = connected
    }

    // ===== Permissions =====
    private fun ensureBtPermsThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
            if (need) {
                permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                return
            }
        }
        block()
    }

    // Convert PID → CSV column name
    private fun headerForPid(pid: Int): String = when (pid) {
        0x03 -> "fuel_system_status"
        0x04 -> "load_pct"
        0x05 -> "coolant_c"
        0x0B -> "map_kpa"
        0x0C -> "rpm"
        0x0D -> "speed_kmh"
        0x0E -> "timing_deg"
        0x0F -> "iat_c"
        0x10 -> "maf_gps"
        0x11 -> "throttle_pct"
        0x15 -> "o2_b1s2_v_or_stft"
        0x45 -> "rel_throttle_pct"
        0x47 -> "ambient_c"
        0x4C -> "cmd_throttle_pct"
        else -> "pid_%02X".format(pid)
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ===== Devices =====
    private fun refreshDevices() {
        val adapter = btAdapter ?: run { toast("Bluetooth not available"); return }
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if (!adapter.isEnabled) {
            toast("Enable Bluetooth")
            tvStatus.text = "Status: Bluetooth off"
            return
        }

        val bonded = try { adapter.bondedDevices?.toList().orEmpty() }
        catch (_: SecurityException) {
            toast("Bluetooth permission required")
            return
        }

        if (bonded.isEmpty()) {
            spDevices.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                listOf("No paired devices")
            )
            selectedDevice = null
            tvStatus.text = "Status: No paired devices"
            return
        }

        val labels = bonded.map { "${it.name} (${it.address})" }
        spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedDevice = bonded[position]
                tvStatus.text = "Status: ${labels[position]}"
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedDevice = null
                tvStatus.text = "Status: No device selected"
            }
        }
        spDevices.setSelection(0)
        selectedDevice = bonded[0]
        tvStatus.text = "Status: Pick a device"
    }

    private fun connectToSelected() {
        val dev = selectedDevice ?: run { toast("Pick your device first"); return }
        tvStatus.text = "Status: Connecting…"
        btnConnect.isEnabled = false

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (obd == null) obd = ObdManager(this@MainActivity)
                    obd!!.connect(dev)
                    obd!!.initElmAuto() || obd!!.smokeTest()
                } catch (_: Throwable) { false }
            }
            if (ok) {
                tvStatus.text = "Status: Connected"
                setUiConnected(true)
            } else {
                tvStatus.text = "Status: Connect failed"
                setUiConnected(false)
                btnConnect.isEnabled = true
            }
        }
    }

    private fun mustBeConnected(): Boolean {
        val ok = obd?.isConnected() == true
        if (!ok) toast("Connect to your OBD adapter first")
        return ok
    }

    // ===== PID selection (matches your XML ids) =====
    private fun buildSelectedPidsFromUi(): Set<Int> {
        val map = listOf(
            cb0C to 0x0C, cb05 to 0x05, cb03 to 0x03, cb04 to 0x04, cb0B to 0x0B,
            cb06 to 0x06, cb0D to 0x0D, cb0E to 0x0E, cb0F to 0x0F, cb10 to 0x10,
            cb11 to 0x11, cb15 to 0x15, cb45 to 0x45, cb47 to 0x47, cb4C to 0x4C
        )
        val out = mutableSetOf<Int>()
        for ((cb, pid) in map) if (cb.isChecked) out += pid
        if (out.isEmpty()) out += setOf(0x0C, 0x05) // fallback: RPM + Coolant
        return out
    }

    // ===== Logging =====
    private fun startLogging() {
        if (!mustBeConnected()) return
        if (pollJob != null) { toast("Already logging"); return }

        val userSelected = buildSelectedPidsFromUi()
        polledPids = (userSelected + CORE_EVAL_PIDS)
        loggedPids = userSelected.toList()
        lastWrittenSec = -1L

        startTsMs = System.currentTimeMillis()
        tvElapsed.text = "t = 0.0 s"

        // CSV setup
        val ts = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(Date(startTsMs))
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        currentLogFile = File(dir, "obd_$ts.csv")

        csvLogger = CsvLogger(currentLogFile!!)   // ← initialize before using it

        val header = buildList {
            add("time_s")
            for (pid in loggedPids) add(headerForPid(pid))
        }
        csvLogger.writeHeader(header)
        tvLogPath.text = "Log: ${currentLogFile!!.absolutePath}"


        evaluator = if (enableDriverEval) DriverEvaluator() else null

        ObdShared.loggingActive = true
        btnStart.isEnabled = false
        tvStatus.text = "Status: Logging…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        pollJob = lifecycleScope.launch(Dispatchers.Main) {
            val periodMs = 200L
            while (isActive && ObdShared.loggingActive) {
                val absNow = System.currentTimeMillis()
                val elapsedSec = (absNow - startTsMs) / 1000
                try {
                    // Read required PIDs
                    val map = mutableMapOf<Int, Double?>()
                    for (pid in polledPids) {
                        val v = when (pid) {
                            0x03 -> null
                            0x04 -> withContext(Dispatchers.IO) { obd!!.readCalcLoadPct() }
                            0x05 -> withContext(Dispatchers.IO) { obd!!.readCoolantC() }
                            0x0B -> withContext(Dispatchers.IO) { obd!!.readMapKpa() }
                            0x0C -> withContext(Dispatchers.IO) { obd!!.readRpm() }
                            0x0D -> withContext(Dispatchers.IO) { obd!!.readSpeedKmh() }
                            0x0F -> withContext(Dispatchers.IO) { obd!!.readIatC() }
                            0x10 -> withContext(Dispatchers.IO) { obd!!.readMafGps() }
                            0x11 -> withContext(Dispatchers.IO) { obd!!.readThrottlePct() }
                            0x45 -> withContext(Dispatchers.IO) { obd!!.readRelativeThrottlePct() }
                            0x47 -> withContext(Dispatchers.IO) { obd!!.readAmbientC() }
                            0x4C -> withContext(Dispatchers.IO) { obd!!.readCmdThrottlePct() }
                            else -> null
                        }
                        map[pid] = v
                    }

                    // Publish for viewer
                    ObdShared.publish(ObdShared.PID_ENGINE_RPM,   map[0x0C])
                    ObdShared.publish(ObdShared.PID_VEHICLE_SPEED, map[0x0D])
                    ObdShared.publish(ObdShared.PID_COOLANT_TEMP,  map[0x05])
                    ObdShared.publish(ObdShared.PID_MAF,           map[0x10])
                    ObdShared.publish(ObdShared.PID_THROTTLE,      map[0x11])

                    // Evaluate scores
                    evaluator?.onSample(
                        DriverEvaluator.Sample(
                            timestampMs = absNow,
                            speedKmh    = map[0x0D],
                            rpm         = map[0x0C],
                            throttlePct = map[0x11],
                            mafGps      = map[0x10],
                            coolantC    = map[0x05]
                        )
                    )?.let { (a,f,o) ->
                        ObdShared.publish(ObdShared.PID_ACCEL_SCORE, a)
                        ObdShared.publish(ObdShared.PID_FUEL_SCORE,  f)
                        ObdShared.publish(ObdShared.PID_OVERALL,     o)
                    }

                    // CSV once per second: seconds + ISO
                    if (elapsedSec != lastWrittenSec) {
                        val row = ArrayList<Any?>(1 + loggedPids.size)
                        row.add(elapsedSec)                      // time_s = elapsed seconds from start (0,1,2,…)
                        for (pid in loggedPids) row.add(map[pid] ?: "")
                        csvLogger.writeRow(row)
                        lastWrittenSec = elapsedSec
                    }

                    // UI
                    val failure = failurePredictor.addSample(
                        floatArrayOf(
                            (map[0x0C] ?: 0.0).toFloat(),
                            (map[0x0D] ?: 0.0).toFloat(),
                            (map[0x05] ?: 0.0).toFloat()
                        )
                    )

                    tvStatus.text = if (failure != null) {
                        "Status: ${"%.0f".format(map[0x0D] ?: 0.0)} km/h, " +
                                "${"%.0f".format(map[0x0C] ?: 0.0)} rpm, " +
                                "fail=${"%.3f".format(failure[0])}"
                    } else {
                        "Status: ${"%.0f".format(map[0x0D] ?: 0.0)} km/h, " +
                                "${"%.0f".format(map[0x0C] ?: 0.0)} rpm"
                    }

                    tvElapsed.text = "t = ${"%.1f".format((absNow - startTsMs) / 1000.0)} s"

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    tvStatus.text = "Status: Read error: ${e.message}"
                }
                delay(periodMs)
            }
        }
    }

    private fun stopLogging() {
        pollJob?.cancel()
        pollJob = null
        ObdShared.loggingActive = false
        btnStart.isEnabled = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (::csvLogger.isInitialized) runCatching { csvLogger.close() }

        // store session
        val accel = ObdShared.read(ObdShared.PID_ACCEL_SCORE)
        val fuel  = ObdShared.read(ObdShared.PID_FUEL_SCORE)
        val total = ObdShared.read(ObdShared.PID_OVERALL)

        fun toPctInt(x: Double?): Int? = x?.let { it.coerceIn(0.0, 100.0).toInt() }

        val session = DriverSession(
            id = UUID.randomUUID().toString(),
            startTimeSec = startTsMs / 1000,
            endTimeSec = System.currentTimeMillis() / 1000,
            csvPath = currentLogFile?.absolutePath,
            accelScore = toPctInt(accel),
            fuelScore = toPctInt(fuel),
            overallScore = toPctInt(total)
        )
        SessionStore(this).save(session)

        // optional copy to Downloads/OBD
        currentLogFile?.let { runCatching { saveCopyToDownloads(it) } }
        tvStatus.text = "Status: Ready"
    }

    private fun saveCopyToDownloads(src: File): Uri {
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
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
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

    // ===== Past Sessions =====
    private fun showPastSessionsDialog() {
        val store = SessionStore(this)
        val sessions = store.list()
        if (sessions.isEmpty()) {
            toast("No past sessions yet")
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val items = sessions.map { s ->
            val startStr = fmt.format(Date(s.startTimeSec * 1000L))
            val endStr = fmt.format(Date(s.endTimeSec * 1000L))
            val overall = s.overallScore?.toString() ?: "—"
            "$startStr → $endStr  (Score: $overall)"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Past Driving Sessions")
            .setItems(items.toTypedArray()) { _, idx ->
                val sel = sessions[idx]
                sel.csvPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) {
                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/csv")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Open CSV with…"))
                    } else toast("CSV not found")
                } ?: toast("No CSV saved")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ===== Realtime =====
    private fun openRealtime() {
        if (!mustBeConnected()) return
        val dev = selectedDevice ?: run { toast("Pick your device"); return }
        val userSelected = buildSelectedPidsFromUi()
        val i = Intent(this, RealtimeActivity::class.java)
        i.putExtra("pids", userSelected.toIntArray())  // Realtime adds the 3 score rows itself
        i.putExtra("bt_addr", dev.address)
        startActivity(i)
    }
}
