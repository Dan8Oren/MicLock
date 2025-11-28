package io.github.miclock.data

import android.content.Context
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
            return false
        }
        
        // Note: DebugLogRecorder will be implemented in task 4
        // For now, we update the state to prepare for integration
        val startTime = System.currentTimeMillis()
        
        // TODO: Call DebugLogRecorder.startRecording(context) when implemented
        // val success = DebugLogRecorder.startRecording(context)
        // if (!success) return false
        
        // Update state to recording
        _state.value = DebugRecordingState(
            isRecording = true,
            startTime = startTime,
            autoStopScheduled = true
        )
        
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
            return null
        }
        
        // Note: DebugLogRecorder will be implemented in task 4
        // For now, we update the state to prepare for integration
        
        // TODO: Call DebugLogRecorder.stopRecording() when implemented
        // val logFile = DebugLogRecorder.stopRecording()
        
        // Update state to not recording
        _state.value = DebugRecordingState(
            isRecording = false,
            startTime = 0L,
            autoStopScheduled = false
        )
        
        // TODO: Return actual log file from DebugLogRecorder
        return null
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
        
        // Note: DebugLogRecorder cleanup will be called when implemented in task 4
        // TODO: Call DebugLogRecorder.cleanup(context) when implemented
        
        // Reset to initial state
        _state.value = DebugRecordingState()
    }
}
