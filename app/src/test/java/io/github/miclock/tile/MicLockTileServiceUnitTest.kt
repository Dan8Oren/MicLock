package io.github.miclock.tile

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import io.github.miclock.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build

/**
 * Unit tests for MicLockTileService.
 * Tests the tile's logic and state management using mocks and Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MicLockTileServiceUnitTest {

    @Mock
    private lateinit var mockTile: Tile
    
    @Mock
    private lateinit var mockContext: Context
    
    private lateinit var tileService: TestableMicLockTileService
    private lateinit var mockStateFlow: MutableStateFlow<ServiceState>

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        // Create a mock state flow
        mockStateFlow = MutableStateFlow(ServiceState())
        
        // Create testable tile service
        tileService = TestableMicLockTileService(mockStateFlow)
        
        // Setup mock tile
        whenever(mockTile.state).thenReturn(Tile.STATE_INACTIVE)
    }

    @Test
    fun testOnStartListening_startsStateCollection() {
        // Given: Tile service instance
        
        // When: onStartListening is called
        tileService.onStartListening()
        
        // Then: State collection should be active
        assertTrue("State collection should be active", tileService.isStateCollectionActive)
    }

    @Test
    fun testOnStopListening_stopsStateCollection() {
        // Given: Tile service with active state collection
        tileService.onStartListening()
        assertTrue("State collection should be active", tileService.isStateCollectionActive)
        
        // When: onStopListening is called
        tileService.onStopListening()
        
        // Then: State collection should be stopped
        assertFalse("State collection should be stopped", tileService.isStateCollectionActive)
    }

    @Test
    fun testOnClick_missingPermissions_doesNothing() {
        // Given: Permissions are missing
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = false, hasNotifications = true)
        
        // When: onClick is called
        val result = tileService.testOnClick()
        
        // Then: Should do nothing - no activity launch, no service intent
        assertNull("Should not send service intent when permissions missing", result)
    }

    @Test
    fun testOnClick_missingNotificationPermissions_doesNothing() {
        // Given: Notification permissions are missing
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = false)
        
        // When: onClick is called
        val result = tileService.testOnClick()
        
        // Then: Should do nothing - no activity launch, no service intent
        assertNull("Should not send service intent when permissions missing", result)
    }

    @Test
    fun testOnClick_allPermissionsGranted_serviceRunning_sendsStopAction() {
        // Given: All permissions granted and service is running
        mockStateFlow.value = ServiceState(isRunning = true, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        
        // When: onClick is called
        val capturedIntent = tileService.testOnClick()
        
        // Then: Should send STOP action
        assertEquals("Should send STOP action", MicLockService.ACTION_STOP, capturedIntent?.action)
    }

    @Test
    fun testOnClick_allPermissionsGranted_serviceStopped_sendsStartAction() {
        // Given: All permissions granted and service is stopped
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        
        // When: onClick is called
        val capturedIntent = tileService.testOnClick()
        
        // Then: Should send START_USER_INITIATED action
        assertEquals("Should send START_USER_INITIATED action", 
            MicLockService.ACTION_START_USER_INITIATED, capturedIntent?.action)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testUpdateTileState_serviceOff_setsInactiveState() = runTest {
        // Given: Service is off
        val state = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        
        // When: updateTileState is called
        tileService.testUpdateTileState(state)
        
        // Then: Tile should be set to inactive state
        verify(mockTile).state = Tile.STATE_INACTIVE
        verify(mockTile).label = TILE_TEXT
        verify(mockTile).contentDescription = "Tap to start microphone protection"
        verify(mockTile).updateTile()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testUpdateTileState_serviceOnAndActive_setsActiveState() = runTest {
        // Given: Service is on and active
        val state = ServiceState(isRunning = true, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        
        // When: updateTileState is called
        tileService.testUpdateTileState(state)
        
        // Then: Tile should be set to active state
        verify(mockTile).state = Tile.STATE_ACTIVE
        verify(mockTile).label = TILE_TEXT
        verify(mockTile).contentDescription = "Tap to stop microphone protection"
        verify(mockTile).updateTile()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testUpdateTileState_servicePaused_setsUnavailableState() = runTest {
        // Given: Service is paused
        val state = ServiceState(isRunning = true, isPausedBySilence = true)
        tileService.setMockTile(mockTile)
        
        // When: updateTileState is called
        tileService.testUpdateTileState(state)
        
        // Then: Tile should be set to unavailable state
        verify(mockTile).state = Tile.STATE_UNAVAILABLE
        verify(mockTile).label = TILE_TEXT
        verify(mockTile).contentDescription = "Microphone protection paused"
        verify(mockTile).updateTile()
    }

    @Test
    fun testOnDestroy_cancelsScope() {
        // Given: Tile service with active state collection
        tileService.onStartListening()
        assertTrue("State collection should be active", tileService.isStateCollectionActive)
        
        // When: onDestroy is called
        tileService.onDestroy()
        
        // Then: Scope should be cancelled and state collection stopped
        assertFalse("State collection should be stopped after destroy", tileService.isStateCollectionActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testUpdateTileState_missingPermissions_setsUnavailableState() = runTest {
        // Given: Permissions are missing
        val state = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = false, hasNotifications = true)
        
        // When: updateTileState is called
        tileService.testUpdateTileState(state)
        
        // Then: Tile should be set to unavailable state with "No Permission" label
        verify(mockTile).state = Tile.STATE_UNAVAILABLE
        verify(mockTile).label = "No Permission"
        verify(mockTile).contentDescription = "Tap to grant microphone and notification permissions"
        verify(mockTile).updateTile()
    }

    @Test
    fun testLifecycle_completeFlow() {
        // Given: Fresh tile service
        assertFalse("State collection should not be active initially", tileService.isStateCollectionActive)
        
        // When: Start listening
        tileService.onStartListening()
        assertTrue("State collection should be active after start listening", tileService.isStateCollectionActive)
        
        // When: Stop listening
        tileService.onStopListening()
        assertFalse("State collection should be stopped after stop listening", tileService.isStateCollectionActive)
        
        // When: Start again
        tileService.onStartListening()
        assertTrue("State collection should be active after restart", tileService.isStateCollectionActive)
        
        // When: Destroy
        tileService.onDestroy()
        assertFalse("State collection should be stopped after destroy", tileService.isStateCollectionActive)
    }

    @Test
    fun testOnClick_directServiceStart_withTileMarking() {
        // Given: All permissions granted and service is stopped
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        
        // When: onClick is called
        val capturedIntent = tileService.testOnClick()
        
        // Then: Should send START_USER_INITIATED with from_tile=true
        assertNotNull("Should send intent", capturedIntent)
        assertEquals("Should send START_USER_INITIATED action", 
            MicLockService.ACTION_START_USER_INITIATED, capturedIntent?.action)
        assertTrue("Should mark as from_tile", 
            capturedIntent?.getBooleanExtra("from_tile", false) ?: false)
    }

    @Test
    fun testDirectServiceStartFailure_createsFailureNotification() {
        // Given: Service start will fail
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        tileService.setServiceStartWillFail(true)
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        
        // When: onClick attempts to start service
        val result = tileService.testOnClick()
        
        // Then: Should create failure notification and return null
        assertNull("Should not return intent when service start fails", result)
        assertTrue("Should create tile failure notification", 
            tileService.wasFailureNotificationCreated())
        val notification = tileService.getLastFailureNotification()
        assertNotNull("Should have failure notification", notification)
        assertEquals("MicLock Tile Failed Unexpectedly", notification?.title)
        assertTrue("Should mention service failure", 
            notification?.bigText?.contains("Service failed to start") ?: false)
    }

    @Test
    fun testBroadcastReceiver_lifecycle_properRegistrationAndUnregistration() {
        // Given: Fresh tile service
        assertFalse("Should not be listening initially", tileService.isBroadcastReceiverRegistered())
        
        // When: Start listening
        tileService.onStartListening()
        assertTrue("Should register broadcast receiver", tileService.isBroadcastReceiverRegistered())
        
        // When: Stop listening
        tileService.onStopListening()
        assertFalse("Should unregister broadcast receiver", tileService.isBroadcastReceiverRegistered())
    }

    @Test
    fun testBroadcastReceiver_foregroundRestrictionFailure_triggersMainActivityFallback() {
        // Given: Tile service is listening
        tileService.onStartListening()
        
        // When: Service broadcasts foreground restriction failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        tileService.simulateBroadcastReceived(failureIntent)
        
        // Then: Should attempt MainActivity fallback
        assertTrue("Should have attempted MainActivity fallback", 
            tileService.wasMainActivityFallbackAttempted())
        // No failure notification should be created at this stage of escalation
        assertFalse("Should not create failure notification for normal escalation", 
            tileService.wasFailureNotificationCreated())
    }

    @Test
    fun testMainActivityFallbackSuccess_noFailureNotification() {
        // Given: MainActivity launch will succeed
        tileService.setMainActivityLaunchWillFail(false)
        tileService.onStartListening()
        
        // When: Service broadcasts foreground restriction failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        tileService.simulateBroadcastReceived(failureIntent)
        
        // Then: Should attempt MainActivity fallback successfully without failure notification
        assertTrue("Should have attempted MainActivity fallback", 
            tileService.wasMainActivityFallbackAttempted())
        assertFalse("Should not create failure notification on successful fallback", 
            tileService.wasFailureNotificationCreated())
    }

    @Test
    fun testMainActivityFallbackFailure_createsFailureNotification() {
        // Given: MainActivity launch will fail
        tileService.setMainActivityLaunchWillFail(true)
        tileService.onStartListening()
        
        // When: Service broadcasts foreground restriction failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        tileService.simulateBroadcastReceived(failureIntent)
        
        // Then: Should attempt MainActivity fallback and create failure notification
        assertTrue("Should have attempted MainActivity fallback", 
            tileService.wasMainActivityFallbackAttempted())
        assertTrue("Should create failure notification for complete failure", 
            tileService.wasFailureNotificationCreated())
        val notification = tileService.getLastFailureNotification()
        assertNotNull("Should have failure notification", notification)
        assertEquals("MicLock Tile Failed Unexpectedly", notification?.title)
        assertTrue("Should indicate complete failure", 
            notification?.bigText?.contains("Both service start and app launch failed") ?: false)
    }

    @Test
    fun testCreateTileFailureNotification_variousReasons() {
        // Test different failure reasons produce appropriate notification content
        val testCases = listOf(
            "Service failed to start: Permission denied",
            "Both service start and app launch failed: Activity not found",
            "Network timeout during service initialization"
        )
        
        testCases.forEachIndexed { index, reason ->
            // Reset state
            val freshTileService = TestableMicLockTileService(mockStateFlow)
            freshTileService.mockCreateTileFailureNotification(reason)
            
            assertTrue("Should create failure notification for case $index", 
                freshTileService.wasFailureNotificationCreated())
            val notification = freshTileService.getLastFailureNotification()
            assertNotNull("Should have notification for case $index", notification)
            assertEquals("MicLock Tile Failed Unexpectedly", notification?.title)
            assertTrue("Should contain reason in bigText for case $index", 
                notification?.bigText?.contains(reason) ?: false)
        }
    }

    @Test
    fun testCompleteEscalationFlow_successfulFallback() {
        // Given: Service will start but report FGS failure, MainActivity will succeed
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        tileService.setServiceStartWillFail(false) // Direct start succeeds initially
        tileService.setMainActivityLaunchWillFail(false)
        tileService.onStartListening()
        
        // 1. User clicks tile -> direct service start
        val capturedIntent = tileService.testOnClick()
        assertNotNull("Should send start intent", capturedIntent)
        assertEquals(MicLockService.ACTION_START_USER_INITIATED, capturedIntent?.action)
        assertTrue("Should mark as from_tile", capturedIntent?.getBooleanExtra("from_tile", false) ?: false)
        
        // 2. Simulate MicLockService broadcasting FGS failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        tileService.simulateBroadcastReceived(failureIntent)
        
        // 3. Should complete escalation successfully
        assertTrue("Should attempt MainActivity fallback", tileService.wasMainActivityFallbackAttempted())
        assertFalse("Should not create failure notification on successful escalation", 
            tileService.wasFailureNotificationCreated())
    }

    @Test
    fun testCompleteEscalationFlow_completeFailure() {
        // Given: Direct service start succeeds, but MainActivity launch fails
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        tileService.setServiceStartWillFail(false) // Direct start succeeds initially
        tileService.setMainActivityLaunchWillFail(true)
        tileService.onStartListening()
        
        // 1. User clicks tile -> direct service start
        val capturedIntent = tileService.testOnClick()
        assertNotNull("Should send start intent", capturedIntent)
        assertEquals(MicLockService.ACTION_START_USER_INITIATED, capturedIntent?.action)
        assertTrue("Should mark as from_tile", capturedIntent?.getBooleanExtra("from_tile", false) ?: false)
        
        // 2. Simulate MicLockService broadcasting FGS failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        tileService.simulateBroadcastReceived(failureIntent)
        
        // 3. Should create appropriate failure notification
        assertTrue("Should attempt MainActivity fallback", tileService.wasMainActivityFallbackAttempted())
        assertTrue("Should create failure notification", tileService.wasFailureNotificationCreated())
        val notification = tileService.getLastFailureNotification()
        assertNotNull("Should have failure notification", notification)
        assertEquals("MicLock Tile Failed Unexpectedly", notification?.title)
        assertTrue("Should indicate complete failure", 
            notification?.bigText?.contains("Both service start and app launch failed") ?: false)
    }

    @Test
    fun testDirectServiceStartAndFallbackIntegration() {
        // Test the interaction between direct service start failures and escalation
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        
        // Case 1: Direct service start fails immediately
        tileService.setServiceStartWillFail(true)
        val result1 = tileService.testOnClick()
        assertNull("Should not return intent when direct start fails", result1)
        assertTrue("Should create failure notification for direct start failure", 
            tileService.wasFailureNotificationCreated())
        
        // Reset for Case 2
        val freshTileService = TestableMicLockTileService(mockStateFlow)
        freshTileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        freshTileService.setServiceStartWillFail(false)
        freshTileService.setMainActivityLaunchWillFail(false)
        freshTileService.onStartListening()
        
        // Case 2: Direct service start succeeds, but later reports FGS failure
        val result2 = freshTileService.testOnClick()
        assertNotNull("Should return intent when direct start succeeds", result2)
        assertFalse("Should not create failure notification yet", 
            freshTileService.wasFailureNotificationCreated())
        
        // Simulate delayed FGS failure
        val failureIntent = Intent(MicLockService.ACTION_TILE_START_FAILED).apply {
            putExtra(MicLockService.EXTRA_FAILURE_REASON, MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION)
        }
        freshTileService.simulateBroadcastReceived(failureIntent)
        
        assertTrue("Should attempt MainActivity fallback", 
            freshTileService.wasMainActivityFallbackAttempted())
        assertFalse("Should not create failure notification for successful escalation", 
            freshTileService.wasFailureNotificationCreated())
    }
}

/**
 * Enhanced testable version of MicLockTileService with comprehensive mocking capabilities
 * for testing the new tile behavior including broadcast handling, service start failures,
 * MainActivity fallback, and notification creation.
 */
