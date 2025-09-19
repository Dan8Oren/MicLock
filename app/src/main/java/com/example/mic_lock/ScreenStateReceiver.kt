package com.example.mic_lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class ScreenStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScreenStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")
        
        val serviceIntent = Intent(context, MicLockService::class.java)
        
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "Screen turned ON - sending START_HOLDING action")
                serviceIntent.action = MicLockService.ACTION_START_HOLDING
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Screen turned OFF - sending STOP_HOLDING action")
                serviceIntent.action = MicLockService.ACTION_STOP_HOLDING
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent.action}")
                return
            }
        }
        
        try {
            context.startService(serviceIntent)
            Log.d(TAG, "Successfully sent action to running service: ${serviceIntent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send action to service: ${e.message}", e)
        }
    }
}