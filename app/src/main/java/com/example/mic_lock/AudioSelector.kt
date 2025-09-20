package com.example.mic_lock

import android.media.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

data class MicChoice(
    val device: AudioDeviceInfo,
    val micInfo: MicrophoneInfo?    // may be null if not mappable
)

data class RouteInfo(
    val deviceInfo: AudioDeviceInfo?,
    val micInfo: MicrophoneInfo?,
    val sessionId: Int,
    val isOnPrimaryArray: Boolean,
    val deviceAddress: String?,
    val micPosition: MicrophoneInfo.Coordinate3F?
)

object AudioSelector {

    data class AudioFormatConfig(
        val sampleRate: Int,
        val channelMask: Int,
        val encoding: Int
    )

    fun getAudioFormatCandidates(): List<AudioFormatConfig> {
        return listOf(
            AudioFormatConfig(8000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT),
            AudioFormatConfig(16000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT),
            AudioFormatConfig(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        )
    }
    private const val TAG = "MicLock"

    /** All built-in mic input devices, best-effort joined to MicrophoneInfo by address. */
    @RequiresApi(Build.VERSION_CODES.P)
    fun listBuiltinMicChoices(am: AudioManager): List<MicChoice> {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val mics: List<MicrophoneInfo> = try { am.microphones } catch (_: Throwable) { emptyList() }

        return inputs.map { dev ->
            val mi = mics.firstOrNull { (it.address ?: "") == (dev.address ?: "") }
            MicChoice(dev, mi)
        }
    }

    /** Bottom mic = smallest Y; origin is bottom-left-back. */




    

    fun encodingName(enc: Int) = when (enc) {
        AudioFormat.ENCODING_PCM_8BIT  -> "PCM8"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        else -> "ENC($enc)"
    }

    fun channelName(mask: Int) = when (mask) {
        AudioFormat.CHANNEL_IN_MONO -> "mono"
        AudioFormat.CHANNEL_IN_STEREO -> "stereo"
        else -> "chMask($mask)"
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun fmtPos(p: MicrophoneInfo.Coordinate3F?): String =
        if (p == null) "unknown" else "x=%.3f y=%.3f z=%.3f".format(p.x, p.y, p.z)

    @RequiresApi(Build.VERSION_CODES.M)
    fun validateCurrentRoute(audioManager: AudioManager, sessionId: Int): RouteInfo? {
        return try {
            val activeConfigs = audioManager.activeRecordingConfigurations
            val myConfig = activeConfigs.firstOrNull { it.clientAudioSessionId == sessionId }
            
            if (myConfig != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val inputDevice = myConfig.audioDevice
                if (inputDevice != null) {
                    val deviceAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) inputDevice.address else null
                    
                    // Find matching microphone info
                    var matchingMic: MicrophoneInfo? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && deviceAddress != null) {
                        try {
                            val microphones = audioManager.microphones
                            matchingMic = microphones.firstOrNull { it.address == deviceAddress }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not retrieve microphone info: ${e.message}")
                        }
                    }
                    
                    val isOnPrimary = isOnPrimaryArray(matchingMic)
                    
                    return RouteInfo(
                        deviceInfo = inputDevice,
                        micInfo = matchingMic,
                        sessionId = sessionId,
                        isOnPrimaryArray = isOnPrimary,
                        deviceAddress = deviceAddress,
                        micPosition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) matchingMic?.position else null
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error validating route: ${e.message}")
            null
        }
    }

    fun isOnPrimaryArray(micInfo: MicrophoneInfo?): Boolean {
        return if (micInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && micInfo.position != null) {
            // Primary array typically has Y >= 0 (not bottom microphone)
            // Bottom mic has the smallest Y value (negative or close to 0)
            micInfo.position.y >= 0.0f
        } else {
            // If we can't determine position, assume it's primary array for safety
            true
        }
    }

        fun isRouteBad(routeInfo: RouteInfo, requestedStereo: Boolean, actualChannelCount: Int): Boolean {
        val isBottomMic = routeInfo.micInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.position != null) {
                it.position.y < 0.0f
            } else false
        } ?: (routeInfo.deviceAddress == "@:bottom")

        return !routeInfo.isOnPrimaryArray ||
                isBottomMic ||
                (requestedStereo && actualChannelCount < 2)
    }

    fun getRouteDebugInfo(routeInfo: RouteInfo): String {
        val parts = mutableListOf<String>()
        
        parts.add("SessionID: ${routeInfo.sessionId}")
        
        routeInfo.deviceInfo?.let { device ->
            parts.add("Device: '${device.productName}'")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts.add("Address: '${device.address ?: "unknown"}'")
                parts.add("Type: ${device.type}")
                parts.add("Channels: ${device.channelCounts.contentToString()}")
                parts.add("SampleRates: ${device.sampleRates.contentToString()}")
            }
        }
        
        routeInfo.micInfo?.let { mic ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                parts.add("MicDesc: '${mic.description}'")
                parts.add("MicPos: ${fmtPos(mic.position)}")
            }
        }
        
        parts.add("PrimaryArray: ${routeInfo.isOnPrimaryArray}")
        
        return parts.joinToString(", ")
    }
}
