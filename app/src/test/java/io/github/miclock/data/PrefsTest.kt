package io.github.miclock.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear preferences before each test
        context.getSharedPreferences("miclock_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `setUseMediaRecorder true should getUseMediaRecorder return true`() {
        // When
        Prefs.setUseMediaRecorder(context, true)

        // Then
        assertThat(Prefs.getUseMediaRecorder(context)).isTrue()
    }

    @Test
    fun `setUseMediaRecorder false should getUseMediaRecorder return false`() {
        // When
        Prefs.setUseMediaRecorder(context, false)

        // Then
        assertThat(Prefs.getUseMediaRecorder(context)).isFalse()
    }

    @Test
    fun `getUseMediaRecorder default value should be false`() {
        // Given: Fresh preferences (cleared in setUp)

        // Then
        assertThat(Prefs.getUseMediaRecorder(context)).isFalse()
    }

    @Test
    fun `setLastRecordingMethod AudioRecord should getLastRecordingMethod return AudioRecord`() {
        // When
        Prefs.setLastRecordingMethod(context, "AudioRecord")

        // Then
        assertThat(Prefs.getLastRecordingMethod(context)).isEqualTo("AudioRecord")
    }

    @Test
    fun `setLastRecordingMethod MediaRecorder should getLastRecordingMethod return MediaRecorder`() {
        // When
        Prefs.setLastRecordingMethod(context, "MediaRecorder")

        // Then
        assertThat(Prefs.getLastRecordingMethod(context)).isEqualTo("MediaRecorder")
    }

    @Test
    fun `getLastRecordingMethod default value should be null`() {
        // Given: Fresh preferences (cleared in setUp)

        // Then
        assertThat(Prefs.getLastRecordingMethod(context)).isNull()
    }

    @Test
    fun `getScreenOnDelayMs default value should be 1300ms`() {
        // Given: Fresh preferences (cleared in setUp)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(1300L)
    }

    @Test
    fun `setScreenOnDelayMs valid value should getScreenOnDelayMs return same value`() {
        // When
        Prefs.setScreenOnDelayMs(context, 2000L)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(2000L)
    }

    @Test
    fun `setScreenOnDelayMs minimum value 0 should work`() {
        // When
        Prefs.setScreenOnDelayMs(context, 0L)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(0L)
    }

    @Test
    fun `setScreenOnDelayMs maximum value 5000 should work`() {
        // When
        Prefs.setScreenOnDelayMs(context, 5000L)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(5000L)
    }

    @Test
    fun `setScreenOnDelayMs never reactivate value should work`() {
        // When
        Prefs.setScreenOnDelayMs(context, Prefs.NEVER_REACTIVATE_VALUE)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(Prefs.NEVER_REACTIVATE_VALUE)
    }

    @Test
    fun `setScreenOnDelayMs always on value should work`() {
        // When
        Prefs.setScreenOnDelayMs(context, Prefs.ALWAYS_KEEP_ON_VALUE)

        // Then
        assertThat(Prefs.getScreenOnDelayMs(context)).isEqualTo(Prefs.ALWAYS_KEEP_ON_VALUE)
    }

    @Test
    fun `setScreenOnDelayMs invalid negative value should throw IllegalArgumentException`() {
        // When/Then
        try {
            Prefs.setScreenOnDelayMs(context, -3L) // Invalid negative value (not -1 or -2)
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Screen-on delay must be between")
        }
    }

    @Test
    fun `setScreenOnDelayMs value above maximum should throw IllegalArgumentException`() {
        // When/Then
        try {
            Prefs.setScreenOnDelayMs(context, 5001L)
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Screen-on delay must be between 0ms and 5000ms")
        }
    }

    @Test
    fun `isValidScreenOnDelay should return true for valid values`() {
        assertThat(Prefs.isValidScreenOnDelay(0L)).isTrue()
        assertThat(Prefs.isValidScreenOnDelay(1300L)).isTrue()
        assertThat(Prefs.isValidScreenOnDelay(5000L)).isTrue()
        assertThat(Prefs.isValidScreenOnDelay(Prefs.NEVER_REACTIVATE_VALUE)).isTrue()
        assertThat(Prefs.isValidScreenOnDelay(Prefs.ALWAYS_KEEP_ON_VALUE)).isTrue()
    }

    @Test
    fun `isValidScreenOnDelay should return false for invalid values`() {
        assertThat(Prefs.isValidScreenOnDelay(-3L)).isFalse() // Invalid negative value
        assertThat(Prefs.isValidScreenOnDelay(5001L)).isFalse()
        assertThat(Prefs.isValidScreenOnDelay(-100L)).isFalse() // Another invalid negative
    }

    @Test
    fun `sliderToDelayMs should map special values correctly`() {
        // Always on (far left)
        assertThat(Prefs.sliderToDelayMs(0f)).isEqualTo(Prefs.ALWAYS_KEEP_ON_VALUE)
        assertThat(Prefs.sliderToDelayMs(5f)).isEqualTo(Prefs.ALWAYS_KEEP_ON_VALUE)
        
        // Never reactivate (far right)
        assertThat(Prefs.sliderToDelayMs(95f)).isEqualTo(Prefs.NEVER_REACTIVATE_VALUE)
        assertThat(Prefs.sliderToDelayMs(100f)).isEqualTo(Prefs.NEVER_REACTIVATE_VALUE)
        
        // Delay range
        assertThat(Prefs.sliderToDelayMs(10f)).isEqualTo(0L) // Start of delay range
        assertThat(Prefs.sliderToDelayMs(90f)).isEqualTo(5000L) // End of delay range
        assertThat(Prefs.sliderToDelayMs(50f)).isEqualTo(2500L) // Middle of delay range
    }

    @Test
    fun `delayMsToSlider should map special values correctly`() {
        // Special values
        assertThat(Prefs.delayMsToSlider(Prefs.ALWAYS_KEEP_ON_VALUE)).isEqualTo(0f)
        assertThat(Prefs.delayMsToSlider(Prefs.NEVER_REACTIVATE_VALUE)).isEqualTo(100f)
        
        // Delay values
        assertThat(Prefs.delayMsToSlider(0L)).isEqualTo(10f) // Start of delay range
        assertThat(Prefs.delayMsToSlider(5000L)).isEqualTo(90f) // End of delay range
        assertThat(Prefs.delayMsToSlider(2500L)).isEqualTo(50f) // Middle of delay range
    }

    @Test
    fun `snapSliderValue should snap to correct zones`() {
        // Always on zone
        assertThat(Prefs.snapSliderValue(0f)).isEqualTo(0f)
        assertThat(Prefs.snapSliderValue(3f)).isEqualTo(0f)
        assertThat(Prefs.snapSliderValue(5f)).isEqualTo(0f)
        
        // Never reactivate zone
        assertThat(Prefs.snapSliderValue(95f)).isEqualTo(100f)
        assertThat(Prefs.snapSliderValue(97f)).isEqualTo(100f)
        assertThat(Prefs.snapSliderValue(100f)).isEqualTo(100f)
        
        // Delay range
        assertThat(Prefs.snapSliderValue(50f)).isEqualTo(50f)
        assertThat(Prefs.snapSliderValue(25.7f)).isEqualTo(26f) // Rounds to nearest integer
    }
}
