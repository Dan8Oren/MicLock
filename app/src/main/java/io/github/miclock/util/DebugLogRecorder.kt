package io.github.miclock.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages logcat recording process lifecycle and log file buffering.
 * Thread-safe singleton for capturing application logs.
 */
object DebugLogRecorder {
    private const val TAG = "DebugLogRecorder"
    private const val LOG_DIR = "debug_logs"
    private const val AUTO_STOP_DELAY_MS = 30 * 60 * 1000L // 30 minutes
    private const val FILESYSTEM_SYNC_DELAY_MS = 100L
    
    private val lock = ReentrantLock()
    
    @Volatile
    private var logcatProcess: java.lang.Process? = null
    
    @Volatile
    private var recordingThread: Thread? = null
    
    @Volatile
    private var currentLogFile: File? = null
    
    @Volatile
    private var recordingStartTime: Long = 0L
    
    @Volatile
    private var writer: BufferedWriter? = null
    
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null
    
    /**
     * Starts logcat recording process.
     * @param context Application context for file access
     * @return true if recording started successfully, false otherwise
     * @throws SecurityException if logcat access is denied
     */
    fun startRecording(context: Context): Boolean = lock.withLock {
        if (isRecording()) {
            Log.w(TAG, "Recording already active")
            return false
        }
        
        try {
            // Create log directory
            val logDir = File(context.cacheDir, LOG_DIR)
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory")
                return false
            }
            
            // Create log file with timestamp
            val timestamp = System.currentTimeMillis()
            currentLogFile = File(logDir, "recording_$timestamp.log")
            
            // Start logcat process with PID filter
            val pid = Process.myPid()
            val command = arrayOf("logcat", "-v", "threadtime", "--pid=$pid")
            logcatProcess = Runtime.getRuntime().exec(command)
            
            recordingStartTime = timestamp
            
            // Create background thread to pipe logcat output to file
            recordingThread = Thread {
                try {
                    writer = BufferedWriter(FileWriter(currentLogFile, true))
                    val reader = BufferedReader(InputStreamReader(logcatProcess?.inputStream))
                    
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        writer?.write(line)
                        writer?.newLine()
                        
                        // Flush periodically to ensure data is written
                        writer?.flush()
                    }
                } catch (e: IOException) {
                    if (logcatProcess?.isAlive == true) {
                        Log.e(TAG, "Error writing to log file", e)
                    }
                    // If process is dead, this is expected during shutdown
                } finally {
                    try {
                        writer?.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing writer", e)
                    }
                }
            }.apply {
                name = "LogcatRecorder"
                isDaemon = true
                start()
            }
            
            Log.d(TAG, "Recording started successfully")
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Logcat access denied", e)
            cleanup(context)
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "IOException: Failed to start recording", e)
            cleanup(context)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting recording", e)
            cleanup(context)
            return false
        }
    }
    
    /**
     * Stops logcat recording and returns the log file.
     * @return File containing captured logs, or null if no logs captured
     */
    fun stopRecording(): File? = lock.withLock {
        if (!isRecording()) {
            Log.w(TAG, "No active recording to stop")
            return null
        }
        
        try {
            Log.d(TAG, "Stopping recording...")
            
            // Destroy the logcat process
            logcatProcess?.destroy()
            
            // Wait for process to terminate (with timeout)
            val terminated = try {
                logcatProcess?.waitFor()
                true
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for process termination")
                logcatProcess?.destroyForcibly()
                false
            }
            
            // Wait for recording thread to finish
            recordingThread?.join(2000) // 2 second timeout
            
            // Explicit flush and close
            try {
                writer?.flush()
                writer?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error flushing/closing writer", e)
            }
            
            // Filesystem sync delay
            Thread.sleep(FILESYSTEM_SYNC_DELAY_MS)
            
            val logFile = currentLogFile
            
            // Verify file exists and has content
            if (logFile?.exists() == true && logFile.length() > 0) {
                Log.d(TAG, "Recording stopped successfully. Log file size: ${logFile.length()} bytes")
                return logFile
            } else {
                Log.w(TAG, "Log file is empty or doesn't exist")
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            return null
        } finally {
            // Clean up references
            logcatProcess = null
            recordingThread = null
            writer = null
            currentLogFile = null
            recordingStartTime = 0L
        }
    }
    
    /**
     * Checks if recording is currently active.
     * @return true if logcat process is running
     */
    fun isRecording(): Boolean {
        return logcatProcess?.isAlive == true
    }
    
    /**
     * Gets the duration of current recording session.
     * @return Duration in milliseconds, or 0 if not recording
     */
    fun getRecordingDuration(): Long {
        if (!isRecording() || recordingStartTime == 0L) {
            return 0L
        }
        return System.currentTimeMillis() - recordingStartTime
    }
    
    /**
     * Cleans up all temporary log files.
     * Called on app destroy or after sharing.
     */
    fun cleanup(context: Context) {
        try {
            val logDir = File(context.cacheDir, LOG_DIR)
            if (logDir.exists() && logDir.isDirectory) {
                logDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith("recording_")) {
                        val deleted = file.delete()
                        Log.d(TAG, "Cleanup: ${file.name} - ${if (deleted) "deleted" else "failed to delete"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Schedules automatic stop after 30 minutes.
     * @param context Application context for notifications
     */
    fun scheduleAutoStop(context: Context) {
        cancelAutoStop() // Cancel any existing scheduled stop
        
        autoStopRunnable = Runnable {
            Log.d(TAG, "Auto-stop triggered after 30 minutes")
            stopRecording()
            // Note: Notification and state update will be handled by DebugRecordingStateManager
        }.also {
            autoStopHandler.postDelayed(it, AUTO_STOP_DELAY_MS)
        }
        
        Log.d(TAG, "Auto-stop scheduled for 30 minutes")
    }
    
    /**
     * Cancels scheduled auto-stop.
     */
    fun cancelAutoStop() {
        autoStopRunnable?.let {
            autoStopHandler.removeCallbacks(it)
            autoStopRunnable = null
            Log.d(TAG, "Auto-stop cancelled")
        }
    }
}
