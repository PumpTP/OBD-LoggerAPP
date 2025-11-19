package com.pump.obdlogger

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class CarProfileActivity : AppCompatActivity() {

    private lateinit var etCar1Name: EditText
    private lateinit var etCar1Odo: EditText
    private lateinit var etCar2Name: EditText
    private lateinit var etCar2Odo: EditText
    private lateinit var etCar3Name: EditText
    private lateinit var etCar3Odo: EditText

    private lateinit var rbCar1: RadioButton
    private lateinit var rbCar2: RadioButton
    private lateinit var rbCar3: RadioButton

    private lateinit var btnSave: Button

    private val prefs by lazy {
        getSharedPreferences("obd_prefs", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_profile)

        bindViews()
        loadCarProfiles()

        btnSave.setOnClickListener {
            saveCarProfiles()
            Toast.makeText(this, "Car profiles saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindViews() {
        etCar1Name = findViewById(R.id.etCar1Name)
        etCar1Odo = findViewById(R.id.etCar1Odo)
        etCar2Name = findViewById(R.id.etCar2Name)
        etCar2Odo = findViewById(R.id.etCar2Odo)
        etCar3Name = findViewById(R.id.etCar3Name)
        etCar3Odo = findViewById(R.id.etCar3Odo)

        rbCar1 = findViewById(R.id.rbCar1)
        rbCar2 = findViewById(R.id.rbCar2)
        rbCar3 = findViewById(R.id.rbCar3)

        btnSave = findViewById(R.id.btnSaveCarProfiles)
    }

    private fun loadCarProfiles() {
        val activeSlot = prefs.getInt("active_car_slot", 1).coerceIn(1, 3)

        loadCarSlot(1, etCar1Name, etCar1Odo)
        loadCarSlot(2, etCar2Name, etCar2Odo)
        loadCarSlot(3, etCar3Name, etCar3Odo)

        when (activeSlot) {
            1 -> rbCar1.isChecked = true
            2 -> rbCar2.isChecked = true
            3 -> rbCar3.isChecked = true
        }
    }

    private fun loadCarSlot(slot: Int, etName: EditText, etOdo: EditText) {
        val nameKey = "car_${slot}_name"
        val odoKey = "car_${slot}_odo"

        val defaultName = "Car $slot"
        val name = prefs.getString(nameKey, defaultName) ?: defaultName
        val odo = prefs.getFloat(odoKey, 0f)

        etName.setText(name)
        etOdo.setText(if (odo > 0f) String.format("%.0f", odo) else "")
    }

    private fun saveCarProfiles() {
        saveCarSlot(1, etCar1Name, etCar1Odo)
        saveCarSlot(2, etCar2Name, etCar2Odo)
        saveCarSlot(3, etCar3Name, etCar3Odo)

        val activeSlot = when {
            rbCar2.isChecked -> 2
            rbCar3.isChecked -> 3
            else -> 1
        }

        prefs.edit()
            .putInt("active_car_slot", activeSlot)
            .apply()
    }

    private fun saveCarSlot(slot: Int, etName: EditText, etOdo: EditText) {
        val nameKey = "car_${slot}_name"
        val odoKey = "car_${slot}_odo"

        val name = etName.text.toString().ifBlank { "Car $slot" }
        val odoText = etOdo.text.toString().trim()

        val currentSaved = prefs.getFloat(odoKey, 0f)
        val odo = odoText.toFloatOrNull() ?: currentSaved

        prefs.edit()
            .putString(nameKey, name)
            .putFloat(odoKey, odo)
            .apply()
    }
}
