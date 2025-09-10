package com.example.mic_lock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
//    private val mainExecutor by lazy { ContextCompat.getMainExecutor(this) }

    // Optional: light CPU wake while actively recording
    private var wakeLock: PowerManager.WakeLock? = null

    private var loopJob: Job? = null
    private val stopFlag = AtomicBoolean(false)

    // Per-run state
    private var currentSelection: String? = null

    // Silencing state (per run)
    @Volatile private var isSilenced: Boolean = false
    private var recCallback: AudioManager.AudioRecordingCallback? = null
    
    // MediaRecorder fallback
    private var mediaRecorderHolder: MediaRecorderHolder? = null
    private var currentRecordingMethod: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFlag.set(true)
        scope.cancel()
        releaseWakeLock()
        try { recCallback?.let { audioManager.unregisterAudioRecordingCallback(it) } } catch (_: Throwable) {}
        recCallback = null
        mediaRecorderHolder?.stopRecording()
        mediaRecorderHolder = null
        currentRecordingMethod = null
        isRunning = false
        isPausedBySilence = false
        currentDeviceAddress = null
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasAllRequirements()) {
            Log.w(TAG, "Missing permission or notifications disabled. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> {
                if (isRunning) {
                    restartLoop("Reconfigure requested from UI")
                    return START_STICKY
                }
            }
        }

        if (!isRunning) {
            isRunning = true
            isPausedBySilence = false
            startForeground(NOTIF_ID, buildNotification("Starting…"))
            stopFlag.set(false)
            loopJob = scope.launch { holdSelectedMicLoop() }
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

    private fun unregisterRecordingCallback(cb: AudioManager.AudioRecordingCallback?) {
        if (cb != null) {
            try { audioManager.unregisterAudioRecordingCallback(cb) } catch (_: Throwable) {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun holdSelectedMicLoop() {
        while (!stopFlag.get()) {
            isSilenced = false
            isPausedBySilence = false

            val useMediaRecorderPref = Prefs.getUseMediaRecorder(this) // User's preference via compatibility toggle

            var primaryAttemptSuccessful = false
            var fallbackAttemptSuccessful = false

            if (useMediaRecorderPref) {
                // User prefers MediaRecorder: Try MediaRecorder first
                Log.d(TAG, "User prefers MediaRecorder. Attempting MediaRecorder mode...")
                if (tryMediaRecorderMode()) {
                    currentRecordingMethod = "MediaRecorder"
                    Prefs.setLastRecordingMethod(this, "MediaRecorder")
                    primaryAttemptSuccessful = true
                } else {
                    Log.w(TAG, "MediaRecorder failed. Attempting AudioRecord as fallback...")
                    // Fallback to AudioRecord
                    val audioRecordResult = tryAudioRecordMode()
                    when (audioRecordResult) {
                        AudioRecordResult.SUCCESS -> {
                            currentRecordingMethod = "AudioRecord"
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
                // User prefers AudioRecord (compatibility mode off): Try AudioRecord first
                Log.d(TAG, "User prefers AudioRecord. Attempting AudioRecord mode...")
                val audioRecordResult = tryAudioRecordMode()
                when (audioRecordResult) {
                    AudioRecordResult.SUCCESS -> {
                        currentRecordingMethod = "AudioRecord"
                        Prefs.setLastRecordingMethod(this, "AudioRecord")
                        primaryAttemptSuccessful = true
                    }
                    AudioRecordResult.BAD_ROUTE -> {
                        Log.w(TAG, "AudioRecord landed on bad route. Attempting MediaRecorder as fallback...")
                        // Fallback to MediaRecorder
                        if (tryMediaRecorderMode()) {
                            currentRecordingMethod = "MediaRecorder"
                            Prefs.setLastRecordingMethod(this, "MediaRecorder")
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

            // If all attempts failed or resulted in a bad state, re-loop after delay.
            if (!primaryAttemptSuccessful && !fallbackAttemptSuccessful) {
                delay(2_000)
                continue
            }

            // If we exited because of silencing, wait to be unsilenced before retrying
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
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferBytes = (minBuf * 2).coerceAtLeast(4096)
        
        var recorder: AudioRecord? = null
        
        try {
            val fmt = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()

            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferBytes)
                .build()

            Log.d(TAG, "No specific device set - letting Android choose audio route")

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                updateNotification("Mic-Lock idle (AudioRecord init failed). Retrying…")
                return AudioRecordResult.FAILED
            }

            // Start recording first
            recorder.startRecording()
            acquireWakeLock()
            
            val recordingSessionId = recorder.audioSessionId
            Log.d(TAG, "AudioRecord session ID: $recordingSessionId")
            
            // Validate route after starting
            delay(100) // Give Android time to establish the route
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val routeInfo = AudioSelector.validateCurrentRoute(audioManager, recordingSessionId)
                if (routeInfo != null) {
                    Log.d(TAG, "Route validation: ${AudioSelector.getRouteDebugInfo(routeInfo)}")
                    currentDeviceAddress = routeInfo.deviceAddress
                    
                    // Check if we're in auto mode and landed on bottom mic (bad route)
                    // Check if current route is a 'bad' route (e.g., bottom mic when auto is preferred non-bottom)
                    // Given that device selection is removed, we simplify this check.
                    // If it's not on the primary array (Y >= 0), consider it a bad route for AudioRecord's primary use case.
                    if (!routeInfo.isOnPrimaryArray) {
                        Log.w(TAG, "Bad route detected: Auto mode but landed on bottom microphone")
                        try {
                            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                            recorder.release()
                        } catch (_: Throwable) {}
                        releaseWakeLock()
                        return AudioRecordResult.BAD_ROUTE
                    }
                } else {
                    Log.w(TAG, "Could not validate route, continuing with AudioRecord")
                    currentDeviceAddress = "AudioRecord (unknown route)"
                }
            } else {
                currentDeviceAddress = "AudioRecord (auto)"
            }

            // Set up silencing callback
            recCallback = object : AudioManager.AudioRecordingCallback() {
                @RequiresApi(Build.VERSION_CODES.Q)
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == recordingSessionId } ?: return
                    val silenced = mine.isClientSilenced
                    if (silenced != isSilenced) {
                        isSilenced = silenced
                        isPausedBySilence = silenced
                        if (silenced) {
                            Log.i(TAG, "AudioRecord silenced by system (other app using mic).")
                            updateNotification("Paused — mic in use by another app")
                            try { recorder.stop() } catch (_: Throwable) {}
                        } else {
                            Log.i(TAG, "AudioRecord unsilenced; will resume.")
                        }
                    }
                }
            }
            registerRecordingCallback(recCallback!!)

            val deviceDesc = "AudioRecord | Auto selection"
            updateNotification("Recording @${sampleRate/1000}kHz — $deviceDesc")

            val buf = ShortArray(bufferBytes / 2)
            while (!stopFlag.get()) {
                if (isSilenced) break
                val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord read() returned $n")
                    delay(40)
                }
            }
            
            return AudioRecordResult.SUCCESS
            
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord error: ${t.message}", t)
            updateNotification("Mic-Lock idle (AudioRecord error: ${t.javaClass.simpleName}). Retrying…")
            return AudioRecordResult.FAILED
        } finally {
            unregisterRecordingCallback(recCallback)
            recCallback = null
            try {
                recorder?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    it.release()
                }
            } catch (_: Throwable) {}
            releaseWakeLock()
        }
    }
    
    private suspend fun tryMediaRecorderMode(): Boolean {
        return try {
            mediaRecorderHolder = MediaRecorderHolder(this, audioManager) { silenced ->
                isSilenced = silenced
                isPausedBySilence = silenced
                if (silenced) {
                    Log.i(TAG, "MediaRecorder silenced by system (other app using mic).")
                    updateNotification("Paused — mic in use by another app")
                } else {
                    Log.i(TAG, "MediaRecorder unsilenced; will resume.")
                }
            }
            
            mediaRecorderHolder!!.startRecording()
            currentDeviceAddress = "MediaRecorder"
            
            updateNotification("Recording (MediaRecorder compatibility mode)")
            
            // Keep running until stopped or silenced
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
    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Mic Lock",
            NotificationManager.IMPORTANCE_LOW // visible ongoing notification
        ).apply { setShowBadge(false) }
        notifManager.createNotificationChannel(ch)
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
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, "Recheck", reconfigPI)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    private fun updateNotification(text: String) {
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }



    companion object {
        private const val TAG = "MicLockService"
        private const val CHANNEL_ID = "mic_lock_channel"
        private const val NOTIF_ID = 42

        // Public/static flags for UI (MainActivity) to read
        @JvmStatic @Volatile var isRunning: Boolean = false
        @JvmStatic @Volatile var isPausedBySilence: Boolean = false
        @JvmStatic @Volatile var currentDeviceAddress: String? = null

        const val ACTION_RECONFIGURE = "com.example.mic_lock.ACTION_RECONFIGURE"
        const val ACTION_STOP = "com.example.mic_lock.ACTION_STOP"
    }
}
