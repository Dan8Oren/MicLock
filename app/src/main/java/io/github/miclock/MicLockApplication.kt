package io.github.miclock

import android.app.Application
import android.os.Build
import android.util.Log
import io.github.miclock.util.CrashHandler

/**
 * Application class for Mic-Lock.
 * Registers the crash handler to intercept uncaught exceptions during debug recording.
 */
class MicLockApplication : Application() {

    companion object {
        private const val TAG = "MicLockApplication"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application onCreate")

        // Register crash handler on Android O+ (required for DebugLogCollector)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = CrashHandler(applicationContext, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
            Log.d(TAG, "Crash handler registered")
        } else {
            Log.d(TAG, "Crash handler not registered (requires Android O+)")
        }
    }
}
