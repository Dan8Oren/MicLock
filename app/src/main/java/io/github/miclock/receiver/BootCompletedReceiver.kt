// app/src/main/java/com/example/miclock/BootCompletedReceiver.kt
package io.github.miclock.receiver

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.miclock.worker.BootServiceWorker
import java.util.concurrent.TimeUnit

/**
 * Receives BOOT_COMPLETED broadcast and schedules a WorkManager task to start the service.
 * This approach is required for Android 15+ to avoid ForegroundServiceStartNotAllowedException
 * when starting foreground services directly from BOOT_COMPLETED.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Received ${intent.action}")

            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            // Check notification status but don't require it
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
                nm.areNotificationsEnabled() && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                nm.areNotificationsEnabled()
            }

            if (!notifsGranted) {
                Log.w(TAG, "Notifications not enabled - service will run without notification updates")
            }

            // Only require microphone permission
            if (micGranted) {
                Log.d(TAG, "Microphone permission granted, scheduling service start via WorkManager")

                // Use WorkManager to start the service with a delay
                // This avoids Android 15+ restrictions on starting foreground services from BOOT_COMPLETED
                val workRequest = OneTimeWorkRequestBuilder<BootServiceWorker>()
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context)
                    .enqueue(workRequest)

                Log.d(TAG, "WorkManager task scheduled to start MicLockService")
            } else {
                Log.d(
                    TAG,
                    "Microphone permission not granted. MicLockService will not start automatically.",
                )
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
