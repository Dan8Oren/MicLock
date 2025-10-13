package io.github.miclock.service

import android.content.Context
import io.github.miclock.data.Prefs
import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for DelayedActivationManager focusing on delay scheduling,
 * cancellation logic, race condition handling, and service state validation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DelayedActivationManagerTest {

    @Mock
    private lateinit var mockService: MicActivationService

    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private lateinit var delayedActivationManager: TestableDelayedActivationManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        testScope = TestScope()
        
        // Setup default mock behavior
        whenever(mockService.getCurrentState()).thenReturn(ServiceState())
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        
        delayedActivationManager = TestableDelayedActivationManager(context, mockService, testScope)
    }

    @Test
    fun testScheduleDelayedActivation_withValidDelay_schedulesSuccessfully() = testScope.runTest {
        // Given: Valid delay configuration
        Prefs.setScreenOnDelayMs(context, 1000L)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)

        // When: Scheduling delayed activation
        val result = delayedActivationManager.scheduleDelayedActivation(1000L)

        // Then: Should schedule successfully
        assertTrue("Should schedule delay successfully", result)
        assertTrue("Should have pending activation", delayedActivationManager.isActivationPending())
        assertEquals("Should have valid start time", 0L, delayedActivationManager.getDelayStartTime())
    }

    @Test
    fun testScheduleDelayedActivation_withZeroDelay_doesNotSchedule() = testScope.runTest {
        // Given: Zero delay (disabled)
        Prefs.setScreenOnDelayMs(context, 0L)

        // When: Attempting to schedule with zero delay
        val result = delayedActivationManager.scheduleDelayedActivation(0L)

        // Then: Should not schedule
        assertFalse("Should not schedule with zero delay", result)
        assertFalse("Should not have pending activation", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testScheduleDelayedActivation_serviceAlreadyRunning_doesNotSchedule() = testScope.runTest {
        // Given: Mic is actively being held
        whenever(mockService.isMicActivelyHeld()).thenReturn(true)

        // When: Attempting to schedule delay
        val result = delayedActivationManager.scheduleDelayedActivation(1000L)

        // Then: Should not schedule
        assertFalse("Should not schedule when mic is actively held", result)
        assertFalse("Should not have pending activation", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testScheduleDelayedActivation_serviceManuallyStoppedByUser_doesNotSchedule() = testScope.runTest {
        // Given: Service was manually stopped by user
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(true)

        // When: Attempting to schedule delay
        val result = delayedActivationManager.scheduleDelayedActivation(1000L)

        // Then: Should not schedule
        assertFalse("Should not schedule when manually stopped by user", result)
        assertFalse("Should not have pending activation", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testScheduleDelayedActivation_servicePausedBySilence_doesNotSchedule() = testScope.runTest {
        // Given: Service is paused by silence (another app using mic)
        val recentTimestamp = System.currentTimeMillis() - 5000L // 5 seconds ago (fresh)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(
            isPausedBySilence = true
        ))

        // When: Attempting to schedule delay
        val result = delayedActivationManager.scheduleDelayedActivation(1000L)

        // Then: Should not schedule
        assertFalse("Should not schedule when paused by silence", result)
        assertFalse("Should not have pending activation", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testDelayCompletion_activatesMicrophone() = testScope.runTest {
        // Given: Valid delay setup
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)

        // When: Scheduling and waiting for delay completion
        delayedActivationManager.scheduleDelayedActivation(100L)
        assertTrue("Should have pending activation", delayedActivationManager.isActivationPending())

        // Advance time to complete delay and run pending coroutines
        advanceTimeBy(100L)
        runCurrent() // Execute any pending coroutines

        // Then: Should activate microphone with fromDelayCompletion=true
        verify(mockService).startMicHolding(fromDelayCompletion = true)
        assertFalse("Should not have pending activation after completion", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testCancelDelayedActivation_cancelsSuccessfully() = testScope.runTest {
        // Given: Scheduled delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        delayedActivationManager.scheduleDelayedActivation(1000L)
        assertTrue("Should have pending activation", delayedActivationManager.isActivationPending())

        // When: Cancelling delay
        val result = delayedActivationManager.cancelDelayedActivation()

        // Then: Should cancel successfully
        assertTrue("Should return true when cancelling existing delay", result)
        assertFalse("Should not have pending activation after cancellation", delayedActivationManager.isActivationPending())
        assertEquals("Should reset delay start time", 0L, delayedActivationManager.getDelayStartTime())
    }

    @Test
    fun testCancelDelayedActivation_noPendingDelay_returnsFalse() = testScope.runTest {
        // Given: No pending delay
        assertFalse("Should not have pending activation initially", delayedActivationManager.isActivationPending())

        // When: Attempting to cancel non-existent delay
        val result = delayedActivationManager.cancelDelayedActivation()

        // Then: Should return false
        assertFalse("Should return false when no delay to cancel", result)
    }

    @Test
    fun testRaceCondition_rapidScreenStateChanges_handlesLatestEventWins() = testScope.runTest {
        // Given: Service state allows delays
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)

        // When: Rapid screen state changes (multiple delay schedules)
        delayedActivationManager.scheduleDelayedActivation(1000L)
        val firstStartTime = delayedActivationManager.getDelayStartTime()

        // Simulate rapid second screen-on event
        advanceTimeBy(50L) // Small delay to ensure different timestamps
        delayedActivationManager.scheduleDelayedActivation(1000L)
        val secondStartTime = delayedActivationManager.getDelayStartTime()

        // Then: Latest event should win
        assertTrue("Second start time should be later or equal", secondStartTime >= firstStartTime)
        assertTrue("Should still have pending activation", delayedActivationManager.isActivationPending())

        // Verify that the delay mechanism is working correctly
        val remainingTime = delayedActivationManager.getRemainingDelayMs()
        assertTrue("Should have remaining time close to 1000ms", remainingTime >= 950L)
        
        // Test that cancellation works
        val cancelled = delayedActivationManager.cancelDelayedActivation()
        assertTrue("Should successfully cancel delay", cancelled)
        assertFalse("Should not have pending activation after cancellation", delayedActivationManager.isActivationPending())
    }

    @Test
    fun testServiceStateChangesDuringDelay_respectsNewState() = testScope.runTest {
        // Given: Initial state allows delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        delayedActivationManager.scheduleDelayedActivation(1000L)

        // When: Service state changes during delay (e.g., manually stopped)
        advanceTimeBy(500L) // Halfway through delay
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(true)

        // Complete the delay
        advanceTimeBy(500L)
        runCurrent() // Execute any pending coroutines

        // Then: Should not activate microphone due to state change
        verify(mockService, never()).startMicHolding(any())
        // Note: The pending activation flag may still be true until the coroutine completes and checks state
    }

    @Test
    fun testServiceStateChangesDuringDelay_serviceBecomesActive() = testScope.runTest {
        // Given: Initial state allows delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        delayedActivationManager.scheduleDelayedActivation(1000L)

        // When: Mic becomes actively held during delay
        advanceTimeBy(500L) // Halfway through delay
        whenever(mockService.isMicActivelyHeld()).thenReturn(true)

        // Complete the delay
        advanceTimeBy(500L)
        runCurrent() // Execute any pending coroutines

        // Then: Should not activate microphone since mic is already actively held
        verify(mockService, never()).startMicHolding(any())
        // Note: The pending activation flag may still be true until the coroutine completes and checks state
    }

    @Test
    fun testGetRemainingDelayMs_calculatesCorrectly() = testScope.runTest {
        // Given: Scheduled delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false, isPausedBySilence = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        Prefs.setScreenOnDelayMs(context, 1000L)
        
        // Debug: Check individual conditions
        val currentState = mockService.getCurrentState()
        val isManuallyStoppedByUser = mockService.isManuallyStoppedByUser()
        val shouldRespectExisting = delayedActivationManager.shouldRespectExistingState()
        val prefDelay = Prefs.getScreenOnDelayMs(context)
        val shouldApply = delayedActivationManager.shouldApplyDelay()
        
        assertFalse("Service should not be running", currentState.isRunning)
        assertFalse("Service should not be paused by silence", currentState.isPausedBySilence)
        assertFalse("Service should not be manually stopped", isManuallyStoppedByUser)
        assertFalse("Should not respect existing state", shouldRespectExisting)
        assertEquals("Preference delay should be 1000ms", 1000L, prefDelay)
        assertTrue("Should apply delay with valid conditions", shouldApply)
        
        val scheduled = delayedActivationManager.scheduleDelayedActivation(1000L)
        assertTrue("Should successfully schedule delay", scheduled)
        assertTrue("Should have pending activation", delayedActivationManager.isActivationPending())

        // When: Checking remaining time at start
        val initialRemaining = delayedActivationManager.getRemainingDelayMs()
        assertEquals("Initial remaining should be 1000ms", 1000L, initialRemaining)
    }

    @Test
    fun testGetRemainingDelayMs_noPendingDelay_returnsZero() = testScope.runTest {
        // Given: No pending delay
        assertFalse("Should not have pending activation", delayedActivationManager.isActivationPending())

        // When: Checking remaining time
        val remaining = delayedActivationManager.getRemainingDelayMs()

        // Then: Should return zero
        assertEquals("Should return 0 when no delay pending", 0L, remaining)
    }

    @Test
    fun testShouldRespectExistingState_variousStates() = testScope.runTest {
        // Test case 1: Mic actively held
        whenever(mockService.isMicActivelyHeld()).thenReturn(true)
        assertTrue("Should respect state when mic is actively held", delayedActivationManager.shouldRespectExistingState())

        // Test case 2: Service paused by silence (with fresh timestamp)
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        val recentTimestamp = System.currentTimeMillis() - 5000L // 5 seconds ago (fresh)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(
            isPausedBySilence = true,
        ))
        assertTrue("Should respect state when paused by silence", delayedActivationManager.shouldRespectExistingState())

        // Test case 3: Manually stopped by user
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(true)
        assertTrue("Should respect state when manually stopped", delayedActivationManager.shouldRespectExistingState())

        // Test case 4: Normal state (not actively held, not paused, not manually stopped)
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        assertFalse("Should not respect state in normal conditions", delayedActivationManager.shouldRespectExistingState())
    }

    @Test
    fun testShouldApplyDelay_variousConditions() = testScope.runTest {
        // Test case 1: Delay disabled (0ms)
        Prefs.setScreenOnDelayMs(context, 0L)
        assertFalse("Should not apply delay when disabled", delayedActivationManager.shouldApplyDelay())

        // Test case 2: Valid delay but existing state should be respected (mic actively held)
        Prefs.setScreenOnDelayMs(context, 1000L)
        whenever(mockService.isMicActivelyHeld()).thenReturn(true)
        assertFalse("Should not apply delay when existing state should be respected", delayedActivationManager.shouldApplyDelay())

        // Test case 3: Valid delay and normal state
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        assertTrue("Should apply delay in normal conditions", delayedActivationManager.shouldApplyDelay())
    }

    @Test
    fun testHandleServiceStateConflict_logsAppropriately() = testScope.runTest {
        // This test verifies that the conflict handler doesn't crash and handles different states
        // Since it mainly logs, we test that it executes without exceptions

        // Test with mic actively held
        whenever(mockService.isMicActivelyHeld()).thenReturn(true)
        assertDoesNotThrow { delayedActivationManager.handleServiceStateConflict() }

        // Test with paused service
        whenever(mockService.isMicActivelyHeld()).thenReturn(false)
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isPausedBySilence = true))
        assertDoesNotThrow { delayedActivationManager.handleServiceStateConflict() }

        // Test with manually stopped service
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(true)
        assertDoesNotThrow { delayedActivationManager.handleServiceStateConflict() }
    }

    @Test
    fun testCleanup_cancelsAllOperations() = testScope.runTest {
        // Given: Scheduled delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        delayedActivationManager.scheduleDelayedActivation(1000L)
        assertTrue("Should have pending activation", delayedActivationManager.isActivationPending())

        // When: Cleaning up
        delayedActivationManager.cleanup()

        // Then: Should cancel all operations
        assertFalse("Should not have pending activation after cleanup", delayedActivationManager.isActivationPending())
        assertEquals("Should reset delay start time", 0L, delayedActivationManager.getDelayStartTime())
    }

    @Test
    fun testTimestampTracking_accuratelyTracksEvents() = testScope.runTest {
        // Given: Initial state
        val initialScreenOnTime = delayedActivationManager.getLastScreenOnTime()
        assertEquals("Initial screen-on time should be 0", 0L, initialScreenOnTime)

        // When: Scheduling delay
        whenever(mockService.getCurrentState()).thenReturn(ServiceState(isRunning = false))
        whenever(mockService.isManuallyStoppedByUser()).thenReturn(false)
        delayedActivationManager.scheduleDelayedActivation(1000L)

        // Then: Should track timestamps accurately using test scheduler time
        val screenOnTime = delayedActivationManager.getLastScreenOnTime()
        val delayStartTime = delayedActivationManager.getDelayStartTime()
        
        assertEquals("Screen-on time should be test scheduler time", 0L, screenOnTime)
        assertEquals("Delay start time should be test scheduler time", 0L, delayStartTime)
        assertEquals("Screen-on time and delay start time should be equal", 
                    screenOnTime, delayStartTime)
    }

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
}

/**
 * Testable version of DelayedActivationManager that uses test scheduler time
 * instead of system time for accurate testing of timing-dependent behavior.
 */
class TestableDelayedActivationManager(
    context: Context,
    service: MicActivationService,
    private val testScope: TestScope
) : DelayedActivationManager(context, service, testScope) {
    
    override fun getCurrentTimeMs(): Long {
        return testScope.testScheduler.currentTime
    }
}
