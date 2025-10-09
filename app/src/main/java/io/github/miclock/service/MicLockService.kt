package io.github.miclock.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.service.quicksettings.TileService
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.miclock.R
import io.github.miclock.audio.AudioSelector
import io.github.miclock.audio.MediaRecorderHolder
import io.github.miclock.data.Prefs
import io.github.miclock.receiver.ScreenStateReceiver
import io.github.miclock.service.DelayedActivationManager
import io.github.miclock.service.model.ServiceState
import io.github.miclock.tile.MicLockTileService
import io.github.miclock.ui.MainActivity
import io.github.miclock.util.WakeLockManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Represents the current state of the MicLockService.
 * * @property isRunning Whether the service is currently active
 * @property isPausedBySilence Whether the service is paused because another app is using the microphone
 * @property currentDeviceAddress The address of the currently held microphone device
 */

/**
 * MicLockService is the core background service that manages microphone access to work around
 * faulty hardware microphones, commonly found on devices like Google Pixel phones after screen replacements.
 *
 * The service operates by:
 * 1. Acquiring a connection to a working microphone (typically the earpiece mic)
 * 2. Holding this connection politely in the background
 * 3. Yielding to other applications when they need microphone access
 * 4. Ensuring other apps inherit the correctly routed microphone path
 *
 * This solves the problem where apps default to a broken bottom microphone and record silence.
 */
