package io.github.miclock.audio.model

import android.media.AudioDeviceInfo
import android.media.MicrophoneInfo

data class RouteInfo(
    val deviceInfo: AudioDeviceInfo?,
    val micInfo: MicrophoneInfo?,
    val sessionId: Int,
    val isOnPrimaryArray: Boolean,
    val deviceAddress: String?,
    val micPosition: MicrophoneInfo.Coordinate3F?
)