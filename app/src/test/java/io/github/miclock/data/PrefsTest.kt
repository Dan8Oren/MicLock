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
}
