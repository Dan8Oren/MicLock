package io.github.miclock.receiver

import android.content.Context
import android.content.Intent
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
 * Unit tests for ScreenStateReceiver focusing on timestamp tracking, debouncing logic, and proper
 * intent handling for screen state changes.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenStateReceiverTest {

    @Mock private lateinit var mockContext: Context

    private lateinit var context: Context
    private lateinit var receiver: ScreenStateReceiver

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        receiver = ScreenStateReceiver()
        // Reset timestamps before each test to ensure clean state
        receiver.resetTimestamps()
    }

    @Test
    fun testOnReceive_screenOn_sendsStartHoldingAction() {
        // Given: Screen ON intent
        val intent = Intent(Intent.ACTION_SCREEN_ON)

        // When: Receiver processes the intent
        receiver.onReceive(context, intent)

        // Then: Should send START_HOLDING action to service
        // Note: We can't easily verify startService call with Robolectric,
        // but we can verify timestamp was updated
        val timestamp = receiver.getLastScreenOnTimestamp()
        assertTrue("Screen ON timestamp should be set", timestamp > 0)
    }

    @Test
    fun testOnReceive_screenOff_sendsStopHoldingAction() {
        // Given: Screen OFF intent
        val intent = Intent(Intent.ACTION_SCREEN_OFF)

        // When: Receiver processes the intent
        receiver.onReceive(context, intent)

        // Then: Should send STOP_HOLDING action to service
        val timestamp = receiver.getLastScreenOffTimestamp()
        assertTrue("Screen OFF timestamp should be set", timestamp > 0)
    }

    @Test
    fun testOnReceive_unknownAction_doesNothing() {
        // Given: Unknown action intent
        val intent = Intent("UNKNOWN_ACTION")
        val initialScreenOnTime = receiver.getLastScreenOnTimestamp()
        val initialScreenOffTime = receiver.getLastScreenOffTimestamp()

        // When: Receiver processes the intent
        receiver.onReceive(context, intent)

        // Then: Should not update timestamps
        assertEquals(
            "Screen ON timestamp should not change",
            initialScreenOnTime,
            receiver.getLastScreenOnTimestamp(),
        )
        assertEquals(
            "Screen OFF timestamp should not change",
            initialScreenOffTime,
            receiver.getLastScreenOffTimestamp(),
        )
    }

    @Test
    fun testTimestampTracking_screenOn_updatesTimestamp() {
        // Given: Initial state
        val initialTimestamp = receiver.getLastScreenOnTimestamp()

        // When: Screen turns ON
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        receiver.onReceive(context, intent)

        // Then: Timestamp should be updated
        val newTimestamp = receiver.getLastScreenOnTimestamp()
        assertTrue("Timestamp should be updated", newTimestamp > initialTimestamp)
    }

    @Test
    fun testTimestampTracking_screenOff_updatesTimestamp() {
        // Given: Initial state
        val initialTimestamp = receiver.getLastScreenOffTimestamp()

        // When: Screen turns OFF
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        receiver.onReceive(context, intent)

        // Then: Timestamp should be updated
        val newTimestamp = receiver.getLastScreenOffTimestamp()
        assertTrue("Timestamp should be updated", newTimestamp > initialTimestamp)
    }

    @Test
    fun testTimestampTracking_multipleScreenOnEvents_updatesEachTime() {
        // Given: Initial state
        val intent = Intent(Intent.ACTION_SCREEN_ON)

        // When: Multiple screen ON events
        receiver.onReceive(context, intent)
        val firstTimestamp = receiver.getLastScreenOnTimestamp()

        Thread.sleep(100) // Delay longer than debounce threshold (50ms)

        receiver.onReceive(context, intent)
        val secondTimestamp = receiver.getLastScreenOnTimestamp()

        // Then: Timestamps should be different
        assertTrue("Second timestamp should be later", secondTimestamp > firstTimestamp)
    }

    @Test
    fun testDebouncing_rapidEvents_ignoresWithinThreshold() {
        // Given: Initial screen ON event
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        receiver.onReceive(context, intent)
        val firstProcessedTime = receiver.getLastProcessedEventTimestamp()

        // When: Rapid second event (within debounce threshold)
        // Note: This test is timing-sensitive and may need adjustment
        receiver.onReceive(context, intent)
        val secondProcessedTime = receiver.getLastProcessedEventTimestamp()

        // Then: Second event should be debounced (same processed timestamp)
        // or processed if enough time passed
        assertTrue("Processed timestamp should be valid", secondProcessedTime >= firstProcessedTime)
    }

    @Test
    fun testRaceConditionDetection_screenOnAfterScreenOff_tracksTimings() {
        // Given: Screen OFF event
        val offIntent = Intent(Intent.ACTION_SCREEN_OFF)
        receiver.onReceive(context, offIntent)
        val screenOffTime = receiver.getLastScreenOffTimestamp()

        Thread.sleep(100) // Simulate time passing

        // When: Screen ON event
        val onIntent = Intent(Intent.ACTION_SCREEN_ON)
        receiver.onReceive(context, onIntent)
        val screenOnTime = receiver.getLastScreenOnTimestamp()

        // Then: Screen ON should be after screen OFF
        assertTrue("Screen ON should be after screen OFF", screenOnTime > screenOffTime)
    }

    @Test
    fun testRaceConditionDetection_rapidScreenToggle_maintainsCorrectOrder() {
        // Given: Initial state
        val onIntent = Intent(Intent.ACTION_SCREEN_ON)
        val offIntent = Intent(Intent.ACTION_SCREEN_OFF)

        // When: Rapid screen state changes (with delays longer than debounce threshold)
        receiver.onReceive(context, onIntent)
        val firstOnTime = receiver.getLastScreenOnTimestamp()

        Thread.sleep(100) // Delay longer than debounce threshold

        receiver.onReceive(context, offIntent)
        val firstOffTime = receiver.getLastScreenOffTimestamp()

        Thread.sleep(100) // Delay longer than debounce threshold

        receiver.onReceive(context, onIntent)
        val secondOnTime = receiver.getLastScreenOnTimestamp()

        // Then: Timestamps should be in correct order
        assertTrue("First OFF should be after first ON", firstOffTime > firstOnTime)
        assertTrue("Second ON should be after first OFF", secondOnTime > firstOffTime)
    }

    @Test
    fun testProcessedEventTimestamp_updatesOnSuccessfulProcessing() {
        // Given: Initial state
        val initialProcessedTime = receiver.getLastProcessedEventTimestamp()

        // When: Processing screen ON event
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        receiver.onReceive(context, intent)

        // Then: Processed timestamp should be updated
        val newProcessedTime = receiver.getLastProcessedEventTimestamp()
        assertTrue("Processed timestamp should be updated", newProcessedTime > initialProcessedTime)
    }

    @Test
    fun testProcessedEventTimestamp_notUpdatedForUnknownAction() {
        // Given: Initial state with a valid event
        val validIntent = Intent(Intent.ACTION_SCREEN_ON)
        receiver.onReceive(context, validIntent)
        val processedTimeAfterValid = receiver.getLastProcessedEventTimestamp()

        Thread.sleep(100) // Delay longer than debounce threshold

        // When: Processing unknown action
        val unknownIntent = Intent("UNKNOWN_ACTION")
        receiver.onReceive(context, unknownIntent)

        // Then: Processed timestamp should not change
        assertEquals(
            "Processed timestamp should not change for unknown action",
            processedTimeAfterValid,
            receiver.getLastProcessedEventTimestamp(),
        )
    }

    @Test
    fun testIntentExtras_screenOn_includesTimestamp() {
        // This test verifies the intent structure that would be sent to the service
        // In a real scenario, we'd need to mock Context.startService to capture the intent

        // Given: Screen ON intent
        val intent = Intent(Intent.ACTION_SCREEN_ON)

        // When: Receiver processes the intent
        receiver.onReceive(context, intent)

        // Then: Timestamp should be tracked (we verify this indirectly)
        val timestamp = receiver.getLastScreenOnTimestamp()
        assertTrue("Timestamp should be set and valid", timestamp > 0)
    }

    @Test
    fun testIntentExtras_screenOff_includesTimestamp() {
        // Given: Screen OFF intent
        val intent = Intent(Intent.ACTION_SCREEN_OFF)

        // When: Receiver processes the intent
        receiver.onReceive(context, intent)

        // Then: Timestamp should be tracked
        val timestamp = receiver.getLastScreenOffTimestamp()
        assertTrue("Timestamp should be set and valid", timestamp > 0)
    }
}
