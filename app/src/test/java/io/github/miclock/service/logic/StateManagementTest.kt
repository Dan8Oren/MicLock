// app/src/test/java/io/github/miclock/service/logic/StateManagementTest.kt
package io.github.miclock.service.logic

import io.github.miclock.TestableStateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for service state management.
 * Tests StateFlow emissions and state transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagementTest {

    private lateinit var stateManager: TestableStateManager

    @Before
    fun setUp() {
        stateManager = TestableStateManager()
    }

    @Test
    fun testInitialState_defaultValues() {
        // Given: Fresh state manager
        val state = stateManager.state.value

        // Then: Initial state is correct
        assertFalse(state.isRunning)
        assertFalse(state.isPausedBySilence)
        assertNull(state.currentDeviceAddress)
    }

    @Test
    fun testStateTransition_startService_updatesRunningState() = runTest {
        // When: Service starts
        stateManager.updateState(running = true)

        // Then: State reflects running status
        val state = stateManager.state.value
        assertTrue(state.isRunning)
        assertFalse(state.isPausedBySilence)
        assertNull(state.currentDeviceAddress)
    }

    @Test
    fun testStateTransition_pauseBySilence_maintainsRunningButSetsPaused() = runTest {
        // Given: Service is running
        stateManager.updateState(running = true, deviceAddr = "AudioRecord")

        // When: Service is paused by silencing
        stateManager.updateState(paused = true)

        // Then: State shows running but paused
        val state = stateManager.state.value
        assertTrue(state.isRunning)
        assertTrue(state.isPausedBySilence)
        assertEquals("AudioRecord", state.currentDeviceAddress)
    }

    @Test
    fun testStateTransition_resumeFromPause_clearsPausedState() = runTest {
        // Given: Service is paused
        stateManager.updateState(running = true, paused = true, deviceAddr = "MediaRecorder")

        // When: Service resumes
        stateManager.updateState(paused = false)

        // Then: State shows active again
        val state = stateManager.state.value
        assertTrue(state.isRunning)
        assertFalse(state.isPausedBySilence)
        assertEquals("MediaRecorder", state.currentDeviceAddress)
    }

    @Test
    fun testStateTransition_stopService_resetsAllState() = runTest {
        // Given: Service is running with device
        stateManager.updateState(running = true, paused = true, deviceAddr = "TestDevice")

        // When: Service stops
        stateManager.updateState(running = false, paused = false)
        stateManager.resetDeviceAddress()

        // Then: State is completely reset
        val state = stateManager.state.value
        assertFalse(state.isRunning)
        assertFalse(state.isPausedBySilence)
        assertNull(state.currentDeviceAddress)
    }
}
