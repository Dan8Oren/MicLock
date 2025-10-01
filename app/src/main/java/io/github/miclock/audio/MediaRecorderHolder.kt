package io.github.miclock.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.miclock.util.WakeLockManager
import java.io.File

class MediaRecorderHolder(
    private val context: Context,
    private val audioManager: AudioManager,
    private val wakeLockManager: WakeLockManager = WakeLockManager(context, "MediaRecorderHolder"),
    private val onSilencedChanged: (Boolean) -> Unit,
) {
    private val TAG = "MediaRecorderHolder"
    private var mediaRecorder: MediaRecorder? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var discardFile: File? = null

    @Volatile
    var isSilenced: Boolean = false
        private set

    fun startRecording() {
        try {
            // Create a temporary file for MediaRecorder output (will be discarded)
            discardFile = File(context.cacheDir, "media_recorder_discard_${System.currentTimeMillis()}.tmp")

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // AAC supports stereo
                setAudioEncodingBitRate(128000) // Higher bitrate for quality
                setAudioSamplingRate(48000) // Explicit 48kHz
                setAudioChannels(2) // Force 2-channel stereo
                setOutputFile(discardFile!!.absolutePath)
                prepare()
                start()
            }

            wakeLockManager.acquire()

            // Register callback to detect silencing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recordingCallback = object : AudioManager.AudioRecordingCallback() {
                    @RequiresApi(Build.VERSION_CODES.Q)
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        // Find our app's recording configuration by checking for our MediaRecorder session
                        // Since clientUid is not available on all API levels, we'll use a different approach
                        val myConfig = configs.firstOrNull { config ->
                            // Look for MediaRecorder sessions (they typically don't have AudioRecord characteristics)
                            try {
                                config.clientAudioSessionId != 0 && config.isClientSilenced != null
                            } catch (e: Exception) {
                                false
                            }
                        }

                        val silenced = myConfig?.isClientSilenced ?: false
                        if (silenced != isSilenced) {
                            isSilenced = silenced
                            Log.d(TAG, "MediaRecorder silencing state changed: $silenced")
                            onSilencedChanged(silenced)
                        }
                    }
                }
                audioManager.registerAudioRecordingCallback(recordingCallback!!, Handler(Looper.getMainLooper()))
            }

            Log.d(TAG, "MediaRecorder started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
            cleanup()
            throw e
        }
    }

    fun stopRecording() {
        Log.d(TAG, "Stopping MediaRecorder")
        cleanup()
    }

    private fun cleanup() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        wakeLockManager.release()

        recordingCallback?.let {
            try {
                audioManager.unregisterAudioRecordingCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback: ${e.message}")
            }
        }
        recordingCallback = null

        // Clean up temporary file
        discardFile?.let {
            try {
                if (it.exists()) it.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting temp file: ${e.message}")
            }
        }
        discardFile = null

        isSilenced = false
    }
}
