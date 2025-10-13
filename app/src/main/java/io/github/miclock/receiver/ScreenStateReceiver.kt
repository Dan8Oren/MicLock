package io.github.miclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.miclock.service.MicLockService
import java.util.concurrent.atomic.AtomicLong

class ScreenStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScreenStateReceiver"

        // Timestamp tracking for race condition detection
        private val lastScreenOnTimestamp = AtomicLong(0L)
        private val lastScreenOffTimestamp = AtomicLong(0L)
        private val lastProcessedEventTimestamp = AtomicLong(0L)

        // Extra key for passing timestamp to service
        const val EXTRA_EVENT_TIMESTAMP = "event_timestamp"

        // Debouncing threshold - ignore events within this window (milliseconds)
        private const val DEBOUNCE_THRESHOLD_MS = 50L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventTimestamp = System.currentTimeMillis()
        Log.i(TAG, "Received broadcast: ${intent.action} at timestamp: $eventTimestamp")

        // Debouncing: Check if this event is too close to the last processed event
        val lastProcessed = lastProcessedEventTimestamp.get()
        val timeSinceLastEvent = eventTimestamp - lastProcessed

        if (timeSinceLastEvent < DEBOUNCE_THRESHOLD_MS && lastProcessed > 0) {
            Log.d(
                TAG,
                "Debouncing: Ignoring event (${timeSinceLastEvent}ms since last event," +
                    " threshold: ${DEBOUNCE_THRESHOLD_MS}ms)",
            )
            return
        }

        val serviceIntent = Intent(context, MicLockService::class.java)
        serviceIntent.putExtra(EXTRA_EVENT_TIMESTAMP, eventTimestamp)

        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                // Check for rapid screen state changes
                val lastScreenOff = lastScreenOffTimestamp.get()
                if (lastScreenOff > 0) {
                    val timeSinceScreenOff = eventTimestamp - lastScreenOff
                    Log.d(TAG, "Screen ON: ${timeSinceScreenOff}ms since last screen-off")
                }

                // Update timestamp for race condition detection
                lastScreenOnTimestamp.set(eventTimestamp)

                Log.i(
                    TAG,
                    "Screen turned ON - sending START_HOLDING action (timestamp: $eventTimestamp)",
                )
                serviceIntent.action = MicLockService.ACTION_START_HOLDING
            }
            Intent.ACTION_SCREEN_OFF -> {
                // Check for rapid screen state changes
                val lastScreenOn = lastScreenOnTimestamp.get()
                if (lastScreenOn > 0) {
                    val timeSinceScreenOn = eventTimestamp - lastScreenOn
                    Log.d(TAG, "Screen OFF: ${timeSinceScreenOn}ms since last screen-on")
                }

                // Update timestamp for race condition detection
                lastScreenOffTimestamp.set(eventTimestamp)

                Log.i(
                    TAG,
                    "Screen turned OFF - sending STOP_HOLDING action (timestamp: $eventTimestamp)",
                )
                serviceIntent.action = MicLockService.ACTION_STOP_HOLDING
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent.action}")
                return
            }
        }

        try {
            context.startService(serviceIntent)
            lastProcessedEventTimestamp.set(eventTimestamp)
            Log.d(TAG, "Successfully sent action to running service: ${serviceIntent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send action to service: ${e.message}", e)
        }
    }

    /**
     * Gets the timestamp of the last screen-on event. Used for race condition detection and
     * debugging.
     */
    fun getLastScreenOnTimestamp(): Long = lastScreenOnTimestamp.get()

    /**
     * Gets the timestamp of the last screen-off event. Used for race condition detection and
     * debugging.
     */
    fun getLastScreenOffTimestamp(): Long = lastScreenOffTimestamp.get()

    /** Gets the timestamp of the last processed event. Used for debouncing logic. */
    fun getLastProcessedEventTimestamp(): Long = lastProcessedEventTimestamp.get()

    /**
     * Resets all timestamps to zero. For testing purposes only.
     * @VisibleForTesting
     */
    fun resetTimestamps() {
        lastScreenOnTimestamp.set(0L)
        lastScreenOffTimestamp.set(0L)
        lastProcessedEventTimestamp.set(0L)
    }
}
