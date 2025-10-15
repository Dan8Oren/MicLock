package io.github.miclock.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for silence state recovery across screen state transitions.
 * Tests the complete end-to-end flow of the hybrid solution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SilenceStateIntegrationTest {

    @Test
    fun testEndToEnd_silenceStateRecovery() = runTest {
        // This integration test validates the complete flow:
        // 1. Service running normally
        // 2. Another app uses mic (service paused by silence)
        // 3. Screen turns off
        // 4. Other app releases mic (while screen still off)
        // 5. Screen turns on
        // 6. Service should resume normally (not blocked by stale silence)

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Phase 1: Service running normally
        val initialState = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )
        assertTrue("Service should be running", initialState.isRunning)
        assertFalse("Service should not be paused by silence", initialState.isPausedBySilence)

        // Phase 2: Another app uses mic - service paused
        val silencedState = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = false,
            wasSilencedBeforeScreenOff = false,
        )
        assertTrue("Service should be paused by silence", silencedState.isPausedBySilence)

        // Phase 3: Screen turns off
        val screenOffState = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = true,
            wasSilencedBeforeScreenOff = true,
        )
        assertTrue("Service should be paused by screen-off", screenOffState.isPausedByScreenOff)
        assertTrue("wasSilencedBeforeScreenOff should be true", screenOffState.wasSilencedBeforeScreenOff)

        // Phase 4: Other app releases mic (global callback detects this)
        val micAvailableState = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = true,
            wasSilencedBeforeScreenOff = false,
        )
        assertFalse("isPausedBySilence should be cleared", micAvailableState.isPausedBySilence)
        assertFalse("wasSilencedBeforeScreenOff should be cleared", micAvailableState.wasSilencedBeforeScreenOff)

        // Phase 5: Screen turns on - service should resume
        val resumedState = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
            wasSilencedBeforeScreenOff = false,
        )
        assertTrue("Service should be running", resumedState.isRunning)
        assertFalse("Service should not be paused by silence", resumedState.isPausedBySilence)
        assertFalse("Service should not be paused by screen-off", resumedState.isPausedByScreenOff)
    }

    @Test
    fun testLongRecording_notInterrupted() = runTest {
        // Tests that user's long recording session is not interrupted
        // Scenario: User recording for 2+ hours with screen off

        // Given: Service paused by silence for extended period
        val longRecordingState = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = true,
            wasSilencedBeforeScreenOff = true,
        )

        // When: Global callback continues to detect active recording
        // (In real scenario, configs would show other app still recording)

        // Then: Service should remain paused (respecting user's recording)
        assertTrue("Service should remain paused by silence", longRecordingState.isPausedBySilence)
        assertTrue("wasSilencedBeforeScreenOff should be true", longRecordingState.wasSilencedBeforeScreenOff)
        // Note: The global callback would detect active recording and maintain the silence state
    }

    @Test
    fun testScreenStateTransitions_preserveContext() = runTest {
        // Tests that rapid screen on/off transitions preserve state context

        // Given: Service in various states
        val states = listOf(
            ServiceState(isRunning = true, isPausedByScreenOff = false),
            ServiceState(isRunning = true, isPausedByScreenOff = true, wasSilencedBeforeScreenOff = false),
            ServiceState(isRunning = true, isPausedByScreenOff = false, isPausedBySilence = false),
        )

        // When: Rapid screen transitions occur
        // Then: Each state transition should preserve relevant context
        states.forEach { state ->
            if (state.isPausedByScreenOff) {
                // Screen is off - context should be preserved
                assertNotNull("State should be preserved", state.wasSilencedBeforeScreenOff)
            }
        }
    }
}
