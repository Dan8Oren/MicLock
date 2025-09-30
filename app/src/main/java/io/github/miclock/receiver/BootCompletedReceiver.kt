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
import io.github.miclock.service.MicLockService
import io.github.miclock.util.ApiGuard
import androidx.core.content.ContextCompat
// // // import android.app.ForegroundServiceStartNotAllowedException // Removed to avoid lint error // Removed to avoid lint error // Removed to avoid lint error

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootCompletedReceiver", "Received ${intent.action}")

            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
                nm.areNotificationsEnabled() && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                nm.areNotificationsEnabled()
            }

            if (micGranted && notifsGranted) {
                Log.d("BootCompletedReceiver", "Permissions granted, attempting to start MicLockService.")
                val serviceIntent = Intent(context, MicLockService::class.java)
                
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d("BootCompletedReceiver", "MicLockService started as foreground service successfully.")
                } catch (e: Exception) {
                    ApiGuard.onApi31_S(
                        block = {
                            if (e.javaClass.canonicalName == "android.app.ForegroundServiceStartNotAllowedException") {
                                Log.w("BootCompletedReceiver", "Foreground service start blocked for MicLockService: ${e.message}.")
                            } else {
                                Log.e("BootCompletedReceiver", "Unexpected error starting MicLockService on API 31+: ${e.message}", e)
                            }
                        },
                        onUnsupported = {
                            Log.e("BootCompletedReceiver", "Unexpected error starting MicLockService on older API: ${e.message}", e)
                        }
                    )
                }
            } else {
                Log.d("BootCompletedReceiver", "Permissions not fully granted. MicLockService will not start automatically.")
            }
        }
    }
}
