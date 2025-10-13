package io.github.miclock.service

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.github.miclock.data.Prefs
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages delayed activation of microphone functionality when screen turns on.
 * Handles race conditions, state validation, and proper cleanup of delay operations.
 */
open class DelayedActivationManager(
    private val context: Context,
    private val service: MicActivationService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DelayedActivationManager"
    }

    // State tracking
    private var delayJob: Job? = null
    private val lastScreenOnTime = AtomicLong(0L)
    private val delayStartTime = AtomicLong(0L)
    private val isActivationPending = AtomicBoolean(false)

    /**
     * Schedules a delayed activation after the specified delay period.
     * Cancels any existing pending activation before scheduling new one.
     * 
     * @param delayMs delay in milliseconds before activation
     * @return true if delay was scheduled, false if conditions don't allow delay
     */
    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun scheduleDelayedActivation(delayMs: Long): Boolean {
        val currentTime = getCurrentTimeMs()
        lastScreenOnTime.set(currentTime)
        
        Log.d(TAG, "Scheduling delayed activation with ${delayMs}ms delay")
        
        // Cancel any existing delay operation
        cancelDelayedActivation()
        
        // Validate that delay should be applied
        if (!shouldApplyDelay()) {
            Log.d(TAG, "Delay not applicable due to service state conditions")
            return false
        }
        
        // Start new delay operation
        delayStartTime.set(currentTime)
        isActivationPending.set(true)
        
        delayJob = scope.launch {
            try {
                Log.d(TAG, "Starting delay countdown: ${delayMs}ms")
                delay(delayMs)
                
                // Check if this delay operation is still valid (not superseded by newer screen events)
                if (isActivationPending.get() && delayStartTime.get() == currentTime) {
                    Log.d(TAG, "Delay completed, activating microphone")
                    
                    // Validate service state one more time before activation
                    if (shouldRespectExistingState()) {
                        Log.d(TAG, "Service state changed during delay, respecting current state")
                        isActivationPending.set(false)
                        handleServiceStateConflict()
                        return@launch
                    }
                    
                    // Clear pending state before activation
                    isActivationPending.set(false)
                    
                    // Activate microphone functionality
                    // Pass fromDelayCompletion=true to skip startForeground (already started in handleStartHolding)
                    service.startMicHolding(fromDelayCompletion = true)
                } else {
                    Log.d(TAG, "Delay operation superseded by newer event, not activating")
                    isActivationPending.set(false)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Delay operation cancelled")
                isActivationPending.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error during delayed activation", e)
                isActivationPending.set(false)
            }
        }
        
        return true
    }

    /**
     * Cancels any pending delayed activation operation.
     * 
     * @return true if there was a pending operation that was cancelled, false otherwise
     */
    fun cancelDelayedActivation(): Boolean {
        val wasPending = isActivationPending.get()
        
        if (wasPending) {
            Log.d(TAG, "Cancelling delayed activation")
        }
        
        delayJob?.cancel()
        delayJob = null
        isActivationPending.set(false)
        delayStartTime.set(0L)
        
        return wasPending
    }

    /**
     * Checks if there is currently a delayed activation pending.
     * 
     * @return true if activation is pending, false otherwise
     */
    fun isActivationPending(): Boolean = isActivationPending.get()

    /**
     * Gets the remaining time in milliseconds for the current delay operation.
     * 
     * @return remaining milliseconds, or 0 if no delay is pending
     */
    fun getRemainingDelayMs(): Long {
        if (!isActivationPending.get()) return 0L
        
        val startTime = delayStartTime.get()
        val delayMs = Prefs.getScreenOnDelayMs(context)
        val currentTime = getCurrentTimeMs()
        val elapsed = currentTime - startTime
        val remaining = (delayMs - elapsed).coerceAtLeast(0L)
        
        return remaining
    }

    /**
     * Gets the current time in milliseconds. Can be overridden for testing.
     */
    protected open fun getCurrentTimeMs(): Long = System.currentTimeMillis()

    /**
     * Determines if the current service state should prevent delayed activation.
     * This method checks for conditions that should be respected (manual stops, active sessions, paused states).
     * 
     * @return true if existing state should be respected (delay should not proceed), false otherwise
     */
    fun shouldRespectExistingState(): Boolean {
        val currentState = service.getCurrentState()
        
        return when {
            // Check if mic is actively being held (not just service running)
            // This allows delay when service is running but paused (screen off scenario)
            service.isMicActivelyHeld() -> {
                Log.d(TAG, "Mic is actively being held, respecting existing state")
                true
            }
            
            // Service is paused by silence (another app using mic) - respect the pause
            currentState.isPausedBySilence -> {
                Log.d(TAG, "Service paused by silence, respecting pause state")
                true
            }
            
            // Service is paused by screen-off - this is normal and delay should be applied
            currentState.isPausedByScreenOff -> {
                Log.d(TAG, "Service paused by screen-off, delay can be applied")
                false
            }
            
            // Check if service was manually stopped by user
            service.isManuallyStoppedByUser() -> {
                Log.d(TAG, "Service manually stopped by user, respecting manual stop")
                true
            }
            
            else -> false
        }
    }

    /**
     * Handles conflicts when service state changes during delay period.
     * This method determines appropriate action when existing state should be respected.
     */
    fun handleServiceStateConflict() {
        val currentState = service.getCurrentState()
        
        Log.d(TAG, "Handling service state conflict - isMicActivelyHeld: ${service.isMicActivelyHeld()}, isPausedBySilence: ${currentState.isPausedBySilence}, isPausedByScreenOff: ${currentState.isPausedByScreenOff}")
        
        when {
            service.isMicActivelyHeld() -> {
                Log.d(TAG, "Mic is actively being held, no action needed")
            }
            
            currentState.isPausedBySilence -> {
                Log.d(TAG, "Service is paused by another app, maintaining pause state")
            }
            
            currentState.isPausedByScreenOff -> {
                Log.d(TAG, "Service is paused by screen-off, this is expected")
            }
            
            service.isManuallyStoppedByUser() -> {
                Log.d(TAG, "Service was manually stopped, not overriding user choice")
            }
            
            else -> {
                Log.d(TAG, "No specific conflict resolution needed")
            }
        }
    }

    /**
     * Checks if the app is configured to never re-enable after screen-off.
     * 
     * @return true if never re-enable mode is active, false otherwise
     */
    fun isNeverReactivateMode(): Boolean {
        return Prefs.getScreenOnDelayMs(context) == Prefs.NEVER_REACTIVATE_VALUE
    }

    /**
     * Checks if the app is configured to always keep mic on (ignore screen state).
     * 
     * @return true if always-on mode is active, false otherwise
     */
    fun isAlwaysOnMode(): Boolean {
        return Prefs.getScreenOnDelayMs(context) == Prefs.ALWAYS_KEEP_ON_VALUE
    }

    /**
     * Determines if delay should be applied based on current conditions.
     * 
     * @return true if delay should be applied, false otherwise
     */
    fun shouldApplyDelay(): Boolean {
        val delayMs = Prefs.getScreenOnDelayMs(context)
        
        return when {
            // Never re-enable mode
            delayMs == Prefs.NEVER_REACTIVATE_VALUE -> {
                Log.d(TAG, "Never re-enable mode active, blocking activation")
                false
            }
            
            // Always-on mode (should not apply delay, but should activate immediately)
            delayMs == Prefs.ALWAYS_KEEP_ON_VALUE -> {
                Log.d(TAG, "Always-on mode active, no delay needed")
                false
            }
            
            // Delay is disabled (0ms)
            delayMs <= 0L -> {
                Log.d(TAG, "Delay disabled (${delayMs}ms)")
                false
            }
            
            // Service state should be respected
            shouldRespectExistingState() -> {
                Log.d(TAG, "Existing service state should be respected")
                false
            }
            
            else -> {
                Log.d(TAG, "Delay should be applied (${delayMs}ms)")
                true
            }
        }
    }

    /**
     * Gets the timestamp of the last screen-on event.
     * Used for race condition detection and debugging.
     * 
     * @return timestamp in milliseconds
     */
    fun getLastScreenOnTime(): Long = lastScreenOnTime.get()

    /**
     * Gets the timestamp when the current delay operation started.
     * 
     * @return timestamp in milliseconds, or 0 if no delay is active
     */
    fun getDelayStartTime(): Long = delayStartTime.get()

    /**
     * Cleanup method to be called when the manager is no longer needed.
     * Cancels any pending operations and cleans up resources.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up DelayedActivationManager")
        cancelDelayedActivation()
    }
}