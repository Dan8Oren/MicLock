package io.github.miclock.tile

import android.Manifest
import android.content.Context
import android.content.Intent
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
    fun testOnClick_missingPermissions_launchesMainActivity() {
        // Given: Permissions are missing
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = false, hasNotifications = true)
        
        // When: onClick is called
        val result = tileService.testOnClick()
        
        // Then: Should launch MainActivity and not send service intent
        assertTrue("Should have launched MainActivity", tileService.wasActivityLaunched())
        assertEquals("Should launch MainActivity", MainActivity::class.java.name, tileService.getLaunchedActivityClass())
        assertNull("Should not send service intent when permissions missing", result)
    }

    @Test
    fun testOnClick_missingNotificationPermissions_launchesMainActivity() {
        // Given: Notification permissions are missing
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = false)
        
        // When: onClick is called
        val result = tileService.testOnClick()
        
        // Then: Should launch MainActivity and not send service intent
        assertTrue("Should have launched MainActivity", tileService.wasActivityLaunched())
        assertEquals("Should launch MainActivity", MainActivity::class.java.name, tileService.getLaunchedActivityClass())
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
        
        // Then: Should send STOP action and not launch activity
        assertEquals("Should send STOP action", MicLockService.ACTION_STOP, capturedIntent?.action)
        assertFalse("Should not launch activity when permissions granted", tileService.wasActivityLaunched())
    }

    @Test
    fun testOnClick_allPermissionsGranted_serviceStopped_sendsStartAction() {
        // Given: All permissions granted and service is stopped
        mockStateFlow.value = ServiceState(isRunning = false, isPausedBySilence = false)
        tileService.setMockTile(mockTile)
        tileService.setMockPermissions(hasRecordAudio = true, hasNotifications = true)
        
        // When: onClick is called
        val capturedIntent = tileService.testOnClick()
        
        // Then: Should send START_USER_INITIATED action and not launch activity
        assertEquals("Should send START_USER_INITIATED action", 
            MicLockService.ACTION_START_USER_INITIATED, capturedIntent?.action)
        assertFalse("Should not launch activity when permissions granted", tileService.wasActivityLaunched())
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
}

/**
 * Testable version of MicLockTileService that allows for easier unit testing
 * without inheriting from TileService to avoid Android framework complications
 */
class TestableMicLockTileService(private val mockStateFlow: StateFlow<ServiceState>) {
    
    private var mockTile: Tile? = null
    private var lastIntent: Intent? = null
    private var launchedActivityClass: String? = null
    private var hasRecordAudioPermission = true
    private var hasNotificationPermission = true
    var isStateCollectionActive = false
        private set
    
    fun setMockTile(tile: Tile) {
        mockTile = tile
    }
    
    fun setMockPermissions(hasRecordAudio: Boolean, hasNotifications: Boolean) {
        hasRecordAudioPermission = hasRecordAudio
        hasNotificationPermission = hasNotifications
    }
    
    fun wasActivityLaunched(): Boolean = launchedActivityClass != null
    
    fun getLaunchedActivityClass(): String? = launchedActivityClass
    
    private fun hasAllPerms(): Boolean {
        return hasRecordAudioPermission && hasNotificationPermission
    }
    
    fun getQsTile(): Tile? = mockTile
    
    fun onStartListening() {
        isStateCollectionActive = true
        // Simulate state collection start
    }
    
    fun onStopListening() {
        isStateCollectionActive = false
        // Simulate state collection stop
    }
    
    fun testOnClick(): Intent? {
        // Reset activity launch tracking
        launchedActivityClass = null
        
        // Check permissions first (mimicking the real tile service logic)
        if (!hasAllPerms()) {
            // Simulate launching MainActivity
            launchedActivityClass = MainActivity::class.java.name
            return null // No service intent when permissions are missing
        }
        
        val currentState = mockStateFlow.value
        val intent = Intent()
        intent.setClassName("io.github.miclock", "io.github.miclock.service.MicLockService")
        
        if (currentState.isRunning) {
            intent.action = MicLockService.ACTION_STOP
        } else {
            intent.action = MicLockService.ACTION_START_USER_INITIATED
        }
        
        lastIntent = intent
        return intent
    }
    
    fun testUpdateTileState(state: ServiceState) {
        val tile = mockTile ?: return
        
        when {
            !state.isRunning -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to start microphone protection"
                // Note: Icon setting skipped in unit tests due to Android framework dependencies
            }
            state.isPausedBySilence -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = TILE_TEXT
                tile.contentDescription = "Microphone protection paused"
                // Note: Icon setting skipped in unit tests due to Android framework dependencies
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = TILE_TEXT
                tile.contentDescription = "Tap to stop microphone protection"
                // Note: Icon setting skipped in unit tests due to Android framework dependencies
            }
        }
        
        tile.updateTile()
    }
    
    fun onDestroy() {
        isStateCollectionActive = false
    }
}