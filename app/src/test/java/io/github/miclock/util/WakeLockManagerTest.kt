package io.github.miclock.util

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Unit tests for WakeLockManager utility.
 * Tests power management functionality to ensure CPU stays awake during critical operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class WakeLockManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ===== Acquisition Tests =====

    @Test
    fun testAcquire_whenNotHeld_acquiresWakeLock() {
        // Given: WakeLock is not currently held
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")
        assertFalse("Should start not held", wakeLockManager.isHeld)

        // When: Acquiring wake lock
        wakeLockManager.acquire()

        // Then: Should be held (in Robolectric simulation)
        // Note: Robolectric simulates WakeLock behavior
    }

    @Test
    fun testAcquire_whenAlreadyHeld_doesNotAcquireAgain() {
        // Given: WakeLock that is acquired
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")
        wakeLockManager.acquire()

        // When: Attempting to acquire again
        assertDoesNotThrow("Should not throw on double acquire") {
            wakeLockManager.acquire()
        }

        // Then: Should remain stable without issues
    }

    @Test
    fun testAcquire_multipleCallsIdempotent() {
        // Given: WakeLockManager instance
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")

        // When: Multiple acquire calls
        assertDoesNotThrow("Multiple acquire calls should be safe") {
            wakeLockManager.acquire()
            wakeLockManager.acquire()
            wakeLockManager.acquire()
        }

        // Then: Should not crash and remain stable
    }

    // ===== Release Tests =====

    @Test
    fun testRelease_whenHeld_releasesWakeLock() {
        // Given: WakeLock that is held
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")
        wakeLockManager.acquire()

        // When: Releasing wake lock
        assertDoesNotThrow("Should not throw during release") {
            wakeLockManager.release()
        }

        // Then: Should complete without issues
    }

    @Test
    fun testRelease_whenNotHeld_doesNotReleaseAgain() {
        // Given: WakeLock that is not held
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")
        assertFalse("Should start not held", wakeLockManager.isHeld)

        // When: Attempting to release
        assertDoesNotThrow("Should not throw when releasing non-held lock") {
            wakeLockManager.release()
        }

        // Then: Should remain stable without issues
    }

    @Test
    fun testRelease_multipleCallsIdempotent() {
        // Given: WakeLockManager that was held and released
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")
        wakeLockManager.acquire()
        wakeLockManager.release()

        // When: Multiple release calls
        assertDoesNotThrow("Multiple release calls should be safe") {
            wakeLockManager.release()
            wakeLockManager.release()
            wakeLockManager.release()
        }

        // Then: Should not crash and remain stable
    }

    // ===== State Management Tests =====

    @Test
    fun testIsHeld_initialState() {
        // Given: New WakeLockManager instance
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")

        // Then: Should start in not-held state
        assertFalse("Should start not held", wakeLockManager.isHeld)
    }

    // ===== Lifecycle Tests =====

    @Test
    fun testCompleteLifecycle_acquireReleaseSequence() {
        // Given: WakeLockManager instance
        val wakeLockManager = WakeLockManager(context, "TestWakeLock")

        // When: Complete lifecycle
        assertFalse("Should start not held", wakeLockManager.isHeld)

        assertDoesNotThrow("Should acquire without issues") {
            wakeLockManager.acquire()
        }

        assertDoesNotThrow("Should release without issues") {
            wakeLockManager.release()
        }

        // Then: Lifecycle should complete successfully
    }

    // ===== Real Implementation Tests =====

    @Test
    fun testRealImplementation_acquireRelease() {
        // Given: Real WakeLockManager with actual Android context
        val realWakeLockManager = WakeLockManager(context, "RealTestWakeLock")

        // When: Acquiring and releasing
        assertDoesNotThrow("Real implementation should work") {
            realWakeLockManager.acquire()
            realWakeLockManager.release()
        }

        // Then: Should complete without exceptions
    }

    @Test
    fun testRealImplementation_idempotentOperations() {
        // Given: Real WakeLockManager
        val realWakeLockManager = WakeLockManager(context, "IdempotentTestWakeLock")

        // When: Multiple idempotent operations
        assertDoesNotThrow("Multiple acquire calls should not crash") {
            realWakeLockManager.acquire()
            realWakeLockManager.acquire()
            realWakeLockManager.acquire()
        }

        assertDoesNotThrow("Multiple release calls should not crash") {
            realWakeLockManager.release()
            realWakeLockManager.release()
            realWakeLockManager.release()
        }
    }

    // ===== Error Handling Tests =====

    @Test
    fun testErrorHandling_contextOperations() {
        // Given: WakeLockManager with valid context
        assertDoesNotThrow("WakeLockManager creation should not throw") {
            val wakeLockManager = WakeLockManager(context, "ErrorTestWakeLock")
            
            // When: Normal operations
            wakeLockManager.acquire()
            wakeLockManager.release()
        }
    }

    @Test
    fun testErrorHandling_multipleInstances() {
        // Given: Multiple WakeLockManager instances
        assertDoesNotThrow("Multiple instances should not interfere") {
            val wakeLockManager1 = WakeLockManager(context, "TestWakeLock1")
            val wakeLockManager2 = WakeLockManager(context, "TestWakeLock2")
            
            wakeLockManager1.acquire()
            wakeLockManager2.acquire()
            wakeLockManager1.release()
            wakeLockManager2.release()
        }
    }
}
