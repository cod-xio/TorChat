package com.torchat.app.network

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.torchat.app.R
import com.torchat.app.TorChatApp
import com.torchat.app.data.ChatRepository
import com.torchat.app.debug.TorChatLogger
import com.torchat.app.model.MessageStatus
import com.torchat.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TorMessagingService : Service() {

    companion object {
        private const val TAG             = "TorService"
        private const val NOTIFICATION_ID = 1001
        // Vordergrund (Screen an): kurze Intervalle
        private const val HEARTBEAT_FG    = 45_000L
        private const val QUEUE_RETRY_FG  = 30_000L
        // Hintergrund (Screen aus): lange Intervalle → schont Akku
        private const val HEARTBEAT_BG    = 3 * 60_000L   // 3 Min
        private const val QUEUE_RETRY_BG  = 2 * 60_000L   // 2 Min
        // Kompatibilität
        private const val HEARTBEAT_MS    = HEARTBEAT_FG
        private const val QUEUE_RETRY_MS  = QUEUE_RETRY_FG
        private const val MAX_RETRIES     = 5

        fun start(context: Context) =
            context.startForegroundService(Intent(context, TorMessagingService::class.java))
        fun stop(context: Context) =
            context.stopService(Intent(context, TorMessagingService::class.java))
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app:             TorChatApp
    private lateinit var repository:      ChatRepository
    private lateinit var p2pEngine:       P2PMessagingEngine
    lateinit var messageQueue:  MessageQueue
    private lateinit var presenceTracker: ContactPresenceTracker

    private var serviceStarted   = false
    private var reconnectJob:    Job? = null
    private var heartbeatJob:    Job? = null
    private var queueRetryJob:   Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver:  BroadcastReceiver? = null

    // Adaptiver Modus: true = Screen aus / Hintergrund
    private var isBackground = false
        set(value) {
            if (field != value) {
                field = value
                TorChatLogger.d(TAG, if (value) "🌙 Hintergrund-Modus" else "☀️ Vordergrund-Modus")
                // Presence-Check-Intervall anpassen
                presenceTracker.checkInterval = if (value) 5 * 60_000L else 60_000L
            }
        }

    override fun onCreate() {
        super.onCreate()
        app           = application as TorChatApp
        repository    = ChatRepository(app.database)
        messageQueue  = MessageQueue(this)
        p2pEngine     = P2PMessagingEngine(
            context      = this,
            keyManager   = app.keyManager,
            torProxy     = app.torProxyManager,
            repository   = repository,
            scope        = serviceScope,
            messageQueue = messageQueue
        )
        presenceTracker = ContactPresenceTracker(
            torProxy   = app.torProxyManager,
            repository = repository,
            scope      = serviceScope
        )
        // Wenn Kontakt online → Queue abarbeiten
        presenceTracker.onContactCameOnline = { onion ->
            serviceScope.launch { flushQueueForContact(onion) }
        }
        app.messagingEngine = p2pEngine
        app.messageQueue    = messageQueue
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceStarted) {
            serviceStarted = true
            try {
                // Scope neu erstellen falls vorher gecancelt
                if (!serviceScope.isActive) {
                    serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    TorChatLogger.d(TAG, "serviceScope neu erstellt")
                }
                promoteToForeground()
                registerNetworkCallback()
                registerScreenReceiver()
                serviceScope.launch { connectWithRetry() }
                startHeartbeat()
                startQueueRetry()
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "onStartCommand Fehler: ${e.message}", e)
                serviceStarted = false
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) { TorChatLogger.w(TAG, "Timeout"); stopSelf() }

    override fun onDestroy() {
        TorChatLogger.d(TAG, "onDestroy")
        unregisterNetworkCallback()
        try {
            screenReceiver?.let { recv -> unregisterReceiver(recv) }
            screenReceiver = null
        } catch (_: Exception) {}
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        queueRetryJob?.cancel()
        onionAddressJob?.cancel()
        incomingJob?.cancel()
        peerOnlineJob?.cancel()
        try { presenceTracker.stopTracking() } catch (_: Exception) {}
        try { p2pEngine.stopListening() } catch (_: Exception) {}
        // Tor NICHT stoppen — damit beim Neustart die Adresse erhalten bleibt
        // und TorService nicht komplett neu hochfahren muss
        app.messagingEngine = null
        serviceStarted = false  // ← Reset damit onStartCommand beim Neustart greift
        try { serviceScope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground ────────────────────────────────

    private fun promoteToForeground() {
        val n = buildNotification("🧅 Tor starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    // ── Verbindung ────────────────────────────────

    private suspend fun connectWithRetry(maxRetries: Int = MAX_RETRIES) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                val label = if (maxRetries == Int.MAX_VALUE) "#$attempt" else "$attempt/$maxRetries"
                updateNotification("🧅 Verbinde... ($label)")
                TorChatLogger.i(TAG, "Verbindungsversuch $label")

                if (app.torProxyManager.checkTorConnectivity()) {
                    onTorConnected()
                    return@launch
                }

                // Aufgeben nur wenn explizit begrenzt (z.B. beim manuellen Neustart)
                if (maxRetries != Int.MAX_VALUE && attempt >= maxRetries) {
                    TorChatLogger.e(TAG, "Tor-Start nach $maxRetries Versuchen fehlgeschlagen")
                    updateNotification("❌ Tor could not be started")
                    // isReconnecting zurücksetzen → Heartbeat kann es erneut versuchen
                    isReconnecting = false
                    return@launch
                }

                // Exponentielles Backoff: 5s, 10s, 20s, 30s, 30s, 30s...
                val wait = (5_000L * attempt).coerceAtMost(30_000L)
                TorChatLogger.w(TAG, "Verbindung fehlgeschlagen — warte ${wait/1000}s...")
                updateNotification("⏳ Warte ${wait/1000}s (Versuch $label)...")
                delay(wait)
            }
        }
    }

    private var onionAddressJob:  kotlinx.coroutines.Job? = null
    private var incomingJob:      kotlinx.coroutines.Job? = null
    private var peerOnlineJob:    kotlinx.coroutines.Job? = null
    // Adresse die zuletzt an Kontakte gesendet wurde
    private var lastBroadcastedOnion: String = ""

    private suspend fun onTorConnected() {
        TorChatLogger.i(TAG, "Tor verbunden — initialisiere P2P...")
        isReconnecting = false  // Reconnect erfolgreich abgeschlossen

        onionAddressJob?.cancel()
        incomingJob?.cancel()
        peerOnlineJob?.cancel()
        val previousOnion = app.keyManager.onionAddress

        // Neue Adresse aus hostname lesen oder auf Flow warten
        val currentOnion = app.embeddedTor.onionAddress.value
        if (!currentOnion.isNullOrEmpty()) {
            app.keyManager.updateOnionAddress(currentOnion)
            updateNotification("🧅 ${currentOnion.take(16)}...")
            TorChatLogger.i(TAG, "Onion-Adresse aus Cache: $currentOnion")
            // Sofort sync wenn Adresse neu/geändert
            serviceScope.launch {
                delay(5000) // Warten bis HS bereit
                syncAddressWithContacts(previousOnion, currentOnion)
            }
        } else {
            updateNotification("🧅 Tor verbunden — warte auf Adresse...")
            onionAddressJob = app.embeddedTor.onionAddress
                .onEach { addr ->
                    if (!addr.isNullOrEmpty()) {
                        app.keyManager.updateOnionAddress(addr)
                        updateNotification("🧅 ${addr.take(16)}...")
                        TorChatLogger.i(TAG, "Onion-Adresse aus Flow: $addr")
                        // Sync sobald Adresse verfügbar
                        serviceScope.launch {
                            delay(5000)
                            syncAddressWithContacts(previousOnion, addr)
                        }
                        onionAddressJob?.cancel()
                    }
                }.launchIn(serviceScope)
        }

        p2pEngine.stopListening()
        p2pEngine.startListening()

        incomingJob = p2pEngine.incomingMessages
            .onEach { msg ->
                showMessageNotification(msg.contactId, msg.content)
            }.launchIn(serviceScope)

        // Wenn ein PONG empfangen wird → Kontakt ist online → Queue leeren
        peerOnlineJob = p2pEngine.peerOnlineStatus
            .onEach { (onion, online) ->
                if (online) {
                    presenceTracker.markOnline(onion)
                    serviceScope.launch { flushQueueForContact(onion) }
                }
            }.launchIn(serviceScope)

        presenceTracker.stopTracking()
        presenceTracker.startTracking()

        serviceScope.launch {
            delay(8000)
            flushAllQueue()
        }

        serviceScope.launch {
            while (isActive) { delay(60_000); repository.deleteExpiredMessages() }
        }
    }

    /**
     * Synchronisiert neue Adresse mit allen Kontakten.
     * Sendet ADDRESS_UPDATE wenn Adresse sich geändert hat.
     * Führt immer KEY_EXCHANGE durch damit beide Seiten aktuelle Keys haben.
     */
    private suspend fun syncAddressWithContacts(oldOnion: String, newOnion: String) {
        if (newOnion.isEmpty()) return

        val contacts = try { repository.getAllContactsOnce() }
                       catch (_: Exception) { return }
        if (contacts.isEmpty()) return

        val addressChanged = oldOnion.isNotEmpty() && oldOnion != newOnion
        val alreadySynced  = lastBroadcastedOnion == newOnion

        if (addressChanged && !alreadySynced) {
            TorChatLogger.i(TAG,
                "📡 Adresse geändert: ${oldOnion.take(16)} → ${newOnion.take(16)}")
            TorChatLogger.i(TAG, "Sende ADDRESS_UPDATE an ${contacts.size} Kontakte")
            p2pEngine.broadcastNewAddress(oldOnion, newOnion, contacts)
            lastBroadcastedOnion = newOnion
        } else if (!alreadySynced) {
            TorChatLogger.i(TAG,
                "🔑 Adresse unverändert — sende KEY_EXCHANGE an ${contacts.size} Kontakte")
            // KEY_EXCHANGE mit allen Kontakten damit beide Seiten aktuelle Keys haben
            for (contact in contacts) {
                if (contact.isGroup || contact.isBlocked) continue
                try {
                    p2pEngine.initiateKeyExchangeIfNeeded(contact)
                } catch (_: Exception) {}
            }
            lastBroadcastedOnion = newOnion
        }
    }

    // ── Offline-Queue ─────────────────────────────

    /**
     * Alle ausstehenden Nachrichten für einen Kontakt senden (der gerade online ist).
     */
    private suspend fun flushQueueForContact(onionAddress: String) {
        val pending = messageQueue.getForContact(onionAddress)
        if (pending.isEmpty()) return

        TorChatLogger.i(TAG, "Queue für ${onionAddress.take(20)}: ${pending.size} Nachrichten")

        // Kontakt per Adresse suchen; falls nicht gefunden synthetischen Contact bauen
        // (passiert bei Gruppe-Mitgliedern die keinen eigenen Contact-Eintrag haben)
        val contact = repository.getContactByOnion(onionAddress)
        val freshContact = if (contact != null) {
            repository.getContactById(contact.id) ?: contact
        } else {
            // Kein DB-Kontakt → ersten Queue-Eintrag für Name/ID nutzen
            val first = pending.first()
            com.torchat.app.model.Contact(
                id           = first.contactId,
                name         = first.contactId.take(12),
                onionAddress = onionAddress
            )
        }

        for (queued in pending) {
            try {
                val ok = when {
                    queued.messageType.startsWith("GROUP_FILE:") -> {
                        val groupId = queued.messageType.removePrefix("GROUP_FILE:")
                        val fp = queued.filePath ?: continue
                        val fn = queued.fileName ?: "datei"
                        val ext = fn.substringAfterLast(".").lowercase()
                        val mt = if (ext in listOf("jpg","jpeg","png")) com.torchat.app.model.MessageType.IMAGE
                                 else com.torchat.app.model.MessageType.FILE
                        p2pEngine.sendFile(freshContact, fp, fn, mt, queued.messageId, groupId)
                    }
                    queued.messageType == "IMAGE" || queued.messageType == "FILE" -> {
                        val fp = queued.filePath ?: continue
                        val fn = queued.fileName ?: "datei"
                        val mt = com.torchat.app.model.MessageType.valueOf(
                            if (queued.messageType == "IMAGE") "IMAGE" else "FILE")
                        p2pEngine.sendFile(freshContact, fp, fn, mt, queued.messageId)
                    }
                    queued.messageType.startsWith("GROUP_INVITE:") -> {
                        p2pEngine.sendTorMessageDirect(
                            onionAddress = freshContact.onionAddress,
                            type         = "GROUP_INVITE",
                            messageId    = queued.messageId,
                            payload      = queued.plaintext
                        )
                    }
                    queued.messageType.startsWith("GROUP:") -> {
                        val groupId = queued.messageType.removePrefix("GROUP:")
                        p2pEngine.sendGroupMessage(
                            recipient = freshContact,
                            text      = queued.plaintext,
                            messageId = queued.messageId,
                            groupId   = groupId
                        )
                    }
                    else -> p2pEngine.sendMessage(freshContact, queued.plaintext, queued.messageId)
                }
                if (ok) {
                    messageQueue.remove(queued.messageId)
                    TorChatLogger.i(TAG, "Queue-Nachricht gesendet: ${queued.messageId.take(8)}")
                } else {
                    messageQueue.incrementRetry(queued.messageId)
                    TorChatLogger.w(TAG, "Queue-Nachricht fehlgeschlagen (Versuch ${queued.retryCount + 1})")
                }
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "flushQueue: ${e.message}", e)
                messageQueue.incrementRetry(queued.messageId)
            }
        }
        val remaining = messageQueue.getAll().size
        updateNotification(if (remaining > 0) "🧅 Verbunden | ⏳ $remaining ausstehend"
                           else "🧅 Verbunden")
    }

    /** Alle Queue-Nachrichten versuchen zu senden */
    private suspend fun flushAllQueue() {
        val allOnions = messageQueue.getAll().map { it.onionAddress }.distinct()
        for (onion in allOnions) {
            val online = presenceTracker.isOnline(onion)
            if (online) flushQueueForContact(onion)
        }
    }

    /** Periodischer Queue-Retry */
    private fun startQueueRetry() {
        queueRetryJob = serviceScope.launch {
            val interval = if (isBackground) QUEUE_RETRY_BG else QUEUE_RETRY_FG
            delay(interval)
            while (isActive) {
                if (!messageQueue.isEmpty() &&
                    app.torProxyManager.torStatus.value == TorStatus.CONNECTED) {
                    TorChatLogger.d(TAG, "Queue-Retry: ${messageQueue.getAll().size} ausstehend")
                    flushAllQueue()
                }
                delay(if (isBackground) QUEUE_RETRY_BG else QUEUE_RETRY_FG)
            }
        }
    }

    // ── Screen-Receiver (Hintergrund-Optimierung) ─

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isBackground = true
                        // Heartbeat und Queue-Retry auf lange Intervalle umstellen
                        restartHeartbeat()
                        restartQueueRetry()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isBackground = false
                        restartHeartbeat()
                        restartQueueRetry()
                        // Sofort Queue prüfen wenn Screen wieder an
                        serviceScope.launch { flushAllQueue() }
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        TorChatLogger.w(TAG, "🔋 Akku niedrig — Presence-Tracking pausiert")
                        presenceTracker.stopTracking()
                    }
                    Intent.ACTION_BATTERY_OKAY -> {
                        TorChatLogger.i(TAG, "🔋 Akku OK — Presence-Tracking fortgesetzt")
                        presenceTracker.startTracking()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        try { registerReceiver(screenReceiver, filter) }
        catch (e: Exception) { TorChatLogger.w(TAG, "screenReceiver: ${e.message}") }
        TorChatLogger.d(TAG, "Screen/Battery-Receiver registriert")
    }

    private fun restartHeartbeat() {
        heartbeatJob?.cancel()
        startHeartbeat()
    }

    private fun restartQueueRetry() {
        queueRetryJob?.cancel()
        startQueueRetry()
    }

    // ── Heartbeat ─────────────────────────────────

    private var isReconnecting = false  // verhindert parallele Reconnects

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            // Erster Check nach kurzem Delay (Startup abwarten)
            delay(10_000L)
            while (isActive) {
                val socksPort  = app.embeddedTor.socksPort
                val socksAlive = try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 2000)
                    }; true
                } catch (_: Exception) { false }

                if (!socksAlive && !isReconnecting) {
                    TorChatLogger.w(TAG, "💔 Heartbeat: SOCKS5:$socksPort tot → starte Reconnect")
                    isReconnecting = true
                    p2pEngine.stopListening()
                    presenceTracker.stopTracking()
                    // Unbegrenzte Versuche — reconnectet solange Tor nicht läuft
                    connectWithRetry(maxRetries = Int.MAX_VALUE)
                    // Heartbeat wartet während reconnect läuft
                } else if (socksAlive && isReconnecting) {
                    // Tor ist wieder da — isReconnecting wird in onTorConnected gesetzt
                    TorChatLogger.d(TAG, "Heartbeat: Tor wieder online")
                }

                // Kürzeres Intervall bei aktivem Reconnect für schnellere Erkennung
                delay(if (isReconnecting) 15_000L
                      else if (isBackground) HEARTBEAT_BG
                      else HEARTBEAT_FG)
            }
        }
    }

    // ── Netzwerk-Callback ────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                TorChatLogger.i(TAG, "🌐 Netzwerk verfügbar → Reconnect")
                if (!isReconnecting) {
                    serviceScope.launch {
                        isReconnecting = true
                        delay(2000)  // Warten bis Netzwerk stabil
                        app.torProxyManager.updateStatus(TorStatus.DISCONNECTED)
                        p2pEngine.stopListening()
                        presenceTracker.stopTracking()
                        reconnectJob?.cancel()  // Laufenden Reconnect abbrechen
                        connectWithRetry(maxRetries = Int.MAX_VALUE)
                    }
                }
            }

            override fun onLost(network: Network) {
                TorChatLogger.w(TAG, "🔴 Netzwerk verloren — warte auf Reconnect")
                app.torProxyManager.updateStatus(TorStatus.DISCONNECTED)
                presenceTracker.stopTracking()
                updateNotification("⏳ No network...")
                // isReconnecting NICHT auf false setzen —
                // connectWithRetry läuft weiter und verbindet sobald Netz wieder da
                // Falls kein Reconnect läuft, Heartbeat triggert es beim nächsten Zyklus
                if (!isReconnecting) {
                    isReconnecting = true
                    serviceScope.launch {
                        // Warten bis Netzwerk zurück (onAvailable triggert dann den Reconnect)
                        // Falls onAvailable nicht feuert, direkt versuchen
                        delay(10_000L)
                        if (isReconnecting) {
                            connectWithRetry(maxRetries = Int.MAX_VALUE)
                        }
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback!!)
        } catch (e: Exception) { TorChatLogger.w(TAG, "NetworkCallback: ${e.message}") }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
    }

    // ── Benachrichtigungen ────────────────────────

    private fun showMessageNotification(contactId: String, content: String) {
        // Keine Push-Benachrichtigung wenn App im Vordergrund aktiv ist
        if (TorChatApp.isInForeground) {
            TorChatLogger.d(TAG, "App im Vordergrund — Push-Benachrichtigung unterdrückt")
            return
        }
        // Keine Push-Benachrichtigung wenn PIN aktiv — Datenschutz
        if (app.pinManager.isPinActive) {
            TorChatLogger.d(TAG, "PIN aktiv — Push-Benachrichtigung unterdrückt")
            return
        }
        // Keine Push-Benachrichtigung wenn Benachrichtigungen deaktiviert
        if (!com.torchat.app.data.SettingsManager.isNotificationsEnabled(this)) {
            TorChatLogger.d(TAG, "Benachrichtigungen deaktiviert — unterdrückt")
            return
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("contactId", contactId)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, TorChatApp.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle(getString(R.string.notification_message_title))
                .setContentText("🔒 $content")
                .setAutoCancel(true).setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, TorChatApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tor)
            .setContentTitle("TorChat")
            .setContentText(status)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(status: String) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
}
