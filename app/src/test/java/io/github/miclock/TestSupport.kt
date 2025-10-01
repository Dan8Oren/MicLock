// app/src/test/java/io/github/miclock/TestSupport.kt
package io.github.miclock

import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Shared testable logic wrappers that can be used across multiple test files
class TestableStateManager {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    fun updateState(running: Boolean? = null, paused: Boolean? = null, deviceAddr: String? = null) {
        _state.update { currentState ->
            ServiceState(
                isRunning = running ?: currentState.isRunning,
                isPausedBySilence = paused ?: currentState.isPausedBySilence,
                currentDeviceAddress = deviceAddr ?: currentState.currentDeviceAddress,
            )
        }
    }

    // Separate method for explicit null assignment
    fun resetDeviceAddress() {
        _state.update { currentState ->
            currentState.copy(currentDeviceAddress = null)
        }
    }
}

class TestableYieldingLogic(
    private val callback: MockableAudioRecordingCallback,
    private val audioManager: MockableAudioManager,
) {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    var isSilenced = false
        private set
    var currentBackoffMs = 500L
        private set
    var lastNotificationText = ""
        private set
    private var silencedTimestamp: Long? = null

    fun startHolding() {
        _state.update { it.copy(isRunning = true, isPausedBySilence = false) }
    }

    fun simulateRecordingConfigChange(clientSilenced: Boolean) {
        if (clientSilenced && !isSilenced) {
            isSilenced = true
            silencedTimestamp = System.currentTimeMillis()
            _state.update { it.copy(isPausedBySilence = true) }
            lastNotificationText = "Paused — mic in use by another app"
        } else if (!clientSilenced && isSilenced) {
            // Note: Actual unsilencing is handled by main loop logic
        }
    }

    fun canAttemptReacquisition(currentTime: Long): Boolean {
        val cooldownComplete = silencedTimestamp?.let { currentTime - it >= 3000L } ?: false

        return cooldownComplete && !audioManager.othersRecording()
    }

    fun getRemainingCooldownMs(currentTime: Long): Long {
        return silencedTimestamp?.let {
            maxOf(0L, 3000L - (currentTime - it))
        } ?: 0L
    }

    fun applyBackoff() {
        currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(5000L)
    }

    fun attemptReacquisition() {
        if (!audioManager.othersRecording()) {
            isSilenced = false
            currentBackoffMs = 500L // Reset backoff
            _state.update { it.copy(isPausedBySilence = false) }
        }
    }
}

// Shared interfaces that can be used across multiple test files
interface MockableAudioRecordingCallback {
    fun onRecordingConfigChanged(silenced: Boolean)
}

interface MockableAudioManager {
    fun othersRecording(): Boolean
    fun validateCurrentRoute(sessionId: Int): MockRouteInfo?
}

// Shared test data classes
data class MockRouteInfo(
    val isOnPrimaryArray: Boolean,
    val deviceAddress: String,
    val actualChannelCount: Int,
)
