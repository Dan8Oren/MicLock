package com.example.mic_lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, MicLockService::class.java)
        
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                serviceIntent.action = MicLockService.ACTION_START_HOLDING
            }
            Intent.ACTION_SCREEN_OFF -> {
                serviceIntent.action = MicLockService.ACTION_STOP_HOLDING
            }
            else -> return
        }
        
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
