package io.github.miclock

import android.content.Context
import android.os.PowerManager
import android.util.Log

class WakeLockManager(context: Context, private val tag: String) {
    private var wakeLock: PowerManager.WakeLock? = null
    private val TAG = "WakeLockManager-$tag"

    init {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miclock:$tag").apply {
            setReferenceCounted(false)
        }
    }

    fun acquire() {
        if (wakeLock?.isHeld == false) {
            try {
                wakeLock?.acquire()
                Log.d(TAG, "WakeLock acquired.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
            }
        }
    }

    fun release() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release wake lock: ${e.message}")
            }
        }
    }

    val isHeld: Boolean
        get() = wakeLock?.isHeld ?: false
}
