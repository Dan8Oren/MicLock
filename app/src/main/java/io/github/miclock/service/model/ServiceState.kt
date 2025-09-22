package io.github.miclock.service.model

data class ServiceState(
    val isRunning: Boolean = false,
    val isPausedBySilence: Boolean = false,
    val currentDeviceAddress: String? = null
)