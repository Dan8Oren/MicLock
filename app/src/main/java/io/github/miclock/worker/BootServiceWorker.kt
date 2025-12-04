package io.github.miclock.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.miclock.service.MicLockService
import kotlinx.coroutines.delay

/**
 * Worker to start MicLockService after boot with a delay.
 * This is required for Android 15+ to avoid ForegroundServiceStartNotAllowedException
 * when starting foreground services from BOOT_COMPLETED broadcast.
 */
class BootServiceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "BootServiceWorker started - waiting before starting service")

            // Wait a bit to ensure system is ready
            delay(5000) // 5 seconds delay

            Log.d(TAG, "Starting MicLockService from worker")
            val serviceIntent = Intent(applicationContext, MicLockService::class.java)
            applicationContext.startService(serviceIntent)

            Log.d(TAG, "MicLockService started successfully from worker")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MicLockService from worker: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "BootServiceWorker"
        const val WORK_NAME = "boot_service_worker"
    }
}
