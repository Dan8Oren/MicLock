package io.github.miclock.audio.model

data class AudioFormatConfig(
    val sampleRate: Int,
    val channelMask: Int,
    val encoding: Int,
)
