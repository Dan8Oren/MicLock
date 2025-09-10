package com.example.mic_lock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var micSpinner: Spinner
    private lateinit var micSelectionWarningText: TextView
    private lateinit var detailedStatusText: TextView
    private lateinit var mediaRecorderToggle: Switch
    private lateinit var mediaRecorderBatteryWarningText: TextView

    private val audioPerms = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val notifPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    @RequiresApi(Build.VERSION_CODES.P)
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasAllPerms()) {
            populateMics()
        }
        updateAllUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        micSpinner = findViewById(R.id.micSpinner)
        micSelectionWarningText = findViewById(R.id.micSelectionWarningText)
        detailedStatusText = findViewById(R.id.detailedStatusText)
        mediaRecorderToggle = findViewById(R.id.mediaRecorderToggle)
        mediaRecorderBatteryWarningText = findViewById(R.id.mediaRecorderBatteryWarningText)

        // Initialize compatibility mode toggle
        mediaRecorderToggle.isChecked = Prefs.getUseMediaRecorder(this)
        mediaRecorderToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setUseMediaRecorder(this, isChecked)
            if (MicLockService.isRunning) {
                val intent = Intent(this, MicLockService::class.java)
                intent.action = MicLockService.ACTION_RECONFIGURE
                ContextCompat.startForegroundService(this, intent)
            }
            updateCompatibilityModeUi()
        }

        startBtn.setOnClickListener {
            if (!hasAllPerms()) {
                reqPerms.launch(audioPerms + notifPerms)
            } else {
                startMicLock()
            }
        }
        stopBtn.setOnClickListener { stopMicLock() }

        micSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val item = parent?.adapter?.getItem(pos) as MicItem
                if (Prefs.getSelectedAddress(this@MainActivity) != item.address) {
                    Prefs.setSelectedAddress(this@MainActivity, item.address)
                    // Ask service to reconfigure if running
                    if (MicLockService.isRunning) {
                        val intent = Intent(this@MainActivity, MicLockService::class.java)
                        intent.action = MicLockService.ACTION_RECONFIGURE
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (hasAllPerms()) populateMics()
        updateAllUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onResume() {
        super.onResume()
        if (hasAllPerms()) populateMics()
        updateAllUi()
    }

    private fun hasAllPerms(): Boolean {
        val all = audioPerms + notifPerms
        return all.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startMicLock() {
        val intent = Intent(this, MicLockService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateAllUi()
    }

    private fun stopMicLock() {
        stopService(Intent(this, MicLockService::class.java))
        updateAllUi()
    }

    private fun updateAllUi() {
        updateMainStatus()
        updateMicSelectionUi()
        updateCompatibilityModeUi()
        updateDetailedStatus()
    }

    private fun updateMainStatus() {
        val running = MicLockService.isRunning
        val paused = MicLockService.isPausedBySilence
        
        statusText.text = when {
            !running -> "Mic-lock is OFF"
            paused -> "Paused — mic in use by another app"
            else -> "Mic-lock is ON"
        }
        
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
    }

    private fun updateMicSelectionUi() {
        val selectedAddr = Prefs.getSelectedAddress(this)
        val isAuto = selectedAddr == Prefs.VALUE_AUTO
        
        micSelectionWarningText.visibility = if (isAuto) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun updateCompatibilityModeUi() {
        val useMediaRecorder = Prefs.getUseMediaRecorder(this)
        val lastMethod = Prefs.getLastRecordingMethod(this)
        
        mediaRecorderToggle.isChecked = useMediaRecorder
        
        // Auto-suggest MediaRecorder if AudioRecord failed
        if (lastMethod == "AudioRecord" && !useMediaRecorder) {
            mediaRecorderBatteryWarningText.text = "AudioRecord mode (optimized battery usage)"
        } else if (useMediaRecorder) {
            mediaRecorderBatteryWarningText.text = "MediaRecorder mode (higher battery usage)"
        } else {
            mediaRecorderBatteryWarningText.text = "AudioRecord mode (optimized battery usage)"
        }
    }

    private fun updateDetailedStatus() {
        val currentDevice = MicLockService.currentDeviceAddress
        val lastMethod = Prefs.getLastRecordingMethod(this)
        
        val details = mutableListOf<String>()
        
        if (currentDevice != null) {
            details.add("Device: $currentDevice")
        }
        
        if (lastMethod != null) {
            details.add("Method: $lastMethod")
        }
        
        detailedStatusText.text = if (details.isNotEmpty()) {
            details.joinToString(" • ")
        } else {
            ""
        }
    }

    // ----- mic list UI -----

    data class MicItem(val label: String, val address: String) {
        override fun toString(): String = label
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun populateMics() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val choices = AudioSelector.listBuiltinMicChoices(am)

        val items = mutableListOf<MicItem>()
        // Ensure Auto is always the first item and is the strong default
        items += MicItem("Auto (bottom mic)", Prefs.VALUE_AUTO)
        choices.forEach { ch ->
            val addr = ch.device.address ?: ch.micInfo?.address ?: "(unknown)"
            val y = ch.micInfo?.position?.y
            val yTxt = y?.let { " y=%.3f".format(it) } ?: ""
            val label = "addr=$addr$yTxt"
            items += MicItem(label, ch.device.address ?: addr)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        micSpinner.adapter = adapter

        // Select current pref - Auto is the strong default for new installs
        val selAddr = Prefs.getSelectedAddress(this) // This already defaults to VALUE_AUTO in Prefs
        val idx = items.indexOfFirst { it.address == selAddr }
            .takeIf { it >= 0 } ?: 0  // Fallback to Auto (index 0) if not found
        micSpinner.setSelection(idx)
    }
}
