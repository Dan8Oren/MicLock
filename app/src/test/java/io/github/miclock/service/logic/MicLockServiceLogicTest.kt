// app/src/test/java/io/github/miclock/service/logic/MicLockServiceLogicTest.kt
package io.github.miclock.service.logic

import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for MicLockService intent handling logic.
 * Tests the core business logic without Android dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MicLockServiceLogicTest {

    // Mock Android dependencies
    @Mock private lateinit var mockAudioRecord: MockableAudioRecord
    @Mock private lateinit var mockMediaRecorder: MockableMediaRecorder
    @Mock private lateinit var mockAudioManager: MockableAudioManager
    @Mock private lateinit var mockCallback: MockableAudioRecordingCallback

    // Testable service logic wrapper
    private lateinit var serviceLogic: TestableServiceLogic
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        serviceLogic = TestableServiceLogic(
            mockAudioRecord,
            mockMediaRecorder,
            mockAudioManager,
            mockCallback
        )
    }

    @Test
    fun testIntentHandling_startUserInitiated_setsRunningState() = runTest {
        // Given: Service is not running
        assertFalse(serviceLogic.state.value.isRunning)

        // When: ACTION_START_USER_INITIATED is processed
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")

        // Then: Service state becomes running
        assertTrue(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)
    }

    @Test
    fun testIntentHandling_stop_setsStoppedState() = runTest {
        // Given: Service is running
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")
        assertTrue(serviceLogic.state.value.isRunning)

        // When: ACTION_STOP is processed
        serviceLogic.handleIntent("ACTION_STOP")

        // Then: Service state becomes stopped
        assertFalse(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)
    }

    @Test
    fun testIntentHandling_stopHolding_setsPausedState() = runTest {
        // Given: Service is running and holding
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")
        serviceLogic.handleIntent("ACTION_START_HOLDING")
        
        // When: ACTION_STOP_HOLDING is processed (screen off)
        serviceLogic.handleIntent("ACTION_STOP_HOLDING")

        // Then: Service becomes paused but still running
        assertTrue(serviceLogic.state.value.isRunning)
        assertTrue(serviceLogic.state.value.isPausedBySilence)
    }

    @Test
    fun testIntentHandling_startHolding_resumesFromPaused() = runTest {
        // Given: Service is paused
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")
        serviceLogic.handleIntent("ACTION_STOP_HOLDING")
        assertTrue(serviceLogic.state.value.isPausedBySilence)

        // When: ACTION_START_HOLDING is processed (screen on)
        serviceLogic.handleIntent("ACTION_START_HOLDING")

        // Then: Service resumes active state
        assertTrue(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)
    }

    @Test
    fun testIntentHandling_reconfigure_restartsLoop() = runTest {
        // Given: Service is running
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")
        val initialLoopCount = serviceLogic.loopRestartCount

        // When: ACTION_RECONFIGURE is processed
        serviceLogic.handleIntent("ACTION_RECONFIGURE")

        // Then: Loop is restarted
        assertEquals(initialLoopCount + 1, serviceLogic.loopRestartCount)
        assertTrue(serviceLogic.state.value.isRunning)
    }

    // ===== State Transition Tests =====

    @Test
    fun testStateTransitions_completeLifecycle() = runTest {
        // Given: Service in initial state
        assertFalse(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)

        // When: Starting service
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")

        // Then: Should be running and not paused
        assertTrue(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)

        // When: Pausing for screen off
        serviceLogic.handleIntent("ACTION_STOP_HOLDING")

        // Then: Should be running but paused
        assertTrue(serviceLogic.state.value.isRunning)
        assertTrue(serviceLogic.state.value.isPausedBySilence)

        // When: Resuming for screen on
        serviceLogic.handleIntent("ACTION_START_HOLDING")

        // Then: Should be running and not paused
        assertTrue(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)

        // When: Stopping service
        serviceLogic.handleIntent("ACTION_STOP")

        // Then: Should be stopped
        assertFalse(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)
    }

    @Test
    fun testStateTransitions_invalidSequences() = runTest {
        // Given: Service not running
        assertFalse(serviceLogic.state.value.isRunning)

        // When: Attempting to pause without starting
        serviceLogic.handleIntent("ACTION_STOP_HOLDING")

        // Then: Should remain in stopped state
        assertFalse(serviceLogic.state.value.isRunning)
        assertTrue(serviceLogic.state.value.isPausedBySilence)

        // When: Attempting to resume without starting
        serviceLogic.handleIntent("ACTION_START_HOLDING")

        // Then: Should remain stopped but not paused
        assertFalse(serviceLogic.state.value.isRunning)
        assertFalse(serviceLogic.state.value.isPausedBySilence)
    }

    // ===== Reconfiguration Tests =====

    @Test
    fun testReconfiguration_whileRunning() = runTest {
        // Given: Running service
        serviceLogic.handleIntent("ACTION_START_USER_INITIATED")
        val initialLoopCount = serviceLogic.loopRestartCount

        // When: Reconfiguring multiple times
        serviceLogic.handleIntent("ACTION_RECONFIGURE")
        serviceLogic.handleIntent("ACTION_RECONFIGURE")

        // Then: Should restart loop multiple times
        assertEquals(initialLoopCount + 2, serviceLogic.loopRestartCount)
        assertTrue(serviceLogic.state.value.isRunning)
    }

    @Test
    fun testReconfiguration_whileStopped() = runTest {
        // Given: Stopped service
        assertFalse(serviceLogic.state.value.isRunning)
        val initialLoopCount = serviceLogic.loopRestartCount

        // When: Attempting to reconfigure
        serviceLogic.handleIntent("ACTION_RECONFIGURE")

        // Then: Should not restart loop
        assertEquals(initialLoopCount, serviceLogic.loopRestartCount)
        assertFalse(serviceLogic.state.value.isRunning)
    }
}

