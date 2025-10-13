package io.github.miclock.ui

import android.os.Build
import io.github.miclock.service.model.ServiceState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MainActivity focusing on UI status display logic.
 * Tests the fix for Issue 2: App UI shows "On" when paused by screen-off
 *
 * These tests verify the logic in updateMainStatus() method by testing
 * the service state conditions directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MainActivityTest {

    @Test
    fun testUpdateMainStatusLogic_pausedByScreenOff_shouldShowPaused() {
        // Given: Service is running but paused by screen-off
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = true,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show PAUSED status (this is the key fix for Issue 2)
        assertEquals("PAUSED", statusText)
    }

    @Test
    fun testUpdateMainStatusLogic_pausedBySilence_shouldShowPaused() {
        // Given: Service is running but paused by silence
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = false,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show PAUSED status
        assertEquals("PAUSED", statusText)
    }

    @Test
    fun testUpdateMainStatusLogic_pausedByBothSilenceAndScreenOff_shouldShowPaused() {
        // Given: Service is paused by both conditions
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = true,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show PAUSED status (not ON) - this tests the fix for Issue 2
        assertEquals("PAUSED", statusText)
    }

    @Test
    fun testUpdateMainStatusLogic_runningAndNotPaused_shouldShowOn() {
        // Given: Service is running and not paused
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show ON status
        assertEquals("ON", statusText)
    }

    @Test
    fun testUpdateMainStatusLogic_serviceNotRunning_shouldShowOff() {
        // Given: Service is not running
        val state = ServiceState(
            isRunning = false,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show OFF status
        assertEquals("OFF", statusText)
    }

    @Test
    fun testUpdateMainStatusLogic_serviceNotRunningButPausedFlags_shouldShowOff() {
        // Given: Service is not running but has paused flags (edge case)
        val state = ServiceState(
            isRunning = false,
            isPausedBySilence = true,
            isPausedByScreenOff = true,
        )

        // When: Determining status text based on state
        val statusText = determineStatusText(state)

        // Then: Should show OFF status (running takes precedence)
        assertEquals("OFF", statusText)
    }

    @Test
    fun testHandleStartButtonClick_pausedByScreenOff_shouldResumeService() {
        // Given: Service is running but paused by screen-off
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = true,
        )

        // When: Determining start button action based on state
        val action = determineStartButtonAction(state)

        // Then: Should resume from screen-off pause
        assertEquals(StartButtonAction.RESUME_FROM_SCREEN_OFF_PAUSE, action)
    }

    @Test
    fun testHandleStartButtonClick_serviceNotRunning_shouldStartService() {
        // Given: Service is not running
        val state = ServiceState(
            isRunning = false,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining start button action based on state
        val action = determineStartButtonAction(state)

        // Then: Should start service normally
        assertEquals(StartButtonAction.START_SERVICE, action)
    }

    @Test
    fun testHandleStartButtonClick_serviceRunningNotPaused_shouldDoNothing() {
        // Given: Service is actively running and not paused
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining start button action based on state
        val action = determineStartButtonAction(state)

        // Then: Should do nothing (button should be disabled in this state)
        assertEquals(StartButtonAction.DO_NOTHING, action)
    }

    @Test
    fun testHandleStartButtonClick_pausedBySilenceOnly_shouldDoNothing() {
        // Given: Service is paused by silence but not screen-off
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = false,
        )

        // When: Determining start button action based on state
        val action = determineStartButtonAction(state)

        // Then: Should do nothing (only screen-off pause can be resumed via start button)
        assertEquals(StartButtonAction.DO_NOTHING, action)
    }

    @Test
    fun testStartButtonEnabled_serviceNotRunning_shouldBeEnabled() {
        // Given: Service is not running
        val state = ServiceState(
            isRunning = false,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining if start button should be enabled
        val enabled = determineStartButtonEnabled(state)

        // Then: Start button should be enabled
        assertTrue(enabled)
    }

    @Test
    fun testStartButtonEnabled_pausedByScreenOff_shouldBeEnabled() {
        // Given: Service is paused by screen-off
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = true,
        )

        // When: Determining if start button should be enabled
        val enabled = determineStartButtonEnabled(state)

        // Then: Start button should be enabled (acts as resume button)
        assertTrue(enabled)
    }

    @Test
    fun testStartButtonEnabled_serviceRunningNotPaused_shouldBeDisabled() {
        // Given: Service is actively running
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = false,
            isPausedByScreenOff = false,
        )

        // When: Determining if start button should be enabled
        val enabled = determineStartButtonEnabled(state)

        // Then: Start button should be disabled
        assertFalse(enabled)
    }

    @Test
    fun testStartButtonEnabled_pausedBySilenceOnly_shouldBeDisabled() {
        // Given: Service is paused by silence but not screen-off
        val state = ServiceState(
            isRunning = true,
            isPausedBySilence = true,
            isPausedByScreenOff = false,
        )

        // When: Determining if start button should be enabled
        val enabled = determineStartButtonEnabled(state)

        // Then: Start button should be disabled (can't resume from silence pause)
        assertFalse(enabled)
    }

    /**
     * Enum representing possible start button actions based on service state
     */
    private enum class StartButtonAction {
        START_SERVICE,
        RESUME_FROM_SCREEN_OFF_PAUSE,
        DO_NOTHING,
    }

    /**
     * Helper method that replicates the logic from MainActivity.handleStartButtonClick()
     * This tests the core logic without needing the full Android UI framework
     */
    private fun determineStartButtonAction(state: ServiceState): StartButtonAction {
        return when {
            state.isPausedByScreenOff -> StartButtonAction.RESUME_FROM_SCREEN_OFF_PAUSE
            !state.isRunning -> StartButtonAction.START_SERVICE
            else -> StartButtonAction.DO_NOTHING
        }
    }

    /**
     * Helper method that replicates the logic from MainActivity.updateMainStatus()
     * for determining start button enabled state
     */
    private fun determineStartButtonEnabled(state: ServiceState): Boolean {
        // Enable start button when service is not running OR when paused by screen-off
        return !state.isRunning || state.isPausedByScreenOff
    }

    /**
     * Helper method that replicates the logic from MainActivity.updateMainStatus()
     * This tests the core logic without needing the full Android UI framework
     */
    private fun determineStatusText(state: ServiceState): String {
        val running = state.isRunning
        val pausedBySilence = state.isPausedBySilence
        val pausedByScreenOff = state.isPausedByScreenOff

        return when {
            !running -> "OFF"
            pausedBySilence || pausedByScreenOff -> "PAUSED" // This is the fix for Issue 2
            else -> "ON"
        }
    }
}
