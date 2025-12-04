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
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import io.github.miclock.util.CollectionResult
import io.github.miclock.util.DebugLogCollector
import io.github.miclock.util.DebugLogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private lateinit var menuButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton

    private lateinit var mediaRecorderToggle: SwitchMaterial
    private lateinit var mediaRecorderBatteryWarningText: TextView

    private lateinit var screenOnDelaySlider: Slider
    private lateinit var screenOnDelaySummary: TextView

    private lateinit var debugRecordingBanner: android.widget.LinearLayout
    private lateinit var debugRecordingTimer: TextView

    private var timerJob: Job? = null

    // Track if we're showing permission dialog to prevent loops
    private var isShowingPermissionDialog = false
    private var hasRequestedNotificationPermission = false
    private var isWaitingForPermissionFromSettings = false

    private val audioPerms = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val notifPerms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        isShowingPermissionDialog = false

        // Check if microphone permission was denied
        val micPermissionGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission()

        if (!micPermissionGranted) {
            // Microphone permission is required - show dialog and exit
            showMicPermissionDeniedDialog()
        } else {
            // Microphone permission granted - update UI
            updateAllUi()
            // Request tile update after permission changes
            requestTileUpdate()

            // Show info if notification permission was denied (optional) - but only once
            val notifPermissionGranted = if (Build.VERSION.SDK_INT >= 33) {
                results[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission()
            } else {
                true
            }

            if (!notifPermissionGranted && Build.VERSION.SDK_INT >= 33 && !hasRequestedNotificationPermission) {
                hasRequestedNotificationPermission = true
                android.widget.Toast.makeText(
                    this,
                    "Notification permission denied. You won't see service status notifications.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showMicPermissionDeniedDialog() {
        if (isShowingPermissionDialog) return
        isShowingPermissionDialog = true

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(getString(R.string.mic_permission_denied_message))
            .setPositiveButton("Open Settings") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                isWaitingForPermissionFromSettings = true
                // Open app settings so user can grant permission manually
                openAppSettings()
            }
            .setNegativeButton("Exit") { _, _ ->
                isShowingPermissionDialog = false
                isWaitingForPermissionFromSettings = false
                finish()
            }
            .setOnDismissListener {
                // Don't auto-exit when dialog is dismissed
                // Only exit if user explicitly clicked "Exit"
                isShowingPermissionDialog = false
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Opens the app settings page where the user can manually grant permissions.
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open app settings: ${e.message}", e)
            android.widget.Toast.makeText(
                this,
                "Unable to open settings. Please grant microphone permission manually in Settings > Apps > Mic-Lock > Permissions",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Called when the activity is first created. Sets up the UI components,
     * initializes event listeners, and requests necessary permissions.
     * * @param savedInstanceState If the activity is being re-initialized after being shut down
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        menuButton = findViewById(R.id.menuButton)
        menuButton.setOnClickListener { showPopupMenu(it) }

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        mediaRecorderToggle = findViewById(R.id.mediaRecorderToggle)
        mediaRecorderBatteryWarningText = findViewById(R.id.mediaRecorderBatteryWarningText)

        screenOnDelaySlider = findViewById(R.id.screenOnDelaySlider)
        screenOnDelaySummary = findViewById(R.id.screenOnDelaySummary)

        debugRecordingBanner = findViewById(R.id.debugRecordingBanner)
        debugRecordingTimer = findViewById(R.id.debugRecordingTimer)

        // Ensure clean state on app start
        DebugRecordingStateManager.reset(this)
        DebugLogRecorder.cleanup(this)

        // Observe recording state
        observeDebugRecordingState()

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
            if (!hasMicPermission()) {
                // Microphone permission is required
                showMicrophonePermissionRequiredDialog()
            } else {
                handleStartButtonClick()
            }
        }
        stopBtn.setOnClickListener { stopMicLock() }

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()

        // Check permissions on app start (only in onCreate, not onResume to avoid loops)
        checkPermissionsOnStart()
        updateAllUi()

        // Handle tile-initiated start
        if (intent.getBooleanExtra(EXTRA_START_SERVICE_FROM_TILE, false)) {
            Log.d("MainActivity", "Starting service from tile fallback request")
            if (hasMicPermission()) {
                startMicLockFromTileFallback()
            } else {
                Log.w("MainActivity", "Microphone permission missing for tile fallback")
                showMicrophonePermissionRequiredDialog()
            }
        }

        // Check for pending crash logs and show dialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            checkForPendingCrashLogs()
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if we're returning from settings with permission granted
        if (isWaitingForPermissionFromSettings) {
            if (hasMicPermission()) {
                // Permission granted! Reset flag and continue
                isWaitingForPermissionFromSettings = false
                android.widget.Toast.makeText(
                    this,
                    "Microphone permission granted. You can now use Mic-Lock.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            // If permission still not granted, keep waiting (don't exit)
        }

        // Only update UI, don't re-request permissions to avoid loops
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

        // Clean up recording resources
        if (DebugRecordingStateManager.state.value.isRecording) {
            DebugRecordingStateManager.stopRecording()
        }
        DebugLogRecorder.cleanup(this)
        DebugLogRecorder.cancelAutoStop()

        stopRecordingTimer()
    }

    /**
     * Shows a popup menu when the menu button is clicked.
     * Dynamically updates the debug tools menu item based on recording state.
     */
    private fun showPopupMenu(view: android.view.View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        // Update debug tools menu item text based on recording state
        val debugItem = popup.menu.findItem(R.id.menu_debug_tools)
        val isRecording = DebugRecordingStateManager.state.value.isRecording
        debugItem.title = if (isRecording) {
            getString(R.string.menu_stop_debug_recording)
        } else {
            getString(R.string.menu_debug_tools)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_feedback -> {
                    showFeedbackBottomSheet()
                    true
                }
                R.id.menu_debug_tools -> {
                    handleDebugToolsClick()
                    true
                }
                R.id.menu_about -> {
                    launchAboutActivity()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    /**
     * Launches the About activity to display app information.
     */
    private fun launchAboutActivity() {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent)
    }

    /**
     * Shows a bottom sheet dialog with feedback options.
     * Provides two options: Report a Bug and Request a Feature.
     * Each option opens the GitHub issues page with appropriate labels.
     */
    private fun showFeedbackBottomSheet() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_feedback, null)
        bottomSheetDialog.setContentView(view)

        // Set peek height and rounded corners for modern appearance
        bottomSheetDialog.behavior.peekHeight = 400
        bottomSheetDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        // Report a Bug card click listener
        val reportBugCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.reportBugCard)
        reportBugCard.setOnClickListener {
            val bugReportUrl = "https://github.com/Dan8Oren/MicLock/issues/new?template=bug_report.md"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(bugReportUrl))
            startActivity(intent)
            bottomSheetDialog.dismiss()
        }

        // Request a Feature card click listener
        val requestFeatureCard = view.findViewById<com.google.android.material.card.MaterialCardView>(
            R.id.requestFeatureCard,
        )
        requestFeatureCard.setOnClickListener {
            val featureRequestUrl = "https://github.com/Dan8Oren/MicLock/issues/new?template=feature_request.md"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(featureRequestUrl))
            startActivity(intent)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    /**
     * Observes debug recording state changes and updates UI accordingly.
     * Collects StateFlow emissions in the lifecycle scope to react to recording state changes.
     */
    private fun observeDebugRecordingState() {
        lifecycleScope.launch {
            DebugRecordingStateManager.state.collect { state ->
                updateDebugRecordingUI(state)
            }
        }
    }

    /**
     * Updates the debug recording UI based on the current state.
     * Shows/hides the recording banner and manages the timer.
     * Note: Menu text is updated dynamically when the popup menu is shown.
     *
     * @param state Current debug recording state
     */
    private fun updateDebugRecordingUI(state: io.github.miclock.data.DebugRecordingState) {
        debugRecordingBanner.visibility = if (state.isRecording) android.view.View.VISIBLE else android.view.View.GONE

        if (state.isRecording) {
            startRecordingTimer(state.startTime)
        } else {
            stopRecordingTimer()
        }
    }

    /**
     * Starts the recording timer that updates every second.
     * Displays elapsed time in MM:SS format.
     *
     * @param startTime Unix timestamp (milliseconds) when recording started
     */
    private fun startRecordingTimer(startTime: Long) {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val minutes = (elapsed / 60000).toInt()
                val seconds = ((elapsed % 60000) / 1000).toInt()
                debugRecordingTimer.text = String.format("%02d:%02d", minutes, seconds)
                delay(1000)
            }
        }
    }

    /**
     * Stops the recording timer and cancels the timer job.
     */
    private fun stopRecordingTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Handles debug tools menu click by routing to start or stop based on current recording state.
     * If recording is active, stops and shares logs. Otherwise, shows warning dialog to start recording.
     */
    private fun handleDebugToolsClick() {
        if (DebugRecordingStateManager.state.value.isRecording) {
            stopAndShareDebugLogs()
        } else {
            showDebugRecordingWarning()
        }
    }

    /**
     * Shows a warning dialog explaining the debug recording feature before starting.
     * Informs the user about battery impact, automatic 30-minute timeout, and cleanup behavior.
     * If the user confirms, starts the debug recording session.
     */
    private fun showDebugRecordingWarning() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.debug_warning_title)
            .setMessage(R.string.debug_warning_message)
            .setPositiveButton(R.string.start_recording) { _, _ ->
                startDebugRecording()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Starts a debug recording session.
     * Calls DebugRecordingStateManager to initiate log capture.
     * Handles SecurityException for devices that restrict logcat access.
     * Shows error dialog if recording fails to start.
     */
    private fun startDebugRecording() {
        lifecycleScope.launch {
            try {
                val success = DebugRecordingStateManager.startRecording(this@MainActivity)
                if (!success) {
                    showDebugRecordingError(getString(R.string.debug_start_failed))
                }
            } catch (e: SecurityException) {
                Log.e("MainActivity", "SecurityException starting debug recording", e)
                showDebugRecordingError(getString(R.string.debug_permission_denied))
            }
        }
    }

    /**
     * Shows an error dialog for debug recording failures.
     *
     * @param message Error message to display to the user
     */
    private fun showDebugRecordingError(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.failed_to_collect_logs)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Stops the debug recording and shares the collected logs.
     *
     * This method performs the following steps:
     * 1. Stops the debug recording and retrieves the log file
     * 2. Collects system state via dumpsys commands
     * 3. Creates a diagnostic package (zip file)
     * 4. Handles the collection result (success, partial success, or failure)
     * 5. Resets the recording state
     *
     * All I/O operations are performed on the IO dispatcher to avoid blocking the UI thread.
     */
    private fun stopAndShareDebugLogs() {
        lifecycleScope.launch {
            try {
                // Show loading state - stopping recording
                statusText.text = getString(R.string.stopping_recording)

                // Get recording start time before stopping (needed for filename)
                val recordingStartTime = DebugRecordingStateManager.state.value.startTime

                // Step 1: Stop recording on IO dispatcher
                val logFile = withContext(Dispatchers.IO) {
                    DebugLogRecorder.stopRecording()
                }

                // Show loading state - collecting system state
                statusText.text = getString(R.string.collecting_system_state)

                // Step 2: Collect system state on IO dispatcher
                val result = withContext(Dispatchers.IO) {
                    DebugLogCollector.collectAndShare(this@MainActivity, logFile, recordingStartTime)
                }

                // Step 3: Handle result based on type
                handleCollectionResult(result)

                // Step 4: Reset state
                DebugRecordingStateManager.reset(this@MainActivity)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to collect debug logs", e)
                showDebugRecordingError(e.message ?: getString(R.string.unknown_error))

                // Reset state even on error
                DebugRecordingStateManager.reset(this@MainActivity)
            } finally {
                // Restore normal status display
                updateAllUi()
            }
        }
    }

    /**
     * Handles the collection result by routing to appropriate dialog or action.
     *
     * @param result CollectionResult from DebugLogCollector
     */
    private fun handleCollectionResult(result: CollectionResult) {
        when (result) {
            is CollectionResult.Success -> {
                // Clean success - just share
                startActivity(Intent.createChooser(result.shareIntent, getString(R.string.share_debug_logs)))
                android.widget.Toast.makeText(this, R.string.logs_ready_to_share, android.widget.Toast.LENGTH_SHORT).show()
            }
            is CollectionResult.PartialSuccess -> {
                // Some failures - warn user but allow sharing
                showPartialSuccessDialog(result)
            }
            is CollectionResult.Failure -> {
                // Critical failure - offer retry
                showFailureDialog(result)
            }
        }
    }

    /**
     * Shows a dialog for partial success scenarios.
     * Allows the user to share the partial logs or start a new recording.
     * Includes context information to help users understand if missing data is relevant.
     *
     * @param result PartialSuccess result containing share intent and failure list
     */
    private fun showPartialSuccessDialog(result: CollectionResult.PartialSuccess) {
        val failureList = result.failures.joinToString("\n") { failure ->
            val context = DebugLogCollector.getFailureContext(failure)
            if (context != null) {
                "• ${failure.component}: ${failure.error}\n  → $context"
            } else {
                "• ${failure.component}: ${failure.error}"
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.logs_collected_with_warnings)
            .setMessage(getString(R.string.partial_collection_message, failureList))
            .setPositiveButton(R.string.share_anyway) { _, _ ->
                startActivity(Intent.createChooser(result.shareIntent, getString(R.string.share_debug_logs)))
            }
            .setNegativeButton(R.string.retry_recording) { _, _ ->
                showDebugRecordingWarning()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    /**
     * Shows a dialog for failure scenarios.
     * Offers the user the option to start a new recording.
     *
     * @param result Failure result containing error message and failure list
     */
    private fun showFailureDialog(result: CollectionResult.Failure) {
        val failureList = result.failures.joinToString("\n") {
            "• ${it.component}: ${it.error}"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.failed_to_collect_logs)
            .setMessage(getString(R.string.collection_failure_message, result.error, failureList))
            .setPositiveButton(R.string.retry_recording) { _, _ ->
                showDebugRecordingWarning()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true)
            .show()
    }

    /**
     * Checks for pending crash logs from a previous crash and shows dialog if found.
     * This is called on app startup to allow users to report crashes that occurred
     * during debug recording.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun checkForPendingCrashLogs() {
        if (!io.github.miclock.util.CrashHandler.hasPendingCrashLogs(this)) {
            return
        }

        Log.d("MainActivity", "Found pending crash logs, showing dialog")

        // Show dialog with crash report options
        showCrashReportDialog()
    }

    /**
     * Shows a dialog offering options to report or share crash logs.
     * Provides three options:
     * - Report on GitHub (opens pre-filled issue)
     * - Share Logs (opens standard share sheet)
     * - Dismiss (clears crash log reference)
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun showCrashReportDialog() {
        val exceptionType = io.github.miclock.util.CrashHandler.getCrashExceptionType(this) ?: "Unknown"
        val exceptionMessage = io.github.miclock.util.CrashHandler.getCrashExceptionMessage(this) ?: "No message"
        val filename = io.github.miclock.util.CrashHandler.getCrashFilename(this) ?: "crash logs"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("App Crashed During Debug Recording")
            .setMessage(
                "Debug logs were automatically saved. Would you like to report this crash?\n\nException: $exceptionType\nMessage: $exceptionMessage",
            )
            .setPositiveButton("Report on GitHub") { _, _ ->
                openGitHubIssue()
            }
            .setNeutralButton("Share Logs") { _, _ ->
                shareCrashLogs()
            }
            .setNegativeButton("Dismiss") { _, _ ->
                io.github.miclock.util.CrashHandler.clearPendingCrashLogs(this)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Opens GitHub issue page with pre-filled crash report.
     * Generates a URL with crash details and instructions to attach the log file.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun openGitHubIssue() {
        try {
            val exceptionType = io.github.miclock.util.CrashHandler.getCrashExceptionType(this) ?: "Unknown"
            val exceptionMessage = io.github.miclock.util.CrashHandler.getCrashExceptionMessage(this) ?: "No message"
            val stackTrace = io.github.miclock.util.CrashHandler.getCrashStackTrace(this) ?: "No stack trace"
            val timestamp = io.github.miclock.util.CrashHandler.getCrashTimestamp(this)
            val filename = io.github.miclock.util.CrashHandler.getCrashFilename(this) ?: "miclock_debug_logs_crash.zip"

            // Get device info
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val androidVersion = Build.VERSION.RELEASE
            val sdkInt = Build.VERSION.SDK_INT

            // Get app version
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            // Format timestamp
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val timestampFormatted = dateFormat.format(java.util.Date(timestamp))

            // Create issue title
            val title = "[Crash] $exceptionType: ${exceptionMessage.take(50)}"

            // Create issue body (keep it concise to avoid URL length limits)
            val body = buildString {
                appendLine("## Crash Report")
                appendLine()
                appendLine("The app crashed during debug log recording. Debug logs have been automatically collected.")
                appendLine()
                appendLine("**Exception:** $exceptionType: $exceptionMessage")
                appendLine()
                appendLine("**Device:** $manufacturer $model (Android $androidVersion, API $sdkInt)")
                appendLine()
                appendLine("**App Version:** $versionName ($versionCode)")
                appendLine()
                appendLine("**Timestamp:** $timestampFormatted")
                appendLine()
                appendLine("**Stack Trace (first 5 lines):**")
                appendLine("```")
                appendLine(stackTrace)
                appendLine("```")
                appendLine()
                appendLine("## Steps to Reproduce")
                appendLine()
                appendLine("(Please describe what you were doing when the crash occurred)")
                appendLine()
                appendLine("## Debug Logs")
                appendLine()
                appendLine("**Important:** Please attach the crash log file from Downloads/miclock_logs/$filename")
                appendLine()
                appendLine("The file is located at: Downloads/miclock_logs/$filename")
            }

            // Limit body length to avoid URL length issues (max ~2000 chars)
            val bodyTruncated = if (body.length > 2000) {
                body.take(1900) + "\n\n[Content truncated - see attached log file for full details]"
            } else {
                body
            }

            // Build GitHub issue URL
            val githubUrl = Uri.parse("https://github.com/Dan8Oren/MicLock/issues/new").buildUpon()
                .appendQueryParameter("labels", "bug,crash")
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", bodyTruncated)
                .build()

            // Open browser
            val intent = Intent(Intent.ACTION_VIEW, githubUrl)
            startActivity(intent)

            // Clear crash logs after opening GitHub
            io.github.miclock.util.CrashHandler.clearPendingCrashLogs(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open GitHub issue", e)
            android.widget.Toast.makeText(this, "Failed to open GitHub: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the share sheet with the crash log file.
     * Allows users to share crash logs via email, Drive, etc.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun shareCrashLogs() {
        try {
            val uriString = io.github.miclock.util.CrashHandler.getCrashLogUri(this)
            if (uriString == null) {
                android.widget.Toast.makeText(this, "Crash log file not found", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val uri = Uri.parse(uriString)
            val filename = io.github.miclock.util.CrashHandler.getCrashFilename(this) ?: "crash logs"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Mic-Lock Crash Logs")
                putExtra(Intent.EXTRA_TITLE, filename)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Crash logs from Mic-Lock app. " +
                        "The app crashed during debug recording and logs were automatically collected.",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(filename, uri)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Crash Logs"))

            // Clear crash logs after sharing
            io.github.miclock.util.CrashHandler.clearPendingCrashLogs(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to share crash logs", e)
            android.widget.Toast.makeText(
                this,
                "Failed to share crash logs: ${e.message}",
                android.widget.Toast.LENGTH_LONG,
            ).show()
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

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (ApiGuard.isApi33_Tiramisu_OrAbove()) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val postNotificationsGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            return notificationManager.areNotificationsEnabled() && postNotificationsGranted
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return notificationManager.areNotificationsEnabled()
        }
    }

    private fun hasAllPerms(): Boolean {
        // Only microphone permission is required
        // Notification permission is optional (nice to have)
        return hasMicPermission()
    }

    private fun checkPermissionsOnStart() {
        if (!ApiGuard.isApi28_P_OrAbove()) {
            Log.d("MainActivity", "Skipping permission check on pre-P device")
            return
        }

        // Check if microphone permission is missing (required)
        if (!hasMicPermission()) {
            showMicrophonePermissionRequiredDialog()
            return
        }

        // Optionally request notification permission if not granted (nice to have)
        // Only request once per app session
        if (!hasRequestedNotificationPermission && !hasNotificationPermission() && ApiGuard.isApi33_Tiramisu_OrAbove()) {
            hasRequestedNotificationPermission = true
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED ||
                !notificationManager.areNotificationsEnabled()
            ) {
                // Request notification permission, but don't block app usage if denied
                isShowingPermissionDialog = true
                reqPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun showMicrophonePermissionRequiredDialog() {
        if (isShowingPermissionDialog) return
        isShowingPermissionDialog = true

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Microphone Permission Required")
            .setMessage(getString(R.string.mic_permission_required_message))
            .setPositiveButton("Grant Permission") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                isWaitingForPermissionFromSettings = true
                // Don't reset flag here - let the permission callback handle it
                reqPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            .setNegativeButton("Exit") { _, _ ->
                isShowingPermissionDialog = false
                isWaitingForPermissionFromSettings = false
                finish()
            }
            .setOnDismissListener {
                // Don't auto-exit when dialog is dismissed
                // Only exit if user explicitly clicked "Exit"
                isShowingPermissionDialog = false
            }
            .setCancelable(false)
            .show()
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

        // Check if service is already running before starting
        val wasRunning = MicLockService.state.value.isRunning

        val intent = Intent(this, MicLockService::class.java).apply {
            action = MicLockService.ACTION_START_USER_INITIATED
        }
        ContextCompat.startForegroundService(this, intent)

        // Only finish if service wasn't already running (initial tile click)
        // If service was already running, user likely reopened from recents - stay open
        if (!wasRunning) {
            Log.d("MainActivity", "Service started from tile - closing activity")
            finish()
        } else {
            Log.d("MainActivity", "Service already running - keeping activity open (likely reopened from recents)")
        }
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
