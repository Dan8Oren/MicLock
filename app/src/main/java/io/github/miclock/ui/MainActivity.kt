package io.github.miclock.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.miclock.R
import io.github.miclock.data.Prefs
import io.github.miclock.service.MicLockService
import io.github.miclock.tile.EXTRA_START_SERVICE_FROM_TILE
import io.github.miclock.tile.MicLockTileService
import io.github.miclock.util.ApiGuard
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * MainActivity provides the user interface for controlling the Mic-Lock service.
 * * This activity allows users to:
 * - Start and stop the microphone protection service
 * - Configure recording modes (MediaRecorder vs AudioRecord)
 * - Monitor service status in real-time
 * - Manage battery optimization settings
 * * The activity communicates with MicLockService through intents and observes
 * service state changes via StateFlow to update the UI accordingly.
 */
open class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton

    private lateinit var mediaRecorderToggle: SwitchMaterial
    private lateinit var mediaRecorderBatteryWarningText: TextView

    private lateinit var screenOnDelaySlider: Slider
    private lateinit var screenOnDelaySummary: TextView

    private val audioPerms = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val notifPerms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        updateAllUi()
        // Request tile update after permission changes
        requestTileUpdate()
    }

    /**
     * Called when the activity is first created. Sets up the UI components,
     * initializes event listeners, and requests necessary permissions.
     * * @param savedInstanceState If the activity is being re-initialized after being shut down
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        mediaRecorderToggle = findViewById(R.id.mediaRecorderToggle)
        mediaRecorderBatteryWarningText = findViewById(R.id.mediaRecorderBatteryWarningText)

        screenOnDelaySlider = findViewById(R.id.screenOnDelaySlider)
        screenOnDelaySummary = findViewById(R.id.screenOnDelaySummary)

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

        // Initialize screen-on delay slider with logical mapping
        screenOnDelaySlider.valueFrom = Prefs.SLIDER_MIN
        screenOnDelaySlider.valueTo = Prefs.SLIDER_MAX
        val currentDelay = Prefs.getScreenOnDelayMs(this)
        screenOnDelaySlider.value = Prefs.delayMsToSlider(currentDelay)
        updateDelayConfigurationUi(currentDelay)

        screenOnDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // Snap to nearest valid position for clear phase boundaries
                val snappedValue = Prefs.snapSliderValue(value)
                
                // Convert snapped position to delay value
                val delayMs = Prefs.sliderToDelayMs(snappedValue)
                
                // Update slider to show the snapped position (creates the snappy feel)
                if (screenOnDelaySlider.value != snappedValue) {
                    screenOnDelaySlider.value = snappedValue
                }
                
                handleDelayPreferenceChange(delayMs)
            }
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

        // Handle tile-initiated start
        if (intent.getBooleanExtra(EXTRA_START_SERVICE_FROM_TILE, false)) {
            Log.d("MainActivity", "Starting service from tile fallback request")
            if (hasAllPerms()) {
                startMicLockFromTileFallback()
            } else {
                Log.w("MainActivity", "Permissions missing for tile fallback - requesting permissions")
                reqPerms.launch(audioPerms + notifPerms)
            }
        }
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
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        var notifGranted = true
        if (ApiGuard.isApi33_Tiramisu_OrAbove()) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val postNotificationsGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            notifGranted = notificationManager.areNotificationsEnabled() && postNotificationsGranted
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifGranted = notificationManager.areNotificationsEnabled()
        }

        return micGranted && notifGranted
    }

    private fun enforcePermsOrRequest() {
        if (ApiGuard.isApi28_P_OrAbove()) {
            if (!hasAllPerms()) {
                val permissionsToRequest = mutableListOf<String>()

                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                }

                if (ApiGuard.isApi33_Tiramisu_OrAbove()) {
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED ||
                        !notificationManager.areNotificationsEnabled()
                    ) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (permissionsToRequest.isNotEmpty()) {
                    reqPerms.launch(permissionsToRequest.toTypedArray())
                }
            }
        } else {
            Log.d("MainActivity", "Skipping enforcePermsOrRequest on pre-P device, standard checks apply.")
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

    protected open fun updateAllUi() {
        updateMainStatus()
        updateCompatibilityModeUi()
        updateDelayConfigurationUi(Prefs.getScreenOnDelayMs(this))
    }

    private fun updateMainStatus() {
        val running = MicLockService.state.value.isRunning
        val pausedBySilence = MicLockService.state.value.isPausedBySilence
        val pausedByScreenOff = MicLockService.state.value.isPausedByScreenOff

        when {
            !running -> {
                statusText.text = "OFF"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                statusText.animate().alpha(1.0f).setDuration(200)
            }
            pausedBySilence || pausedByScreenOff -> {
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

    private fun startMicLockFromTileFallback() {
        Log.d("MainActivity", "Starting MicLock service as tile fallback")
        val intent = Intent(this, MicLockService::class.java).apply {
            action = MicLockService.ACTION_START_USER_INITIATED
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

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

    /**
     * Updates the delay configuration UI to reflect the current delay value.
     * Shows appropriate summary text for all behavior modes.
     */
    private fun updateDelayConfigurationUi(delayMs: Long) {
        when {
            delayMs == Prefs.NEVER_REACTIVATE_VALUE -> {
                screenOnDelaySummary.text = getString(R.string.screen_on_delay_never_reactivate)
            }
            delayMs == Prefs.ALWAYS_KEEP_ON_VALUE -> {
                screenOnDelaySummary.text = getString(R.string.screen_on_delay_always_on)
            }
            delayMs <= 0L -> {
                screenOnDelaySummary.text = getString(R.string.screen_on_delay_disabled)
            }
            else -> {
                val delaySeconds = delayMs / 1000.0
                screenOnDelaySummary.text = getString(R.string.screen_on_delay_summary, delaySeconds)
            }
        }
    }

    /**
     * Handles changes to the delay preference from the UI.
     * Validates the input, saves the preference, and updates any pending delay operations.
     * Provides user feedback for configuration changes.
     */
    private fun handleDelayPreferenceChange(delayMs: Long) {
        try {
            // Validate the delay value
            if (!Prefs.isValidScreenOnDelay(delayMs)) {
                Log.w("MainActivity", "Invalid delay value: ${delayMs}ms")
                return
            }

            // Save the preference
            Prefs.setScreenOnDelayMs(this, delayMs)

            // Update UI to reflect the change
            updateDelayConfigurationUi(delayMs)

            Log.d("MainActivity", "Screen-on delay updated to ${delayMs}ms")

            // Preference changes take effect immediately:
            // - The new delay value will be used for the next screen-on event
            // - Any currently pending delay operation will complete with its original delay
            // - No service restart is required
            // - This provides predictable behavior where in-flight operations complete as scheduled

        } catch (e: IllegalArgumentException) {
            Log.e("MainActivity", "Failed to set screen-on delay: ${e.message}")
        }
    }
}
