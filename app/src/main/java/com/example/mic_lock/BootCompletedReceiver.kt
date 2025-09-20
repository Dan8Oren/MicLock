// app/src/main/java/com/example/miclock/BootCompletedReceiver.kt
package io.github.miclock

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasNotif = nm.areNotificationsEnabled()

        if (hasMic && hasNotif) {
            val svc = Intent(context, MicLockService::class.java)
            ContextCompat.startForegroundService(context, svc)
        }
        // If missing perms, do nothing; user can open the app and grant.
    }
}
