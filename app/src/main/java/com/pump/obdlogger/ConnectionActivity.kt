package com.pump.obdlogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionActivity : AppCompatActivity() {

    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var lvDevices: ListView
    private lateinit var tvStatus: TextView

    private val btAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private var deviceList: List<BluetoothDevice> = emptyList()
    private var selectedDevice: BluetoothDevice? = null

    private var obd: ObdManager? = null

    // Permissions launcher
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        bindViews()

        btnScan.setOnClickListener { ensureBtPermsThen { refreshDevices() } }
        btnConnect.setOnClickListener { connectToDevice() }

        ensureBtPermsThen { refreshDevices() }
    }

    private fun bindViews() {
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        lvDevices = findViewById(R.id.lvDevices)
        tvStatus = findViewById(R.id.tvConnectionStatus)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // Permissions
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

    private fun refreshDevices() {
        val adapter = btAdapter ?: return toast("Bluetooth unavailable")

        if (!adapter.isEnabled) {
            tvStatus.text = "Bluetooth OFF"
            toast("Please enable Bluetooth")
            return
        }

        val bonded = try { adapter.bondedDevices.toList() }
        catch (_: SecurityException) { toast("BT permission required"); return }

        if (bonded.isEmpty()) {
            toast("No paired OBD devices")
            tvStatus.text = "No devices"
            return
        }

        deviceList = bonded

        val names = bonded.map { "${it.name} (${it.address})" }

        val adapterList = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, names)
        lvDevices.choiceMode = ListView.CHOICE_MODE_SINGLE
        lvDevices.adapter = adapterList

        lvDevices.setOnItemClickListener { _, _, pos, _ ->
            selectedDevice = deviceList[pos]
            tvStatus.text = "Selected: ${selectedDevice!!.name}"
        }

        // Auto-select first
        selectedDevice = bonded.first()
        lvDevices.setItemChecked(0, true)
        tvStatus.text = "Selected: ${selectedDevice!!.name}"
    }

    private fun connectToDevice() {
        val dev = selectedDevice ?: return toast("Select a device")

        tvStatus.text = "Connecting…"

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (obd == null) obd = ObdManager(this@ConnectionActivity)
                    obd!!.connect(dev)
                    obd!!.initElmAuto() || obd!!.smokeTest()
                } catch (_: Exception) {
                    false
                }
            }

            if (!ok) {
                tvStatus.text = "Connection failed"
                toast("Connection failed")
            } else {
                tvStatus.text = "Connected!"

                // Return selected device back to MainActivity
                val data = intent
                data.putExtra("bt_addr", dev.address)
                setResult(RESULT_OK, data)
                finish()
            }
        }
    }
}
