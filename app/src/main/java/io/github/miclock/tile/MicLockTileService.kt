package io.github.miclock.tile

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

import androidx.core.content.ContextCompat
import io.github.miclock.R
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import io.github.miclock.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

const val TILE_TEXT = "MicLock"

class MicLockTileService : TileService() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectionJob: Job? = null
    
    companion object {
        private const val TAG = "MicLockTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")
        
        // Force immediate tile update when we start listening
        // This ensures permissions are re-checked
        updateTileState(MicLockService.state.value)
        
        // Start observing service state with fallback
        stateCollectionJob = scope.launch {
            try {
                MicLockService.state.collect { state ->
                    updateTileState(state)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to observe service state: ${e.message}")
                // Fallback: Check if service is actually running
                val fallbackState = checkServiceRunningState()
                updateTileState(fallbackState)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "Tile stopped listening")
        
        // Stop observing service state
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
        
        // Force permission re-check and tile update on every click
        val currentPerms = hasAllPerms()
        Log.d(TAG, "onClick permission check result: $currentPerms")
        
        if (!currentPerms) {
            Log.d(TAG, "Permissions missing - forcing tile update to show unavailable state")
            // Force immediate tile update to reflect current permission state
            updateTileState(MicLockService.state.value)
            return
        }
        
        val currentState = MicLockService.state.value
        val intent = Intent(this, MicLockService::class.java)
        
        if (currentState.isRunning) {
            // Stop the service - use regular startService since service is already running
            intent.action = MicLockService.ACTION_STOP
            Log.d(TAG, "Stopping MicLock service via tile")
            try {
                startService(intent)
                Log.d(TAG, "Successfully sent stop intent to running service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop intent to service: ${e.message}", e)
            }
        } else {
            // Start the service - use startForegroundService for new service start
            intent.action = MicLockService.ACTION_START_USER_INITIATED
            Log.d(TAG, "Starting MicLock service via tile")
            try {
                ContextCompat.startForegroundService(this, intent)
                Log.d(TAG, "Successfully sent foreground start intent to service")
                
                // Schedule a delayed check to verify service actually started
                scope.launch {
                    delay(2000) // Wait 2 seconds for service to start
                    val newState = MicLockService.state.value
                    if (!newState.isRunning) {
                        Log.w(TAG, "Service failed to start properly - updating tile to inactive")
                        updateTileState(newState)
                    } else {
                        Log.d(TAG, "Service start verification successful")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send foreground start intent to service: ${e.message}", e)
                // Update tile to show failure state
                updateTileState(ServiceState(isRunning = false))
                // Fallback: try regular startService if foreground start fails
                try {
                    startService(intent)
                    Log.i(TAG, "Fallback to regular startService succeeded")
                    // Schedule delayed check for fallback as well
                    scope.launch {
                        delay(2000)
                        val newState = MicLockService.state.value
                        if (!newState.isRunning) {
                            Log.w(TAG, "Fallback service also failed to start properly")
                            updateTileState(newState)
                        }
                    }
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "Fallback startService also failed: ${fallbackException.message}", fallbackException)
                    updateTileState(ServiceState(isRunning = false))
                }
            }
        }
    }

    private fun updateTileState(state: ServiceState) {
        val tile = qsTile ?: return
        
        // Always re-check permissions fresh
        val hasPerms = hasAllPerms()
        Log.d(TAG, "updateTileState: hasPerms=$hasPerms, isRunning=${state.isRunning}, isPaused=${state.isPausedBySilence}")
        
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
        Log.d(TAG, "Tile updated - Running: ${state.isRunning}, Paused: ${state.isPausedBySilence}, HasPerms: $hasPerms")
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