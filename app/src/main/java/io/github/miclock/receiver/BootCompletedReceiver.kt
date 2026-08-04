package io.github.miclock.receiver

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.miclock.service.MicLockService

/**
 * After boot / unlock / package replaced, schedules **MicLockService** via
 * [PendingIntent.getForegroundService] + [AlarmManager]. Unlike [Context.startForegroundService]
 * from app code or [android.app.Activity] launched from a worker, alarm delivery is a
 * **system-sent PendingIntent** — not blocked by goo.gle/android-bal the way `BootResumeActivity` was.
 *
 * Delay lets CE storage and audio stack settle after reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val shouldSchedule =
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> true
                else -> false
            }
        if (!shouldSchedule) {
            Log.d(TAG, "Ignoring action: $action")
            return
        }

        Log.d(TAG, "Received $action")

        val appCtx = context.applicationContext
        val micGranted =
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            Log.d(
                TAG,
                "Microphone permission not granted. MicLockService will not start automatically.",
            )
            return
        }

        scheduleAlarmMicStart(appCtx)
    }

    private fun scheduleAlarmMicStart(appCtx: Context) {
        val svcIntent =
            Intent(appCtx, MicLockService::class.java).apply {
                action = MicLockService.ACTION_START_USER_INITIATED
            }

        val piFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                })

        val pi: PendingIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    appCtx,
                    ALARM_REQUEST_CODE,
                    svcIntent,
                    piFlags,
                )
            } else {
                PendingIntent.getService(appCtx, ALARM_REQUEST_CODE, svcIntent, piFlags)
            }

        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerElapsed = SystemClock.elapsedRealtime() + DELAY_MILLIS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsed,
                    pi,
                )
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            }
            Log.d(TAG, "Scheduled mic FGS via alarm + getForegroundService in ${DELAY_MILLIS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm schedule failed; trying direct startForegroundService", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appCtx, svcIntent)
                } else {
                    appCtx.startService(svcIntent)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Direct startForegroundService also failed", e2)
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val ALARM_REQUEST_CODE = 0x4D434C42 // 'MCB'

        /** Elapsed realtime delay so boot / unlock / audio stack are ready. */
        private const val DELAY_MILLIS = 15_000L
    }
}
