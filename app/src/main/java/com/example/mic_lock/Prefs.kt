package com.example.mic_lock

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val FILE = "miclock_prefs"
    private const val KEY_ADDR = "selected_addr"  // "auto" or device address
    private const val KEY_USE_MEDIA_RECORDER = "use_media_recorder"
    private const val KEY_LAST_RECORDING_METHOD = "last_recording_method"

    const val VALUE_AUTO = "auto"

    fun getSelectedAddress(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ADDR, VALUE_AUTO) ?: VALUE_AUTO

    fun setSelectedAddress(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_ADDR, value)
        }
    }

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
}