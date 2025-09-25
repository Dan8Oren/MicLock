package io.github.miclock.ui

import android.Manifest
import android.app.NotificationManager
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
import io.github.miclock.R
import io.github.miclock.data.Prefs
import io.github.miclock.service.MicLockService
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.ComponentName
import android.service.quicksettings.TileService
import io.github.miclock.tile.MicLockTileService

/**
 * MainActivity provides the user interface for controlling the Mic-Lock service.
 * 
 * This activity allows users to:
 * - Start and stop the microphone protection service
 * - Configure recording modes (MediaRecorder vs AudioRecord)
 * - Monitor service status in real-time
 * - Manage battery optimization settings
 * 
 * The activity communicates with MicLockService through intents and observes
 * service state changes via StateFlow to update the UI accordingly.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton


    private lateinit var mediaRecorderToggle: SwitchMaterial
    private lateinit var mediaRecorderBatteryWarningText: TextView
    


    private val audioPerms = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val notifPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    @RequiresApi(Build.VERSION_CODES.P)
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateAllUi()
        // Request tile update after permission changes
        requestTileUpdate()
    }

    /**
     * Called when the activity is first created. Sets up the UI components,
     * initializes event listeners, and requests necessary permissions.
     * 
     * @param savedInstanceState If the activity is being re-initialized after being shut down
     */
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
            if (MicLockService.state.value.isRunning) {
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

        // Always enforce permissions on every app start
        enforcePermsOrRequest()
        updateAllUi()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions every time activity becomes visible
        enforcePermsOrRequest()
        updateAllUi()

        lifecycleScope.launch {
            MicLockService.state.collect { _ ->
                updateAllUi()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
    }
    
    override fun onDestroy() {
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
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        var notifGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val postNotificationsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            notifGranted = notificationManager.areNotificationsEnabled() && postNotificationsGranted
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifGranted = notificationManager.areNotificationsEnabled()
        }
        
        return micGranted && notifGranted
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun enforcePermsOrRequest() {
        if (!hasAllPerms()) {
            val permissionsToRequest = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !notificationManager.areNotificationsEnabled()) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                reqPerms.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    /**
     * Starts the MicLockService with user-initiated action.
     * This sends an ACTION_START_USER_INITIATED intent to the service.
     */
    private fun startMicLock() {
        val intent = Intent(this, MicLockService::class.java)
        intent.action = MicLockService.ACTION_START_USER_INITIATED
        ContextCompat.startForegroundService(this, intent)
        
        // Request tile update to reflect service start
        requestTileUpdate()
    }

    /**
     * Stops the MicLockService using ACTION_STOP intent for consistency with tile service.
     */
    private fun stopMicLock() {
        // Use the same ACTION_STOP intent that the tile uses for consistency
        val intent = Intent(this, MicLockService::class.java)
        intent.action = MicLockService.ACTION_STOP
        startService(intent) // Send stop command to service
        
        // Request tile update after service stop
        requestTileUpdate()
    }

    private fun updateAllUi() {
        updateMainStatus()
        updateCompatibilityModeUi()
    }

    private fun updateMainStatus() {
        val running = MicLockService.state.value.isRunning
        val paused = MicLockService.state.value.isPausedBySilence

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
    
    /**
     * Requests the Quick Settings tile to update its state.
     * This ensures the tile reflects current permission and service status.
     */
    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val componentName = ComponentName(this, MicLockTileService::class.java)
                TileService.requestListeningState(this, componentName)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to request tile update: ${e.message}")
            }
        }
    }
    

    

    



}
