package com.example

import android.app.Application
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.SiriDatabase
import com.example.worker.SiriWatchdogWorker
import java.util.concurrent.TimeUnit

class SiriApplication : Application() {
    
    val database: SiriDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            SiriDatabase::class.java,
            "siri_assistant_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        scheduleWatchdog()
    }

    private fun scheduleWatchdog() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<SiriWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "SiriWatchdogWork",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            android.util.Log.e("SiriApplication", "Failed to schedule watchdog", e)
        }
    }

    companion object {
        lateinit var instance: SiriApplication
            private set
    }
}
