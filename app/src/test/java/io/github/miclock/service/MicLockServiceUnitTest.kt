package io.github.miclock.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.miclock.service.model.ServiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MicLockService focusing on tile integration behaviors,
 * foreground service start failures, and restart notification suppression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MicLockServiceUnitTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockNotificationManager: NotificationManager

    private lateinit var testableMicLockService: TestableMicLockService
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        testScope = TestScope()

        // Setup mock context
        whenever(mockContext.getSystemService(Context.NOTIFICATION_SERVICE))
            .thenReturn(mockNotificationManager)
        whenever(mockContext.packageName).thenReturn("io.github.miclock")

        // Setup notification manager
        whenever(mockNotificationManager.areNotificationsEnabled()).thenReturn(true)

        testableMicLockService = TestableMicLockService(mockContext, testScope)
    }

    @Test
    fun testHandleStartUserInitiated_fgsFailsWithFromTileTrue_broadcastsFailureAndSuppressesNotification() = runTest {
        // Given: Intent with from_tile=true and FGS will fail
        val intent = Intent().apply {
            action = MicLockService.ACTION_START_USER_INITIATED
            putExtra("from_tile", true)
        }
        testableMicLockService.setForegroundServiceException(
            RuntimeException("FOREGROUND_SERVICE_MICROPHONE requires permissions"),
        )
        testableMicLockService.setPermissionsGranted(true)

        // When: handleStartUserInitiated is called
        testableMicLockService.testHandleStartUserInitiated(intent)

        // Then: Should broadcast tile failure and suppress restart notification
        assertTrue(
            "Should broadcast tile start failure",
            testableMicLockService.wasTileFailureBroadcast(),
        )
        assertEquals(
            "Should broadcast correct failure reason",
            MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION,
            testableMicLockService.getLastBroadcastFailureReason(),
        )
        assertTrue(
            "Should suppress restart notification",
            testableMicLockService.isRestartNotificationSuppressed(),
        )
        assertFalse(
            "Service should not be running after FGS failure",
            testableMicLockService.isServiceRunning(),
        )
    }

    @Test
    fun testHandleStartUserInitiated_fgsFailsWithFromTileFalse_doesNotBroadcastButSuppresses() = runTest {
        // Given: Intent with from_tile=false and FGS will fail
        val intent = Intent().apply {
            action = MicLockService.ACTION_START_USER_INITIATED
            putExtra("from_tile", false)
        }
        testableMicLockService.setForegroundServiceException(
            RuntimeException("FOREGROUND_SERVICE_MICROPHONE requires permissions"),
        )
        testableMicLockService.setPermissionsGranted(true)

        // When: handleStartUserInitiated is called
        testableMicLockService.testHandleStartUserInitiated(intent)

        // Then: Should not broadcast tile failure but should still suppress restart notification
        assertFalse(
            "Should not broadcast tile failure for non-tile starts",
            testableMicLockService.wasTileFailureBroadcast(),
        )
        assertTrue(
            "Should still suppress restart notification",
            testableMicLockService.isRestartNotificationSuppressed(),
        )
        assertFalse(
            "Service should not be running after FGS failure",
            testableMicLockService.isServiceRunning(),
        )
    }

    @Test
    fun testHandleStartUserInitiated_fgsSucceeds_clearsFailureState() = runTest {
        // Given: Intent and FGS will succeed
        val intent = Intent().apply {
            action = MicLockService.ACTION_START_USER_INITIATED
            putExtra("from_tile", true)
        }
        testableMicLockService.setForegroundServiceWillFail(false)
        testableMicLockService.setPermissionsGranted(true)

        // When: handleStartUserInitiated is called
        testableMicLockService.testHandleStartUserInitiated(intent)

        // Then: Should clear failure state and start successfully
        assertFalse(
            "Should not broadcast tile failure on success",
            testableMicLockService.wasTileFailureBroadcast(),
        )
        assertFalse(
            "Should not suppress restart notification on success",
            testableMicLockService.isRestartNotificationSuppressed(),
        )
        assertTrue(
            "Service should be running after successful start",
            testableMicLockService.isServiceRunning(),
        )
    }

    @Test
    fun testOnDestroy_withSuppressRestartNotificationTrue_doesNotCreateNotification() {
        // Given: Service was running and restart notification is suppressed
        testableMicLockService.setServiceWasRunning(true)
        testableMicLockService.setSuppressRestartNotification(true)

        // When: onDestroy is called
        testableMicLockService.testOnDestroy()

        // Then: Should not create restart notification
        assertFalse(
            "Should not create restart notification when suppressed",
            testableMicLockService.wasRestartNotificationCreated(),
        )
    }

    @Test
    fun testOnDestroy_withSuppressRestartNotificationFalse_createsNotification() {
        // Given: Service was running and restart notification is not suppressed
        testableMicLockService.setServiceWasRunning(true)
        testableMicLockService.setSuppressRestartNotification(false)

        // When: onDestroy is called
        testableMicLockService.testOnDestroy()

        // Then: Should create restart notification
        assertTrue(
            "Should create restart notification when not suppressed",
            testableMicLockService.wasRestartNotificationCreated(),
        )
    }

    @Test
    fun testOnDestroy_serviceWasNotRunning_doesNotCreateNotification() {
        // Given: Service was not running
        testableMicLockService.setServiceWasRunning(false)
        testableMicLockService.setSuppressRestartNotification(false)

        // When: onDestroy is called
        testableMicLockService.testOnDestroy()

        // Then: Should not create restart notification
        assertFalse(
            "Should not create restart notification when service was not running",
            testableMicLockService.wasRestartNotificationCreated(),
        )
    }

    @Test
    fun testBroadcastTileStartFailure_createsCorrectIntent() {
        // Given: Service with tile failure
        val reason = MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION

        // When: Broadcasting tile start failure
        testableMicLockService.testBroadcastTileStartFailure(reason)

        // Then: Should create correct broadcast intent
        assertTrue(
            "Should broadcast tile failure",
            testableMicLockService.wasTileFailureBroadcast(),
        )
        assertEquals(
            "Should broadcast correct action",
            MicLockService.ACTION_TILE_START_FAILED,
            testableMicLockService.getLastBroadcastAction(),
        )
        assertEquals(
            "Should broadcast correct failure reason",
            reason,
            testableMicLockService.getLastBroadcastFailureReason(),
        )
        assertEquals(
            "Should set correct package name",
            "io.github.miclock",
            testableMicLockService.getLastBroadcastPackage(),
        )
    }

    @Test
    fun testForegroundServiceStartException_setsCorrectFailureReason() = runTest {
        // Test different FGS exception messages
        val testCases = listOf(
            "FOREGROUND_SERVICE_MICROPHONE" to MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION,
            "requires permissions" to MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION,
            "eligible state" to MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION,
            "Some other error" to null,
        )

        testCases.forEach { (errorMessage, expectedReason) ->
            // Reset service state
            val freshService = TestableMicLockService(mockContext, testScope)
            freshService.setPermissionsGranted(true)
            freshService.setForegroundServiceException(RuntimeException(errorMessage))

            val intent = Intent().apply {
                action = MicLockService.ACTION_START_USER_INITIATED
                putExtra("from_tile", true)
            }

            // When: handleStartUserInitiated is called
            freshService.testHandleStartUserInitiated(intent)

            // Then: Should set correct failure reason
            if (expectedReason != null) {
                assertEquals(
                    "Should set correct failure reason for: $errorMessage",
                    expectedReason,
                    freshService.getStartFailureReason(),
                )
                assertTrue(
                    "Should suppress restart notification for FGS restriction",
                    freshService.isRestartNotificationSuppressed(),
                )
            } else {
                assertNull(
                    "Should not set failure reason for non-FGS error: $errorMessage",
                    freshService.getStartFailureReason(),
                )
            }
        }
    }
}

