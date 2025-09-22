package io.github.miclock.audio.model

import android.media.AudioDeviceInfo
import android.media.MicrophoneInfo

data class MicChoice(
    val device: AudioDeviceInfo,
    val micInfo: MicrophoneInfo?    // may be null if not mappable
)