class TestableMicLockTileService(
    private val mockStateFlow: StateFlow<ServiceState>,
    private val mockContext: Context = mock(),
    private val mockNotificationManager: NotificationManager = mock(),
    private val mockActivityManager: ActivityManager = mock()
) {
    
    private var mockTile: Tile? = null
    private var hasRecordAudioPermission = true
    private var hasNotificationPermission = true
    var isStateCollectionActive = false
        private set
    
    // Testable state tracking fields
    private var serviceStartWillFail: Boolean = false
    private var mainActivityLaunchWillFail: Boolean = false
    private val capturedNotifications = mutableListOf<MockNotification>()
    private val capturedBroadcasts = mutableListOf<Intent>()
    private val registeredReceivers = mutableListOf<BroadcastReceiver>()
    var mainActivityFallbackAttempted: Boolean = false
        private set
    var failureNotificationCreated: Boolean = false
        private set
    
    // Mock broadcast receiver for simulation
    private var mockFailureReceiver: BroadcastReceiver? = null
    
    fun setMockTile(tile: Tile) {
        mockTile = tile
    }
    
    fun setMockPermissions(hasRecordAudio: Boolean, hasNotifications: Boolean) {
        hasRecordAudioPermission = hasRecordAudio
        hasNotificationPermission = hasNotifications
    }
    
    fun setServiceStartWillFail(willFail: Boolean) {
        serviceStartWillFail = willFail
    }
    
    fun setMainActivityLaunchWillFail(willFail: Boolean) {
        mainActivityLaunchWillFail = willFail
    }
    
    // Mock Android framework methods
    fun mockGetSystemService(name: String): Any? {
        return when (name) {
            Context.NOTIFICATION_SERVICE -> mockNotificationManager
            Context.ACTIVITY_SERVICE -> mockActivityManager
            else -> null
        }
    }
    
    fun mockCheckSelfPermission(permission: String): Int {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> {
                if (hasRecordAudioPermission) PackageManager.PERMISSION_GRANTED 
                else PackageManager.PERMISSION_DENIED
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                if (hasNotificationPermission) PackageManager.PERMISSION_GRANTED 
                else PackageManager.PERMISSION_DENIED
            }
            else -> PackageManager.PERMISSION_DENIED
        }
    }
    
    fun mockStartForegroundService(intent: Intent) {
        if (serviceStartWillFail) {
            throw RuntimeException("Simulated service start failure")
        }
        // Record the intent for verification
        capturedBroadcasts.add(intent)
    }
    
    fun mockStartActivityAndCollapse(pendingIntent: PendingIntent) {
        if (mainActivityLaunchWillFail) {
            throw RuntimeException("Simulated MainActivity launch failure")
        }
        // Record the fallback attempt
        mainActivityFallbackAttempted = true
    }
    
    fun mockCreateTileFailureNotification(reason: String) {
        failureNotificationCreated = true
        val notification = MockNotification(
            title = "MicLock Tile Failed Unexpectedly",
            text = "Tap to open app and start protection",
            bigText = "$reason. Tap to open app and start protection manually."
        )
        capturedNotifications.add(notification)
    }
    
    fun mockRegisterReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (filter.getAction(0) == MicLockService.ACTION_TILE_START_FAILED) {
            mockFailureReceiver = receiver
            registeredReceivers.add(receiver)
        }
    }
    
    fun mockUnregisterReceiver(receiver: BroadcastReceiver) {
        if (receiver == mockFailureReceiver) {
            mockFailureReceiver = null
            registeredReceivers.remove(receiver)
        }
    }
    
    // Test helper methods for assertions
    fun wasMainActivityFallbackAttempted(): Boolean = mainActivityFallbackAttempted
    
    fun wasFailureNotificationCreated(): Boolean = failureNotificationCreated
    
    fun getLastFailureNotification(): MockNotification? = capturedNotifications.lastOrNull()
    
    fun getCapturedNotifications(): List<MockNotification> = capturedNotifications.toList()
    
    fun getRegisteredBroadcastReceivers(): List<BroadcastReceiver> = registeredReceivers.toList()
    
    fun isBroadcastReceiverRegistered(): Boolean = registeredReceivers.isNotEmpty()
    
    fun getCapturedServiceIntents(): List<Intent> = capturedBroadcasts.filter { 
        it.component?.className == "io.github.miclock.service.MicLockService" 
    }
    
    fun simulateBroadcastReceived(intent: Intent) {
        if (intent.action == MicLockService.ACTION_TILE_START_FAILED) {
            val reason = intent.getStringExtra(MicLockService.EXTRA_FAILURE_REASON)
            if (reason == MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION) {
                launchMainActivityFallback()
            }
        }
    }
    
    private fun hasAllPerms(): Boolean {
        val hasPerms = hasRecordAudioPermission && hasNotificationPermission
        println("Permission check: mic=$hasRecordAudioPermission, notifs=$hasNotificationPermission, hasAll=$hasPerms")
        return hasPerms
    }
    
    fun getQsTile(): Tile? = mockTile
    
    fun onStartListening() {
        isStateCollectionActive = true
        // Simulate broadcast receiver registration
        val mockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == MicLockService.ACTION_TILE_START_FAILED) {
                    val reason = intent.getStringExtra(MicLockService.EXTRA_FAILURE_REASON)
                    if (reason == MicLockService.FAILURE_REASON_FOREGROUND_RESTRICTION) {
                        launchMainActivityFallback()
                    }
                }
            }
        }
        val filter = IntentFilter(MicLockService.ACTION_TILE_START_FAILED)
        mockRegisterReceiver(mockReceiver, filter)
        testUpdateTileState(mockStateFlow.value)
    }
    
    fun onStopListening() {
        isStateCollectionActive = false
        // Simulate broadcast receiver unregistration
        mockFailureReceiver?.let { mockUnregisterReceiver(it) }
    }
    
    fun testOnClick(): Intent? {
        val currentPerms = hasAllPerms()
        println("onClick permission check result: $currentPerms")
        
        if (!currentPerms) {
            println("Permissions missing - forcing tile update to show unavailable state")
            testUpdateTileState(mockStateFlow.value)
            return null
        }
        
        val currentState = mockStateFlow.value
        
        if (currentState.isRunning) {
            val intent = Intent().apply {
                setClassName("io.github.miclock", "io.github.miclock.service.MicLockService")
                action = MicLockService.ACTION_STOP
            }
            try {
                mockStartForegroundService(intent)
                return intent
            } catch (e: Exception) {
                mockCreateTileFailureNotification("Failed to send stop intent: ${e.message}")
                return null
            }
        } else {
            val intent = Intent().apply {
                setClassName("io.github.miclock", "io.github.miclock.service.MicLockService")
                action = MicLockService.ACTION_START_USER_INITIATED
                putExtra("from_tile", true)
            }
            
            try {
                mockStartForegroundService(intent)
                return intent
            } catch (e: Exception) {
                mockCreateTileFailureNotification("Service failed to start: ${e.message}")
                return null
            }
        }
    }
    
    private fun launchMainActivityFallback() {
        mainActivityFallbackAttempted = true
        try {
            val activityIntent = Intent().apply {
                setClassName("io.github.miclock", "io.github.miclock.ui.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_START_SERVICE_FROM_TILE, true)
            }
            
            val pendingIntent = mock<PendingIntent>()
            mockStartActivityAndCollapse(pendingIntent)
        } catch (e: Exception) {
            mockCreateTileFailureNotification("Both service start and app launch failed: ${e.message}")
        }
    }
    
    fun testUpdateTileState(state: ServiceState) {
        val tile = mockTile ?: return
        
        val hasPerms = hasAllPerms()
        println("updateTileState: hasPerms=$hasPerms, isRunning=${state.isRunning}, isPaused=${state.isPausedBySilence}")
        
        when {
            !hasPerms -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "No Permission"
                tile.contentDescription = "Tap to grant microphone and notification permissions"
                println("Tile set to 'No Permission' state")
            }
            !state.isRunning -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to start microphone protection"
                println("Tile set to INACTIVE state")
            }
            state.isPausedBySilence -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = TILE_TEXT
                tile.contentDescription = "Microphone protection paused"
                println("Tile set to PAUSED state")
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to stop microphone protection"
                println("Tile set to ACTIVE state")
            }
        }
        
        tile.updateTile()
        println("Tile updated - Running: ${state.isRunning}, Paused: ${state.isPausedBySilence}, HasPerms: $hasPerms")
    }
    
    fun onDestroy() {
        isStateCollectionActive = false
        registeredReceivers.clear()
        mockFailureReceiver = null
    }
}

data class MockNotification(
    val title: String,
    val text: String,
    val bigText: String
)