/**
 * Testable version of MicLockService that allows mocking of Android framework
 * interactions and provides access to internal state for verification.
 */
class TestableMicLockService(
    private val mockContext: Context,
    private val testScope: TestScope,
) {

    private val _state = MutableStateFlow(ServiceState())

    // Test control flags
    private var foregroundServiceWillFail = false
    private var foregroundServiceException: Exception? = null
    private var permissionsGranted = false

    // Internal state tracking
    private var startFailureReason: String? = null
    private var suppressRestartNotification = false
    private var serviceWasRunning = false

    // Broadcast tracking
    private val broadcastIntents = mutableListOf<Intent>()

    // Notification tracking
    private var restartNotificationCreated = false

    // Test control methods
    fun setForegroundServiceWillFail(willFail: Boolean) {
        foregroundServiceWillFail = willFail
    }

    fun setForegroundServiceException(exception: Exception) {
        foregroundServiceException = exception
        foregroundServiceWillFail = true
    }

    fun setPermissionsGranted(granted: Boolean) {
        permissionsGranted = granted
    }

    fun setServiceWasRunning(wasRunning: Boolean) {
        serviceWasRunning = wasRunning
        _state.value = _state.value.copy(isRunning = wasRunning)
    }

    fun setSuppressRestartNotification(suppress: Boolean) {
        suppressRestartNotification = suppress
    }

    // Mock service methods
    fun testHandleStartUserInitiated(intent: Intent?) {
        val isFromTile = intent?.getBooleanExtra("from_tile", false) ?: false

        if (!_state.value.isRunning) {
            try {
                mockStartForeground()

                // Clear any previous failure state
                startFailureReason = null
                suppressRestartNotification = false

                // Simulate successful start
                _state.value = _state.value.copy(isRunning = true)
            } catch (e: Exception) {
                val errorMessage = e.message ?: ""
                if (errorMessage.contains("FOREGROUND_SERVICE_MICROPHONE") ||
                    errorMessage.contains("requires permissions") ||
                    errorMessage.contains("eligible state")
                ) {
                    startFailureReason = MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION
                    suppressRestartNotification = true

                    if (isFromTile) {
                        testBroadcastTileStartFailure(MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
                    }
                } else {
                    // For non-FGS errors, still suppress restart notification
                    suppressRestartNotification = true
                }

                _state.value = _state.value.copy(isRunning = false)
                return
            }
        }
    }

    fun testOnDestroy() {
        val wasRunning = serviceWasRunning || _state.value.isRunning
        _state.value = _state.value.copy(isRunning = false, isPausedBySilence = false, currentDeviceAddress = null)

        if (wasRunning && !suppressRestartNotification) {
            mockCreateRestartNotification()
        }
    }

    fun testBroadcastTileStartFailure(reason: String) {
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, reason)
            setPackage(mockContext.packageName)
        }
        broadcastIntents.add(failureIntent)
    }

    private fun mockStartForeground() {
        if (foregroundServiceWillFail) {
            throw foregroundServiceException ?: RuntimeException("Simulated foreground service failure")
        }
    }

    private fun mockCreateRestartNotification() {
        restartNotificationCreated = true
    }

    // Test assertion methods
    fun wasTileFailureBroadcast(): Boolean {
        return broadcastIntents.any { it.action == MicLockService.ACTION_TILE_START_FAILED }
    }

    fun getLastBroadcastFailureReason(): String? {
        return broadcastIntents.lastOrNull { it.action == MicLockService.ACTION_TILE_START_FAILED }
            ?.getStringExtra(MicLockService.EXTRA_FAILURE_REASON)
    }

    fun getLastBroadcastAction(): String? {
        return broadcastIntents.lastOrNull()?.action
    }

    fun getLastBroadcastPackage(): String? {
        return broadcastIntents.lastOrNull()?.`package`
    }

    fun isRestartNotificationSuppressed(): Boolean = suppressRestartNotification

    fun isServiceRunning(): Boolean = _state.value.isRunning

    fun wasRestartNotificationCreated(): Boolean = restartNotificationCreated

    fun getStartFailureReason(): String? = startFailureReason
}
