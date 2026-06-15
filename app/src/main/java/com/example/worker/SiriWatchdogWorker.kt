package com.example.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.service.SiriForegroundService

class SiriWatchdogWorker(val context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        Log.d("SiriWatchdogWorker", "Watchdog triggered. Checking if SiriForegroundService is running...")
        
        val isRunning = isServiceRunning(SiriForegroundService::class.java)
        val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
        val fgEnabled = prefs.getBoolean("foreground_service_enabled", true)
        
        Log.d("SiriWatchdogWorker", "SiriForegroundService: isRunning=$isRunning, fgEnabled=$fgEnabled")
        
        if (fgEnabled && !isRunning) {
            Log.d("SiriWatchdogWorker", "Service is dead but expected to run. Launching...")
            val intent = Intent(context, SiriForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("SiriWatchdogWorker", "Failed to restart Foreground Service from Watchdog", e)
            }
        }
        
        return Result.success()
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
