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
            val sel = Prefs.getSelectedAddress(this)
            currentSelection = sel
            val preferredDevice = findInputDeviceByAddress(sel)
            currentDeviceAddress = preferredDevice?.address

            // Recorder config
            val sampleRate = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            val bufferBytes = (minBuf * 2).coerceAtLeast(4096)

            var recorder: AudioRecord? = null
            isSilenced = false
            isPausedBySilence = false

            try {
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()

                // Default to MIC for best compatibility
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()

                // Only set preferred device if not in auto mode
                if (preferredDevice != null) {
                    Log.d(TAG, "Setting preferred device: ${preferredDevice.address} (type=${preferredDevice.type})")
                    try { 
                        recorder.setPreferredDevice(preferredDevice) 
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreferredDevice failed: ${t.message}")
                    }
                } else {
                    Log.d(TAG, "No preferred device set - letting Android choose audio route")
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    updateNotification("Mic-Lock idle (init failed). Retrying…")
                    delay(1500)
                    continue
                }

                // Observe silencing for THIS session
                val recordingSessionId = recorder.audioSessionId
                recCallback = object : AudioManager.AudioRecordingCallback() {
                    @RequiresApi(Build.VERSION_CODES.Q)
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        val mine = configs.firstOrNull { it.clientAudioSessionId == recordingSessionId } ?: return
                        val silenced = mine.isClientSilenced
                        if (silenced != isSilenced) {
                            isSilenced = silenced
                            isPausedBySilence = silenced
                            if (silenced) {
                                Log.i(TAG, "Silenced by system (other app using mic).")
                                updateNotification("Paused — mic in use by another app")
                                try { recorder.stop() } catch (_: Throwable) {}
                            } else {
                                Log.i(TAG, "Unsilenced; will resume.")
                            }
                        }
                    }
                }
                registerRecordingCallback(recCallback!!)

                // Start capture
                recorder.startRecording()
                acquireWakeLock()

                // Log audioSessionId after AudioRecord creation
                val validationSessionId = recorder.audioSessionId
                Log.d(TAG, "AudioRecord session ID: $validationSessionId")

                // Add debug logs for AudioFormat configuration
                Log.d(TAG, "AudioRecord config - Sample rate: ${sampleRate}Hz, Encoding: $encoding, Channel mask: $channelMask, Buffer: ${bufferBytes} bytes")

                // Add route validation logging
                logRouteValidation(validationSessionId)

                val deviceDesc = preferredDevice?.let { d ->
                    val addr = d.address ?: "unknown"
                    "Preferred mic | addr=$addr"
                } ?: "Auto mic selection"
                updateNotification("Recording @${sampleRate/1000}kHz, buf=$bufferBytes — $deviceDesc")

                val buf = ShortArray(bufferBytes / 2)
                while (!stopFlag.get()) {
                    if (isSilenced) break
                    val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        Log.w(TAG, "read() returned $n")
                        delay(40)
                    }
                    // Intentionally discard audio — the point is to hold/occupy the mic politely.
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Loop error: ${t.message}", t)
                updateNotification("Mic-Lock idle (error: ${t.javaClass.simpleName}). Retrying…")
                delay(2_000)
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

            // If we exited because of silencing, wait to be unsilenced before retrying
            if (isSilenced && !stopFlag.get()) {
                var waited = 0
                while (isSilenced && !stopFlag.get() && waited < 10_000) {
                    delay(200)
                    waited += 200
                }
                // small cooldown to avoid thrash when the other app toggles quickly
                delay(300)
                continue
            }

            delay(300)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun findInputDeviceByAddress(sel: String): AudioDeviceInfo? {
        // Remove device pinning by default - return null for auto mode to let Android's policy choose
        if (sel == Prefs.VALUE_AUTO) {
            Log.d(TAG, "Auto mode selected - returning null to let Android choose audio route naturally")
            return null
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.address == sel }
            ?: devices.firstOrNull { it.address == sel }
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

    @RequiresApi(Build.VERSION_CODES.M)
    private fun logRouteValidation(sessionId: Int) {
        try {
            val activeConfigs = audioManager.activeRecordingConfigurations
            val myConfig = activeConfigs.firstOrNull { it.clientAudioSessionId == sessionId }
            
            if (myConfig != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val inputDevice = myConfig.audioDevice
                    if (inputDevice != null) {
                        val deviceAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) inputDevice.address else "unknown"
                        Log.d(TAG, "Active input device - Name: '${inputDevice.productName}', Address: '$deviceAddress', Type: ${inputDevice.type}")
                        Log.d(TAG, "Device info - IsSource: ${inputDevice.isSource}, IsSink: ${inputDevice.isSink}")
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // Log channel counts and sample rates
                            Log.d(TAG, "Device capabilities - Channel counts: ${inputDevice.channelCounts.contentToString()}, Sample rates: ${inputDevice.sampleRates.contentToString()}")
                            
                            // Check for microphone position info
                            try {
                                val microphones = audioManager.microphones
                                val deviceAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) inputDevice.address else null
                                val matchingMic = microphones.firstOrNull { it.address == deviceAddr }
                                if (matchingMic != null) {
                                    val pos = matchingMic.position
                                    Log.d(TAG, "Microphone position - X: ${pos.x}, Y: ${pos.y}, Z: ${pos.z}")
                                    
                                    // Validate we're NOT on @:bottom route when in auto mode
                                    val currentSelection = Prefs.getSelectedAddress(this)
                                    if (currentSelection == Prefs.VALUE_AUTO) {
                                        if (pos.y < 0) {
                                            Log.d(TAG, "ROUTE VALIDATION: Auto mode selected a bottom microphone (Y < 0). This is acceptable if it's Android's natural choice.")
                                        } else {
                                            Log.d(TAG, "ROUTE VALIDATION: Auto mode selected a non-bottom microphone (Y >= 0). Android chose this route naturally.")
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "No matching microphone info found for address: $deviceAddr")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not retrieve microphone position info: ${e.message}")
                            }
                        }
                    } else {
                        Log.d(TAG, "No input device info available for session $sessionId")
                    }
                } else {
                    Log.d(TAG, "AudioDevice info not available on API < N for session $sessionId")
                }
            } else {
                Log.d(TAG, "No active recording configuration found for session $sessionId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during route validation: ${e.message}")
        }
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
