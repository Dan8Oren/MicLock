package io.github.miclock.service

import io.github.miclock.data.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLockServiceSpecialDelayScreenOnTest {

    @Test
    fun alwaysOn_coldBoot_starts() {
        assertTrue(
            MicLockService.shouldStartMicOnSpecialScreenOnDelay(
                Prefs.ALWAYS_KEEP_ON_VALUE,
                isPausedByScreenOff = false,
                micActivelyHeld = false,
                isPausedBySilence = false,
            ),
        )
    }

    @Test
    fun never_coldBoot_starts() {
        assertTrue(
            MicLockService.shouldStartMicOnSpecialScreenOnDelay(
                Prefs.NEVER_REACTIVATE_VALUE,
                isPausedByScreenOff = false,
                micActivelyHeld = false,
                isPausedBySilence = false,
            ),
        )
    }

    @Test
    fun never_afterScreenOff_skips() {
        assertFalse(
            MicLockService.shouldStartMicOnSpecialScreenOnDelay(
                Prefs.NEVER_REACTIVATE_VALUE,
                isPausedByScreenOff = true,
                micActivelyHeld = false,
                isPausedBySilence = false,
            ),
        )
    }

    @Test
    fun alwaysOn_micAlreadyHeld_skips() {
        assertFalse(
            MicLockService.shouldStartMicOnSpecialScreenOnDelay(
                Prefs.ALWAYS_KEEP_ON_VALUE,
                isPausedByScreenOff = false,
                micActivelyHeld = true,
                isPausedBySilence = false,
            ),
        )
    }

    @Test
    fun alwaysOn_pausedBySilence_skips() {
        assertFalse(
            MicLockService.shouldStartMicOnSpecialScreenOnDelay(
                Prefs.ALWAYS_KEEP_ON_VALUE,
                isPausedByScreenOff = false,
                micActivelyHeld = false,
                isPausedBySilence = true,
            ),
        )
    }
}
