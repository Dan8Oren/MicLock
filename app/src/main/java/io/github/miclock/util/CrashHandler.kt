package io.github.miclock.util

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.miclock.data.DebugRecordingStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Custom uncaught exception handler that automatically collects debug logs
 * when a crash occurs during an active debug recording session.
 * 
 * This handler intercepts crashes, saves diagnostic information including
 * the exception details, and chains to the default exception handler to
 * ensure normal crash handling continues.
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    
    companion object {
        private const val TAG = "CrashHandler"
        private const val COLLECTION_TIMEOUT_MS = 5000L // 5 seconds max
        private const val PREFS_NAME = "crash_logs"
        private const val KEY_CRASH_LOG_URI = "crash_log_uri"
        private const val KEY_CRASH_EXCEPTION_TYPE = "crash_exception_type"
        private const val KEY_CRASH_EXCEPTION_MESSAGE = "crash_exception_message"
        private const val KEY_CRASH_STACK_TRACE = "crash_stack_trace"
        private const val KEY_CRASH_TIMESTAMP = "crash_timestamp"
        private const val KEY_CRASH_FILENAME = "crash_filename"
        
        /**
         * Checks if there are pending crash logs from a previous crash.
         * @param context Application context
         * @return true if crash logs are pending
         */
        fun hasPendingCrashLogs(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.contains(KEY_CRASH_LOG_URI)
        }
        
        /**
         * Gets the crash log URI from SharedPreferences.
         * @param context Application context
         * @return Crash log URI string, or null if not available
         */
        fun getCrashLogUri(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_LOG_URI, null)
        }
        
        /**
         * Gets the crash exception type from SharedPreferences.
         * @param context Application context
         * @return Exception type string, or null if not available
         */
        fun getCrashExceptionType(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_EXCEPTION_TYPE, null)
        }
        
        /**
         * Gets the crash exception message from SharedPreferences.
         * @param context Application context
         * @return Exception message string, or null if not available
         */
        fun getCrashExceptionMessage(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_EXCEPTION_MESSAGE, null)
        }
        
        /**
         * Gets the crash stack trace summary from SharedPreferences.
         * @param context Application context
         * @return Stack trace summary string, or null if not available
         */
        fun getCrashStackTrace(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_STACK_TRACE, null)
        }
        
        /**
         * Gets the crash timestamp from SharedPreferences.
         * @param context Application context
         * @return Crash timestamp in milliseconds, or 0 if not available
         */
        fun getCrashTimestamp(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_CRASH_TIMESTAMP, 0L)
        }
        
        /**
         * Gets the crash log filename from SharedPreferences.
         * @param context Application context
         * @return Crash log filename, or null if not available
         */
        fun getCrashFilename(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_FILENAME, null)
        }
        
        /**
         * Clears pending crash logs from SharedPreferences.
         * @param context Application context
         */
        fun clearPendingCrashLogs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "Cleared pending crash logs")
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
        
        try {
            // Check if debug recording is active
            val isRecording = DebugRecordingStateManager.state.value.isRecording
            
            if (isRecording) {
                Log.d(TAG, "Debug recording active during crash, collecting logs...")
                
                // Collect crash logs with timeout to prevent ANR
                try {
                    collectCrashLogsSync(throwable)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to collect crash logs", e)
                }
            } else {
                Log.d(TAG, "Debug recording not active, skipping crash log collection")
            }
        } catch (e: Exception) {
            // Catch any errors in crash handling to prevent secondary crashes
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // Always chain to default handler to ensure normal crash handling
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    /**
     * Collects crash logs synchronously with timeout protection.
     * This runs on the crashing thread and must complete quickly.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun collectCrashLogsSync(throwable: Throwable) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        // Launch collection with timeout
        val job = scope.launch {
            try {
                withTimeout(COLLECTION_TIMEOUT_MS) {
                    collectCrashLogs(throwable)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Crash log collection failed or timed out", e)
            }
        }
        
        // Block and wait for completion (with timeout)
        try {
            // Use Thread.sleep to wait for the job to complete
            // This is acceptable in crash handler as we're already crashing
            val startTime = System.currentTimeMillis()
            while (job.isActive && (System.currentTimeMillis() - startTime) < COLLECTION_TIMEOUT_MS) {
                Thread.sleep(100)
            }
            
            if (job.isActive) {
                Log.w(TAG, "Crash log collection timed out")
                job.cancel()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for crash log collection")
            job.cancel()
        }
    }
    
    /**
     * Collects crash logs asynchronously.
     * Stops recording, collects system state, creates diagnostic package with crash info.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun collectCrashLogs(throwable: Throwable) {
        try {
            val recordingStartTime = DebugRecordingStateManager.state.value.startTime
            
            // Stop the logcat recording to flush logs to file
            Log.d(TAG, "Stopping logcat recording...")
            val logFile = DebugLogRecorder.stopRecording()
            
            if (logFile == null) {
                Log.w(TAG, "No log file available from recording")
            }
            
            // Create crash info file
            val crashInfo = createCrashInfo(throwable)
            val crashInfoFile = saveCrashInfo(crashInfo)
            
            // Collect system state via dumpsys (same as normal stop flow)
            Log.d(TAG, "Collecting system state...")
            val result = DebugLogCollector.collectAndShare(
                context,
                logFile,
                recordingStartTime,
                isCrashCollection = true,
                crashInfoFile = crashInfoFile
            )
            
            // Handle result and save crash details
            when (result) {
                is CollectionResult.Success -> {
                    Log.d(TAG, "Crash logs collected successfully")
                    saveCrashDetails(result, throwable, recordingStartTime)
                }
                is CollectionResult.PartialSuccess -> {
                    Log.d(TAG, "Crash logs collected with warnings")
                    saveCrashDetails(result, throwable, recordingStartTime)
                }
                is CollectionResult.Failure -> {
                    Log.e(TAG, "Failed to collect crash logs: ${result.error}")
                }
            }
            
            // Reset state to not recording
            DebugRecordingStateManager.reset(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting crash logs", e)
        }
    }
    
    /**
     * Creates crash information text.
     */
    private fun createCrashInfo(throwable: Throwable): String {
        val stackTrace = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTrace))
        
        return buildString {
            appendLine("=== CRASH INFORMATION ===")
            appendLine()
            appendLine("Exception Type: ${throwable.javaClass.simpleName}")
            appendLine("Exception Message: ${throwable.message ?: "No message"}")
            appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(stackTrace.toString())
        }
    }
    
    /**
     * Saves crash info to a temporary file.
     */
    private fun saveCrashInfo(crashInfo: String): File? {
        return try {
            val logDir = File(context.cacheDir, "debug_logs")
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory")
                return null
            }
            
            val crashFile = File(logDir, "crash_info_${System.currentTimeMillis()}.txt")
            crashFile.writeText(crashInfo)
            Log.d(TAG, "Saved crash info to ${crashFile.absolutePath}")
            crashFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash info", e)
            null
        }
    }
    
    /**
     * Saves crash details to SharedPreferences for retrieval after app restart.
     */
    private fun saveCrashDetails(
        result: CollectionResult,
        throwable: Throwable,
        recordingStartTime: Long
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Extract URI from share intent
            val shareIntent = when (result) {
                is CollectionResult.Success -> result.shareIntent
                is CollectionResult.PartialSuccess -> result.shareIntent
                else -> return
            }
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                shareIntent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                shareIntent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            }
            
            if (uri == null) {
                Log.w(TAG, "No URI found in share intent")
                return
            }
            
            // Get filename from intent
            val filename = shareIntent.getStringExtra(android.content.Intent.EXTRA_TITLE)
            
            // Get stack trace summary (first 5 lines)
            val stackTrace = StringWriter()
            throwable.printStackTrace(PrintWriter(stackTrace))
            val stackTraceLines = stackTrace.toString().lines()
            val stackTraceSummary = stackTraceLines.take(5).joinToString("\n")
            
            prefs.edit().apply {
                putString(KEY_CRASH_LOG_URI, uri.toString())
                putString(KEY_CRASH_EXCEPTION_TYPE, throwable.javaClass.simpleName)
                putString(KEY_CRASH_EXCEPTION_MESSAGE, throwable.message ?: "No message")
                putString(KEY_CRASH_STACK_TRACE, stackTraceSummary)
                putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
                if (filename != null) {
                    putString(KEY_CRASH_FILENAME, filename)
                }
                apply()
            }
            
            Log.d(TAG, "Saved crash details to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash details", e)
        }
    }
}
