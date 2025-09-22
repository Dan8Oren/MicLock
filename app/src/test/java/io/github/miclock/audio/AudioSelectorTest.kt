package io.github.miclock.audio

import android.media.AudioFormat
import android.os.Build
import io.github.miclock.audio.model.RouteInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AudioSelector route validation logic.
 * These tests validate the core business logic for identifying good vs bad microphone routes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class AudioSelectorTest {

    // ===== Bad Route Detection Tests =====

    @Test
    fun testIsRouteBad_notOnPrimaryArray_returnsTrue() {
        // Given: RouteInfo not on primary array
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = false,
            deviceAddress = "@:secondary",
            micPosition = null
        )

        // When: Checking if route is bad
        val result = AudioSelector.isRouteBad(routeInfo, requestedStereo = false, actualChannelCount = 1)

        // Then: Should return true (bad route)
        assertTrue("Non-primary array should be detected as bad route", result)
    }

    @Test
    fun testIsRouteBad_bottomMicByAddress_returnsTrue() {
        // Given: RouteInfo with bottom mic address
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = true,
            deviceAddress = "@:bottom",
            micPosition = null
        )

        // When: Checking if route is bad
        val result = AudioSelector.isRouteBad(routeInfo, requestedStereo = false, actualChannelCount = 1)

        // Then: Should return true (bad route)
        assertTrue("Bottom mic address should be detected as bad route", result)
    }

    @Test
    fun testIsRouteBad_stereoRequestedButMonoProvided_returnsTrue() {
        // Given: RouteInfo with good mic but insufficient channels
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = true,
            deviceAddress = "@:primary",
            micPosition = null
        )

        // When: Requesting stereo but only getting mono
        val result = AudioSelector.isRouteBad(routeInfo, requestedStereo = true, actualChannelCount = 1)

        // Then: Should return true (bad route)
        assertTrue("Insufficient channels should be detected as bad route", result)
    }

    @Test
    fun testIsRouteBad_goodRoute_returnsFalse() {
        // Given: RouteInfo with good mic (top array, stereo)
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = true,
            deviceAddress = "@:primary",
            micPosition = null
        )

        // When: Checking good route
        val result = AudioSelector.isRouteBad(routeInfo, requestedStereo = true, actualChannelCount = 2)

        // Then: Should return false (good route)
        assertFalse("Good route should not be detected as bad", result)
    }

    // ===== Primary Array Logic Tests =====

    @Test
    fun testIsOnPrimaryArray_nullMicInfo_returnsTrue() {
        // Given: null MicrophoneInfo
        val micInfo = null

        // When: Checking if on primary array
        val result = AudioSelector.isOnPrimaryArray(micInfo)

        // Then: Should return true (safe default)
        assertTrue("Null mic info should default to primary array for safety", result)
    }

    // ===== Audio Format Candidates Tests =====

    @Test
    fun testGetAudioFormatCandidates_returnsExpectedFormats() {
        // When: Getting audio format candidates
        val candidates = AudioSelector.getAudioFormatCandidates()

        // Then: Should return expected formats
        assertEquals("Should return 3 candidates", 3, candidates.size)
        
        // Verify specific candidates
        val rates = candidates.map { it.sampleRate }
        assertTrue("Should include 8kHz", rates.contains(8000))
        assertTrue("Should include 16kHz", rates.contains(16000))
        assertTrue("Should include 48kHz", rates.contains(48000))
        
        // All should be stereo PCM 16-bit
        candidates.forEach { candidate ->
            assertEquals("Should use stereo channel mask", AudioFormat.CHANNEL_IN_STEREO, candidate.channelMask)
            assertEquals("Should use PCM 16-bit encoding", AudioFormat.ENCODING_PCM_16BIT, candidate.encoding)
        }
    }

    // ===== Utility Function Tests =====

    @Test
    fun testGetRouteDebugInfo_basicRouteInfo_returnsFormattedString() {
        // Given: Basic RouteInfo without complex Android objects
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = true,
            deviceAddress = "@:primary",
            micPosition = null
        )

        // When: Getting debug info
        val debugInfo = AudioSelector.getRouteDebugInfo(routeInfo)

        // Then: Should contain expected basic information
        assertTrue("Should contain session ID", debugInfo.contains("SessionID: 12345"))
        assertTrue("Should contain primary array status", debugInfo.contains("PrimaryArray: true"))
    }

    // ===== Integration Tests =====

    @Test
    fun testIsRouteBad_multipleConditions_detectsBadRoute() {
        // Given: RouteInfo with multiple bad conditions
        val routeInfo = RouteInfo(
            deviceInfo = null,
            micInfo = null,
            sessionId = 12345,
            isOnPrimaryArray = false, // Bad condition 1
            deviceAddress = "@:bottom", // Bad condition 2
            micPosition = null
        )

        // When: Checking if route is bad
        val result = AudioSelector.isRouteBad(routeInfo, requestedStereo = true, actualChannelCount = 1) // Bad condition 3

        // Then: Should detect as bad route
        assertTrue("Route with multiple bad conditions should be detected as bad", result)
    }

    @Test
    fun testChannelName_returnsCorrectNames() {
        // Test the utility method that doesn't require mocking
        assertEquals("mono", AudioSelector.channelName(AudioFormat.CHANNEL_IN_MONO))
        assertEquals("stereo", AudioSelector.channelName(AudioFormat.CHANNEL_IN_STEREO))
    }

    @Test
    fun testEncodingName_returnsCorrectNames() {
        // Test the utility method that doesn't require mocking
        assertEquals("PCM16", AudioSelector.encodingName(AudioFormat.ENCODING_PCM_16BIT))
        assertEquals("PCM8", AudioSelector.encodingName(AudioFormat.ENCODING_PCM_8BIT))
        assertEquals("PCM_FLOAT", AudioSelector.encodingName(AudioFormat.ENCODING_PCM_FLOAT))
    }
}