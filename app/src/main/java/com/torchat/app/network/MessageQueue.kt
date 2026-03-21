// Build fix 1773557613
package com.torchat.app.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchat.app.debug.TorChatLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistente Nachrichtenwarteschlange für Offline-Nachrichten.
 *
 * Speichert Nachrichten in SharedPreferences → überlebt App-Neustart.
 * Wenn Empfänger offline ist → Nachricht in Queue → bei Online-Erkennung automatisch senden.
 */
class MessageQueue(context: Context) {

    companion object {
        private const val TAG       = "MessageQueue"
        private const val PREF_FILE = "torchat_queue"
        private const val KEY_QUEUE = "pending_messages"
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 Tage
        private const val MAX_SIZE   = 500
    }

    data class QueuedMessage(
        val messageId:    String,
        val contactId:    String,
        val onionAddress: String,
        val plaintext:    String,
        val messageType:  String = "TEXT",  // TEXT, IMAGE, FILE
        val filePath:     String? = null,
        val fileName:     String? = null,
        val enqueuedAt:   Long = System.currentTimeMillis(),
        val retryCount:   Int  = 0
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize

    init { cleanup(); _queueSize.value = getAll().size }

    fun enqueue(msg: QueuedMessage) {
        val current = getAll().toMutableList()
        // Duplikat vermeiden
        if (current.any { it.messageId == msg.messageId }) return
        // Max-Größe
        if (current.size >= MAX_SIZE) current.removeAt(0)
        current.add(msg)
        save(current)
        _queueSize.value = current.size
        TorChatLogger.i(TAG, "Eingereiht: ${msg.messageId.take(8)} für ${msg.onionAddress.take(20)} (${current.size} gesamt)")
    }

    fun getForContact(onionAddress: String): List<QueuedMessage> =
        getAll().filter { it.onionAddress == onionAddress }

    fun getAll(): List<QueuedMessage> {
        return try {
            val json = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
            val type = object : TypeToken<List<QueuedMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun remove(messageId: String) {
        val updated = getAll().filter { it.messageId != messageId }
        save(updated)
        _queueSize.value = updated.size
    }

    fun removeForContact(onionAddress: String) {
        val updated = getAll().filter { it.onionAddress != onionAddress }
        save(updated)
        _queueSize.value = updated.size
        TorChatLogger.d(TAG, "Queue für ${onionAddress.take(20)} geleert")
    }

    /**
     * Aktualisiert die onionAddress in allen Queue-Einträgen eines Kontakts.
     * Wird aufgerufen wenn ein Kontakt eine neue Tor-Adresse bekommt (ADDRESS_UPDATE).
     */
    fun updateOnionAddress(oldAddress: String, newAddress: String) {
        val all = getAll()
        val affected = all.count { it.onionAddress == oldAddress }
        if (affected == 0) return
        val updated = all.map {
            if (it.onionAddress == oldAddress) it.copy(onionAddress = newAddress) else it
        }
        save(updated)
        TorChatLogger.i(TAG, "Queue: $affected Nachrichten auf neue Adresse umgestellt " +
            "${oldAddress.take(16)} → ${newAddress.take(16)}")
    }

    fun incrementRetry(messageId: String) {
        val updated = getAll().map {
            if (it.messageId == messageId) it.copy(retryCount = it.retryCount + 1) else it
        }
        save(updated)
    }

    /** Abgelaufene Nachrichten (> 7 Tage) und zu oft retryed (> 20) entfernen */
    private fun cleanup() {
        val now     = System.currentTimeMillis()
        val all     = getAll()
        val cleaned = all.filter {
            it.enqueuedAt > now - MAX_AGE_MS && it.retryCount < 20
        }
        if (cleaned.size != all.size) {
            save(cleaned)
            TorChatLogger.d(TAG, "Queue bereinigt: ${cleaned.size} verbleibend")
        }
    }

    private fun save(list: List<QueuedMessage>) {
        prefs.edit().putString(KEY_QUEUE, gson.toJson(list)).apply()
    }

    fun isEmpty(): Boolean = getAll().isEmpty()
}
