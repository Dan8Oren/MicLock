package com.example.mic_lock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MicLockService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }

    // Optional: light CPU wake while actively recording
    private var wakeLock: PowerManager.WakeLock? = null

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
    private var needsForegroundMode = false

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createChannels()
        
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
            this, 4, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
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

    override fun onDestroy() {
        super.onDestroy()
        val wasRunning = isRunning
        stopFlag.set(true)
        scope.cancel()
        releaseWakeLock()
        try { recCallback?.let { audioManager.unregisterAudioRecordingCallback(it) } } catch (_: Throwable) {}
        recCallback = null
        mediaRecorderHolder?.stopRecording()
        mediaRecorderHolder = null
        
        // Unregister screen state receiver
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "ScreenStateReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering ScreenStateReceiver: ${e.message}")
            }
        }
        screenStateReceiver = null
        
        updateServiceState(running = false, paused = false, deviceAddr = null)

        if (wasRunning) {
            createRestartNotification()
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called with action: ${intent?.action}, isRunning: $isRunning")
        
        if (!hasAllRequirements()) {
            Log.w(TAG, "Missing permission or notifications disabled. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_USER_INITIATED -> {
                notifManager.cancel(RESTART_NOTIF_ID)
                Log.i(TAG, "Received ACTION_START_USER_INITIATED - user-initiated start")
                needsForegroundMode = true
                if (!isRunning) {
                    isStartedFromBoot = false
                    updateServiceState(running = true)
                    Log.i(TAG, "Starting service from user action - immediate foreground activation")
                    startMicHolding()
                } else {
                    Log.i(TAG, "Service already running - activating foreground mode for user action")
                    // Always start foreground when user explicitly starts the service
                    // This satisfies Android's requirement for startForegroundService() calls
                    startForeground(NOTIF_ID, buildNotification("Mic-Lock activated by user"))
                }
            }
            ACTION_START_HOLDING -> {
                Log.i(TAG, "Received ACTION_START_HOLDING, isRunning: $isRunning")
                if (isRunning) {
                    needsForegroundMode = true
                    startMicHolding()
                } else {
                    Log.w(TAG, "Service not running, ignoring START_HOLDING action. (Consider starting service first)")
                }
            }
            ACTION_STOP_HOLDING -> {
                Log.i(TAG, "Received ACTION_STOP_HOLDING")
                needsForegroundMode = false
                stopMicHolding()
            }
            ACTION_STOP -> {
                updateServiceState(running = false)
                needsForegroundMode = false
                stopMicHolding()
                stopSelf() // Full stop from user
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> {
                if (isRunning) {
                    restartLoop("Reconfigure requested from UI")
                }
            }
            // Boot-initiated start (no explicit action from BOOT_COMPLETED)
            null -> {
                if (!isRunning) {
                    isStartedFromBoot = true
                    updateServiceState(running = true)
                    Log.i(TAG, "Service started from boot - waiting for screen state events")
                    // Don't start mic holding immediately from boot to avoid foreground service restriction
                }
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent?.action}")
            }
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
        loopJob = scope.launch  { holdSelectedMicLoop() }
    }

    private fun hasAllRequirements(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifs = notifManager.areNotificationsEnabled()
        return mic && notifs
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:rec").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Throwable) {}
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { try { if (it.isHeld) it.release() } catch (_: Throwable) {} }
        wakeLock = null
    }

    private fun registerRecordingCallback(cb: AudioManager.AudioRecordingCallback) {
        audioManager.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
    }

        @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicHolding() {
        if (loopJob?.isActive == true) {
            Log.d(TAG, "Mic holding is already active.")
            return
        }
        Log.i(TAG, "Screen is ON. Starting mic holding logic.")
        
        // For user-initiated starts, always try to start foreground immediately
        // For boot-initiated starts, use delayed activation to comply with Android 14+ restrictions
        if (!isStartedFromBoot) {
            Log.i(TAG, "User-initiated start - attempting foreground service activation")
            try {
                startForeground(NOTIF_ID, buildNotification("Starting…"))
                Log.i(TAG, "Successfully activated foreground service for user-initiated start")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service for user-initiated start: ${e.message}")
                // Continue with mic holding even if foreground service fails
            }
        } else if (canStartForegroundService()) {
            Log.i(TAG, "Boot-initiated start - attempting foreground service activation")
            try {
                startForeground(NOTIF_ID, buildNotification("Starting…"))
                Log.i(TAG, "Successfully activated foreground service for boot-initiated start")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service for boot-initiated start: ${e.message}")
                // Continue with mic holding even if foreground service fails
            }
        } else {
            Log.w(TAG, "Boot-initiated start - delaying foreground service start due to Android 14+ restriction")
            scheduleDelayedForegroundStart()
        }
        
        stopFlag.set(false)
        isPausedBySilence = false
        loopJob = scope.launch { holdSelectedMicLoop() }
    }

    private fun stopMicHolding() {
        if (loopJob == null) return
        Log.i(TAG, "Screen is OFF. Stopping mic holding logic.")
        stopFlag.set(true)
        loopJob?.cancel()
        loopJob = null

        // Release all resources
        releaseWakeLock()
        unregisterRecordingCallback(recCallback)
        recCallback = null
        mediaRecorderHolder?.stopRecording()
        mediaRecorderHolder = null
        currentDeviceAddress = null

        // Stop the foreground service to remove the notification, but keep the service alive.
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun unregisterRecordingCallback(cb: AudioManager.AudioRecordingCallback?) {
        if (cb != null) {
            try { audioManager.unregisterAudioRecordingCallback(cb) } catch (_: Throwable) {}
        }
    }
    
    private fun canStartForegroundService(): Boolean {
        // Allow foreground service if enough time has passed since boot
        // or if not started from BOOT_COMPLETED
        return !isStartedFromBoot || 
               (SystemClock.elapsedRealtime() > 10_000) // 10 seconds after boot
    }
    
    private fun scheduleDelayedForegroundStart() {
        scope.launch {
            delay(10_000) // Wait 10 seconds
            if (needsForegroundMode && !stopFlag.get()) {
                try {
                    startForeground(NOTIF_ID, buildNotification("Recording active"))
                    Log.i(TAG, "Delayed foreground service start successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service after delay: ${e.message}", e)
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
                    Log.d(TAG, "Other active recorders detected. Applying backoff. Current backoff: ${backoffMs} ms.")
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
                val minBuf = AudioRecord.getMinBufferSize(candidate.sampleRate, candidate.channelMask, candidate.encoding)
                if (minBuf <= 0) {
                    Log.w(TAG, "Unsupported format: ${candidate.sampleRate}Hz, ${AudioSelector.channelName(candidate.channelMask)}")
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
                    updateNotification("Mic-Lock idle (AudioRecord init failed). Retrying…")
                    recorder.release()
                    recorder = null
                    continue
                }

                recorder.startRecording()
                acquireWakeLock()
                
                val recordingSessionId = recorder.audioSessionId
                Log.d(TAG, "AudioRecord session ID: $recordingSessionId")
                
                val actualChannelCount = recorder.format.channelCount
                Log.d(TAG, "AudioRecord actual channel count: $actualChannelCount (requested: ${if (candidate.channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1})")
                
                delay(100)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val routeInfo = AudioSelector.validateCurrentRoute(audioManager, recordingSessionId)
                    if (routeInfo != null) {
                        Log.d(TAG, "Route validation: ${AudioSelector.getRouteDebugInfo(routeInfo)}")
                        updateServiceState(deviceAddr = routeInfo.deviceAddress)
                        
                        val isBottomMic = routeInfo.micInfo?.let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.position != null) {
                                it.position.y < 0.0f
                            } else false
                        } ?: false
                        
                        Log.d(TAG, "Route validation details: channels=$actualChannelCount, isOnPrimaryArray=${routeInfo.isOnPrimaryArray}, isBottomMic=$isBottomMic")
                        
                        val isBadRoute = !routeInfo.isOnPrimaryArray || (candidate.channelMask == AudioFormat.CHANNEL_IN_STEREO && actualChannelCount < 2)
                        
                        if (isBadRoute) {
                            val reason = when {
                                !routeInfo.isOnPrimaryArray && isBottomMic -> "Bottom microphone detected (Y=${routeInfo.micInfo?.position?.y})"
                                !routeInfo.isOnPrimaryArray -> "Non-primary array microphone"
                                actualChannelCount < 2 -> "Single-channel route when multi-channel was requested"
                                else -> "Unknown bad route condition"
                            }
                            Log.w(TAG, "Bad route detected with ${candidate.sampleRate}Hz config: $reason - trying next candidate")
                            
                            recorder.stop()
                            recorder.release()
                            recorder = null
                            releaseWakeLock()
                            continue // Try next format
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
                updateNotification("Recording @${recorder.sampleRate/1000}kHz ${AudioSelector.channelName(recorder.format.channelMask)} — $deviceDesc")

                val buf = ShortArray(bufferBytes / 2)
                while (!stopFlag.get()) {
                    if (isSilenced) break
                    val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord read() returned $n")
                        delay(40)
                    }
                }
                
                Log.i(TAG, "Good route found with ${candidate.sampleRate}Hz, ${AudioSelector.channelName(candidate.channelMask)}")
                return AudioRecordResult.SUCCESS

            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord error with ${candidate.sampleRate}Hz: ${t.message}", t)
                lastError = t as? Exception ?: Exception(t)
            } finally {
                recorder?.release()
                releaseWakeLock()
            }
        }

        Log.e(TAG, "All AudioRecord candidates failed. Last error: ${lastError?.message}", lastError)
        updateNotification("Mic-Lock idle (AudioRecord error: ${lastError?.javaClass?.simpleName}). Retrying…")
        return AudioRecordResult.FAILED
    }
    
    private suspend fun tryMediaRecorderMode(): Boolean {
        return try {
            mediaRecorderHolder = MediaRecorderHolder(this, audioManager) { silenced ->
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
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notifManager.createNotificationChannel(statusChannel)

        val restartChannel = NotificationChannel(
            RESTART_CHANNEL_ID,
            "Mic Lock Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { setShowBadge(true) }
        notifManager.createNotificationChannel(restartChannel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 1, openIntent, flags)

        val reconfigPI = PendingIntent.getService(
            this, 2,
            Intent(this, MicLockService::class.java).setAction(ACTION_RECONFIGURE),
            flags
        )
        val stopPI = PendingIntent.getService(
            this, 3,
            Intent(this, MicLockService::class.java).setAction(ACTION_STOP),
            flags
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
    
    private fun broadcastStatusUpdate() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_IS_PAUSED, isPausedBySilence)
            putExtra(EXTRA_DEVICE_ADDRESS, currentDeviceAddress)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcasted status update: running=$isRunning, paused=$isPausedBySilence")
    }
    
    private val stateLock = Any()
    
    private fun updateServiceState(running: Boolean? = null, paused: Boolean? = null, deviceAddr: String? = null) {
        synchronized(stateLock) {
            running?.let { isRunning = it }
            paused?.let { isPausedBySilence = it }
            deviceAddr?.let { currentDeviceAddress = it }
            
            broadcastStatusUpdate()
        }
    }

    companion object {
        private const val TAG = "MicLockService"
        private const val CHANNEL_ID = "mic_lock_channel"
        private const val RESTART_CHANNEL_ID = "mic_lock_restart_channel"
        private const val NOTIF_ID = 42
        private const val RESTART_NOTIF_ID = 43

        @JvmStatic @Volatile var isRunning: Boolean = false
        @JvmStatic @Volatile var isPausedBySilence: Boolean = false
        @JvmStatic @Volatile var currentDeviceAddress: String? = null

        const val ACTION_RECONFIGURE = "com.example.mic_lock.ACTION_RECONFIGURE"
        const val ACTION_STOP = "com.example.mic_lock.ACTION_STOP"

        const val ACTION_START_HOLDING = "com.example.mic_lock.ACTION_START_HOLDING"
        const val ACTION_STOP_HOLDING = "com.example.mic_lock.ACTION_STOP_HOLDING"
        const val ACTION_START_USER_INITIATED = "com.example.mic_lock.ACTION_START_USER_INITIATED"
        
        // Broadcast actions for UI updates
        const val ACTION_STATUS_CHANGED = "com.example.mic_lock.STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }
}