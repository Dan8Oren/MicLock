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
    fun `setScreenOnDelayMs negative value should throw IllegalArgumentException`() {
        // When/Then
        try {
            Prefs.setScreenOnDelayMs(context, -1L)
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Screen-on delay must be between 0ms and 5000ms")
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
    }

    @Test
    fun `isValidScreenOnDelay should return false for invalid values`() {
        assertThat(Prefs.isValidScreenOnDelay(-1L)).isFalse()
        assertThat(Prefs.isValidScreenOnDelay(5001L)).isFalse()
    }
}
