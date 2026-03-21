package com.torchat.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.torchat.app.debug.TorChatLogger

/**
 * Startet TorChat-Dienste automatisch:
 * - Nach Gerätestart (BOOT_COMPLETED)
 * - Nach App-Update (MY_PACKAGE_REPLACED)
 * - Hersteller-spezifische Boot-Actions (Samsung, Huawei, HTC, OnePlus)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // Hersteller-spezifische Boot-Actions
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,                          // Standard Android
            "android.intent.action.QUICKBOOT_POWERON",            // HTC
            "com.htc.intent.action.QUICKBOOT_POWERON",            // HTC (alt)
            "android.intent.action.MY_PACKAGE_REPLACED",          // App-Update (eigenes Paket)
            Intent.ACTION_MY_PACKAGE_REPLACED,                    // App-Update
            "com.samsung.intent.action.BOOT_COMPLETED",           // Samsung
            "android.intent.action.ACTION_BOOT_COMPLETED",        // Einige Custom-ROMs
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        TorChatLogger.i(TAG, "Boot-Event: $action — starte TorChat-Dienste")

        try {
            // Kurze Verzögerung damit das System bereit ist
            // goAsync() erlaubt länger als 10s im BroadcastReceiver
            val pendingResult = goAsync()

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    TorMessagingService.start(context.applicationContext)
                    TorChatLogger.i(TAG, "✅ TorMessagingService gestartet nach $action")
                } catch (e: Exception) {
                    TorChatLogger.e(TAG, "Service-Start fehlgeschlagen: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }, if (action == Intent.ACTION_BOOT_COMPLETED) 5000L else 1000L)
            // Nach Gerät-Boot 5s warten, nach App-Update 1s

        } catch (e: Exception) {
            TorChatLogger.e(TAG, "onReceive Fehler: ${e.message}", e)
        }
    }
}