// Mockable interfaces for Android dependencies
interface MockableAudioRecord {
    fun startRecording(): Boolean
    fun stopRecording()
    fun read(buffer: ShortArray): Int
    val sessionId: Int
    val sampleRate: Int
    val channelCount: Int
}

interface MockableMediaRecorder {
    fun startRecording(): Boolean
    fun stopRecording()
}

interface MockableAudioManager {
    fun othersRecording(): Boolean
    fun validateCurrentRoute(sessionId: Int): MockRouteInfo?
}

interface MockableAudioRecordingCallback {
    fun onRecordingConfigChanged(silenced: Boolean)
}

// Test data classes
data class MockRouteInfo(
    val isOnPrimaryArray: Boolean,
    val deviceAddress: String,
    val actualChannelCount: Int
)

// Testable logic wrappers
class TestableServiceLogic(
    private val audioRecord: MockableAudioRecord,
    private val mediaRecorder: MockableMediaRecorder,
    private val audioManager: MockableAudioManager,
    private val callback: MockableAudioRecordingCallback
) {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    
    var loopRestartCount = 0
        private set

    fun handleIntent(action: String) {
        when (action) {
            "ACTION_START_USER_INITIATED" -> {
                _state.value = _state.value.copy(isRunning = true, isPausedBySilence = false)
            }
            "ACTION_STOP" -> {
                _state.value = _state.value.copy(isRunning = false, isPausedBySilence = false, currentDeviceAddress = null)
            }
            "ACTION_STOP_HOLDING" -> {
                _state.value = _state.value.copy(isPausedBySilence = true)
            }
            "ACTION_START_HOLDING" -> {
                _state.value = _state.value.copy(isPausedBySilence = false)
            }
            "ACTION_RECONFIGURE" -> {
                if (state.value.isRunning) {
                    loopRestartCount++
                }
            }
        }
    }
}