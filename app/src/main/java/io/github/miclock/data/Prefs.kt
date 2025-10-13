package io.github.miclock.data

import android.content.Context
import androidx.core.content.edit
import kotlin.math.round

object Prefs {
    private const val FILE = "miclock_prefs"

    private const val KEY_USE_MEDIA_RECORDER = "use_media_recorder"
    private const val KEY_LAST_RECORDING_METHOD = "last_recording_method"
    private const val KEY_SCREEN_ON_DELAY = "screen_on_delay_ms"

    const val VALUE_AUTO = "auto"

    // Screen-on delay constants
    const val DEFAULT_SCREEN_ON_DELAY_MS = 1300L
    const val MIN_SCREEN_ON_DELAY_MS = 0L
    const val MAX_SCREEN_ON_DELAY_MS = 5000L

    // Special behavior values
    const val NEVER_REACTIVATE_VALUE = -1L // Never re-enable after screen-off
    const val ALWAYS_KEEP_ON_VALUE = -2L // Always keep mic on (ignore screen state)

    fun getUseMediaRecorder(ctx: Context): Boolean =
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_USE_MEDIA_RECORDER, false)

    fun setUseMediaRecorder(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_USE_MEDIA_RECORDER, value)
        }
    }

    fun getLastRecordingMethod(ctx: Context): String? =
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_RECORDING_METHOD, null)

    fun setLastRecordingMethod(ctx: Context, method: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_RECORDING_METHOD, method)
        }
    }

    /**
     * Gets the screen-on delay in milliseconds.
     * @return delay in milliseconds, defaults to 1300ms
     */
    fun getScreenOnDelayMs(ctx: Context): Long =
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getLong(KEY_SCREEN_ON_DELAY, DEFAULT_SCREEN_ON_DELAY_MS)

    /**
     * Sets the screen-on delay in milliseconds with validation.
     * @param delayMs delay in milliseconds, must be between 0-5000ms, -1 for never re-enable, or -2
     * for always on
     * @throws IllegalArgumentException if delay is outside valid range
     */
    fun setScreenOnDelayMs(ctx: Context, delayMs: Long) {
        require(isValidScreenOnDelay(delayMs)) {
            "Screen-on delay must be between ${MIN_SCREEN_ON_DELAY_MS}ms and ${MAX_SCREEN_ON_DELAY_MS}ms, ${NEVER_REACTIVATE_VALUE} for never re-enable, or ${ALWAYS_KEEP_ON_VALUE} for always on, got ${delayMs}ms"
        }

        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putLong(KEY_SCREEN_ON_DELAY, delayMs)
        }
    }

    /**
     * Validates if the given delay value is within acceptable range.
     * @param delayMs delay in milliseconds to validate
     * @return true if valid, false otherwise
     */
    fun isValidScreenOnDelay(delayMs: Long): Boolean =
            delayMs == NEVER_REACTIVATE_VALUE ||
                    delayMs == ALWAYS_KEEP_ON_VALUE ||
                    delayMs in MIN_SCREEN_ON_DELAY_MS..MAX_SCREEN_ON_DELAY_MS

    // Slider mapping constants
    const val SLIDER_MIN = 0f
    const val SLIDER_MAX = 100f
    const val SLIDER_ALWAYS_ON = 0f // Far left (immediate/no delay)
    const val SLIDER_NEVER_REACTIVATE = 100f // Far right (maximum restriction)
    const val SLIDER_DELAY_START = 10f // Start of delay range
    const val SLIDER_DELAY_END = 90f // End of delay range

    /**
     * Converts slider position (0-100) to delay value in milliseconds with snappy transitions.
     * @param sliderValue slider position (0-100)
     * @return delay in milliseconds or special behavior value
     */
    fun sliderToDelayMs(sliderValue: Float): Long {
        return when {
            // Snap zone for "Always on" (0-5) - far left
            sliderValue <= 9f -> ALWAYS_KEEP_ON_VALUE

            // Snap zone for "Never re-enable" (95-100) - far right
            sliderValue >= 91f -> NEVER_REACTIVATE_VALUE

            // Delay range (10-90) with snapping to nearest valid position
            else -> {
                // Snap to the delay range boundaries if close
                val snappedValue =
                        when {
                            sliderValue < SLIDER_DELAY_START -> SLIDER_DELAY_START
                            sliderValue > SLIDER_DELAY_END -> SLIDER_DELAY_END
                            else -> sliderValue
                        }

                // Map to delay range (0-5000ms)
                val normalizedValue =
                        (snappedValue - SLIDER_DELAY_START) /
                                (SLIDER_DELAY_END - SLIDER_DELAY_START)
                val delayMs = (normalizedValue * MAX_SCREEN_ON_DELAY_MS).toLong()
                // Round to nearest 100ms
                (delayMs / 100L) * 100L
            }
        }
    }

    /**
     * Snaps slider value to the nearest valid position with clear phase boundaries.
     * @param sliderValue current slider position
     * @return snapped slider position
     */
    fun snapSliderValue(sliderValue: Float): Float {
        return when {
            // Snap to "Always on" zone (far left)
            sliderValue <= 5f -> SLIDER_ALWAYS_ON

            // Snap to "Never re-enable" zone (far right)
            sliderValue >= 95f -> SLIDER_NEVER_REACTIVATE

            // Snap to delay range boundaries if in transition zones
            sliderValue < SLIDER_DELAY_START -> SLIDER_DELAY_START
            sliderValue > SLIDER_DELAY_END -> SLIDER_DELAY_END

            // Within delay range - round to nearest integer
            else -> round(sliderValue)
        }
    }

    /**
     * Converts delay value to slider position (0-100).
     * @param delayMs delay in milliseconds or special behavior value
     * @return slider position (0-100), rounded to integer
     */
    fun delayMsToSlider(delayMs: Long): Float {
        return when (delayMs) {
            ALWAYS_KEEP_ON_VALUE -> SLIDER_ALWAYS_ON // Position 0 (far left)
            NEVER_REACTIVATE_VALUE -> SLIDER_NEVER_REACTIVATE // Position 100 (far right)
            else -> {
                // Map delay range (0-5000ms) to slider range (10-90)
                val normalizedDelay = delayMs.toFloat() / MAX_SCREEN_ON_DELAY_MS.toFloat()
                val sliderValue =
                        SLIDER_DELAY_START +
                                (normalizedDelay * (SLIDER_DELAY_END - SLIDER_DELAY_START))
                // Round to nearest integer to align with stepSize
                round(sliderValue)
            }
        }
    }
}
