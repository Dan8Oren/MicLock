package io.github.miclock.audio.model

import android.media.AudioFormat

data class AudioFormatConfig(
    val sampleRate: Int,
    val channelMask: Int,
    val encoding: Int
)