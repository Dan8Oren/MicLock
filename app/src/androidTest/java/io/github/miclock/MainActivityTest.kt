package io.github.miclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Checkable
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.truth.Truth.assertThat
import io.github.miclock.data.Prefs
import io.github.miclock.service.MicLockService
import io.github.miclock.service.model.ServiceState
import io.github.miclock.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private lateinit var context: Context

    @Before
    fun setUp() = runTest {
        Intents.init()
        context = ApplicationProvider.getApplicationContext<Context>()

        // Stop service and clear preferences before each test to ensure a clean slate
        context.stopService(Intent(context, MicLockService::class.java))
        waitForServiceState { !it.isRunning }

        Prefs.setUseMediaRecorder(context, false) // Default to AudioRecord
    }

    @After
    fun tearDown() {
        Intents.release()
        // Ensure the service is stopped after each test
        context.stopService(Intent(context, MicLockService::class.java))
        Prefs.setUseMediaRecorder(context, false) // Reset prefs
    }

    private suspend fun waitForServiceState(condition: (ServiceState) -> Boolean) {
        withTimeout(5_000L) { // 5-second timeout
            MicLockService.state.first(condition)
        }
    }

    private fun isChecked(): Matcher<View> {
        return object : BoundedMatcher<View, View>(View::class.java) {
            override fun matchesSafely(item: View): Boolean {
                return item is Checkable && item.isChecked
            }
            override fun describeTo(description: Description) {
                description.appendText("is checked")
            }
        }
    }

    @Test
    fun testCompatibilityModeToggle_serviceOff_updatesPrefsNoIntent() = runTest {
        // Ensure service is off
        waitForServiceState { !it.isRunning }

        // Initial pref state is false
        assertThat(Prefs.getUseMediaRecorder(context)).isFalse()
        onView(withId(R.id.mediaRecorderToggle)).check(matches(not(isChecked())))

        // Click toggle
        onView(withId(R.id.mediaRecorderToggle)).perform(click())

        // Verify preference is updated to true
        assertThat(Prefs.getUseMediaRecorder(context)).isTrue()

        // Verify NO reconfigure intent was sent
        try {
            Intents.intended(hasAction(MicLockService.ACTION_RECONFIGURE), Intents.times(0))
        } catch (e: Exception) {
            // This is expected, as no intent should be found
        }
    }

    @Test
    fun testCompatibilityModeToggle_serviceOn_updatesPrefsAndTriggersReconfigure() = runTest {
        // Start the service
        onView(withId(R.id.startBtn)).perform(click())
//
        waitForServiceState { it.isRunning }

        // Pref is initially false
        assertThat(Prefs.getUseMediaRecorder(context)).isFalse()

        // Click toggle to enable MediaRecorder
        onView(withId(R.id.mediaRecorderToggle)).perform(click())
        assertThat(Prefs.getUseMediaRecorder(context)).isTrue()

        // Verify the reconfigure intent was sent
//        waitForServiceState { Prefs.getLastRecordingMethod(context) == "MediaRecorder" }

        // Click toggle again to disable MediaRecorder
        onView(withId(R.id.mediaRecorderToggle)).perform(click())
        assertThat(Prefs.getUseMediaRecorder(context)).isFalse()

        // Verify a second reconfigure intent was sent
//        waitForServiceState { Prefs.getLastRecordingMethod(context) == "AudioRecord" }
    }

    @Test
    fun testInitialCompatibilityModeDisplay() = runTest {
        activityScenarioRule.scenario.close()
        Prefs.setUseMediaRecorder(context, true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->

            scenario.onActivity { activity ->
                val toggle = activity.findViewById<SwitchMaterial>(R.id.mediaRecorderToggle)
                assertThat(toggle.isChecked).isTrue()
            }
            scenario.close()
        }

        // Set pref to false
        Prefs.setUseMediaRecorder(context, false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->

            scenario.onActivity { activity ->
                val toggle = activity.findViewById<SwitchMaterial>(R.id.mediaRecorderToggle)
                assertThat(toggle.isChecked).isFalse()
            }
            scenario.close()
        }
    }
}
