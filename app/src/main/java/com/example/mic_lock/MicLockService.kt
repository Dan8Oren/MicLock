package com.example.mic_lock

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MicLockService : Service() {

    companion object {
        private const val CHANNEL_ID = "mic_lock_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_RECONFIGURE = "com.example.mic_lock.RECONFIGURE" // sent by Activity
        @Volatile var isRunning: Boolean = false
        private const val TAG = "MicLock"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopFlag = AtomicBoolean(false)
    private var lastNotifText: String? = null
    private var currentSelection: String? = null  // "auto" or address


    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRecordAudio() || !areNotificationsEnabledCompat()) {
            Log.e(TAG, "Missing permission(s); stopping")
            stopSelf(); return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting mic-lock…"))

        val sel = Prefs.getSelectedAddress(this)
        if (intent?.action == ACTION_RECONFIGURE) {
            // Selection changed → restart loop with new target
            if (sel != currentSelection) {
                Log.i(TAG, "Reconfiguring to selection=$sel")
                restartLoop()
            }
        } else if (!isRunning) {
            isRunning = true
            currentSelection = sel
            stopFlag.set(false)
            scope.launch { holdSelectedMicLoop() }
        }
        return START_STICKY
    }


    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun restartLoop() {
        stopFlag.set(true)
        scope.coroutineContext.cancelChildren()
        stopFlag.set(false)
        currentSelection = Prefs.getSelectedAddress(this)
        scope.launch { holdSelectedMicLoop() }
    }

    override fun onDestroy() {
        stopFlag.set(true)
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasRecordAudio() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun areNotificationsEnabledCompat(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "Mic Lock",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                description = "Keeps a selected built-in mic busy to force alternate routing"
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(fullText: String): Notification {
        lastNotifText = fullText
        val collapsed = if (fullText.length > 64) fullText.take(64) + "…" else fullText
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("Mic-Lock active")
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        val updated = buildNotification(text)
        // Refresh the FS tile reliably:
        CoroutineScope(Dispatchers.Main).launch {
            try { startForeground(NOTIF_ID, updated) }
            catch (_: Throwable) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, updated)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun holdSelectedMicLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (!stopFlag.get()) {
            var recorder: AudioRecord? = null
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val selection = currentSelection ?: Prefs.getSelectedAddress(this)
                val choice = AudioSelector.chooseByAddress(am, selection)

                val (sr, enc, chMask) = AudioSelector.chooseLowestPowerFormat()
                val src = AudioSelector.preferUnprocessedSource()

                val minBuf = AudioRecord.getMinBufferSize(sr, chMask, enc)
                if (minBuf <= 0) throw IllegalStateException("Invalid min buffer size")
                val bufSize = (minBuf * 2).coerceAtLeast(4096)

                recorder = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(enc)
                            .setSampleRate(sr)
                            .setChannelMask(chMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()

                choice?.device?.let {
                    try {
                        val ok = recorder.setPreferredDevice(it)
                        Log.i(TAG, "Preferred device=${it.productName} ok=$ok addr=${it.address}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreferredDevice failed: ${t.message}")
                    }
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    updateNotification("Mic-Lock idle (init failed). Retrying…")
                    delay(10_000); continue
                }

                val label = if (selection == Prefs.VALUE_AUTO) "Auto (bottom)" else "Selected"
                val desc = buildString {
                    append("$label | ")
                    choice?.let { ch ->
                        val addr = ch.device.address ?: ch.micInfo?.address
                        if (!addr.isNullOrBlank()) append("addr=").append(addr).append(" | ")
                        ch.micInfo?.position?.let { append("pos y=%.3f | ".format(it.y)) }
                    }
                    append("${sr/1000}kHz ${AudioSelector.channelName(chMask)} ${AudioSelector.encodingName(enc)} | ")
                    append("source=").append(
                        if (src == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED"
                        else if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION"
                        else "SRC($src)"
                    )
                }
                updateNotification(desc)

                try { recorder.startRecording() } catch (sec: SecurityException) {
                    updateNotification("Mic-Lock idle (SecurityException). Retrying…")
                    delay(10_000); continue
                }

                val buf = ByteArray(bufSize)
                while (!stopFlag.get()) {
                    val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IllegalStateException("read()=$n")
                    // drop audio
                    // If selection changed while running, restart quickly:
                    val selNow = Prefs.getSelectedAddress(this@MicLockService)
                    if (selNow != currentSelection) {
                        Log.i(TAG, "Selection changed during run → restart loop")
                        currentSelection = selNow
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Loop error: ${t.message}", t)
                updateNotification("Mic-Lock idle (error: ${t.javaClass.simpleName}). Retrying…")
                delay(5_000)
            } finally {
                try {
                    recorder?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                        it.release()
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}
