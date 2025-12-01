package io.github.miclock.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.github.miclock.R
import io.github.miclock.service.MicLockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Collects system state snapshots and packages diagnostic data.
 * Handles dumpsys command execution, device info collection, and zip file creation.
 */
object DebugLogCollector {
    private const val TAG = "DebugLogCollector"
    private const val LOG_DIR = "debug_logs"
    private const val DUMPSYS_TIMEOUT_SECONDS = 10L
    private const val MAX_DUMPSYS_SIZE_BYTES = 100 * 1024 // 100KB
    private const val RETRY_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 500L
    private const val DEBUG_LOGS_SAVED_NOTIF_ID = 44

    // Dumpsys services to collect
    private val DUMPSYS_SERVICES = listOf(
        "audio",
        "telecom",
        "media.session",
        "media.audio_policy",
        "media.audio_flinger"
    )

    // Critical services that require retry logic
    private val CRITICAL_SERVICES = setOf("telecom")

    // Context information for each dumpsys service
    private val SERVICE_CONTEXT = mapOf(
        "audio" to "Relevant for all audio routing issues",
        "telecom" to "Relevant when issues occur during phone calls",
        "media.session" to "Relevant when media players (Spotify, YouTube, etc.) are involved",
        "media.audio_policy" to "Relevant for audio focus and routing policy issues",
        "media.audio_flinger" to "Relevant for low-level audio HAL issues"
    )

