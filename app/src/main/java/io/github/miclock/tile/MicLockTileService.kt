package io.github.miclock.tile

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.miclock.R
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import io.github.miclock.ui.MainActivity
import io.github.miclock.util.ApiGuard
import kotlinx.coroutines.*

const val TILE_TEXT = "MicLock"
const val EXTRA_START_SERVICE_FROM_TILE = "start_service_from_tile"

class MicLockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectionJob: Job? = null
    private var failureReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "MicLockTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")

        failureReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ApiGuard.onApi34_UpsideDownCake(
                    block = {
                        if (intent.action == MicLockService.ACTION_TILE_START_FAILED) {
                            val reason = intent.getStringExtra(MicLockService.EXTRA_FAILURE_REASON)
                            if (reason == MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION) {
                                Log.d(TAG, "Service failed due to foreground restrictions - launching MainActivity")
                                launchMainActivityFallback()
                            }
                        }
                    },
                    onUnsupported = {
                        Log.d(TAG, "Received onReceive on unsupported API. Doing nothing.")
                    },
                )
            }
        }

        val filter = IntentFilter(MicLockService.ACTION_TILE_START_FAILED)
        if (ApiGuard.isApi26_O_OrAbove()) {
            registerReceiver(failureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, failureReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        val actualState = getCurrentAppState()
        updateTileState(actualState)

        stateCollectionJob = scope.launch {
            try {
                MicLockService.state.collect { state ->
                    updateTileState(state)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to observe service state: ${e.message}")
                val fallbackState = checkServiceRunningState()
                updateTileState(fallbackState)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "Tile stopped listening")

        failureReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering failure receiver: ${e.message}")
            }
        }
        failureReceiver = null

        stateCollectionJob?.cancel()
        stateCollectionJob = null
    }

    private fun hasAllPerms(): Boolean {
        val micGranted = try {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking RECORD_AUDIO permission: ${e.message}")
            false
        }

        val notifs = try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 33) {
                nm.areNotificationsEnabled() &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                nm.areNotificationsEnabled()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking notification permissions: ${e.message}")
            false
        }

        val hasPerms = micGranted && notifs
        Log.d(TAG, "Permission check: mic=$micGranted, notifs=$notifs, hasAll=$hasPerms")
        return hasPerms
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")

        val currentPerms = hasAllPerms()
        Log.d(TAG, "onClick permission check result: $currentPerms")

        if (!currentPerms) {
            Log.d(TAG, "Permissions missing - forcing tile update to show unavailable state")
            updateTileState(getCurrentAppState())
            return
        }

        val currentState = getCurrentAppState()

        if (currentState.isRunning) {
            val intent = Intent(this, MicLockService::class.java)
            intent.action = MicLockService.ACTION_STOP
            Log.d(TAG, "Stopping MicLock service via tile")
            try {
                startService(intent)
                Log.d(TAG, "Successfully sent stop intent to running service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop intent to service: ${e.message}", e)
            }
        } else {
            val intent = Intent(this, MicLockService::class.java).apply {
                action = MicLockService.ACTION_START_USER_INITIATED
                putExtra("from_tile", true)
            }

            Log.d(TAG, "Attempting direct service start from tile")
            try {
                ContextCompat.startForegroundService(this, intent)
                Log.d(TAG, "Direct service start request sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service directly: ${e.message}", e)
                createTileFailureNotification("Service failed to start: ${e.message}")
            }
        }
    }

    private fun launchMainActivityFallback() {
        ApiGuard.onApi34_UpsideDownCake(
            block = {
                Log.d(TAG, "Launching MainActivity as fallback for service start")
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_START_SERVICE_FROM_TILE, true)
                }

                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
                )

                try {
                    @Suppress("NewApi")
                    ApiGuard.onApi34_UpsideDownCake(block = {
                        startActivityAndCollapse(pendingIntent)
                    },)
                    Log.d(TAG, "MainActivity fallback launched successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "MainActivity fallback also failed: ${e.message}", e)
                    createTileFailureNotification("Both service start and app launch failed: ${e.message}")
                }
            },
            onUnsupported = {
                Log.e(TAG, "launchMainActivityFallback called on unsupported device. This should not happen.")
                createTileFailureNotification("MainActivity fallback not supported on this Android version.")
            },
        )
    }

    private fun createTileFailureNotification(reason: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val restartIntent = Intent(this, MainActivity::class.java)
        val restartPI = PendingIntent.getActivity(
            this,
            6,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(this, MicLockService.RESTART_CHANNEL_ID)
            .setContentTitle("MicLock Tile Failed Unexpectedly")
            .setContentText("Tap to open app and start protection")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText("$reason. Tap to open app and start protection manually."),
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(restartPI)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(45, notification)
        Log.d(TAG, "Tile failure notification created: $reason")
    }

    private fun updateTileState(state: ServiceState) {
        val tile = qsTile ?: return

        // Always re-check permissions fresh
        val hasPerms = hasAllPerms()
        Log.d(
            TAG,
            "updateTileState: hasPerms=$hasPerms, isRunning=${state.isRunning}, isPaused=${state.isPausedBySilence}",
        )

        when {
            !hasPerms -> {
                // Permissions missing - show unavailable state
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "No Permission"
                tile.contentDescription = "Tap to grant microphone and notification permissions"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_off)
                Log.d(TAG, "Tile set to 'No Permission' state")
            }
            !state.isRunning -> {
                // Service is OFF
                tile.state = Tile.STATE_INACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to start microphone protection"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_off)
                Log.d(TAG, "Tile set to INACTIVE state")
            }
            state.isPausedBySilence -> {
                // Service is PAUSED
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = TILE_TEXT
                tile.contentDescription = "Microphone protection paused"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_pause)
                Log.d(TAG, "Tile set to PAUSED state")
            }
            else -> {
                // Service is ON
                tile.state = Tile.STATE_ACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to stop microphone protection"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_on)
                Log.d(TAG, "Tile set to ACTIVE state")
            }
        }

        tile.updateTile()
        Log.d(
            TAG,
            "Tile updated - Running: ${state.isRunning}, Paused: ${state.isPausedBySilence}, HasPerms: $hasPerms",
        )
    }

    private fun getCurrentAppState(): ServiceState {
        // Get current state from StateFlow
        val currentState = MicLockService.state.value

        // If StateFlow says service isn't running, double-check with system services
        val actualState = if (!currentState.isRunning) {
            val systemState = checkServiceRunningState()
            if (systemState.isRunning) {
                Log.d(TAG, "StateFlow out of sync - service is actually running according to system")
                systemState
            } else {
                currentState
            }
        } else {
            currentState
        }

        return actualState
    }

    private fun checkServiceRunningState(): ServiceState {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == MicLockService::class.java.name }
            ServiceState(isRunning = isRunning, isPausedBySilence = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check service running state: ${e.message}")
            ServiceState(isRunning = false, isPausedBySilence = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
