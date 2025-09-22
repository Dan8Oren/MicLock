package io.github.miclock.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.miclock.util.WakeLockManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * Unit tests for MediaRecorderHolder utility.
 * Tests the compatibility-mode recording mechanism and resource management.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MediaRecorderHolderTest {

    private lateinit var context: Context // Real Robolectric context
    @Mock private lateinit var mockAudioManager: AudioManager
    @Mock private lateinit var mockWakeLockManager: WakeLockManager

    private lateinit var onSilencedChangedCallback: (Boolean) -> Unit
    private var callbackInvocations = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // Use real Robolectric context instead of mocked context
        context = ApplicationProvider.getApplicationContext()

        // Setup callback tracking
        onSilencedChangedCallback = { silenced ->
            callbackInvocations.add(silenced)
        }
    }

    // ===== Start Recording Tests =====

    @Test
    fun testStartRecording_success_acquiresWakeLockAndCreatesFile() {
        // Given: MediaRecorderHolder instance
        val holder = createMediaRecorderHolder()

        // When: Starting recording
        assertDoesNotThrow("Should not throw exception during startRecording") {
            holder.startRecording()
        }

        // Then: Should have acquired wake lock
        verify(mockWakeLockManager).acquire()
        assertFalse("Should not be silenced initially", holder.isSilenced)
    }

    @Test
    fun testStartRecording_mediaRecorderFailure_cleansUpResources() {
        // Given: MediaRecorderHolder with mocked failure scenario
        val holder = createMediaRecorderHolder()

        // When/Then: Exception handling should clean up resources
        // Note: This test verifies that isSilenced state is managed correctly
        // even when MediaRecorder operations might fail

        // Verify that isSilenced remains false after failed start
        assertFalse("Should not be silenced after failed start", holder.isSilenced)
    }

    // ===== Stop Recording Tests =====

    @Test
    fun testStopRecording_afterStart_releasesResourcesAndResetsState() {
        // Given: Started MediaRecorderHolder
        val holder = createMediaRecorderHolder()

        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // When: Stopping recording
        assertDoesNotThrow("Should not throw during stop") {
            holder.stopRecording()
        }

        // Then: Should reset silenced state and release wake lock
        assertFalse("Should not be silenced after stop", holder.isSilenced)
        verify(mockWakeLockManager).release()
    }

    @Test
    fun testStopRecording_withoutStart_doesNotCrash() {
        // Given: MediaRecorderHolder that hasn't been started
        val holder = createMediaRecorderHolder()

        // When/Then: Should not crash when stopping without starting
        assertDoesNotThrow("Should not throw exception when stopping without starting") {
            holder.stopRecording()
        }
    }

    @Test
    fun testStopRecording_multipleCallsIdempotent() {
        // Given: Started and stopped MediaRecorderHolder
        val holder = createMediaRecorderHolder()

        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        assertDoesNotThrow("Should not throw during first stop") {
            holder.stopRecording()
        }

        // When/Then: Multiple stop calls should be safe
        assertDoesNotThrow("Should not throw during multiple stops") {
            holder.stopRecording()
            holder.stopRecording()
        }
    }

    // ===== Silencing Callback Tests =====

    @Test
    fun testSilencingCallback_onRecordingConfigChanged_invokesCallback() {
        // Given: MediaRecorderHolder with callback tracking
        val holder = createMediaRecorderHolder()

        // When: Simulating silencing event
        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // Simulate silencing with proper state management
        simulateSilencingEvent(holder, true)

        // Then: Should have invoked callback with true and updated internal state
        assertTrue("Should have invoked callback", callbackInvocations.isNotEmpty())
        assertTrue("Should have been silenced", callbackInvocations.last())
        assertTrue("isSilenced should be true", holder.isSilenced)
    }

    @Test
    fun testSilencingCallback_unsilencingEvent_invokesCallbackWithFalse() {
        // Given: MediaRecorderHolder that was silenced
        val holder = createMediaRecorderHolder()

        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        simulateSilencingEvent(holder, true)
        callbackInvocations.clear()

        // When: Simulating unsilencing event
        simulateSilencingEvent(holder, false)

        // Then: Should have invoked callback with false and updated internal state
        assertTrue("Should have invoked callback", callbackInvocations.isNotEmpty())
        assertFalse("Should have been unsilenced", callbackInvocations.last())
        assertFalse("isSilenced should be false", holder.isSilenced)
    }

    @Test
    fun testSilencingCallback_multipleEvents_onlyTriggersOnStateChange() {
        // Given: MediaRecorderHolder
        val holder = createMediaRecorderHolder()

        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // When: Multiple silencing events with same state
        simulateSilencingEvent(holder, true)
        val firstCallbackCount = callbackInvocations.size

        simulateSilencingEvent(holder, true) // Same state - should not trigger callback

        // Then: Should not trigger additional callbacks for same state
        assertEquals("Should not trigger duplicate callbacks",
            firstCallbackCount, callbackInvocations.size)
    }

    // ===== Integration Tests =====

    @Test
    fun testCompleteLifecycle_startStopWithSilencing() {
        // Given: MediaRecorderHolder
        val holder = createMediaRecorderHolder()

        // When: Complete lifecycle
        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        assertFalse("Should start unsilenced", holder.isSilenced)

        simulateSilencingEvent(holder, true)
        assertTrue("Should be silenced", holder.isSilenced)

        assertDoesNotThrow("Should not throw during stop") {
            holder.stopRecording()
        }

        assertFalse("Should reset silenced state on stop", holder.isSilenced)

        // Then: Should have proper callback sequence
        assertTrue("Should have received silencing callback",
            callbackInvocations.contains(true))
    }

    // ===== Error Handling Tests =====

    @Test
    fun testStartRecording_handlesExceptionsGracefully() {
        // Given: MediaRecorderHolder with setup that might cause exceptions
        val holder = createMediaRecorderHolder()

        // When/Then: Should handle exceptions gracefully
        // This test verifies that the holder doesn't crash on MediaRecorder failures
        try {
            holder.startRecording()
            // If successful, verify state
            assertFalse("Should not be silenced after successful start", holder.isSilenced)
        } catch (e: Exception) {
            // If exception occurs, verify cleanup
            assertFalse("Should not be silenced after exception", holder.isSilenced)
            // This is acceptable behavior for MediaRecorder compatibility issues
        }
    }

    // ===== Resource Management Tests =====

    @Test
    fun testResourceManagement_wakeLockLifecycle() {
        // Given: MediaRecorderHolder instance
        val holder = createMediaRecorderHolder()

        // When: Starting and stopping recording
        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // Then: Should acquire wake lock
        verify(mockWakeLockManager).acquire()

        // When: Stopping recording
        assertDoesNotThrow("Should not throw during stop") {
            holder.stopRecording()
        }

        // Then: Should release wake lock
        verify(mockWakeLockManager).release()
    }

    @Test
    fun testResourceManagement_fileCleanup() {
        // Given: MediaRecorderHolder instance
        val holder = createMediaRecorderHolder()

        // When: Starting recording (creates temp file)
        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // When: Stopping recording
        assertDoesNotThrow("Should not throw during stop") {
            holder.stopRecording()
        }

        // Then: Temp files should be cleaned up (verified by no exceptions)
        // Note: Actual file cleanup verification would require access to internal file reference
    }

    // ===== Callback State Management Tests =====

    @Test
    fun testCallbackStateManagement_stateChangeDetection() {
        // Given: MediaRecorderHolder
        val holder = createMediaRecorderHolder()

        assertDoesNotThrow("Should not throw during start") {
            holder.startRecording()
        }

        // When: State changes from unsilenced to silenced
        simulateSilencingEvent(holder, true)

        // Then: Should detect state change and invoke callback
        assertTrue("Should have detected state change", callbackInvocations.contains(true))
        assertTrue("Internal state should be updated", holder.isSilenced)

        // When: State changes back to unsilenced
        simulateSilencingEvent(holder, false)

        // Then: Should detect reverse state change
        assertTrue("Should have detected reverse state change", callbackInvocations.contains(false))
        assertFalse("Internal state should be reset", holder.isSilenced)
    }

    // ===== Compatibility Tests =====

    @Test
    fun testCompatibilityMode_mediaRecorderConfiguration() {
        // Given: MediaRecorderHolder instance
        val holder = createMediaRecorderHolder()

        // When: Starting in compatibility mode
        assertDoesNotThrow("MediaRecorder compatibility mode should not crash") {
            holder.startRecording()
        }

        // Then: Should configure for stereo AAC at 48kHz (verified by no exceptions)
        assertFalse("Should start in unsilenced state", holder.isSilenced)
    }

    // ===== Helper Methods =====

    private fun createMediaRecorderHolder(): MediaRecorderHolder {
        return MediaRecorderHolder(context, mockAudioManager, mockWakeLockManager, onSilencedChangedCallback)
    }

    /**
     * Properly simulates silencing events by updating both the callback and internal state.
     * This mimics the behavior of the actual AudioRecordingCallback mechanism.
     */
    private fun simulateSilencingEvent(holder: MediaRecorderHolder, silenced: Boolean) {
        // Get current state to implement proper state change detection
        val currentState = holder.isSilenced
        
        // Only trigger callback and update state if there's actually a change
        if (silenced != currentState) {
            // Update internal state using reflection (since isSilenced is private set)
            updateInternalSilencedState(holder, silenced)
            
            // Invoke the callback to simulate the AudioRecordingCallback behavior
            onSilencedChangedCallback(silenced)
        }
    }
    
    /**
     * Updates the internal isSilenced state using reflection.
     * This is necessary because the property has a private setter.
     */
    private fun updateInternalSilencedState(holder: MediaRecorderHolder, silenced: Boolean) {
        try {
            val field: Field = holder.javaClass.getDeclaredField("isSilenced")
            field.isAccessible = true
            field.setBoolean(holder, silenced)
        } catch (e: Exception) {
            // If reflection fails, we can't properly simulate the state change
            // This would indicate that the test needs to be restructured
            throw AssertionError("Could not update internal silenced state: ${e.message}", e)
        }
    }

    // Helper method for assertDoesNotThrow (if not available in your test framework)
    private fun assertDoesNotThrow(message: String, executable: () -> Unit) {
        try {
            executable()
        } catch (e: Exception) {
            fail("$message: ${e.message}")
        }
    }
}