    /**
     * Collects all diagnostic data, saves to Downloads, and creates share intent.
     * @param context Application context
     * @param logFile Log file from DebugLogRecorder, or null
     * @param recordingStartTime Timestamp when recording started (for filename generation)
     * @return CollectionResult indicating success, partial success, or failure
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun collectAndShare(context: Context, logFile: File?, recordingStartTime: Long): CollectionResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<CollectionFailure>()
        val dumpsysData = mutableMapOf<String, String>()

        Log.d(TAG, "Starting diagnostic data collection")

        // Collect dumpsys data for each service
        for (service in DUMPSYS_SERVICES) {
            val isCritical = service in CRITICAL_SERVICES
            val attempts = if (isCritical) RETRY_ATTEMPTS else 1

            var success = false
            var lastError: String? = null

            for (attempt in 1..attempts) {
                try {
                    val output = executeDumpsys(service)
                    if (output.isNotEmpty()) {
                        dumpsysData[service] = output
                        success = true
                        Log.d(TAG, "Successfully collected dumpsys $service (attempt $attempt/$attempts)")
                        break
                    } else {
                        lastError = "Empty output"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    Log.w(TAG, "Failed to collect dumpsys $service (attempt $attempt/$attempts): $lastError")
                }

                // Delay before retry
                if (attempt < attempts) {
                    delay(RETRY_DELAY_MS)
                }
            }

            // Add failure if all attempts failed
            if (!success) {
                failures.add(
                    CollectionFailure(
                        component = "dumpsys $service",
                        error = lastError ?: "Failed to collect",
                        isCritical = isCritical
                    )
                )
            }
        }

        // Collect device info
        val recordingDuration = if (logFile != null) {
            // Estimate duration from file timestamp if available
            val timestamp = logFile.name.removePrefix("recording_").removeSuffix(".log").toLongOrNull() ?: 0L
            if (timestamp > 0) {
                System.currentTimeMillis() - timestamp
            } else {
                0L
            }
        } else {
            0L
        }

        val deviceInfo = collectDeviceInfo(context, recordingDuration)

        // Check minimum viable data
        val hasLogFile = logFile?.exists() == true && logFile.length() > 0
        val hasTelecomDumpsys = dumpsysData.containsKey("telecom")

        if (!hasLogFile && !hasTelecomDumpsys) {
            Log.e(TAG, "No viable data collected")
            return@withContext CollectionResult.Failure(
                error = "No diagnostic data could be collected. App logs and critical system state are unavailable.",
                failures = failures
            )
        }

        // Create zip file with all collected data
        val zipFile = try {
            createZipFile(context, logFile, dumpsysData, deviceInfo, failures)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create zip file", e)
            return@withContext CollectionResult.Failure(
                error = "Failed to package diagnostic data: ${e.message}",
                failures = failures
            )
        }

        if (zipFile == null) {
            return@withContext CollectionResult.Failure(
                error = "Failed to create diagnostic package",
                failures = failures
            )
        }

        // Save zip file to Downloads/miclock_logs folder
        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, zipFile, recordingStartTime)
        } else {
            // For Android 9 and below, use legacy external storage
            // For now, we'll just use the cache file and skip Downloads save
            null
        }

        // Handle save to Downloads failure
        if (savedUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.e(TAG, "Failed to save to Downloads")
            failures.add(
                CollectionFailure(
                    component = "Save to Downloads",
                    error = "Failed to save file to Downloads/miclock_logs folder",
                    isCritical = false
                )
            )
            
            // Offer to share from cache as fallback
            val cacheShareIntent = createShareIntent(context, zipFile)
            return@withContext CollectionResult.PartialSuccess(cacheShareIntent, failures)
        }

        // Generate filename for notification
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recordingStartTime))
        val fileName = "miclock_debug_logs_${timestamp}.zip"

        // Show notification with file location and share action
        if (savedUri != null) {
            showDebugLogsSavedNotification(context, savedUri, fileName)
        }

        // Create share intent with Downloads Uri (or cache file for Android 9-)
        val shareIntent = if (savedUri != null) {
            createShareIntentFromUri(context, savedUri, fileName)
        } else {
            // Android 9 and below: share from cache
            createShareIntent(context, zipFile)
        }

        // Determine result type based on failures
        return@withContext if (failures.isEmpty()) {
            Log.d(TAG, "Collection completed successfully")
            CollectionResult.Success(shareIntent)
        } else {
            Log.d(TAG, "Collection completed with ${failures.size} failure(s)")
            CollectionResult.PartialSuccess(shareIntent, failures)
        }
    }

    /**
     * Executes a dumpsys command and returns output.
     * @param service Service name (e.g., "audio", "telecom")
     * @return Command output as string
     * @throws Exception if command fails or times out
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun executeDumpsys(service: String): String = withContext(Dispatchers.IO) {
        val command = arrayOf("dumpsys", service)
        val process = Runtime.getRuntime().exec(command)

        try {
            // Wait for process with timeout
            val completed = process.waitFor(DUMPSYS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                throw Exception("Timeout after $DUMPSYS_TIMEOUT_SECONDS seconds")
            }

            // Check exit code
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                throw Exception("Command failed with exit code $exitCode: $errorOutput")
            }

            // Read output with size limit
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var totalBytes = 0
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val lineBytes = line!!.toByteArray().size + 1 // +1 for newline
                if (totalBytes + lineBytes > MAX_DUMPSYS_SIZE_BYTES) {
                    output.append("\n[Output truncated at ${MAX_DUMPSYS_SIZE_BYTES / 1024}KB limit]")
                    break
                }
                output.append(line).append("\n")
                totalBytes += lineBytes
            }

            return@withContext output.toString()

        } finally {
            process.destroy()
        }
    }

    /**
     * Collects device and app metadata.
     * @param context Application context
     * @param recordingDuration Duration of recording session in milliseconds
     * @return Formatted device info string
     */
    private fun collectDeviceInfo(context: Context, recordingDuration: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = dateFormat.format(Date())

        // Format recording duration as HH:MM:SS
        val hours = TimeUnit.MILLISECONDS.toHours(recordingDuration)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(recordingDuration) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(recordingDuration) % 60
        val durationFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // Get app version from package manager
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        // Get service state
        val serviceState = MicLockService.state.value

        return buildString {
            appendLine("Mic-Lock Debug Information")
            appendLine("==========================")
            appendLine()
            appendLine("Device Information:")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Model: ${Build.MODEL}")
            appendLine("- Device: ${Build.DEVICE}")
            appendLine("- Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("App Information:")
            appendLine("- Version: $versionName ($versionCode)")
            appendLine("- Package: ${context.packageName}")
            appendLine()
            appendLine("Recording Information:")
            appendLine("- Timestamp: $timestamp")
            appendLine("- Duration: $durationFormatted")
            appendLine()
            appendLine("Service State:")
            appendLine("- Running: ${serviceState.isRunning}")
            appendLine("- Paused by Silence: ${serviceState.isPausedBySilence}")
            appendLine("- Paused by Screen Off: ${serviceState.isPausedByScreenOff}")
            appendLine("- Current Device Address: ${serviceState.currentDeviceAddress ?: "None"}")
            appendLine("- Delayed Activation Pending: ${serviceState.isDelayedActivationPending}")
            if (serviceState.isDelayedActivationPending) {
                appendLine("- Delayed Activation Remaining: ${serviceState.delayedActivationRemainingMs}ms")
            }
        }
    }

    /**
     * Creates zip file with all diagnostic data.
     * @param context Application context
     * @param logFile App log file (optional)
     * @param dumpsysData Map of service name to dumpsys output
     * @param deviceInfo Device metadata string
     * @param failures List of collection failures
     * @return Zip file, or null on failure
     */
    private suspend fun createZipFile(
        context: Context,
        logFile: File?,
        dumpsysData: Map<String, String>,
        deviceInfo: String,
        failures: List<CollectionFailure>
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Create log directory
            val logDir = File(context.cacheDir, LOG_DIR)
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory")
                return@withContext null
            }

            // Create zip file with timestamp
            val timestamp = System.currentTimeMillis()
            val zipFile = File(logDir, "debug_package_$timestamp.zip")

            java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                // Add collection report
                val report = createCollectionReport(logFile, dumpsysData, failures)
                zip.putNextEntry(java.util.zip.ZipEntry("collection_report.txt"))
                zip.write(report.toByteArray())
                zip.closeEntry()

                // Add app logs if available
                if (logFile?.exists() == true) {
                    zip.putNextEntry(java.util.zip.ZipEntry("app_logs.txt"))
                    logFile.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }

                // Add dumpsys outputs
                for ((service, output) in dumpsysData) {
                    val filename = "dumpsys_${service.replace(".", "_")}.txt"
                    zip.putNextEntry(java.util.zip.ZipEntry(filename))
                    zip.write(output.toByteArray())
                    zip.closeEntry()
                }

                // Add device info
                zip.putNextEntry(java.util.zip.ZipEntry("device_info.txt"))
                zip.write(deviceInfo.toByteArray())
                zip.closeEntry()
            }

