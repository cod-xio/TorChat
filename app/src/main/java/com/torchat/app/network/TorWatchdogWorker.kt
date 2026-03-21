package com.torchat.app.network

import android.content.Context
import androidx.work.*
import com.torchat.app.debug.TorChatLogger
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Watchdog: prüft alle 15 Min ob TorMessagingService läuft.
 * Startet ihn neu falls Android ihn gekillt hat.
 * Läuft auch im Doze-Modus (mit Constraints).
 */
class TorWatchdogWorker(
    private val context: Context,
    workerParams:        WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG       = "TorWatchdog"
        private const val WORK_NAME = "tor_watchdog"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TorWatchdogWorker>(
                15, TimeUnit.MINUTES,
                5,  TimeUnit.MINUTES   // Flex-Intervall
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            TorChatLogger.d(TAG, "Watchdog eingeplant (alle 15 Min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        TorChatLogger.d(TAG, "Watchdog läuft...")
        return try {
            // Service starten (macht nichts wenn er bereits läuft)
            TorMessagingService.start(context)
            TorChatLogger.d(TAG, "✅ Service-Check OK")
            Result.success()
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "Watchdog Fehler: ${e.message}", e)
            Result.retry()
        }
    }
}