class MicLockService : Service(), MicActivationService {

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }

    // Optional: light CPU wake while actively recording
    private val wakeLockManager by lazy { WakeLockManager(this, "MicLockService") }

    // Delayed activation manager for screen-on delay handling
    private lateinit var delayedActivationManager: DelayedActivationManager

    private var loopJob: Job? = null
    private val stopFlag = AtomicBoolean(false)

    // Silencing state (per run)
    @Volatile private var isSilenced: Boolean = false
    private var markCooldownStart: Long? = null
    private var backoffMs: Long = 500L
    private var recCallback: AudioManager.AudioRecordingCallback? = null

    // MediaRecorder fallback
    private var mediaRecorderHolder: MediaRecorderHolder? = null

    // Dynamic screen state receiver
    private var screenStateReceiver: ScreenStateReceiver? = null

    // Android 14+ foreground service restriction handling
    private var isStartedFromBoot = false
    private var serviceHealthy = false

    private var startFailureReason: String? = null
    private var suppressRestartNotification = false

    @RequiresApi(Build.VERSION_CODES.O)
    /**
     * Called when the service is first created. Initializes notification channels and
     * registers the screen state receiver for managing service lifecycle.
     */
    override fun onCreate() {
        super.onCreate()
        createChannels()

        // Initialize delayed activation manager
        delayedActivationManager = DelayedActivationManager(this, this, scope)
        Log.d(TAG, "DelayedActivationManager initialized")

        // Register screen state receiver dynamically
        screenStateReceiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        Log.d(TAG, "ScreenStateReceiver registered dynamically")
    }

    private fun createRestartNotification() {
        val restartIntent = Intent(this, MicLockService::class.java).apply {
            action = ACTION_START_USER_INITIATED
        }
        val restartPI = PendingIntent.getService(
            this,
            4,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(this, RESTART_CHANNEL_ID)
            .setContentTitle("Mic-Lock Stopped")
            .setContentText("Tap to restart microphone protection")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(restartPI)
            .setAutoCancel(true)
            .build()

        notifManager.notify(RESTART_NOTIF_ID, notification)
    }

    private fun broadcastTileStartFailure(reason: String) {
        val failureIntent = Intent(ACTION_TILE_START_FAILED).apply {
            putExtra(EXTRA_FAILURE_REASON, reason)
            setPackage(packageName)
        }
        sendBroadcast(failureIntent)
        Log.d(TAG, "Broadcasted tile start failure: $reason")
    }

    override fun onDestroy() {
        super.onDestroy()
        val wasRunning = state.value.isRunning
        stopFlag.set(true)
        
        // Cleanup delayed activation manager
        if (::delayedActivationManager.isInitialized) {
            delayedActivationManager.cleanup()
            Log.d(TAG, "DelayedActivationManager cleaned up")
        }
        
        scope.cancel()
        wakeLockManager.release()
        try { recCallback?.let { audioManager.unregisterAudioRecordingCallback(it) } } catch (_: Throwable) {}
        recCallback = null
        mediaRecorderHolder?.stopRecording()
        mediaRecorderHolder = null

        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "ScreenStateReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering ScreenStateReceiver: ${e.message}")
            }
        }
        screenStateReceiver = null

        updateServiceState(running = false, paused = false, pausedByScreenOff = false, deviceAddr = null)

        if (wasRunning && !suppressRestartNotification) {
            createRestartNotification()
        } else if (suppressRestartNotification) {
            Log.d(TAG, "Restart notification suppressed due to tile fallback scenario")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleStartUserInitiated(intent: Intent?) {
        notifManager.cancel(RESTART_NOTIF_ID)
        Log.i(TAG, "Received ACTION_START_USER_INITIATED - user-initiated start")

        // Check if this is a manual override from tile during delay
        val isCancelDelay = intent?.getBooleanExtra("cancel_delay", false) ?: false
        if (isCancelDelay) {
            Log.i(TAG, "Manual override requested - cancelling delay and starting immediately")
        }

        // Cancel any pending delayed activation when user manually starts service
        if (::delayedActivationManager.isInitialized) {
            val wasCancelled = delayedActivationManager.cancelDelayedActivation()
            if (wasCancelled) {
                Log.d(TAG, "Cancelled pending delayed activation due to manual user start")
                updateServiceState(delayPending = false, delayRemainingMs = 0)
            }
        }

        val isFromTile = intent?.getBooleanExtra("from_tile", false) ?: false

        if (!state.value.isRunning) {
            isStartedFromBoot = false
            Log.i(TAG, "Starting service from user action - immediate foreground activation")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification("Starting…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                } else {
                    startForeground(NOTIF_ID, buildNotification("Starting…"))
                }
                serviceHealthy = true
                Log.d(TAG, "Foreground service started successfully")

                startFailureReason = null
                suppressRestartNotification = false

                startMicHolding(fromDelayCompletion = false)
                updateServiceState(running = true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
                serviceHealthy = false

                val errorMessage = e.message ?: ""
                if (errorMessage.contains("FOREGROUND_SERVICE_MICROPHONE") ||
                    errorMessage.contains("requires permissions") ||
                    errorMessage.contains("eligible state")
                ) {
                    startFailureReason = FAILURE_REASON_FOREGROUND_RESTRICTION
                    suppressRestartNotification = true

                    if (isFromTile) {
                        broadcastTileStartFailure(FAILURE_REASON_FOREGROUND_RESTRICTION)
                    }
                }

                updateServiceState(running = false)
                stopSelf()
                return
            }
        } else {
            Log.i(TAG, "Service already running - activating foreground mode for user action")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification("Mic-Lock activated by user"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                } else {
                    startForeground(NOTIF_ID, buildNotification("Mic-Lock activated by user"))
                }
                serviceHealthy = true
                Log.d(TAG, "Foreground service started successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
                serviceHealthy = false
                updateServiceState(running = false)
                createRestartNotification()
                stopSelf()
                return
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleStartHolding(intent: Intent? = null) {
        val eventTimestamp = intent?.getLongExtra(ScreenStateReceiver.EXTRA_EVENT_TIMESTAMP, 0L) ?: 0L
        Log.i(TAG, "Received ACTION_START_HOLDING, isRunning: ${state.value.isRunning}, timestamp: $eventTimestamp")
        
        if (state.value.isRunning) {
            // Get configured delay
            val delayMs = Prefs.getScreenOnDelayMs(this)
            
            // Check if delay should be applied
            if (delayMs > 0 && delayedActivationManager.shouldApplyDelay()) {
                Log.d(TAG, "Applying screen-on delay of ${delayMs}ms")
                
                // Cancel any existing pending activation (latest-event-wins strategy)
                if (delayedActivationManager.isActivationPending()) {
                    Log.d(TAG, "Cancelling previous pending activation - restarting delay from beginning")
                    delayedActivationManager.cancelDelayedActivation()
                }
                
                // CRITICAL: Start foreground service BEFORE delay to satisfy Android 14+ FGS restrictions
                // The service must be started from an eligible state/context (screen-on event)
                if (canStartForegroundService()) {
                    try {
                        val countdownText = "Starting in ${delayMs / 1000.0}s…"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIF_ID,
                                buildNotification(countdownText),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                            )
                        } else {
                            startForeground(NOTIF_ID, buildNotification(countdownText))
                        }
                        serviceHealthy = true
                        Log.d(TAG, "Foreground service started with countdown notification")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not start foreground service during delay: ${e.message}")
                        serviceHealthy = false
                        updateServiceState(running = false)
                        createRestartNotification()
                        stopSelf()
                        return
                    }
                } else {
                    Log.d(TAG, "Delaying foreground service start due to boot restrictions")
                    scheduleDelayedForegroundStart()
                }
                
                // Schedule delayed activation
                val scheduled = delayedActivationManager.scheduleDelayedActivation(delayMs)
                
                if (scheduled) {
                    // Update state to reflect pending activation
                    updateServiceState(
                        delayPending = true,
                        delayRemainingMs = delayMs
                    )
                    Log.d(TAG, "Delayed activation scheduled successfully")
                } else {
                    // Delay not applicable, start immediately
                    Log.d(TAG, "Delay not applicable, starting immediately")
                    startMicHolding(fromDelayCompletion = false)
                }
            } else {
                // No delay configured or delay not applicable, start immediately
                Log.d(TAG, "No delay configured (${delayMs}ms), starting immediately")
                startMicHolding(fromDelayCompletion = false)
            }
        } else {
            Log.w(TAG, "Service not running, ignoring START_HOLDING action. (Consider starting service first)")
        }
    }

    private fun handleStopHolding(intent: Intent? = null) {
        val eventTimestamp = intent?.getLongExtra(ScreenStateReceiver.EXTRA_EVENT_TIMESTAMP, 0L) ?: 0L
        Log.i(TAG, "Received ACTION_STOP_HOLDING, timestamp: $eventTimestamp")
        
        // Check if always-on mode is enabled
        if (::delayedActivationManager.isInitialized && delayedActivationManager.isAlwaysOnMode()) {
            Log.d(TAG, "Always-on mode enabled, ignoring screen-off event")
            return
        }
        
        // Cancel any pending delayed activation
        if (::delayedActivationManager.isInitialized) {
            val wasCancelled = delayedActivationManager.cancelDelayedActivation()
            if (wasCancelled) {
                Log.d(TAG, "Cancelled pending delayed activation due to screen-off")
                updateServiceState(
                    delayPending = false,
                    delayRemainingMs = 0
                )
            }
        }
        
        stopMicHolding()
    }

    private fun handleStop(): Int {
        // Cancel any pending delayed activation when service is manually stopped
        if (::delayedActivationManager.isInitialized) {
            val wasCancelled = delayedActivationManager.cancelDelayedActivation()
            if (wasCancelled) {
                Log.d(TAG, "Cancelled pending delayed activation due to manual stop")
            }
        }
        
        updateServiceState(running = false, delayPending = false, delayRemainingMs = 0)
        stopMicHolding()
        stopSelf() // Full stop from user
        return START_NOT_STICKY
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleReconfigure() {
        if (state.value.isRunning) {
            restartLoop("Reconfigure requested from UI")
        }
    }

    private fun handleBootStart() {
        if (!state.value.isRunning) {
            isStartedFromBoot = true
            updateServiceState(running = true)
            Log.i(TAG, "Service started from boot - waiting for screen state events")
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    /**
     * Handles incoming intents to control the service behavior.
     * * @param intent The Intent supplied to start the service
     * @param flags Additional data about this start request
     * @param startId A unique integer representing this specific request to start
     * @return START_STICKY to indicate the service should be restarted if killed
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called with action: ${intent?.action}, isRunning: ${state.value.isRunning}")

        if (!hasAllRequirements()) {
            Log.w(TAG, "Missing permission or notifications disabled. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_USER_INITIATED -> handleStartUserInitiated(intent)
            ACTION_START_HOLDING -> handleStartHolding(intent)
            ACTION_STOP_HOLDING -> handleStopHolding(intent)
            ACTION_STOP -> return handleStop()
            ACTION_RECONFIGURE -> handleReconfigure()
            null -> handleBootStart()
            else -> Log.w(TAG, "Unknown action received: ${intent.action}")
        }

        return START_STICKY
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    private fun restartLoop(reason: String) {
        Log.i(TAG, "Restarting loop: $reason")
        stopFlag.set(true)
        loopJob?.cancel()
        unregisterRecordingCallback(recCallback)
        recCallback = null
        isSilenced = false
        stopFlag.set(false)
        loopJob = scope.launch { holdSelectedMicLoop() }
    }

    private fun hasAllRequirements(): Boolean {
        val mic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notifs = notifManager.areNotificationsEnabled()
        return mic && notifs
    }

    private fun registerRecordingCallback(cb: AudioManager.AudioRecordingCallback) {
        audioManager.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startMicHolding(fromDelayCompletion: Boolean) {
        // This check is important. If the loop is active, we don't need to do anything.
        if (loopJob?.isActive == true) {
            Log.d(TAG, "Mic holding is already active.")
            return
        }
        // Change the log message to be more generic for screen on or user start
        Log.i(TAG, "Starting or resuming mic holding logic. fromDelayCompletion=$fromDelayCompletion")
        
        // Clear any delay state since we're actually starting now
        if (fromDelayCompletion || (::delayedActivationManager.isInitialized && delayedActivationManager.isActivationPending())) {
            Log.d(TAG, "Clearing delay state as mic holding is starting (fromDelayCompletion=$fromDelayCompletion)")
            updateServiceState(delayPending = false, delayRemainingMs = 0)
        }

        // Only start foreground service if NOT called from delay completion
        // When called from delay completion, foreground service was already started in handleStartHolding
        if (!fromDelayCompletion) {
            // Try to start foreground service based on current conditions
            if (canStartForegroundService()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIF_ID,
                            buildNotification("Starting…"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                        )
                    } else {
                        startForeground(NOTIF_ID, buildNotification("Starting…"))
                    }
                    serviceHealthy = true
                    Log.d(TAG, "Foreground service started successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start foreground service: ${e.message}")
                    serviceHealthy = false
                    updateServiceState(running = false)
                    createRestartNotification()
                    stopSelf()
                    return
                }
            } else {
                Log.d(TAG, "Delaying foreground service start due to boot restrictions")
                scheduleDelayedForegroundStart()
            }
        } else {
            Log.d(TAG, "Skipping startForeground call - service already in foreground from delay scheduling")
            // Update notification to show activation is complete
            updateNotification("Recording active")
        }

        stopFlag.set(false)
        // Only update state if service is healthy
        if (serviceHealthy) {
            updateServiceState(paused = false, pausedByScreenOff = false, running = true)
        } else {
            updateServiceState(paused = false, pausedByScreenOff = false, running = false)
        }
        loopJob = scope.launch { holdSelectedMicLoop() }
    }

    private fun stopMicHolding() {
        if (loopJob == null && !state.value.isPausedBySilence) return // Avoid redundant calls
        Log.i(TAG, "Screen is OFF. Pausing mic holding logic.")
        stopFlag.set(true)
        loopJob?.cancel()
        loopJob = null

        // Release all resources
        wakeLockManager.release()
        unregisterRecordingCallback(recCallback)
        recCallback = null
        mediaRecorderHolder?.stopRecording()
        mediaRecorderHolder = null
        updateServiceState(deviceAddr = null, pausedByScreenOff = true) // Mark as paused by screen-off

        updateNotification("Paused (Screen off)")
    }

    private fun unregisterRecordingCallback(cb: AudioManager.AudioRecordingCallback?) {
        if (cb != null) {
            try { audioManager.unregisterAudioRecordingCallback(cb) } catch (_: Throwable) {}
        }
    }

    private fun canStartForegroundService(): Boolean {
        // Allow foreground service if enough time has passed since boot
        // or if not started from BOOT_COMPLETED
        return !isStartedFromBoot || (SystemClock.elapsedRealtime() > 10_000) // 10 seconds after boot
    }

    private fun scheduleDelayedForegroundStart() {
        scope.launch {
            delay(10_000) // Wait 10 seconds
            if (state.value.isRunning && !stopFlag.get()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIF_ID,
                            buildNotification("Recording active"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                        )
                    } else {
                        startForeground(NOTIF_ID, buildNotification("Recording active"))
                    }
                    serviceHealthy = true
                    Log.i(TAG, "Delayed foreground service start successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service after delay: ${e.message}", e)
                    serviceHealthy = false
                    updateServiceState(running = false)
                    createRestartNotification()
                    stopSelf()
                }
            }
        }
    }

    /**
     * Helper function to determine if any other app is currently recording and not silenced.
     * This is the core of the "polite holder" strategy.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun othersRecording(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        val configs = audioManager.activeRecordingConfigurations
        val hasOtherActiveRecorders = configs.any { cfg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                !cfg.isClientSilenced
            } else {
                true
            }
        }
        Log.d(TAG, "Checking for othersRecording: $hasOtherActiveRecorders (configs: ${configs.size} total)")
        return hasOtherActiveRecorders
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun holdSelectedMicLoop() {
        while (!stopFlag.get()) {
            if (isSilenced) {
                val cooldownDuration = 3000L
                val timeSinceSilenced = markCooldownStart?.let { System.currentTimeMillis() - it } ?: 0L
                Log.d(TAG, "Silenced state detected. Time since silenced: $timeSinceSilenced ms")

                if (timeSinceSilenced < cooldownDuration) {
                    Log.d(TAG, "Still in cooldown period. Waiting ${cooldownDuration - timeSinceSilenced} ms.")
                    delay(300)
                    continue
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && othersRecording()) {
                    Log.d(TAG, "Other active recorders detected. Applying backoff. Current backoff: $backoffMs ms.")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(5000L)
                    continue
                } else {
                    Log.d(TAG, "No other active recorders. Resetting silenced state and backoff.")
                    isSilenced = false
                    updateServiceState(paused = false)
                    backoffMs = 500L
                }
            }

            isSilenced = false
            updateServiceState(paused = false)

            val useMediaRecorderPref = Prefs.getUseMediaRecorder(this)

            var primaryAttemptSuccessful = false
            var fallbackAttemptSuccessful = false

            if (useMediaRecorderPref) {
                Log.d(TAG, "User prefers MediaRecorder. Attempting MediaRecorder mode...")
                if (tryMediaRecorderMode()) {
                    Prefs.setLastRecordingMethod(this, "MediaRecorder")
                    backoffMs = 500L
                    primaryAttemptSuccessful = true
                } else {
                    Log.w(TAG, "MediaRecorder failed. Attempting AudioRecord as fallback...")
                    val audioRecordResult = tryAudioRecordMode()
                    when (audioRecordResult) {
                        AudioRecordResult.SUCCESS -> {
                            Prefs.setLastRecordingMethod(this, "AudioRecord")
                            fallbackAttemptSuccessful = true
                        }
                        AudioRecordResult.BAD_ROUTE -> {
                            Log.w(TAG, "AudioRecord fallback landed on bad route. No further fallback specified.")
                        }
                        AudioRecordResult.FAILED -> {
                            Log.e(TAG, "AudioRecord fallback also failed.")
                        }
                    }
                }
            } else {
                Log.d(TAG, "User prefers AudioRecord. Attempting AudioRecord mode...")
                val audioRecordResult = tryAudioRecordMode()
                when (audioRecordResult) {
                    AudioRecordResult.SUCCESS -> {
                        Prefs.setLastRecordingMethod(this, "AudioRecord")
                        backoffMs = 500L
                        primaryAttemptSuccessful = true
                    }
                    AudioRecordResult.BAD_ROUTE -> {
                        Log.w(TAG, "AudioRecord landed on bad route. Attempting MediaRecorder as fallback...")
                        if (tryMediaRecorderMode()) {
                            Prefs.setLastRecordingMethod(this, "MediaRecorder")
                            backoffMs = 500L
                            fallbackAttemptSuccessful = true
                        } else {
                            Log.e(TAG, "MediaRecorder fallback also failed.")
                        }
                    }
                    AudioRecordResult.FAILED -> {
                        Log.e(TAG, "AudioRecord failed. No explicit fallback for this path, retrying...")
                    }
                }
            }

            if (!primaryAttemptSuccessful && !fallbackAttemptSuccessful) {
                delay(2_000)
                continue
            }

            if (isSilenced && !stopFlag.get()) {
                var waited = 0
                while (isSilenced && !stopFlag.get() && waited < 10_000) {
                    delay(200)
                    waited += 200
                }
                delay(300)
                continue
            }

            delay(300)
        }
    }

    private enum class AudioRecordResult {
        SUCCESS, BAD_ROUTE, FAILED
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun tryAudioRecordMode(): AudioRecordResult {
        val candidates = AudioSelector.getAudioFormatCandidates()
        var lastError: Exception? = null

        for (candidate in candidates) {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    candidate.sampleRate,
                    candidate.channelMask,
                    candidate.encoding,
                )
                if (minBuf <= 0) {
                    Log.w(
                        TAG,
                        "Unsupported format: ${candidate.sampleRate}Hz, ${AudioSelector.channelName(
                            candidate.channelMask,
                        )}",
                    )
                    continue
                }
                val bufferBytes = (minBuf * 2).coerceAtLeast(4096)

                val fmt = AudioFormat.Builder()
                    .setSampleRate(candidate.sampleRate)
                    .setEncoding(candidate.encoding)
                    .setChannelMask(candidate.channelMask)
                    .build()

                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()

                Log.d(TAG, "No specific device set - letting Android choose audio route")

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    continue
                }

                recorder.startRecording()
                wakeLockManager.acquire()

                val recordingSessionId = recorder.audioSessionId
                Log.d(TAG, "AudioRecord session ID: $recordingSessionId")

                val actualChannelCount = recorder.format.channelCount
                Log.d(
                    TAG,
                    "AudioRecord actual channel count: $actualChannelCount " +
                        "(requested: ${if (candidate.channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1})",
                )

                delay(100)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val routeInfo = AudioSelector.validateCurrentRoute(audioManager, recordingSessionId)
                    if (routeInfo != null) {
                        Log.d(TAG, "Route validation: ${AudioSelector.getRouteDebugInfo(routeInfo)}")
                        updateServiceState(deviceAddr = routeInfo.deviceAddress)

                        val isBottomMic = routeInfo.micInfo?.let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.position != null) {
                                it.position.y < 0.0f
                            } else {
                                false
                            }
                        } ?: false

                        Log.d(
                            TAG,
                            "Route validation details: channels=$actualChannelCount, " +
                                "isOnPrimaryArray=${routeInfo.isOnPrimaryArray}, " +
                                "isBottomMic=$isBottomMic",
                        )

                        val isBadRoute = AudioSelector.isRouteBad(
                            routeInfo,
                            candidate.channelMask == AudioFormat.CHANNEL_IN_STEREO,
                            actualChannelCount,
                        )

                        if (isBadRoute) {
                            val reason = when {
                                !routeInfo.isOnPrimaryArray && isBottomMic ->
                                    "Bottom microphone detected (Y=${routeInfo.micInfo?.position?.y})"
                                !routeInfo.isOnPrimaryArray -> "Non-primary array microphone"
                                actualChannelCount < 2 -> "Single-channel route when multi-channel was requested"
                                else -> "Unknown bad route condition"
                            }
                            Log.w(
                                TAG,
                                "Bad route detected with ${candidate.sampleRate}Hz config: " +
                                    "$reason - trying next candidate",
                            )

                            wakeLockManager.release()
                            recorder.release()
                            continue
                        }
                    }
                }

                recCallback = object : AudioManager.AudioRecordingCallback() {
                    @RequiresApi(Build.VERSION_CODES.Q)
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        val mine = configs.firstOrNull { it.clientAudioSessionId == recordingSessionId } ?: return
                        val silenced = mine.isClientSilenced
                        if (silenced && !isSilenced) {
                            isSilenced = true
                            updateServiceState(paused = true)
                            Log.i(TAG, "AudioRecord silenced by system (other app using mic).")
                            markCooldownStart = System.currentTimeMillis()
                            updateNotification("Paused — mic in use by another app")
                            try { recorder.stop() } catch (_: Throwable) {}
                        } else if (!silenced && isSilenced) {
                            Log.i(TAG, "AudioRecord unsilenced; will resume (handled by main loop).")
                        }
                    }
                }
                registerRecordingCallback(recCallback!!)

                val deviceDesc = "AudioRecord | Auto selection"
                updateNotification(
                    "Recording @${recorder.sampleRate / 1000}kHz " +
                        "${AudioSelector.channelName(recorder.format.channelMask)} — $deviceDesc",
                )

                val buf = ShortArray(bufferBytes / 2)
                while (!stopFlag.get()) {
                    if (isSilenced) break
                    val n = recorder.read(
                        buf,
                        0,
                        buf.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord read() returned $n")
                        delay(40)
                    }
                }

                Log.i(
                    TAG,
                    "Good route found with ${candidate.sampleRate}Hz, " +
                        AudioSelector.channelName(candidate.channelMask),
                )
                return AudioRecordResult.SUCCESS
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord error with ${candidate.sampleRate}Hz: ${t.message}", t)
                lastError = t as? Exception ?: Exception(t)
            } finally {
                recorder?.release()
                wakeLockManager.release()
            }
        }

        Log.e(TAG, "All AudioRecord candidates failed. Last error: ${lastError?.message}", lastError)
        updateNotification("Mic-Lock idle (AudioRecord error: ${lastError?.javaClass?.simpleName}). Retrying…")
        return AudioRecordResult.FAILED
    }

    private suspend fun tryMediaRecorderMode(): Boolean {
        return try {
            mediaRecorderHolder = MediaRecorderHolder(
                this,
                audioManager,
                wakeLockManager = WakeLockManager(this, "MediaRecorderHolder"),
            ) { silenced ->
                if (silenced && !isSilenced) {
                    isSilenced = true
                    updateServiceState(paused = true)
                    Log.i(TAG, "MediaRecorder silenced by system (other app using mic).")
                    markCooldownStart = System.currentTimeMillis()
                    updateNotification("Paused — mic in use by another app")
                } else if (!silenced && isSilenced) {
                    Log.i(TAG, "MediaRecorder unsilenced; will resume (handled by main loop).")
                }
            }

            mediaRecorderHolder!!.startRecording()
            updateServiceState(deviceAddr = "MediaRecorder")

            updateNotification("Recording (MediaRecorder compatibility mode)")

            while (!stopFlag.get() && !isSilenced) {
                delay(100)
            }

            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "MediaRecorder error: ${t.message}", t)
            updateNotification("Mic-Lock idle (MediaRecorder error: ${t.javaClass.simpleName}). Retrying…")
            false
        } finally {
            mediaRecorderHolder?.stopRecording()
            mediaRecorderHolder = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            "Mic Lock Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notifManager.createNotificationChannel(statusChannel)

        val restartChannel = NotificationChannel(
            RESTART_CHANNEL_ID,
            "Mic Lock Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { setShowBadge(true) }
        notifManager.createNotificationChannel(restartChannel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 1, openIntent, flags)

        val reconfigPI = PendingIntent.getService(
            this,
            2,
            Intent(this, MicLockService::class.java).setAction(ACTION_RECONFIGURE),
            flags,
        )
        val stopPI = PendingIntent.getService(
            this,
            3,
            Intent(this, MicLockService::class.java).setAction(ACTION_STOP),
            flags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mic-Lock")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prevents dismissal
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .addAction(0, "Recheck", reconfigPI)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    private fun updateNotification(text: String) {
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun updateServiceState(
        running: Boolean? = null, 
        paused: Boolean? = null, 
        pausedByScreenOff: Boolean? = null,
        deviceAddr: String? = null,
        delayPending: Boolean? = null,
        delayRemainingMs: Long? = null
    ) {
        _state.update { currentState ->
            currentState.copy(
                isRunning = running ?: currentState.isRunning,
                isPausedBySilence = paused ?: currentState.isPausedBySilence,
                isPausedByScreenOff = pausedByScreenOff ?: currentState.isPausedByScreenOff,
                currentDeviceAddress = deviceAddr ?: currentState.currentDeviceAddress,
                isDelayedActivationPending = delayPending ?: currentState.isDelayedActivationPending,
                delayedActivationRemainingMs = delayRemainingMs ?: currentState.delayedActivationRemainingMs,
            )
        }
        
        // Request tile update whenever service state changes
        requestTileUpdate()
    }

    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val componentName = ComponentName(this, MicLockTileService::class.java)
                TileService.requestListeningState(this, componentName)
                Log.d(TAG, "Requested tile update")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request tile update: ${e.message}")
            }
        }
    }

    /**
     * Gets the current service state.
     * Used by DelayedActivationManager for state validation.
     * 
     * @return current ServiceState
     */
    override fun getCurrentState(): ServiceState = state.value

    /**
     * Checks if the service was manually stopped by the user.
     * This is determined by checking if the service is not running and was explicitly stopped.
     * 
     * @return true if manually stopped by user, false otherwise
     */
    override fun isManuallyStoppedByUser(): Boolean {
        val currentState = state.value
        // Service is considered manually stopped if it's not running and not paused by silence
        // This indicates user intentionally stopped it rather than system pausing it
        return !currentState.isRunning && !currentState.isPausedBySilence
    }

    /**
     * Checks if the microphone is actively being held (recording loop is active).
     * This is different from service running - service can be running but paused (screen off).
     * 
     * @return true if mic is actively held, false otherwise
     */
    override fun isMicActivelyHeld(): Boolean {
        return loopJob?.isActive == true
    }

    companion object {
        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state.asStateFlow()
        private const val TAG = "MicLockService"
        private const val CHANNEL_ID = "mic_lock_channel"
        const val RESTART_CHANNEL_ID = "mic_lock_restart_channel"
        private const val NOTIF_ID = 42
        private const val RESTART_NOTIF_ID = 43

        const val ACTION_RECONFIGURE = "io.github.miclock.ACTION_RECONFIGURE"
        const val ACTION_STOP = "io.github.miclock.ACTION_STOP"

        const val ACTION_START_HOLDING = "io.github.miclock.ACTION_START_HOLDING"
        const val ACTION_STOP_HOLDING = "io.github.miclock.ACTION_STOP_HOLDING"
        const val ACTION_START_USER_INITIATED = "io.github.miclock.ACTION_START_USER_INITIATED"

        const val ACTION_TILE_START_FAILED = "io.github.miclock.TILE_START_FAILED"
        const val EXTRA_FAILURE_REASON = "failure_reason"
        const val FAILURE_REASON_FOREGROUND_RESTRICTION = "foreground_restriction"
    }
}