            Log.d(TAG, "Created zip file: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            return@withContext zipFile

        } catch (e: Exception) {
            Log.e(TAG, "Error creating zip file", e)
            return@withContext null
        }
    }

    /**
     * Creates a collection report documenting what succeeded and failed.
     * Includes context information for each failed component to help users understand relevance.
     * @param logFile App log file (optional)
     * @param dumpsysData Map of service name to dumpsys output
     * @param failures List of collection failures
     * @return Formatted report string
     */
    private fun createCollectionReport(
        logFile: File?,
        dumpsysData: Map<String, String>,
        failures: List<CollectionFailure>
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = dateFormat.format(Date())

        return buildString {
            appendLine("=== Debug Log Collection Report ===")
            appendLine("Timestamp: $timestamp")
            appendLine()

            if (failures.isNotEmpty()) {
                appendLine("=== Collection Status ===")
                appendLine("⚠ ${failures.size} component(s) failed:")
                for (failure in failures) {
                    val severity = if (failure.isCritical) "CRITICAL" else "WARNING"
                    appendLine("  ✗ [$severity] ${failure.component}")
                    appendLine("    Error: ${failure.error}")
                    
                    // Add context information for dumpsys services
                    val serviceName = failure.component.removePrefix("dumpsys ")
                    val context = SERVICE_CONTEXT[serviceName]
                    if (context != null) {
                        appendLine("    Context: $context")
                    }
                }
                appendLine()
            }

            appendLine("=== Collected Data ===")

            // App logs status
            if (logFile?.exists() == true) {
                appendLine("App Logcat: ✓ (${logFile.length()} bytes)")
            } else {
                appendLine("App Logcat: ✗ (not available)")
            }

            // Dumpsys status
            for (service in DUMPSYS_SERVICES) {
                if (dumpsysData.containsKey(service)) {
                    appendLine("dumpsys $service: ✓")
                } else {
                    appendLine("dumpsys $service: ✗")
                }
            }

            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("=== Notes ===")
                appendLine("⚠ Some system state data missing. Logs may be incomplete.")
                appendLine()
                appendLine("=== Context Information ===")
                appendLine("Understanding what's missing:")
                for (failure in failures) {
                    val serviceName = failure.component.removePrefix("dumpsys ")
                    val context = SERVICE_CONTEXT[serviceName]
                    if (context != null) {
                        appendLine("• $serviceName: $context")
                    }
                }
            }
        }
    }

    /**
     * Saves zip file to Downloads/miclock_logs folder using MediaStore API with timestamped filename.
     * @param context Application context
     * @param zipFile Temporary zip file from cache
     * @param recordingStartTime Timestamp when recording started (for filename generation)
     * @return Uri of saved file in Downloads, or null on failure
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveToDownloads(
        context: Context,
        zipFile: File,
        recordingStartTime: Long
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Generate filename with timestamp from recording start time
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recordingStartTime))
            val fileName = "miclock_debug_logs_${timestamp}.zip"

            Log.d(TAG, "Saving debug logs to Downloads/miclock_logs/$fileName")

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/miclock_logs")
            }

            // Insert the file entry
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri == null) {
                Log.e(TAG, "Failed to create MediaStore entry")
                return@withContext null
            }

            // Copy zip file from cache to Downloads
            resolver.openOutputStream(uri)?.use { output ->
                zipFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open output stream for $uri")
                resolver.delete(uri, null, null)
                return@withContext null
            }


            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Log.d(TAG, "Successfully saved debug logs to Downloads: $uri")

            // Delete temporary cache zip file after successful save
            if (zipFile.delete()) {
                Log.d(TAG, "Deleted temporary cache file: ${zipFile.absolutePath}")
            } else {
                Log.w(TAG, "Failed to delete temporary cache file: ${zipFile.absolutePath}")
            }

            return@withContext uri

        } catch (e: IOException) {
            Log.e(TAG, "IOException while saving to Downloads", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while saving to Downloads", e)
            return@withContext null
        }
    }

    /**
     * Creates a share intent for the diagnostic package from a file.
     * @param context Application context
     * @param zipFile Zip file to share
     * @return Share intent configured with FileProvider URI
     */
    private fun createShareIntent(context: Context, zipFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        return createShareIntentFromUri(context, uri)
    }

    /**
     * Creates a share intent for the diagnostic package from a Uri.
     * @param context Application context
     * @param uri Uri of the zip file to share
     * @param fileName Optional filename to display in share sheet
     * @return Share intent configured with the provided URI
     */
    private fun createShareIntentFromUri(context: Context, uri: Uri, fileName: String? = null): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mic-Lock Debug Logs")
            if (fileName != null) {
                putExtra(Intent.EXTRA_TITLE, fileName)
                // Add clip data with label for better filename display
                clipData = android.content.ClipData.newRawUri(fileName, uri)
            }
            putExtra(
                Intent.EXTRA_TEXT,
                "Debug logs from Mic-Lock app. " +
                    "This package contains application logs and system audio state information."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Gets context information for a collection failure.
     * Helps users understand when the missing data is relevant to their issue.
     * @param failure The collection failure
     * @return Context string explaining when this data is relevant, or null if no context available
     */
    fun getFailureContext(failure: CollectionFailure): String? {
        val serviceName = failure.component.removePrefix("dumpsys ")
        return SERVICE_CONTEXT[serviceName]
    }

    /**
     * Shows a notification that debug logs have been saved to Downloads.
     * Includes a "Share" action button to open the share sheet with the saved file.
     *
     * @param context Application context
     * @param savedUri Uri of the saved file in Downloads
     * @param fileName Name of the saved file
     */
    fun showDebugLogsSavedNotification(context: Context, savedUri: Uri, fileName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open the file directly
        val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(savedUri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            101,
            openFileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create share intent with the saved file Uri and proper filename
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, savedUri)
            putExtra(Intent.EXTRA_SUBJECT, "Mic-Lock Debug Logs")
            putExtra(Intent.EXTRA_TITLE, fileName) // Add filename for better display
            putExtra(
                Intent.EXTRA_TEXT,
                "Debug logs from Mic-Lock app. " +
                    "This package contains application logs and system audio state information."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Add clip data with label for better filename display
            clipData = android.content.ClipData.newRawUri(fileName, savedUri)
        }

        // Wrap in chooser and create PendingIntent for share action
        val chooserIntent = Intent.createChooser(shareIntent, context.getString(R.string.share_debug_logs))
        
        val sharePendingIntent = PendingIntent.getActivity(
            context,
            100,
            chooserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification with BigTextStyle
        val notification = NotificationCompat.Builder(context, MicLockService.RESTART_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.debug_logs_saved_title))
            .setContentText(context.getString(R.string.logs_saved_to_downloads))
            .setSmallIcon(R.drawable.ic_mic_tile)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.debug_logs_saved_big_text))
            )
            .setContentIntent(contentPendingIntent)
            .addAction(
                0, // No icon for action
                context.getString(R.string.share),
                sharePendingIntent
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Show notification with unique ID
        notificationManager.notify(DEBUG_LOGS_SAVED_NOTIF_ID, notification)

        Log.d(TAG, "Debug logs saved notification shown for file: $fileName")
    }
}
