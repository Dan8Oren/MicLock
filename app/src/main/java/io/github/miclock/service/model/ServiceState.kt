package io.github.miclock.service.model

data class ServiceState(
    val isRunning: Boolean = false,
    val isPausedBySilence: Boolean = false,
    val isPausedByScreenOff: Boolean = false,
    val currentDeviceAddress: String? = null,
    val isDelayedActivationPending: Boolean = false,
    val delayedActivationRemainingMs: Long = 0,
    val pausedBySilenceTimestamp: Long = 0L,
    val wasSilencedBeforeScreenOff: Boolean = false,
)
