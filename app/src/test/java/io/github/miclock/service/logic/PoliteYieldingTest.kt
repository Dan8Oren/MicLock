// app/src/test/java/io/github/miclock/service/logic/PoliteYieldingTest.kt
package io.github.miclock.service.logic

import io.github.miclock.TestableYieldingLogic
import io.github.miclock.MockableAudioRecordingCallback
import io.github.miclock.MockableAudioManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for polite yielding and re-acquisition behavior.
 * Tests the core "polite holder" strategy specified in DEV_SPECS.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoliteYieldingTest {

    @Mock private lateinit var mockCallback: MockableAudioRecordingCallback
    @Mock private lateinit var mockAudioManager: MockableAudioManager

    private lateinit var yieldingLogic: TestableYieldingLogic

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        yieldingLogic = TestableYieldingLogic(mockCallback, mockAudioManager)
    }

    @Test
    fun testSilencing_anotherAppRecords_setsSilencedAndPausesState() = runTest {
        // Given: Service is actively holding microphone
        yieldingLogic.startHolding()
        assertFalse(yieldingLogic.isSilenced)
        assertFalse(yieldingLogic.state.value.isPausedBySilence)

        // When: Another app starts recording (callback triggered)
        yieldingLogic.simulateRecordingConfigChange(clientSilenced = true)

        // Then: Service becomes silenced and state shows paused
        assertTrue(yieldingLogic.isSilenced)
        assertTrue(yieldingLogic.state.value.isPausedBySilence)
        assertEquals("Paused — mic in use by another app", yieldingLogic.lastNotificationText)
    }

    @Test
    fun testCooldownPeriod_whileSilenced_waitsBeforeReacquisition() = runTest {
        // Given: Service is silenced
        yieldingLogic.startHolding()
        yieldingLogic.simulateRecordingConfigChange(clientSilenced = true)
        val silencedTime = System.currentTimeMillis()

        // When: Checking if ready to re-acquire during cooldown
        val canReacquire = yieldingLogic.canAttemptReacquisition(silencedTime + 1000L) // 1 sec later

        // Then: Should still be in cooldown (3 second minimum)
        assertFalse(canReacquire)
        assertEquals(2000L, yieldingLogic.getRemainingCooldownMs(silencedTime + 1000L))
    }

    @Test
    fun testExponentialBackoff_othersStillRecording_increasesWaitTime() = runTest {
        // Given: Service completed cooldown but others still recording
        yieldingLogic.startHolding()
        yieldingLogic.simulateRecordingConfigChange(clientSilenced = true)
        val silencedTime = System.currentTimeMillis() - 4000L // 4 seconds ago (past cooldown)
        whenever(mockAudioManager.othersRecording()).thenReturn(true)

        // When: Attempting re-acquisition multiple times
        val firstBackoff = yieldingLogic.currentBackoffMs // Initial backoff is 500L
        yieldingLogic.applyBackoff()
        val secondBackoff = yieldingLogic.currentBackoffMs

        // Then: Backoff increases exponentially
        assertEquals(500L, firstBackoff) // Initial backoff
        assertEquals(1000L, secondBackoff) // Doubled
        assertTrue(secondBackoff > firstBackoff)
    }

    @Test
    fun testReacquisition_othersFinishRecording_resetsStateAndBackoff() = runTest {
        // Given: Service was silenced with backoff applied
        yieldingLogic.startHolding()
        yieldingLogic.simulateRecordingConfigChange(clientSilenced = true)
        yieldingLogic.applyBackoff()
        yieldingLogic.applyBackoff() // Backoff = 2000ms
        whenever(mockAudioManager.othersRecording()).thenReturn(false)

        // When: Others finish recording and re-acquisition is attempted
        yieldingLogic.attemptReacquisition()

        // Then: Silenced state is reset and backoff is reset
        assertFalse(yieldingLogic.isSilenced)
        assertFalse(yieldingLogic.state.value.isPausedBySilence)
        assertEquals(500L, yieldingLogic.currentBackoffMs) // Reset to initial value
    }
}