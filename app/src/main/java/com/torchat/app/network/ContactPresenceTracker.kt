// Build fix 1773557613
package com.torchat.app.network

import com.torchat.app.data.ChatRepository
import com.torchat.app.debug.TorChatLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Prüft ob ein Kontakt online ist (erreichbarer Hidden Service auf Port 11009).
 * Hält eine Liste bekannter Online-Kontakte und feuert Callbacks wenn jemand online geht.
 */
class ContactPresenceTracker(
    private val torProxy: TorProxyManager,
    private val repository: ChatRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG            = "PresenceTracker"
        private const val CHECK_PORT     = 11009
        private const val TIMEOUT_MS     = 15_000
    }

    // Anpassbar: kurz im Vordergrund, lang im Hintergrund
    var checkInterval: Long = 60_000L

    // onionAddress → online
    private val _onlineContacts = MutableStateFlow<Set<String>>(emptySet())
    val onlineContacts: StateFlow<Set<String>> = _onlineContacts

    private var checkJob: Job? = null

    // Callback wenn Kontakt online geht
    var onContactCameOnline: ((onionAddress: String) -> Unit)? = null

    fun startTracking() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                checkAllContacts()
                delay(checkInterval)
            }
        }
        TorChatLogger.d(TAG, "Presence-Tracking gestartet")
    }

    fun stopTracking() {
        checkJob?.cancel()
        TorChatLogger.d(TAG, "Presence-Tracking gestoppt")
    }

    /** Prüft ob Kontakt online ist via PING-Nachricht */
    suspend fun isOnline(onionAddress: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val socket = torProxy.connectToOnion(onionAddress, CHECK_PORT)
                    ?: run {
                        updateOnlineStatus(onionAddress, false)
                        return@withContext false
                    }
                socket.soTimeout = TIMEOUT_MS
                try {
                    val writer = java.io.PrintWriter(socket.getOutputStream(), true)
                    val reader = java.io.BufferedReader(
                        java.io.InputStreamReader(socket.getInputStream()))

                    // PING senden
                    val ping = com.google.gson.Gson().toJson(mapOf(
                        "version" to 1,
                        "type"    to "PING",
                        "senderId"  to "presence-check",
                        "recipientId" to onionAddress,
                        "messageId" to java.util.UUID.randomUUID().toString(),
                        "encryptedPayload" to "",
                        "signature" to ""
                    ))
                    writer.println(ping)

                    // PONG abwarten (max TIMEOUT_MS)
                    val response = try { reader.readLine() } catch (_: Exception) { null }
                    val online = response != null
                    TorChatLogger.d(TAG, if (online) "✅ $onionAddress online (PONG)" 
                                        else "⭕ $onionAddress keine Antwort")
                    updateOnlineStatus(onionAddress, online)
                    online
                } finally {
                    runCatching { socket.close() }
                }
            } catch (_: Exception) {
                updateOnlineStatus(onionAddress, false)
                false
            }
        }

    private suspend fun checkAllContacts() {
        val contacts = try { repository.getAllContactsOnce() } catch (_: Exception) { return }
        val realContacts = contacts.filter { !it.isGroup && it.onionAddress.endsWith(".onion") }
        if (realContacts.isEmpty()) return

        TorChatLogger.d(TAG, "Presence-Check für ${realContacts.size} Kontakte...")
        val nowOnline = mutableSetOf<String>()

        for (contact in realContacts) {
            val wasOnline = _onlineContacts.value.contains(contact.onionAddress)
            val isNowOnline = isOnline(contact.onionAddress)
            if (isNowOnline) nowOnline.add(contact.onionAddress)

        // Wenn Kontakt gerade online gegangen ist → Callback + sofort Key-Exchange
            if (isNowOnline && !wasOnline) {
                TorChatLogger.i(TAG, "🟢 ${contact.name} ist jetzt online!")
                // Key-Exchange anstoßen wenn kein Public Key vorhanden
                if (contact.publicKeyBase64.isEmpty()) {
                    scope.launch {
                        try {
                            val socket = torProxy.connectToOnion(contact.onionAddress, CHECK_PORT)
                            socket?.close()
                        } catch (_: Exception) {}
                    }
                }
                onContactCameOnline?.invoke(contact.onionAddress)
            }
        }
        _onlineContacts.value = nowOnline
    }

    private fun updateOnlineStatus(onionAddress: String, online: Boolean) {
        val current = _onlineContacts.value.toMutableSet()
        if (online) current.add(onionAddress) else current.remove(onionAddress)
        _onlineContacts.value = current
    }

    fun markOnline(onionAddress: String) = updateOnlineStatus(onionAddress, true)
    fun markOffline(onionAddress: String) = updateOnlineStatus(onionAddress, false)

    /** Sofort alle Kontakte prüfen (z.B. nach WLAN-Reconnect) */
    fun checkNow() {
        scope.launch { checkAllContacts() }
    }
}
