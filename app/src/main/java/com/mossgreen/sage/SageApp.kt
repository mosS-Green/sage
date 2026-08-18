package com.mossgreen.sage

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mossgreen.sage.manager.KeyManager
import com.mossgreen.sage.worker.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class SageApp : Application() {
    /**
     * The one KeyManager for the process. Rate-limit benching, invalid-key marks and the
     * round-robin cursor are in-memory only, so a second instance starts blind: it re-tries keys
     * another instance already knows are benched, and never learns what that one learned. It also
     * breaks [KeyManager.addKey]'s un-benching — re-adding a key in the UI cleared the invalid
     * mark on the UI's instance while the accessibility service kept benching it for the full TTL.
     *
     * Lazy so the Keystore round trip in the constructor stays off Application.onCreate.
     */
    val keyManager: KeyManager by lazy { KeyManager(this) }

    companion object {
        const val CHANNEL_TRANSCRIPTION = "sage_transcription"
    }

    override fun onCreate() {
        super.onCreate()
        // Pre-warm SharedPreferences — triggers async disk load so they're
        // in memory by the time the ViewModel creates managers
        getSharedPreferences("settings", Context.MODE_PRIVATE)
        getSharedPreferences("commands", Context.MODE_PRIVATE)
        getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)
        getSharedPreferences("stats", Context.MODE_PRIVATE)

        createNotificationChannels()
        scheduleUpdateCheck()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_TRANSCRIPTION,
                "Voice Note Transcriptions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Transcriptions of WhatsApp voice notes"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            24, TimeUnit.HOURS,
            6, TimeUnit.HOURS  // Flex window: system picks best time in last 6h
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }
}
