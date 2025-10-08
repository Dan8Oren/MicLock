package io.github.miclock.data

import android.content.Context
import androidx.core.content.edit

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
     * @param delayMs delay in milliseconds, must be between 0-5000ms
     * @throws IllegalArgumentException if delay is outside valid range
     */
    fun setScreenOnDelayMs(ctx: Context, delayMs: Long) {
        require(delayMs in MIN_SCREEN_ON_DELAY_MS..MAX_SCREEN_ON_DELAY_MS) {
            "Screen-on delay must be between ${MIN_SCREEN_ON_DELAY_MS}ms and ${MAX_SCREEN_ON_DELAY_MS}ms, got ${delayMs}ms"
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
        delayMs in MIN_SCREEN_ON_DELAY_MS..MAX_SCREEN_ON_DELAY_MS
}
