package com.example.mic_lock

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val FILE = "miclock_prefs"
    private const val KEY_ADDR = "selected_addr"  // "auto" or device address

    const val VALUE_AUTO = "auto"

    fun getSelectedAddress(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ADDR, VALUE_AUTO) ?: VALUE_AUTO

    fun setSelectedAddress(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_ADDR, value)
        }
    }
}