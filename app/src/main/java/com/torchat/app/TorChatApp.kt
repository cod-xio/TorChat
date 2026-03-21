package com.torchat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.torchat.app.debug.TorChatLogger
import com.torchat.app.crypto.KeyManager
import com.torchat.app.data.AppDatabase
import com.torchat.app.network.EmbeddedTorManager
import com.torchat.app.network.TorProxyManager
import com.torchat.app.network.TorWatchdogWorker
import com.torchat.app.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TorChatApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database:        AppDatabase        private set
    lateinit var keyManager:      KeyManager         private set
    lateinit var torProxyManager: TorProxyManager    private set
    lateinit var embeddedTor:     EmbeddedTorManager private set
    lateinit var pinManager:      PinManager         private set

    var messagingEngine: com.torchat.app.network.P2PMessagingEngine? = null
    var messageQueue:    com.torchat.app.network.MessageQueue?        = null

    override fun onCreate() {
        super.onCreate()

        // CrashHandler als Erstes installieren
        com.torchat.app.debug.CrashHandler.install(this)
        // Sprache laden
        com.torchat.app.i18n.AppStrings.init(this)

        // Vordergrund-Tracking: zählt laufende Activities
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity)  { foregroundCount++ }
            override fun onActivityStopped(a: android.app.Activity)  { foregroundCount-- }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity)  {}
            override fun onActivityPaused(a: android.app.Activity)   {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // StrictMode deaktivieren (Samsung One UI)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())

        try {
            instance        = this
            database        = AppDatabase.getInstance(this)
            keyManager      = KeyManager(this)
            embeddedTor     = EmbeddedTorManager(this)
            torProxyManager = TorProxyManager(this, embeddedTor)
            pinManager      = PinManager(this)

            applicationScope.launch(Dispatchers.IO) {
                // Diagnose im Hintergrund
                com.torchat.app.debug.StartupDiagnostics.run(this@TorChatApp)
                try { keyManager.initializeKeys() }
                catch (e: Exception) { TorChatLogger.e(TAG, "KeyManager init: ${e.message}") }
            }

            createNotificationChannels()
            // Watchdog: startet Service neu falls Android ihn killt
            try { TorWatchdogWorker.schedule(this) } catch (_: Exception) {}
            TorChatLogger.i(TAG, "TorChatApp initialisiert")
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "App-Init Fehler: ${e.message}", e)
            try {
                if (!::database.isInitialized)       database        = AppDatabase.getInstance(this)
                if (!::keyManager.isInitialized)      keyManager      = KeyManager(this)
                if (!::embeddedTor.isInitialized)     embeddedTor     = EmbeddedTorManager(this)
                if (!::torProxyManager.isInitialized) torProxyManager = TorProxyManager(this, embeddedTor)
                if (!::pinManager.isInitialized)      pinManager      = PinManager(this)
            } catch (e2: Exception) { TorChatLogger.e(TAG, "Notfall-Init: ${e2.message}", e2) }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannels(listOf(
                    NotificationChannel(CHANNEL_MESSAGES, "Nachrichten",
                        NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Eingehende verschlüsselte Nachrichten"
                        enableVibration(true)
                    },
                    NotificationChannel(CHANNEL_SERVICE, "TorChat Dienst",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Hintergrundverbindung zum Tor-Netzwerk"
                    }
                ))
            } catch (e: Exception) { TorChatLogger.w(TAG, "Notification Channels: ${e.message}") }
        }
    }

    companion object {
        private const val TAG = "TorChatApp"
        lateinit var instance: TorChatApp private set
        const val CHANNEL_MESSAGES = "torchat_messages"
        const val CHANNEL_SERVICE  = "torchat_service"
        const val TOR_PROXY_HOST   = "127.0.0.1"
        const val P2P_PORT         = 11009
        const val APP_VERSION      = "1.0.0"

        /** true wenn mindestens eine Activity sichtbar ist */
        @Volatile var foregroundCount: Int = 0
        val isInForeground: Boolean get() = foregroundCount > 0
    }
}
