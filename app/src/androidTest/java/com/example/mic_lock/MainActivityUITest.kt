package io.github.miclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Checkable
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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

@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var context: Context

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest{
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testUIStatusUpdates() = runTest {

            // Initial state: OFF
            onView(withId(R.id.statusText)).check(matches(withText("OFF")))
            onView(withId(R.id.startBtn)).check(matches(isEnabled()))
            onView(withId(R.id.stopBtn)).check(matches(not(isEnabled())))

            // Click Start
            onView(withId(R.id.startBtn)).perform(click())
        waitForServiceState { it.isRunning }

            // Running state: ON
            onView(withId(R.id.statusText)).check(matches(withText("ON")))
            onView(withId(R.id.startBtn)).check(matches(not(isEnabled())))
            onView(withId(R.id.stopBtn)).check(matches(isEnabled()))

            // Click Stop
            onView(withId(R.id.stopBtn)).perform(click())
            waitForServiceState { !it.isRunning }

            // Final state: OFF
            onView(withId(R.id.statusText)).check(matches(withText("OFF")))
            onView(withId(R.id.startBtn)).check(matches(isEnabled()))
            onView(withId(R.id.stopBtn)).check(matches(not(isEnabled())))
    }

}
