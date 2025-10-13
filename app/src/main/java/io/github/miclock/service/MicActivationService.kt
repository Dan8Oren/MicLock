package io.github.miclock.service

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.github.miclock.service.model.ServiceState

/**
 * Interface for services that can activate microphone functionality.
 * This interface breaks the circular dependency between DelayedActivationManager and MicLockService.
 */
interface MicActivationService {
    
    /**
     * Gets the current service state.
     * @return current ServiceState
     */
    fun getCurrentState(): ServiceState
    
    /**
     * Checks if the microphone is actively being held (recording loop is active).
     * @return true if mic is actively held, false otherwise
     */
    fun isMicActivelyHeld(): Boolean
    
    /**
     * Checks if the service was manually stopped by the user.
     * @return true if manually stopped by user, false otherwise
     */
    fun isManuallyStoppedByUser(): Boolean
    
    /**
     * Starts microphone holding functionality.
     * @param fromDelayCompletion true if called from delay completion, false otherwise
     */
    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startMicHolding(fromDelayCompletion: Boolean)
}