package io.github.miclock.tile

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.miclock.R
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class MicLockTileService : TileService() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectionJob: Job? = null
    
    companion object {
        private const val TAG = "MicLockTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")
        
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

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")
        
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send foreground start intent to service: ${e.message}", e)
                // Fallback: try regular startService if foreground start fails
                try {
                    startService(intent)
                    Log.i(TAG, "Fallback to regular startService succeeded")
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "Fallback startService also failed: ${fallbackException.message}", fallbackException)
                }
            }
        }
    }

    private fun updateTileState(state: ServiceState) {
        val tile = qsTile ?: return
        
        when {
            !state.isRunning -> {
                // Service is OFF
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Mic Protection"
                tile.contentDescription = "Tap to start microphone protection"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_off)
            }
            state.isPausedBySilence -> {
                // Service is PAUSED
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Mic Protection"
                tile.contentDescription = "Microphone protection paused"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_pause)
            }
            else -> {
                // Service is ON
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Mic Protection"
                tile.contentDescription = "Tap to stop microphone protection"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_mic_on)
            }
        }
        
        tile.updateTile()
        Log.d(TAG, "Tile updated - Running: ${state.isRunning}, Paused: ${state.isPausedBySilence}")
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