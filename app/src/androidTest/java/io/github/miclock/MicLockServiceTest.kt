package io.github.miclock

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import io.github.miclock.util.WakeLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MicLockServiceTest {

    private lateinit var context: Context
    private lateinit var serviceIntent: Intent

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.WAKE_LOCK
    )

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("miclock_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        serviceIntent = Intent(context, MicLockService::class.java)

        stopServiceAndWait(serviceIntent)
    }

    @After
    fun tearDown() {
        stopServiceAndWait(serviceIntent)
    }

    private fun stopServiceAndWait(intent: Intent = serviceIntent) {
        // Force service cleanup on the Main thread
        runBlocking(Dispatchers.Main) {
            context.stopService(intent)
            waitForServiceState { !it.isRunning }
        }
    }

    private fun startServiceAndWait(intent: Intent = Intent(context, MicLockService::class.java)) {
        // Force service startup on the Main thread
        runBlocking(Dispatchers.Main) {
            context.startService(intent)
            waitForServiceState { it.isRunning }
        }
    }

    private suspend fun waitForServiceState(condition: (ServiceState) -> Boolean) {
        withTimeout(5_000L) { // 10-second timeout due to being sticky
            MicLockService.state.first(condition)
        }
    }

    @Test
    fun testServiceInitializationWithoutCrash() = runTest {
        try {
            context.startService(serviceIntent)
            kotlinx.coroutines.delay(500) // Allow time for service to initialize
        } catch (e: Exception) {
            throw AssertionError("Service initialization should not throw exceptions: ${e.message}", e)
        }
    }

    @Test
    fun testWakeLockPermissionHandling() = runTest {
        try {
            val wakeLockManager = WakeLockManager(context, "TestWakeLock")
            wakeLockManager.acquire()
            assert(wakeLockManager.isHeld) { "WakeLock should be acquired successfully" }
            wakeLockManager.release()
            assert(!wakeLockManager.isHeld) { "WakeLock should be released successfully" }
        } catch (e: SecurityException) {
            throw AssertionError("WAKE_LOCK permission should be granted: ${e.message}", e)
        } catch (e: Exception) {
            throw AssertionError("WakeLock operations should not fail: ${e.message}", e)
        }
    }

    @Test
    fun testReconfigurationRaceCondition() = runTest {
        try {
            startServiceAndWait(serviceIntent.apply { action = MicLockService.ACTION_START_USER_INITIATED })

            context.startService(serviceIntent.apply { action = MicLockService.ACTION_RECONFIGURE })
            kotlinx.coroutines.delay(300)

            context.startService(serviceIntent.apply { action = MicLockService.ACTION_RECONFIGURE })
            kotlinx.coroutines.delay(1000)

            val serviceState = MicLockService.state.value
            assert(serviceState.isRunning)
        } catch (e: Exception) {
            throw AssertionError("Reconfiguration should not cause crashes or exceptions: ${e.message}", e)
        }
    }

    @Test
    fun testServiceStartStopCycle() = runTest {
        var serviceState = MicLockService.state.value
        assert(!serviceState.isRunning) { "Service should not be running initially" }

        startServiceAndWait(serviceIntent.apply { action = MicLockService.ACTION_START_USER_INITIATED })
        serviceState = MicLockService.state.value
        assert(serviceState.isRunning) { "Service should be running after start" }

        runBlocking(Dispatchers.Main) {
            context.startService(serviceIntent.apply { action = MicLockService.ACTION_STOP })
            waitForServiceState { !it.isRunning }
        }
        serviceState = MicLockService.state.value
        assert(!serviceState.isRunning) { "Service should be stopped after stop action" }
    }

    @Test
    fun testScreenOnOffCycle() = runTest {
        startServiceAndWait(serviceIntent.apply { action = MicLockService.ACTION_START_USER_INITIATED })

        context.startService(Intent(context, MicLockService::class.java).apply { action = MicLockService.ACTION_STOP_HOLDING })
        kotlinx.coroutines.delay(500)

        context.startService(Intent(context, MicLockService::class.java).apply { action = MicLockService.ACTION_START_HOLDING })
        kotlinx.coroutines.delay(500)

        val serviceState = MicLockService.state.value
        assert(serviceState.isRunning) { "Service should remain running through screen state changes" }
    }

    @Test
    fun testBootUpInitialization() = runTest {
        startServiceAndWait()

        val serviceState = MicLockService.state.value
        assert(serviceState.isRunning)

        context.startService(Intent(context, MicLockService::class.java).apply { action = MicLockService.ACTION_START_HOLDING })
        kotlinx.coroutines.delay(1000)
    }
}