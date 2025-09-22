// app/src/test/java/io/github/miclock/service/logic/MicrophoneHoldingLoopTest.kt
package io.github.miclock.service.logic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for the microphone holding loop logic.
 * Tests the core dual-strategy acquisition and fallback behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneHoldingLoopTest {

    @Mock private lateinit var mockAudioSelector: MockableAudioSelector
    @Mock private lateinit var mockMediaRecorderHolder: MockableMediaRecorderHolder
    @Mock private lateinit var mockPrefs: MockablePrefs

    private lateinit var holdingLogic: TestableHoldingLogic

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        holdingLogic = TestableHoldingLogic(
            mockAudioSelector,
            mockMediaRecorderHolder,
            mockPrefs
        )
    }

    @Test
    fun testSuccessScenario_goodRoute_staysInHoldingPhase() = runTest {
        // Given: AudioRecord mode preferred and good route available
        whenever(mockPrefs.getUseMediaRecorder()).thenReturn(false)
        whenever(mockAudioSelector.validateCurrentRoute(any(), any())).thenReturn(
            createGoodRouteInfo()
        )
        whenever(mockAudioSelector.isRouteBad(any(), any(), any())).thenReturn(false)

        // When: Holding loop executes
        val result = holdingLogic.executeHoldingAttempt()

        // Then: AudioRecord succeeds and stays in holding phase
        assertEquals(HoldingResult.SUCCESS_AUDIO_RECORD, result.method)
        assertTrue(result.success)
        verify(mockAudioSelector).isRouteBad(any(), any(), any())
    }

    @Test
    fun testBadRouteFallback_audioRecordBadRoute_fallsBackToMediaRecorder() = runTest {
        // Given: AudioRecord preferred but lands on bad route
        whenever(mockPrefs.getUseMediaRecorder()).thenReturn(false)
        whenever(mockAudioSelector.validateCurrentRoute(any(), any())).thenReturn(
            createBadRouteInfo()
        )
        whenever(mockAudioSelector.isRouteBad(any(), any(), any())).thenReturn(true)
        whenever(mockMediaRecorderHolder.startRecording()).thenReturn(true)

        // When: Holding loop executes
        val result = holdingLogic.executeHoldingAttempt()

        // Then: Falls back to MediaRecorder successfully
        assertEquals(HoldingResult.SUCCESS_MEDIA_RECORDER, result.method)
        assertTrue(result.success)
        verify(mockMediaRecorderHolder).startRecording()
        verify(mockAudioSelector).isRouteBad(any(), any(), any())
    }

    @Test
    fun testDualFailure_bothMethodsFail_waitsAndRetries() = runTest {
        // Given: Both AudioRecord and MediaRecorder fail
        whenever(mockPrefs.getUseMediaRecorder()).thenReturn(false)
        whenever(mockAudioSelector.validateCurrentRoute(any(), any())).thenReturn(null)
        whenever(mockMediaRecorderHolder.startRecording()).thenReturn(false)

        // When: Holding loop executes
        val result = holdingLogic.executeHoldingAttempt()

        // Then: Both methods fail, enters retry state
        assertEquals(HoldingResult.FAILED, result.method)
        assertFalse(result.success)
        assertTrue(result.shouldRetry)
        assertEquals(2000L, result.retryDelayMs) // 2 second wait as per specs
    }

    private fun createGoodRouteInfo(): MockRouteInfo {
        return MockRouteInfo(
            isOnPrimaryArray = true,
            deviceAddress = "@:primary",
            actualChannelCount = 2
        )
    }

    private fun createBadRouteInfo(): MockRouteInfo {
        return MockRouteInfo(
            isOnPrimaryArray = false,
            deviceAddress = "@:bottom",
            actualChannelCount = 1
        )
    }
}

interface MockableAudioSelector {
    fun validateCurrentRoute(audioManager: MockableAudioManager, sessionId: Int): MockRouteInfo?
    fun isRouteBad(routeInfo: MockRouteInfo, requestedStereo: Boolean, actualChannelCount: Int): Boolean
}

interface MockableMediaRecorderHolder {
    fun startRecording(): Boolean
    fun stopRecording()
}

interface MockablePrefs {
    fun getUseMediaRecorder(): Boolean
    fun setLastRecordingMethod(method: String)
}

enum class HoldingResult {
    SUCCESS_AUDIO_RECORD,
    SUCCESS_MEDIA_RECORDER,
    FAILED
}

data class HoldingAttemptResult(
    val method: HoldingResult,
    val success: Boolean,
    val shouldRetry: Boolean = false,
    val retryDelayMs: Long = 0L
)

class TestableHoldingLogic(
    private val audioSelector: MockableAudioSelector,
    private val mediaRecorderHolder: MockableMediaRecorderHolder,
    private val prefs: MockablePrefs
) {
    fun executeHoldingAttempt(): HoldingAttemptResult {
        val useMediaRecorderPref = prefs.getUseMediaRecorder()
        
        return if (useMediaRecorderPref) {
            tryMediaRecorderFirst()
        } else {
            tryAudioRecordFirst()
        }
    }
    
    private fun tryMediaRecorderFirst(): HoldingAttemptResult {
        return if (mediaRecorderHolder.startRecording()) {
            prefs.setLastRecordingMethod("MediaRecorder")
            HoldingAttemptResult(HoldingResult.SUCCESS_MEDIA_RECORDER, true)
        } else {
            // Fallback to AudioRecord
            tryAudioRecordFallback()
        }
    }
    
    private fun tryAudioRecordFirst(): HoldingAttemptResult {
        // Simulate route validation logic
        val routeInfo = audioSelector.validateCurrentRoute(object : MockableAudioManager {
            override fun othersRecording(): Boolean = false
            override fun validateCurrentRoute(sessionId: Int): MockRouteInfo? = null
        }, 12345)

        return if (routeInfo != null && !audioSelector.isRouteBad(routeInfo, true, routeInfo.actualChannelCount)) {
            prefs.setLastRecordingMethod("AudioRecord")
            HoldingAttemptResult(HoldingResult.SUCCESS_AUDIO_RECORD, true)
        } else {
            // Bad route - fallback to MediaRecorder
            if (mediaRecorderHolder.startRecording()) {
                prefs.setLastRecordingMethod("MediaRecorder")
                HoldingAttemptResult(HoldingResult.SUCCESS_MEDIA_RECORDER, true)
            } else {
                HoldingAttemptResult(HoldingResult.FAILED, false, shouldRetry = true, retryDelayMs = 2000L)
            }
        }
    }
    
    private fun tryAudioRecordFallback(): HoldingAttemptResult {
        val routeInfo = audioSelector.validateCurrentRoute(object : MockableAudioManager {
            override fun othersRecording(): Boolean = false
            override fun validateCurrentRoute(sessionId: Int): MockRouteInfo? = null
        }, 12345)
        return if (routeInfo != null && !audioSelector.isRouteBad(routeInfo, true, routeInfo.actualChannelCount)) {
            prefs.setLastRecordingMethod("AudioRecord")
            HoldingAttemptResult(HoldingResult.SUCCESS_AUDIO_RECORD, true)
        } else {
            HoldingAttemptResult(HoldingResult.FAILED, false, shouldRetry = true, retryDelayMs = 2000L)
        }
    }
}