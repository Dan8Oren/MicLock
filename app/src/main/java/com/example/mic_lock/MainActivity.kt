package com.example.mic_lock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton


    private lateinit var mediaRecorderToggle: SwitchMaterial
    private lateinit var mediaRecorderBatteryWarningText: TextView
    
    private var statusReceiver: BroadcastReceiver? = null

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

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()



        updateAllUi()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onResume() {
        super.onResume()
        registerStatusReceiver()
        updateAllUi()
    }
    
    override fun onPause() {
        super.onPause()
        unregisterStatusReceiver()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterStatusReceiver()
        super.onDestroy()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun hasAllPerms(): Boolean {
        val all = audioPerms + notifPerms
        return all.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startMicLock() {
        val intent = Intent(this, MicLockService::class.java)
        intent.action = MicLockService.ACTION_START_USER_INITIATED
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMicLock() {
        stopService(Intent(this, MicLockService::class.java))
    }

    private fun updateAllUi() {
        updateMainStatus()
        updateCompatibilityModeUi()
    }

    private fun updateMainStatus() {
        val running = MicLockService.isRunning
        val paused = MicLockService.isPausedBySilence

        when {
            !running -> {
                statusText.text = "OFF"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                statusText.animate().alpha(1.0f).setDuration(200)
            }
            paused -> {
                statusText.text = "PAUSED"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
                statusText.animate().alpha(0.6f).setDuration(500).withEndAction {
                    statusText.animate().alpha(1.0f).setDuration(500)
                }
            }
            else -> {
                statusText.text = "ON"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.secondary_green))
                statusText.animate().alpha(1.0f).setDuration(200)
            }
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
            mediaRecorderBatteryWarningText.text = "MediaRecorder mode (Higher battery usage, may resolve issues)"
        } else {
            mediaRecorderBatteryWarningText.text = "AudioRecord mode (optimized battery usage)"
        }
    }
    
    private fun setupStatusReceiver() {
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MicLockService.ACTION_STATUS_CHANGED) {
                    Log.d("MainActivity", "Received status update broadcast")
                    runOnUiThread {
                        updateAllUi()
                    }
                }
            }
        }
    }
    
    private fun registerStatusReceiver() {
        if (statusReceiver == null) {
            setupStatusReceiver()
        }
        val filter = IntentFilter(MicLockService.ACTION_STATUS_CHANGED)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.d("MainActivity", "Status receiver registered")
    }
    
    private fun unregisterStatusReceiver() {
        statusReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d("MainActivity", "Status receiver unregistered")
            } catch (e: Exception) {
                Log.w("MainActivity", "Error unregistering status receiver: ${e.message}")
            }
        }
    }


}
