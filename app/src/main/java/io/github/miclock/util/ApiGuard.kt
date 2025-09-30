package io.github.miclock.util

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.IntRange

/**
 * A centralized utility object for handling Android SDK version checks.
 * This provides a safe, reusable, and maintainable way to execute
 * API-level-specific code, preventing runtime crashes on older devices.
 */
object ApiGuard {

    /**
     * Checks if the current device is running on Android O (API 26) or higher.
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    fun isApi26_O_OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    /**
     * Checks if the current device is running on Android P (API 28) or higher.
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    fun isApi28_P_OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    /**
     * Checks if the current device is running on Android S (API 31) or higher.
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun isApi31_S_OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Checks if the current device is running on Android Tiramisu (API 33) or higher.
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun isApi33_Tiramisu_OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Checks if the current device is running on Android Upside Down Cake (API 34) or higher.
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun isApi34_UpsideDownCake_OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    /**
     * Executes a block of code only if the device is on API 34 (Upside Down Cake) or higher.
     * Provides an optional `onUnsupported` block for graceful degradation.
     *
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */

    /**
     * Executes a block of code only if the device is on a specific API level or higher.
     * Provides an optional `onUnsupported` block for graceful degradation.
     *
     * @param minApi The minimum API level required for the block to execute.
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */
    inline fun onApiLevel(@IntRange(from = 1) minApi: Int, block: () -> Unit, noinline onUnsupported: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= minApi) {
            block()
        } else {
            onUnsupported?.invoke()
        }
    }

    /**
     * Executes a block of code only if the device is on API 34 (Upside Down Cake) or higher.
     * Provides an optional `onUnsupported` block for graceful degradation.
     *
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */
    inline fun onApi34_UpsideDownCake(block: () -> Unit, noinline onUnsupported: (() -> Unit)? = null) {
        onApiLevel(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, block, onUnsupported)
    }

    /**
     * Executes a block of code only if the device is on API 33 (Tiramisu) or higher.
     * Provides an optional `onUnsupported` block for graceful degradation.
     *
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */
    inline fun onApi33_Tiramisu(block: () -> Unit, noinline onUnsupported: (() -> Unit)? = null) {
        onApiLevel(Build.VERSION_CODES.TIRAMISU, block, onUnsupported)
    }

    /**
     * Executes a block of code only if the device is on API 31 (S) or higher.
     * Useful for handling API-specific exceptions like ForegroundServiceStartNotAllowedException.
     *
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */
    inline fun onApi31_S(block: () -> Unit, noinline onUnsupported: (() -> Unit)? = null) {
        onApiLevel(Build.VERSION_CODES.S, block, onUnsupported)
    }

    /**
     * Executes a block of code only if the device is on API 28 (P) or higher.
     * Provides an optional `onUnsupported` block for graceful degradation.
     *
     * @param block The lambda to execute for supported APIs.
     * @param onUnsupported The lambda to execute on older devices.
     */
    inline fun onApi28_P(block: () -> Unit, noinline onUnsupported: (() -> Unit)? = null) {
        onApiLevel(Build.VERSION_CODES.P, block, onUnsupported)
    }
}