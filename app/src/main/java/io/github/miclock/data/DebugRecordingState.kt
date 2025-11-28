package io.github.miclock.data

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
