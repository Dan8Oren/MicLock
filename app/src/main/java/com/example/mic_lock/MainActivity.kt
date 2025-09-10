package com.example.mic_lock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button


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
        updateAllUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

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



        updateAllUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onResume() {
        super.onResume()
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


}
