package io.github.miclock.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.miclock.R
import io.github.miclock.ui.MainActivity
import io.github.miclock.util.DebugLogRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Represents the current state of debug log recording.
 * Used with StateFlow for reactive UI updates.
 * 
 * @param isRecording Whether a recording session is currently active
 * @param startTime Unix timestamp (milliseconds) when recording started, or 0 if not recording
 * @param autoStopScheduled Whether the 30-minute auto-stop timeout is scheduled
 */
data class DebugRecordingState(
    val isRecording: Boolean = false,
    val startTime: Long = 0L,
    val autoStopScheduled: Boolean = false
)

/**
 * Manages the state of debug log recording with reactive updates.
 * Provides a single source of truth for recording state across the application.
 * 
 * This manager coordinates with DebugLogRecorder to control the recording lifecycle
 * and exposes state changes via StateFlow for UI observation.
 */
object DebugRecordingStateManager {
    private const val TAG = "DebugRecordingStateMgr"
    private const val DEBUG_CHANNEL_ID = "debug_recording_channel"
    private const val AUTO_STOP_NOTIF_ID = 44
    
    private val _state = MutableStateFlow(DebugRecordingState())
    
    /**
     * Observable state flow for debug recording state.
     * UI components should collect this flow to react to state changes.
     */
    val state: StateFlow<DebugRecordingState> = _state.asStateFlow()
    
    /**
     * Starts a debug recording session.
     * 
     * This method transitions to the recording state and initiates log capture.
     * The state will be updated to reflect the recording status, start time, and
     * auto-stop scheduling.
     * 
     * @param context Application context for file access and recording operations
     * @return true if recording started successfully, false otherwise
     * @throws SecurityException if logcat access is denied on the device
     */
    fun startRecording(context: Context): Boolean {
        // Check if already recording
        if (_state.value.isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        // Start the actual recording
        val success = DebugLogRecorder.startRecording(context)
        if (!success) {
            Log.e(TAG, "Failed to start DebugLogRecorder")
            return false
        }
        
        val startTime = System.currentTimeMillis()
        
        // Schedule auto-stop with callback to update state
        DebugLogRecorder.scheduleAutoStop(context) { 
            handleAutoStop(context) 
        }
        
        // Update state to recording
        _state.value = DebugRecordingState(
            isRecording = true,
            startTime = startTime,
            autoStopScheduled = true
        )
        
        Log.d(TAG, "Recording started successfully")
        return true
    }
    
    /**
     * Stops the current debug recording session.
     * 
     * This method terminates log capture and transitions back to the non-recording state.
     * The captured log file is returned for further processing (collection and sharing).
     * 
     * @return File containing captured logs, or null if no logs were captured or not recording
     */
    fun stopRecording(): File? {
        // Check if actually recording
        if (!_state.value.isRecording) {
            Log.w(TAG, "Not recording, cannot stop")
            return null
        }
        
        // Cancel auto-stop since we're manually stopping
        DebugLogRecorder.cancelAutoStop()
        
        // Stop the actual recording
        val logFile = DebugLogRecorder.stopRecording()
        
        // Update state to not recording
        _state.value = DebugRecordingState(
            isRecording = false,
            startTime = 0L,
            autoStopScheduled = false
        )
        
        Log.d(TAG, "Recording stopped")
        return logFile
    }
    
    /**
     * Resets the recording state to initial values.
     * 
     * This method ensures a clean state, typically called on app startup or after
     * sharing logs. It stops any active recording and cleans up resources.
     * 
     * @param context Application context for cleanup operations
     */
    fun reset(context: Context) {
        // If recording is active, stop it first
        if (_state.value.isRecording) {
            stopRecording()
        }
        
        // Clean up any leftover files
        DebugLogRecorder.cleanup(context)
        
        // Cancel any pending auto-stop
        DebugLogRecorder.cancelAutoStop()
        
        // Reset to initial state
        _state.value = DebugRecordingState()
        
        Log.d(TAG, "State reset")
    }
    
    /**
     * Handles auto-stop when the 30-minute timeout is reached.
     * Shows a notification to inform the user and updates the state.
     * 
     * @param context Application context for notifications
     */
    private fun handleAutoStop(context: Context) {
        Log.d(TAG, "Auto-stop triggered")
        
        // Stop recording
        val logFile = DebugLogRecorder.stopRecording()
        
        // Update state
        _state.value = DebugRecordingState(
            isRecording = false,
            startTime = 0L,
            autoStopScheduled = false
        )
        
        // Show notification
        showAutoStopNotification(context)
        
        Log.d(TAG, "Auto-stop completed, log file: ${logFile?.absolutePath}")
    }
    
    /**
     * Shows a notification when auto-stop is triggered.
     * 
     * @param context Application context
     */
    private fun showAutoStopNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DEBUG_CHANNEL_ID,
                "Debug Recording",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for debug recording events"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Create intent to open MainActivity
        val openIntent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, 0, openIntent, flags)
        
        // Build notification
        val notification = NotificationCompat.Builder(context, DEBUG_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.debug_auto_stop_title))
            .setContentText(context.getString(R.string.debug_auto_stop_message))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(AUTO_STOP_NOTIF_ID, notification)
        
        Log.d(TAG, "Auto-stop notification shown")
    }
}
