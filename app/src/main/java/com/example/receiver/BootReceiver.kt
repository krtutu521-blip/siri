package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.service.SiriForegroundService
import com.example.service.SiriFloatingBubbleService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received boot/reboot event broadcast: $action")
        
        val allowedActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        
        if (action in allowedActions) {
            val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
            val fgEnabled = prefs.getBoolean("foreground_service_enabled", true) // Default true to ensure auto-start
            val bubbleEnabled = prefs.getBoolean("bubble_service_enabled", true) // Default true
            
            Log.d("BootReceiver", "Restoring services: fgEnabled=$fgEnabled, bubbleEnabled=$bubbleEnabled")
            
            if (fgEnabled) {
                val serviceIntent = Intent(context, SiriForegroundService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error starting foreground service on boot", e)
                }
            }
            
            if (bubbleEnabled && Settings.canDrawOverlays(context)) {
                val bubbleIntent = Intent(context, SiriFloatingBubbleService::class.java)
                try {
                    context.startService(bubbleIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error starting floating bubble service on boot", e)
                }
            }
        }
    }
}
