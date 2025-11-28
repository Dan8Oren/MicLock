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
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.miclock.R
import io.github.miclock.data.DebugRecordingStateManager
import io.github.miclock.data.Prefs
import io.github.miclock.service.MicLockService
import io.github.miclock.tile.EXTRA_START_SERVICE_FROM_TILE
import io.github.miclock.tile.MicLockTileService
import io.github.miclock.util.ApiGuard
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
open class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
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

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

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
                handleStartButtonClick()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val debugItem = menu.findItem(R.id.menu_debug_tools)
        val isRecording = DebugRecordingStateManager.state.value.isRecording
        debugItem.title = if (isRecording) {
            getString(R.string.menu_stop_debug_recording)
        } else {
            getString(R.string.menu_debug_tools)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_debug_tools -> {
                handleDebugToolsClick()
                true
            }
            R.id.menu_about -> {
                // TODO: Handle about menu item (future implementation)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Handles debug tools menu click by routing to start or stop based on current recording state.
     * If recording is active, stops and shares logs. Otherwise, shows warning dialog to start recording.
     */
    private fun handleDebugToolsClick() {
        if (DebugRecordingStateManager.state.value.isRecording) {
            // TODO: stopAndShareDebugLogs() will be implemented in task 14
            Log.d("MainActivity", "Debug recording is active - stop & share will be implemented in task 14")
        } else {
            // TODO: showDebugRecordingWarning() will be implemented in task 12
            Log.d("MainActivity", "Debug recording not active - warning dialog will be implemented in task 12")
        }
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
     * Handles start button click with proper screen-off pause state handling.
     * Checks service state and routes to appropriate action (start, resume, or request permissions).
     */
    private fun handleStartButtonClick() {
        try {
            val currentState = MicLockService.state.value

            when {
                currentState.isPausedByScreenOff -> {
                    // Resume from screen-off pause (like tile does)
                    Log.d("MainActivity", "Resuming service from screen-off pause")
                    val intent = Intent(this, MicLockService::class.java).apply {
                        action = MicLockService.ACTION_START_USER_INITIATED
                        putExtra("from_main_activity", true)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    requestTileUpdate()
                }
                !currentState.isRunning -> {
                    // Normal start flow
                    Log.d("MainActivity", "Starting service from stopped state")
                    startMicLock()
                }
                else -> {
                    // Service is running but not paused by screen-off
                    // This shouldn't happen since start button should be disabled when running
                    Log.w("MainActivity", "Start button clicked while service is running and not paused by screen-off")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to handle start button click: ${e.message}", e)
            handleStartButtonFailure(e)
        }
    }

    /**
     * Starts the MicLockService with user-initiated action.
     * This sends an ACTION_START_USER_INITIATED intent to the service.
     */
    private fun startMicLock() {
        try {
            val intent = Intent(this, MicLockService::class.java)
            intent.action = MicLockService.ACTION_START_USER_INITIATED
            intent.putExtra("from_main_activity", true)
            ContextCompat.startForegroundService(this, intent)

            // Request tile update to reflect service start
            requestTileUpdate()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start MicLock service: ${e.message}", e)
            handleStartButtonFailure(e)
        }
    }

    /**
     * Handles start button failures with user-friendly error messages.
     * Logs the error and shows appropriate feedback to the user.
     */
    private fun handleStartButtonFailure(e: Exception) {
        Log.e("MainActivity", "Start button action failed: ${e.message}", e)

        // Update UI state to reflect failure
        updateAllUi()

        // Note: In a real implementation, you might want to show a Snackbar or Toast
        // For now, we'll just log the error as the current UI doesn't have error display components
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

        // Enable start button when service is not running OR when paused by screen-off
        // This allows the start button to act as a "Resume" button for screen-off pause
        startBtn.isEnabled = !running || pausedByScreenOff
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
                screenOnDelaySummary.text = getString(R.string.screen_off_stays_off_description)
            }
            delayMs == Prefs.ALWAYS_KEEP_ON_VALUE -> {
                screenOnDelaySummary.text = getString(R.string.screen_off_always_on_description)
            }
            delayMs <= 0L -> {
                screenOnDelaySummary.text = getString(R.string.screen_off_no_delay_description)
            }
            else -> {
                val delaySeconds = delayMs / 1000.0
                screenOnDelaySummary.text = getString(R.string.screen_off_delay_description, delaySeconds)
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
