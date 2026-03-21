package com.torchat.app.network

import com.google.gson.Gson
import com.torchat.app.TorChatApp
import com.torchat.app.crypto.EncryptedMessage
import com.torchat.app.crypto.KeyManager
import com.torchat.app.data.ChatRepository
import com.torchat.app.debug.TorChatLogger
import com.torchat.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class P2PMessagingEngine(
    private val context:        android.content.Context,
    private val keyManager:     KeyManager,
    private val torProxy:       TorProxyManager,
    private val repository:     ChatRepository,
    private val scope:          CoroutineScope,
    private val messageQueue:   MessageQueue? = null
) {
    companion object {
        private const val TAG     = "P2PEngine"
        private const val PORT    = TorChatApp.P2P_PORT   // 11009
        private const val PROTO_V = 1
        private const val TIMEOUT         = 20_000  // 20s — für MSG/FILE
        private const val TIMEOUT_FAST    =  8_000  // 8s  — für PING/PONG (Presence)
        private const val TIMEOUT_KEYX    = 30_000  // 30s — KEY_EXCHANGE (frischer Circuit)
    }

    private val gson   = Gson()
    private var server: ServerSocket? = null

    private val _incoming = MutableSharedFlow<Message>()
    val incomingMessages: SharedFlow<Message> = _incoming

    private val _peerStatus = MutableSharedFlow<Pair<String, Boolean>>()
    val peerOnlineStatus: SharedFlow<Pair<String, Boolean>> = _peerStatus

    private val _remoteDeleted = MutableSharedFlow<String>()
    val remoteDeletedMessages: SharedFlow<String> = _remoteDeleted

    // ── Server: eingehende Verbindungen ──────────

    fun startListening() {
        scope.launch(Dispatchers.IO) {
            val localServer = try {
                server?.close()
                ServerSocket(PORT).also { server = it }
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "Server-Start fehlgeschlagen (Port $PORT): ${e.message}", e)
                return@launch
            }
            TorChatLogger.i(TAG, "✅ P2P Server lauscht auf Port $PORT")
            try {
                while (isActive) {
                    try {
                        val socket = localServer.accept()
                        TorChatLogger.d(TAG, "Neue Verbindung von ${socket.inetAddress}")
                        launch { handleConnection(socket) }
                    } catch (e: Exception) {
                        if (isActive && e !is java.net.SocketException) {
                            TorChatLogger.e(TAG, "Accept-Fehler: ${e.message}", e)
                        } else if (isActive) {
                            TorChatLogger.d(TAG, "Accept gestoppt: ${e.message}")
                        }
                    }
                }
            } finally {
                runCatching { localServer.close() }
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = TIMEOUT
            val line = withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            }
            if (line.isNullOrBlank()) {
                TorChatLogger.d(TAG, "Leere Verbindung (Presence-Check oder Netzwerktest)")
                return
            }

            TorChatLogger.d(TAG, "Empfangen: ${line.take(120)}")

            // Null-sicher parsen — Gson gibt null-Felder bei fehlenden JSON-Keys zurück
            val msg = runCatching {
                gson.fromJson(line, TorMessage::class.java)
            }.getOrNull()

            if (msg == null) {
                TorChatLogger.w(TAG, "JSON konnte nicht geparst werden: ${line.take(60)}")
                return
            }

            // Pflichtfelder prüfen
            val msgType    = msg.type?.trim()
            val msgSender  = msg.senderId?.trim()
            val msgId      = msg.messageId?.trim()

            if (msgType.isNullOrEmpty()) {
                TorChatLogger.w(TAG, "Nachricht ohne Typ ignoriert")
                return
            }
            if (msgSender.isNullOrEmpty() && msgType != "PING") {
                TorChatLogger.w(TAG, "Nachricht ohne senderId ignoriert (Typ: $msgType)")
                return
            }

            // Sicheres TorMessage-Objekt mit garantiert nicht-null Feldern
            val safeMsg = msg.copy(
                type             = msgType,
                senderId         = msgSender ?: "unknown",
                messageId        = msgId ?: java.util.UUID.randomUUID().toString(),
                encryptedPayload = msg.encryptedPayload ?: "",
                recipientId      = msg.recipientId ?: "",
                groupId          = msg.groupId ?: "",
                nonce            = msg.nonce ?: "",
                signature        = msg.signature ?: "",
                senderPublicKey  = msg.senderPublicKey ?: ""
            )

            val writer = withContext(Dispatchers.IO) { PrintWriter(socket.getOutputStream(), true) }
            TorChatLogger.i(TAG, "Nachricht Typ=${safeMsg.type} Von=${safeMsg.senderId.take(20)}")

            when (safeMsg.type) {
                "MSG"            -> handleIncomingMsg(safeMsg, writer)
                "FILE"           -> handleIncomingFile(safeMsg, writer)
                "KEY_EXCHANGE"   -> handleKeyExchange(safeMsg, writer)
                "ADDRESS_UPDATE" -> handleAddressUpdate(safeMsg, writer)
                "GROUP_INVITE"   -> handleGroupInvite(safeMsg, writer)
                "MSG_DELETE"     -> handleRemoteDelete(safeMsg)
                "ACK"            -> {
                    if (!safeMsg.messageId.isNullOrEmpty())
                        repository.updateMessageStatus(safeMsg.messageId, MessageStatus.DELIVERED)
                    TorChatLogger.d(TAG, "✅✅ ACK → DELIVERED: ${safeMsg.messageId.take(8)}")
                }
                "PING"           -> {
                    val stealth = com.torchat.app.data.SettingsManager.isStealthEnabled(
                        context.applicationContext)
                    if (!stealth) {
                        writer.println(gson.toJson(makePong(safeMsg)))
                        TorChatLogger.d(TAG, "PONG gesendet")
                    } else {
                        TorChatLogger.d(TAG, "Stealth: PING ignoriert")
                    }
                }
                "PONG" -> _peerStatus.emit(safeMsg.senderId to true)
                else   -> TorChatLogger.w(TAG, "Unbekannter Typ: ${safeMsg.type}")
            }
        } catch (e: java.net.SocketTimeoutException) {
            TorChatLogger.d(TAG, "handleConnection: Timeout (normal bei Presence-Check)")
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleConnection: ${e.message}", e)
        } finally {
            withContext(Dispatchers.IO) { runCatching { socket.close() } }
        }
    }

    private suspend fun handleIncomingMsg(msg: TorMessage, writer: PrintWriter) {
        var contact = repository.getContactByOnion(msg.senderId)

        // Unbekannte Adresse → prüfen ob es eine Gruppenachricht ist
        // Bei Gruppenachrichten von bekannten Gruppen keinen Einzelkontakt anlegen
        if (contact == null) {
            val isGroupMsg = msg.groupId.isNotEmpty() && repository.getContactById(msg.groupId)?.isGroup == true
            if (isGroupMsg) {
                // Gruppenachricht von unbekanntem Absender → virtuellen Kontakt bauen (nur für diese Session)
                // Kein DB-Eintrag: der Absender ist nur Gruppenmitglied, kein eigenständiger Kontakt
                TorChatLogger.d(TAG, "Gruppenachricht von unbekanntem Mitglied ${msg.senderId.take(20)} — kein Einzelkontakt")
                contact = com.torchat.app.model.Contact(
                    id           = "grp_" + java.security.MessageDigest.getInstance("SHA-256")
                        .digest(msg.senderId.toByteArray()).take(12).joinToString("") { "%02x".format(it) },
                    name         = "Mitglied",
                    onionAddress = msg.senderId
                )
            } else {
                val stableId = "auto_" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest(msg.senderId.toByteArray())
                    .take(16)
                    .joinToString("") { "%02x".format(it) }

                val existing = repository.getContactById(stableId)
                if (existing == null) {
                    TorChatLogger.i(TAG, "Neuer Kontakt von ${msg.senderId.take(20)}")
                    // senderDisplayName direkt aus der Nachricht nutzen wenn vorhanden
                    val autoName = msg.senderDisplayName.trim().ifEmpty {
                        repository.generateContactName(context.applicationContext)
                    }
                    repository.addContact(com.torchat.app.model.Contact(
                        id              = stableId,
                        name            = autoName,
                        onionAddress    = msg.senderId,
                        publicKeyBase64 = msg.senderPublicKey.trim(),
                        addedAt         = System.currentTimeMillis()
                    ))
                    TorChatLogger.i(TAG, "Kontakt angelegt: \"$autoName\"")
                    // KEY_EXCHANGE starten für gegenseitigen Key-Austausch
                    sendKeyExchangeTo(msg.senderId)
                } else if (existing.name.startsWith("User ") && msg.senderDisplayName.isNotEmpty()) {
                    // Bereits vorhanden aber noch Platzhalter-Name → jetzt updaten
                    repository.updateContact(existing.copy(name = msg.senderDisplayName.trim()))
                }
                contact = repository.getContactByOnion(msg.senderId)
            }
        }

        val knownContact = contact ?: run {
            TorChatLogger.e(TAG, "Kontakt nicht gefunden nach auto-create"); return
        }

        // Blockierte Kontakte ignorieren
        if (knownContact.isBlocked) {
            TorChatLogger.d(TAG, "Nachricht von blockiertem Kontakt ignoriert: ${knownContact.name}")
            return
        }

        // Public Key ermitteln: entweder aus DB oder direkt aus Nachricht (senderPublicKey)
        var effectivePubKey = knownContact.publicKeyBase64

        // Wenn Nachricht den Public Key enthält → sofort speichern (nur für echte DB-Kontakte)
        val inlinePubKey = msg.senderPublicKey.trim()
        if (inlinePubKey.isNotEmpty() && effectivePubKey != inlinePubKey) {
            effectivePubKey = inlinePubKey
            // Nur in DB schreiben wenn Kontakt wirklich existiert (nicht virtuell)
            val isVirtual = knownContact.id.startsWith("grp_") &&
                repository.getContactById(knownContact.id) == null
            if (!isVirtual) {
                TorChatLogger.i(TAG, "Public Key aus Nachricht erhalten → sofort gespeichert")
                repository.updateContactPublicKey(knownContact.id, inlinePubKey)
                decryptPendingMessages(knownContact.copy(publicKeyBase64 = inlinePubKey), inlinePubKey)
            }
        }

        // Kein Public Key vorhanden → raw Payload speichern, nach KEY_EXCHANGE entschlüsseln
        if (effectivePubKey.isEmpty()) {
            TorChatLogger.w(TAG, "Kein Public Key — speichere Payload für spätere Entschlüsselung")
            val pendingMsg = Message(
                id               = msg.messageId,
                contactId        = knownContact.id,
                content          = "🔐 Wird entschlüsselt...",
                encryptedContent = msg.encryptedPayload,
                isOutgoing       = false,
                timestamp        = msg.timestamp,
                status           = MessageStatus.SENDING,
                senderOnion      = if (msg.groupId.isNotEmpty()) msg.senderId else ""
            )
            repository.saveMessage(pendingMsg)
            repository.updateContactLastSeen(knownContact.id)
            _incoming.emit(pendingMsg)
            writer.println(gson.toJson(TorMessage(
                version = PROTO_V, type = "ACK",
                senderId = keyManager.onionAddress, recipientId = msg.senderId,
                messageId = msg.messageId, encryptedPayload = "", signature = ""
            )))
            return
        }

        // Entschlüsseln mit effectivePubKey (aus DB oder inline aus Nachricht)
        val plaintext = tryDecrypt(msg.encryptedPayload, effectivePubKey)
        TorChatLogger.i(TAG, "📨 MSG von ${knownContact.name}: ${plaintext.take(60)}")

        val disappearing = com.torchat.app.data.SettingsManager.isDisappearingEnabled(
            context.applicationContext)
        val disappearsAt = if (disappearing)
            System.currentTimeMillis() +
            com.torchat.app.data.SettingsManager.getDisappearingSeconds(
                context.applicationContext) * 1000L
        else null

        // ── Gruppen- oder Einzelnachricht? ───────────────────────────
        // Wenn groupId gesetzt → Nachricht im Gruppenchat speichern
        val targetContactId = if (msg.groupId.isNotEmpty()) {
            // Prüfen ob diese Gruppe bekannt ist
            val group = repository.getContactById(msg.groupId)
            if (group != null && group.isGroup) {
                // Prüfen ob Absender in dieser Gruppe blockiert ist
                val senderMember = repository.getGroupMembers(msg.groupId)
                    .find { it.onionAddress == msg.senderId }
                if (senderMember?.isBlockedInGroup == true) {
                    TorChatLogger.d(TAG, "Gruppenachricht von blockiertem Mitglied ignoriert: ${knownContact.name}")
                    writer.println(gson.toJson(TorMessage(
                        version = PROTO_V, type = "ACK",
                        senderId = keyManager.onionAddress, recipientId = msg.senderId,
                        messageId = msg.messageId, encryptedPayload = "", signature = ""
                    )))
                    return
                }
                TorChatLogger.d(TAG, "Gruppenachricht von ${knownContact.name} → Gruppe ${group.name}")
                group.id
            } else {
                // Gruppe unbekannt → als Einzelnachricht behandeln
                TorChatLogger.w(TAG, "Gruppe ${msg.groupId.take(16)} unbekannt → Einzelchat")
                knownContact.id
            }
        } else {
            knownContact.id
        }

        val message = Message(
            id           = msg.messageId,
            contactId    = targetContactId,
            content      = plaintext,
            isOutgoing   = false,
            timestamp    = msg.timestamp,
            status       = MessageStatus.DELIVERED,
            disappearsAt = disappearsAt,
            // Bei Gruppenachrichten: Absender-Onion für Anzeige im Chat speichern
            senderOnion  = if (msg.groupId.isNotEmpty()) msg.senderId else ""
        )
        repository.saveMessage(message)
        // lastSeen nur aktualisieren wenn echter DB-Kontakt (nicht virtueller Gruppen-Sender)
        if (!knownContact.id.startsWith("grp_") || repository.getContactById(knownContact.id) != null) {
            repository.updateContactLastSeen(knownContact.id)
        }
        _incoming.emit(message)

        // ACK senden
        writer.println(gson.toJson(TorMessage(
            version = PROTO_V, type = "ACK",
            senderId = keyManager.onionAddress, recipientId = msg.senderId,
            messageId = msg.messageId, encryptedPayload = "", signature = ""
        )))
    }

    /**
     * Versucht zu entschlüsseln. Gibt bei Fehler lesbaren Fallback-Text zurück.
     */
    private suspend fun tryDecrypt(encryptedPayload: String, publicKeyBase64: String): String {
        if (encryptedPayload.isBlank()) return "(leer)"

        // 1. Versuche AES/RSA-Entschlüsselung wenn Public Key vorhanden
        if (publicKeyBase64.isNotEmpty()) {
            try {
                val enc = gson.fromJson(encryptedPayload, EncryptedMessage::class.java)
                if (enc?.ciphertext != null && enc.encryptedKey != null && enc.iv != null) {
                    val result = keyManager.decryptMessage(enc, publicKeyBase64)
                    TorChatLogger.d(TAG, "Entschlüsselung ✅")
                    return result
                }
            } catch (e: Exception) {
                TorChatLogger.w(TAG, "Entschlüsselung fehlgeschlagen: ${e.message}")
            }
        }

        // 2. Wenn EncryptedMessage-JSON aber kein Key → Key-Exchange ausstehend
        try {
            val jsonObj = gson.fromJson(encryptedPayload, com.google.gson.JsonObject::class.java)
            if (jsonObj?.has("ciphertext") == true) {
                TorChatLogger.w(TAG, "Verschlüsselt aber kein Public Key — KEY_EXCHANGE ausstehend")
                return "🔒 (Key-Exchange läuft...)"
            }
            return encryptedPayload
        } catch (_: Exception) {}

        // 3. Fallback
        return if (encryptedPayload.length < 200) encryptedPayload
               else "🔒 (verschlüsselte Nachricht)"
    }

    private suspend fun handleKeyExchange(msg: TorMessage, writer: PrintWriter) {
        TorChatLogger.d(TAG, "KEY_EXCHANGE von ${msg.senderId.take(20)}")
        try {
            val payload = gson.fromJson(msg.encryptedPayload, KeyExchangePayload::class.java)
            if (payload?.publicKey.isNullOrEmpty()) {
                TorChatLogger.w(TAG, "KEY_EXCHANGE: leerer Public Key")
            } else {
                // ── Strategie 1: exakte Adresse (immer bevorzugt) ──────────
                var contact = repository.getContactByOnion(msg.senderId)

                // ── Strategie 2: Adresse geändert nach Reconnect ───────────
                // Suche per Public Key NUR wenn:
                //   a) Adresse nicht gefunden
                //   b) Key eindeutig (nur 1 Kontakt hat diesen Key)
                //   c) Key nicht leer
                if (contact == null) {
                    val allContacts   = repository.getAllContactsOnce()
                    val matchesByKey  = allContacts.filter {
                        it.publicKeyBase64.isNotEmpty() &&
                        it.publicKeyBase64 == payload.publicKey
                    }
                    if (matchesByKey.size == 1) {
                        // Eindeutig → sicher zuzuordnen
                        val matched = matchesByKey.first()
                        TorChatLogger.i(TAG,
                            "KEY_EXCHANGE: Kontakt per eindeutigem PubKey identifiziert " +
                            "(${matched.name}) ${matched.onionAddress.take(16)} → ${msg.senderId.take(16)}")
                        val oldOnion = matched.onionAddress
                        repository.updateContact(matched.copy(onionAddress = msg.senderId))
                        messageQueue?.updateOnionAddress(oldOnion, msg.senderId)
                        contact = repository.getContactByOnion(msg.senderId)
                    } else if (matchesByKey.size > 1) {
                        // Mehrdeutig → KEIN Update, würde falschen Kontakt treffen
                        TorChatLogger.w(TAG,
                            "KEY_EXCHANGE: Public Key nicht eindeutig (${matchesByKey.size} Treffer) " +
                            "— warte auf ADDRESS_UPDATE für sichere Zuordnung")
                    }
                    // Kein Treffer → unbekannter Kontakt, wird durch handleIncomingMsg angelegt
                }

                if (contact != null) {
                    val updatedContact = contact.copy(
                        publicKeyBase64   = payload.publicKey,
                        onionAddress      = msg.senderId,
                        name              = payload.displayName.ifEmpty { contact.name },
                        remoteDisplayName = payload.displayName
                    )
                    repository.updateContact(updatedContact)
                    if (payload.displayName.isNotEmpty()) {
                        repository.updateGroupMemberDisplayName(msg.senderId, payload.displayName)
                    }
                    TorChatLogger.i(TAG,
                        "✅ KEY_EXCHANGE: Key für ${updatedContact.name} " +
                        "(${msg.senderId.take(16)}) gespeichert")
                    decryptPendingMessages(updatedContact, payload.publicKey)
                } else {
                    TorChatLogger.w(TAG,
                        "KEY_EXCHANGE: Kontakt ${msg.senderId.take(20)} unbekannt — " +
                        "wird bei nächster MSG auto-erstellt")
                }
            }
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleKeyExchange: ${e.message}", e)
        }

        // Eigenen Key zurücksenden — immer an die senderId dieser Nachricht
        writer.println(gson.toJson(TorMessage(
            version = PROTO_V, type = "KEY_EXCHANGE",
            senderId    = keyManager.onionAddress,
            recipientId = msg.senderId,  // Antwort geht zurück an DIESE Adresse
            messageId   = UUID.randomUUID().toString(),
            encryptedPayload = gson.toJson(KeyExchangePayload(
                publicKey    = keyManager.publicKeyBase64,
                onionAddress = keyManager.onionAddress,
                displayName  = keyManager.transmittedDisplayName
            )),
            signature = ""
        )))
    }

    /** Entschlüsselt alle Nachrichten die noch nicht entschlüsselt wurden */
    private suspend fun decryptPendingMessages(contact: Contact, publicKey: String) {
        try {
            val pending = repository.getPendingMessages(contact.id)
            if (pending.isEmpty()) return
            TorChatLogger.i(TAG, "Entschlüssele ${pending.size} ausstehende Nachrichten für ${contact.name}")
            for (msg in pending) {
                if (msg.encryptedContent.isBlank()) continue
                try {
                    val enc = gson.fromJson(msg.encryptedContent, EncryptedMessage::class.java)
                    if (enc?.ciphertext != null) {
                        val plaintext = keyManager.decryptMessage(enc, publicKey)
                        val updated = msg.copy(content = plaintext, status = MessageStatus.DELIVERED)
                        repository.updateMessage(updated)
                        _incoming.emit(updated)
                        TorChatLogger.d(TAG, "✅ Nachträgliche Entschlüsselung: ${plaintext.take(30)}")
                    }
                } catch (e: Exception) {
                    TorChatLogger.w(TAG, "Nachträgliche Entschlüsselung: ${e.message}")
                }
            }
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "decryptPendingMessages: ${e.message}", e)
        }
    }

    private suspend fun sendKeyExchangeTo(onionAddress: String) {
        try {
            sendTorMessage(onionAddress, TorMessage(
                version = PROTO_V, type = "KEY_EXCHANGE",
                senderId = keyManager.onionAddress, recipientId = onionAddress,
                messageId = UUID.randomUUID().toString(),
                encryptedPayload = gson.toJson(KeyExchangePayload(
                    publicKey    = keyManager.publicKeyBase64,
                    onionAddress = keyManager.onionAddress,
                    displayName  = keyManager.transmittedDisplayName
                )),
                signature = ""
            ))
        } catch (e: Exception) { TorChatLogger.w(TAG, "sendKeyExchange: ${e.message}") }
    }

    private fun makePong(ping: TorMessage) = TorMessage(
        version = PROTO_V, type = "PONG",
        senderId = keyManager.onionAddress, recipientId = ping.senderId,
        messageId = ping.messageId, encryptedPayload = "", signature = ""
    )

    // ── Client: ausgehende Nachrichten ──────────

    suspend fun sendMessage(contact: Contact, plaintext: String, messageId: String): Boolean {
        // ── WICHTIG: Immer frischen Kontakt aus DB laden ──────────────────
        // Der übergebene Contact kann eine veraltete onionAddress haben,
        // z.B. nach ADDRESS_UPDATE eines reconnecteten Kontakts.
        val freshContact = repository.getContactById(contact.id) ?: contact
        TorChatLogger.d(TAG, "Sende an ${freshContact.name} (${freshContact.onionAddress.take(20)})")

        var pubKey = freshContact.publicKeyBase64
        if (pubKey.isEmpty()) {
            TorChatLogger.d(TAG, "Kein Public Key → KEY_EXCHANGE")
            pubKey = performKeyExchangeGetKey(freshContact) ?: ""
            if (pubKey.isEmpty()) {
                TorChatLogger.d(TAG, "KEY_EXCHANGE fehlgeschlagen → neuer Circuit, Versuch 2")
                try { torProxy.newCircuit() } catch (_: Exception) {}
                delay(3_000)
                // Nochmal frisch aus DB nach dem Circuit-Wechsel
                val freshContact2 = repository.getContactById(contact.id) ?: freshContact
                pubKey = performKeyExchangeGetKey(freshContact2) ?: ""
            }
            if (pubKey.isEmpty()) {
                enqueueForRetry(freshContact, plaintext, messageId, "TEXT")
                TorChatLogger.w(TAG, "📥 Kontakt nicht erreichbar — in Queue eingereiht")
                return false
            }
        }
        return try {
            val encrypted = keyManager.encryptMessage(plaintext, pubKey)
            val ok = sendTorMessage(freshContact.onionAddress, TorMessage(
                version          = PROTO_V,
                type             = "MSG",
                senderId         = keyManager.onionAddress,
                recipientId      = freshContact.onionAddress,
                messageId        = messageId,
                encryptedPayload = gson.toJson(encrypted),
                signature        = "",
                senderPublicKey  = keyManager.publicKeyBase64,  // immer mitsenden
                senderDisplayName = keyManager.displayName
            ))
            if (ok) {
                repository.updateMessageStatus(messageId, MessageStatus.SENT)
                repository.updateContactLastSeen(freshContact.id)
                TorChatLogger.i(TAG, "Gesendet ✅ an ${freshContact.name}")
            } else {
                enqueueForRetry(freshContact, plaintext, messageId, "TEXT")
                TorChatLogger.w(TAG, "📥 Senden fehlgeschlagen — in Queue")
            }
            ok
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "sendMessage: ${e.message}", e)
            enqueueForRetry(freshContact, plaintext, messageId, "TEXT")
            false
        }
    }

    private fun enqueueForRetry(
        contact:  Contact,
        plaintext: String,
        messageId: String,
        msgType:   String,
        filePath:  String? = null,
        fileName:  String? = null
    ) {
        messageQueue?.enqueue(MessageQueue.QueuedMessage(
            messageId    = messageId,
            contactId    = contact.id,
            onionAddress = contact.onionAddress,
            plaintext    = plaintext,
            messageType  = msgType,
            filePath     = filePath,
            fileName     = fileName
        ))
        // OFFLINE-Status setzen (rotes Häkchen)
        scope.launch {
            try { repository.updateMessageStatus(messageId, MessageStatus.OFFLINE) }
            catch (_: Exception) {}
        }
    }

    /**
     * Sendet eine Gruppenachricht an einen einzelnen Empfänger.
     * Wird vom ViewModel für jeden Empfänger einzeln aufgerufen.
     * groupId wird im Protokoll mitgeschickt damit der Empfänger
     * die Nachricht im richtigen Gruppenchat anzeigt.
     */
    suspend fun sendGroupMessage(
        recipient: Contact,
        text:      String,
        messageId: String,
        groupId:   String
    ): Boolean {
        // Frischen Kontakt aus DB laden
        val freshRecipient = repository.getContactById(recipient.id) ?: recipient
        var pubKey = freshRecipient.publicKeyBase64

        if (pubKey.isEmpty()) {
            pubKey = performKeyExchangeGetKey(freshRecipient) ?: ""
            if (pubKey.isEmpty()) {
                // In Queue mit Gruppen-Kontext
                // contactId = onionAddress damit flushQueueForContact direkt findet
                messageQueue?.enqueue(MessageQueue.QueuedMessage(
                    messageId    = messageId,
                    contactId    = freshRecipient.onionAddress,
                    onionAddress = freshRecipient.onionAddress,
                    plaintext    = text,
                    messageType  = "GROUP:$groupId"
                ))
                TorChatLogger.w(TAG, "Gruppe: ${recipient.name} offline — in Queue")
                return false
            }
        }

        return try {
            val encrypted = keyManager.encryptMessage(text, pubKey)
            val ok = sendTorMessage(freshRecipient.onionAddress, TorMessage(
                version          = PROTO_V,
                type             = "MSG",
                senderId         = keyManager.onionAddress,
                recipientId      = freshRecipient.onionAddress,
                messageId        = messageId,
                encryptedPayload = gson.toJson(encrypted),
                signature        = "",
                groupId          = groupId,
                senderPublicKey  = keyManager.publicKeyBase64,
                senderDisplayName = keyManager.displayName
            ))
            if (!ok) {
                messageQueue?.enqueue(MessageQueue.QueuedMessage(
                    messageId    = messageId,
                    contactId    = freshRecipient.onionAddress,
                    onionAddress = freshRecipient.onionAddress,
                    plaintext    = text,
                    messageType  = "GROUP:$groupId"
                ))
                TorChatLogger.w(TAG, "Gruppe: Senden fehlgeschlagen — in Queue")
            }
            ok
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "sendGroupMessage an ${recipient.name}: ${e.message}")
            messageQueue?.enqueue(MessageQueue.QueuedMessage(
                messageId    = messageId,
                contactId    = freshRecipient.onionAddress,
                onionAddress = freshRecipient.onionAddress,
                plaintext    = text,
                messageType  = "GROUP:$groupId"
            ))
            false
        }
    }

    // ── Adress-Synchronisation ───────────────────

    /**
     * Sendet neue Onion-Adresse an alle Kontakte.
     * Wird aufgerufen wenn die eigene Adresse sich geändert hat.
     */
    suspend fun broadcastNewAddress(
        oldAddress: String,
        newAddress: String,
        contacts:   List<Contact>
    ) {
        if (contacts.isEmpty()) return
        TorChatLogger.i(TAG, "Broadcast neue Adresse an ${contacts.size} Kontakte")

        val payload = gson.toJson(com.torchat.app.model.AddressUpdatePayload(
            oldOnionAddress = oldAddress,
            newOnionAddress = newAddress,
            displayName     = keyManager.transmittedDisplayName,
            publicKey       = keyManager.publicKeyBase64
        ))

        for (contact in contacts) {
            if (contact.isGroup) continue
            try {
                val ok = sendTorMessage(contact.onionAddress, TorMessage(
                    version          = PROTO_V,
                    type             = "ADDRESS_UPDATE",
                    senderId         = newAddress,
                    recipientId      = contact.onionAddress,
                    messageId        = java.util.UUID.randomUUID().toString(),
                    encryptedPayload = payload,
                    signature        = ""
                ))
                TorChatLogger.d(TAG,
                    "${if (ok) "✅" else "❌"} ADDRESS_UPDATE → ${contact.name}")
            } catch (e: Exception) {
                TorChatLogger.w(TAG, "broadcastNewAddress an ${contact.name}: ${e.message}")
            }
        }
    }

    private suspend fun handleAddressUpdate(msg: TorMessage, writer: PrintWriter) {
        TorChatLogger.i(TAG, "ADDRESS_UPDATE von ${msg.senderId.take(20)}")
        try {
            val payload = gson.fromJson(
                msg.encryptedPayload,
                com.torchat.app.model.AddressUpdatePayload::class.java
            ) ?: run {
                TorChatLogger.e(TAG, "ADDRESS_UPDATE: Payload konnte nicht geparst werden")
                return
            }

            val newOnion = payload.newOnionAddress.trim()
            val oldOnion = payload.oldOnionAddress.trim()

            if (newOnion.isEmpty()) {
                TorChatLogger.e(TAG, "ADDRESS_UPDATE: Neue Adresse leer")
                return
            }

            // ── Kontakt suchen: 4 Strategien ─────────────────────────────
            // 1. Alte Adresse aus Payload
            // 2. senderId (neue Adresse, falls Kontakt schon aktualisiert wurde)
            // 3. Payload-alte Adresse via senderId-Absender
            // 4. Fuzzy-Suche über ersten 20 Zeichen
            val contact =
                repository.getContactByOnion(oldOnion)
                ?: repository.getContactByOnion(msg.senderId)
                ?: repository.getContactByOnion(newOnion)
                ?: run {
                    TorChatLogger.w(TAG,
                        "ADDRESS_UPDATE: Kontakt nicht gefunden für " +
                        "oldOnion=${oldOnion.take(20)} senderId=${msg.senderId.take(20)}")
                    // Kontakt anlegen wenn unbekannt (kann bei erstem Kontakt passieren)
                    null
                }

            if (contact == null) {
                // Unbekannter Absender — ignorieren, KEY_EXCHANGE erledigt das
                writer.println(gson.toJson(TorMessage(
                    version = PROTO_V, type = "ACK",
                    senderId = keyManager.onionAddress, recipientId = msg.senderId,
                    messageId = msg.messageId, encryptedPayload = "", signature = ""
                )))
                return
            }

            // ── DB sofort aktualisieren ───────────────────────────────────
            val effectiveOld = contact.onionAddress
            val isAutoName = contact.name.matches(Regex("User \\d+")) ||
                contact.name == "TorChat User" || contact.name.isBlank()
            val updatedContact = contact.copy(
                onionAddress      = newOnion,
                publicKeyBase64   = payload.publicKey.ifEmpty { contact.publicKeyBase64 },
                name              = if (payload.displayName.isNotEmpty() && isAutoName)
                                        payload.displayName
                                    else contact.name,
                remoteDisplayName = payload.displayName.ifEmpty { contact.remoteDisplayName }
            )
            repository.updateContact(updatedContact)
            if (payload.displayName.isNotEmpty()) {
                repository.updateGroupMemberDisplayName(newOnion, payload.displayName)
            }
            TorChatLogger.i(TAG,
                "✅ DB aktualisiert: ${effectiveOld.take(16)} → ${newOnion.take(16)}")

            // ── MessageQueue migrieren ────────────────────────────────────
            // Alle Queue-Einträge mit alter Adresse auf neue Adresse umstellen
            messageQueue?.updateOnionAddress(effectiveOld, newOnion)
            if (effectiveOld != oldOnion) {
                // Auch Payload-alte Adresse migrieren falls abweichend
                messageQueue?.updateOnionAddress(oldOnion, newOnion)
            }

            // ── System-Nachricht im Chat ──────────────────────────────────
            if (effectiveOld != newOnion) {
                repository.saveMessage(com.torchat.app.model.Message(
                    contactId  = contact.id,
                    content    = "📍 ${contact.name} hat eine neue Adresse:\n$newOnion",
                    isOutgoing = false,
                    type       = com.torchat.app.model.MessageType.SYSTEM,
                    status     = com.torchat.app.model.MessageStatus.DELIVERED
                ))
            }

            // ── KEY_EXCHANGE mit neuer Adresse starten ────────────────────
            // Stellt sicher dass der Public Key für die neue Verbindung frisch ist
            scope.launch {
                try {
                    delay(1_000)  // Kurz warten bis Verbindung stabil
                    sendKeyExchangeTo(newOnion)
                    TorChatLogger.i(TAG, "🔑 KEY_EXCHANGE an neue Adresse gestartet: ${newOnion.take(20)}")
                } catch (e: Exception) {
                    TorChatLogger.w(TAG, "KEY_EXCHANGE nach ADDRESS_UPDATE: ${e.message}")
                }
            }

            // ── ACK zurücksenden ──────────────────────────────────────────
            writer.println(gson.toJson(TorMessage(
                version = PROTO_V, type = "ACK",
                senderId = keyManager.onionAddress,
                recipientId = msg.senderId,
                messageId = msg.messageId,
                encryptedPayload = "", signature = ""
            )))

        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleAddressUpdate: ${e.message}", e)
        }
    }

    private suspend fun performKeyExchange(contact: Contact): Boolean =
        performKeyExchangeGetKey(contact) != null

    /** Startet KEY_EXCHANGE wenn kein Public Key vorhanden oder Adresse unbekannt */
    suspend fun initiateKeyExchangeIfNeeded(contact: Contact) {
        try {
            TorChatLogger.d(TAG, "KEY_EXCHANGE → ${contact.name}")
            performKeyExchangeGetKey(contact)
        } catch (e: Exception) {
            TorChatLogger.d(TAG, "initiateKeyExchange ${contact.name}: ${e.message}")
        }
    }

    /**
     * KEY_EXCHANGE durchführen — gibt Public Key direkt zurück (kein DB-Reload nötig).
     */
    private suspend fun performKeyExchangeGetKey(contact: Contact): String? =
        withContext(Dispatchers.IO) {
            val socket = try {
                torProxy.connectToOnion(contact.onionAddress, PORT)
                    ?: return@withContext null.also {
                        TorChatLogger.e(TAG, "KEY_EXCHANGE: keine Verbindung zu ${contact.onionAddress.take(20)}")
                    }
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "performKeyExchangeGetKey Verbindung: ${e.message}", e)
                return@withContext null
            }
            try {
                socket.soTimeout = TIMEOUT_KEYX
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(gson.toJson(TorMessage(
                    version = PROTO_V, type = "KEY_EXCHANGE",
                    senderId = keyManager.onionAddress, recipientId = contact.onionAddress,
                    messageId = UUID.randomUUID().toString(),
                    encryptedPayload = gson.toJson(KeyExchangePayload(
                        publicKey    = keyManager.publicKeyBase64,
                        onionAddress = keyManager.onionAddress,
                        displayName  = keyManager.transmittedDisplayName
                    )),
                    signature = ""
                )))
                TorChatLogger.d(TAG, "KEY_EXCHANGE gesendet an ${contact.name}")

                val responseLine = try { reader.readLine() } catch (_: Exception) { null }

                if (responseLine != null) {
                    val response = runCatching {
                        gson.fromJson(responseLine, TorMessage::class.java)
                    }.getOrNull()
                    if (response?.type == "KEY_EXCHANGE") {
                        val payload = runCatching {
                            gson.fromJson(response.encryptedPayload, KeyExchangePayload::class.java)
                        }.getOrNull()
                        val pubKey = payload?.publicKey?.takeIf { it.isNotEmpty() }
                        if (pubKey != null) {
                            val confirmedOnion = payload.onionAddress.trim()
                                .let { if (it.endsWith(".onion")) it else "$it.onion" }

                            // Adresse im Kontakt aktualisieren wenn abweichend
                            val isAutoName = contact.name.matches(Regex("User \\d+")) ||
                                contact.name == "TorChat User" || contact.name.isBlank()
                            if (confirmedOnion.length >= 20 && confirmedOnion != contact.onionAddress) {
                                TorChatLogger.i(TAG,
                                    "Onion-Adresse aktualisiert: ${contact.onionAddress.take(16)} " +
                                    "→ ${confirmedOnion.take(16)}")
                                val oldOnion = contact.onionAddress
                                val newName = if (payload.displayName.isNotEmpty() && isAutoName)
                                    payload.displayName else contact.name
                                repository.updateContact(contact.copy(
                                    onionAddress      = confirmedOnion,
                                    publicKeyBase64   = pubKey,
                                    name              = newName,
                                    remoteDisplayName = payload.displayName.ifEmpty { contact.remoteDisplayName }
                                ))
                                messageQueue?.updateOnionAddress(oldOnion, confirmedOnion)
                                if (payload.displayName.isNotEmpty()) {
                                    repository.updateGroupMemberDisplayName(confirmedOnion, newName)
                                }
                            } else {
                                val newName = if (payload.displayName.isNotEmpty() && isAutoName)
                                    payload.displayName else contact.name
                                repository.updateContact(contact.copy(
                                    publicKeyBase64   = pubKey,
                                    name              = newName,
                                    remoteDisplayName = payload.displayName.ifEmpty { contact.remoteDisplayName }
                                ))
                                if (payload.displayName.isNotEmpty()) {
                                    repository.updateGroupMemberDisplayName(contact.onionAddress, newName)
                                }
                            }
                            TorChatLogger.i(TAG, "✅ Public Key von ${payload.displayName} erhalten")
                            return@withContext pubKey
                        }
                    }
                    TorChatLogger.w(TAG, "KEY_EXCHANGE Antwort: ${response?.type}")
                } else {
                    TorChatLogger.w(TAG, "KEY_EXCHANGE: keine Antwort")
                }
                null
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "performKeyExchangeGetKey: ${e.message}", e)
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    private suspend fun sendTorMessage(onionAddress: String, msg: TorMessage): Boolean =
        withContext(Dispatchers.IO) {
            val socket = try {
                torProxy.connectToOnion(onionAddress, PORT)
                    ?: return@withContext false.also {
                        TorChatLogger.e(TAG, "Keine Verbindung zu $onionAddress")
                    }
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "sendTorMessage Verbindung: ${e.message}", e)
                return@withContext false
            }
            try {
                socket.soTimeout = TIMEOUT
                PrintWriter(socket.getOutputStream(), true).println(gson.toJson(msg))
                try {
                    val responseStr = BufferedReader(InputStreamReader(socket.getInputStream()))
                        .readLine()
                    if (responseStr != null) {
                        try {
                            val response = gson.fromJson(responseStr, TorMessage::class.java)
                            when (response.type) {
                                "ACK" -> {
                                    TorChatLogger.d(TAG, "ACK erhalten")
                                    // Nachricht als DELIVERED markieren wenn ACK empfangen
                                    if (!msg.messageId.isNullOrEmpty() && msg.type in listOf("MSG", "FILE")) {
                                        scope.launch {
                                            try { repository.updateMessageStatus(msg.messageId, MessageStatus.DELIVERED) }
                                            catch (_: Exception) {}
                                        }
                                    }
                                }
                                else  -> TorChatLogger.d(TAG, "Antwort: ${response.type}")
                            }
                        } catch (_: Exception) {
                            TorChatLogger.d(TAG, "Antwort (raw): ${responseStr.take(60)}")
                        }
                    }
                } catch (_: Exception) {}
                TorChatLogger.d(TAG, "Gesendet ${msg.type} → ${onionAddress.take(20)}")
                true
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "sendTorMessage fehlgeschlagen: ${e.message}", e)
                false
            } finally {
                runCatching { socket.close() }
            }
        }

    // ── Datei-Übertragung ────────────────────────

    /**
     * Sendet eine Datei (Foto/Audio) verschlüsselt über Tor.
     * Datei wird als Base64 im JSON-Payload übertragen.
     * Max-Größe: 5MB (größere Dateien werden abgelehnt)
     */
    suspend fun sendFile(
        contact: Contact,
        filePath: String,
        fileName: String,
        messageType: MessageType,
        messageId: String,
        groupId: String = ""
    ): Boolean {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            TorChatLogger.e(TAG, "Datei nicht gefunden: $filePath")
            repository.updateMessageStatus(messageId, MessageStatus.FAILED)
            return false
        }
        val maxSize = 5 * 1024 * 1024L // 5MB
        if (file.length() > maxSize) {
            TorChatLogger.e(TAG, "Datei zu groß: ${file.length() / 1024}KB (max 5MB)")
            repository.updateMessageStatus(messageId, MessageStatus.FAILED)
            return false
        }

        TorChatLogger.i(TAG, "Sende ${messageType.name} '${fileName}' (${file.length()/1024}KB) an ${contact.name}")

        // Frischen Kontakt aus DB laden — für virtuelle Gruppen-Mitglieder: contact direkt verwenden
        var currentContact = if (contact.id.startsWith("grp_") && repository.getContactById(contact.id) == null) {
            contact
        } else {
            repository.getContactById(contact.id) ?: contact
        }

        if (currentContact.publicKeyBase64.isEmpty()) {
            TorChatLogger.d(TAG, "Kein Public Key → Key-Exchange zuerst")
            var resolvedKey = ""
            repeat(3) { attempt ->
                if (resolvedKey.isEmpty()) {
                    if (attempt > 0) delay(5000)
                    // Erst aus DB versuchen (für normale Kontakte)
                    val fresh = repository.getContactById(contact.id)
                    if (fresh?.publicKeyBase64?.isNotEmpty() == true) {
                        resolvedKey = fresh.publicKeyBase64
                        currentContact = fresh
                    } else {
                        // Key-Exchange direkt – gibt Key zurück auch für virtuelle Kontakte
                        val key = performKeyExchangeGetKey(currentContact)
                        if (key != null) {
                            resolvedKey = key
                            currentContact = currentContact.copy(publicKeyBase64 = key)
                        }
                    }
                }
            }
            if (resolvedKey.isEmpty()) {
                repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                return false
            }
        }

        return try {
            val fileBytes  = file.readBytes()
            val base64Data = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)
            val filePayload = gson.toJson(FilePayload(
                fileName  = fileName,
                mimeType  = when {
                    fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    fileName.endsWith(".png", ignoreCase = true)  -> "image/png"
                    fileName.endsWith(".m4a", ignoreCase = true)  -> "audio/m4a"
                    fileName.endsWith(".aac", ignoreCase = true)  -> "audio/aac"
                    else                                          -> "application/octet-stream"
                },
                data      = base64Data,
                size      = file.length(),
                msgType   = messageType.name
            ))

            val encrypted = keyManager.encryptMessage(filePayload, currentContact.publicKeyBase64)
            val torMsg = TorMessage(
                version          = PROTO_V,
                type             = "FILE",
                senderId         = keyManager.onionAddress,
                recipientId      = currentContact.onionAddress,
                messageId        = messageId,
                encryptedPayload = gson.toJson(encrypted),
                signature        = "",
                groupId          = groupId,          // ← Gruppen-Kontext mitsenden
                senderPublicKey  = keyManager.publicKeyBase64,
                senderDisplayName = keyManager.displayName
            )
            val ok = sendTorMessage(currentContact.onionAddress, torMsg)
            if (ok) {
                // ACK-Handler in sendTorMessage setzt DELIVERED falls ACK empfangen wurde.
                // Falls nicht (z.B. Timeout beim Lesen), setzen wir SENT als Fallback.
                repository.updateMessageStatus(messageId, MessageStatus.SENT)
                TorChatLogger.i(TAG, "Datei gesendet ✅")
            } else {
                enqueueForRetry(currentContact, "", messageId,
                    if (groupId.isEmpty()) messageType.name else "GROUP_FILE:$groupId",
                    filePath, fileName)
                TorChatLogger.w(TAG, "📥 Datei offline — in Queue")
            }
            ok
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "sendFile: ${e.message}", e)
            enqueueForRetry(currentContact, "", messageId,
                if (groupId.isEmpty()) messageType.name else "GROUP_FILE:$groupId",
                filePath, fileName)
            false
        }
    }

    private suspend fun handleIncomingFile(msg: TorMessage, writer: PrintWriter) {
        // Absender ermitteln – bei Gruppenachrichten keinen neuen Einzelkontakt anlegen
        val isGroupMsg = msg.groupId.isNotEmpty() &&
            repository.getContactById(msg.groupId)?.isGroup == true

        val contact = repository.getContactByOnion(msg.senderId) ?: run {
            if (isGroupMsg) {
                // Virtuellen Kontakt für Gruppenabsender bauen
                com.torchat.app.model.Contact(
                    id = "grp_" + java.security.MessageDigest.getInstance("SHA-256")
                        .digest(msg.senderId.toByteArray()).take(12)
                        .joinToString("") { "%02x".format(it) },
                    name = "Mitglied",
                    onionAddress = msg.senderId
                )
            } else {
                TorChatLogger.w(TAG, "FILE von unbekanntem Absender — lege Kontakt an")
                val autoName = msg.senderDisplayName.trim().ifEmpty {
                    repository.generateContactName(context.applicationContext)
                }
                val nc = com.torchat.app.model.Contact(
                    id = "auto_${msg.senderId.take(32).filter { it.isLetterOrDigit() }}",
                    name = autoName,
                    onionAddress = msg.senderId, addedAt = System.currentTimeMillis()
                )
                repository.addContact(nc)
                repository.getContactByOnion(msg.senderId) ?: return
            }
        }

        // Public Key: aus DB oder inline aus Nachricht
        val pubKey = contact.publicKeyBase64.ifEmpty {
            msg.senderPublicKey.trim().also { inline ->
                if (inline.isNotEmpty() && !isGroupMsg) {
                    repository.updateContactPublicKey(contact.id, inline)
                }
            }
        }

        try {
            val enc         = gson.fromJson(msg.encryptedPayload, EncryptedMessage::class.java)
            val jsonPayload = keyManager.decryptMessage(enc, pubKey)
            val payload     = gson.fromJson(jsonPayload, FilePayload::class.java)

            val cacheDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    if (payload.msgType == "IMAGE") android.os.Environment.DIRECTORY_PICTURES
                    else android.os.Environment.DIRECTORY_MUSIC
                ), "TorChat"
            ).also { it.mkdirs() }

            val saved = java.io.File(cacheDir, payload.fileName)
            saved.writeBytes(android.util.Base64.decode(payload.data, android.util.Base64.NO_WRAP))
            TorChatLogger.i(TAG, "📁 Gespeichert: ${saved.absolutePath}")

            // Ziel-Chat: Gruppe oder Einzelchat
            val targetContactId = if (isGroupMsg) msg.groupId else contact.id

            val msgType = try { MessageType.valueOf(payload.msgType) } catch (_: Exception) { MessageType.FILE }
            val message = Message(
                id = msg.messageId, contactId = targetContactId,
                content = if (msgType == MessageType.IMAGE) "📷 ${payload.fileName}" else "🎤 ${payload.fileName}",
                isOutgoing = false, timestamp = msg.timestamp, status = MessageStatus.DELIVERED,
                type = msgType, filePath = saved.absolutePath, fileSize = payload.size, fileName = payload.fileName,
                senderOnion = if (isGroupMsg) msg.senderId else ""
            )
            repository.saveMessage(message)
            _incoming.emit(message)
            TorChatLogger.i(TAG, "📨 ${payload.msgType} empfangen: ${payload.fileName}")
            writer.println(gson.toJson(TorMessage(version = PROTO_V, type = "ACK",
                senderId = keyManager.onionAddress, recipientId = msg.senderId,
                messageId = msg.messageId, encryptedPayload = "", signature = "")))
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleIncomingFile: ${e.message}", e)
        }
    }

    suspend fun pingContact(contact: Contact): Boolean {
        TorChatLogger.d(TAG, "PING → ${contact.name}")
        return sendTorMessage(contact.onionAddress, TorMessage(
            version = PROTO_V, type = "PING",
            senderId = keyManager.onionAddress, recipientId = contact.onionAddress,
            messageId = UUID.randomUUID().toString(), encryptedPayload = "", signature = ""
        ))
    }

    /**
     * Empfänger löscht die Nachricht beim Empfang eines MSG_DELETE.
     * Payload = messageId der zu löschenden Nachricht.
     */
    private suspend fun handleRemoteDelete(msg: TorMessage) {
        val targetId = msg.encryptedPayload  // messageId im Payload
        if (targetId.isBlank()) return
        try {
            repository.deleteMessageById(targetId)
            _remoteDeleted.emit(targetId)
            TorChatLogger.i(TAG, "🗑 Remote-Löschen: $targetId")
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleRemoteDelete: ${e.message}", e)
        }
    }

    /**
     * Sendet MSG_DELETE an den Kontakt — löscht Nachricht beim Empfänger.
     */
    suspend fun sendDeleteForAll(contact: Contact, messageId: String, deleteLocal: Boolean = true): Boolean {
        val ok = sendTorMessage(contact.onionAddress, TorMessage(
            version = PROTO_V, type = "MSG_DELETE",
            senderId = keyManager.onionAddress, recipientId = contact.onionAddress,
            messageId = UUID.randomUUID().toString(),
            encryptedPayload = messageId,
            signature = ""
        ))
        if (ok && deleteLocal) {
            repository.deleteMessageById(messageId)
            TorChatLogger.i(TAG, "🗑 Für alle gelöscht: $messageId")
        }
        return ok
    }

    fun stopListening() {
        runCatching { server?.close() }
        server = null
        TorChatLogger.i(TAG, "P2P Server gestoppt")
    }

    // ── Gruppe-Einladung senden ─────────────────────────────────────

    /**
     * Sendet eine TorMessage direkt mit einem vorbereiteten Payload-String.
     * Wird von flushQueue für GROUP_INVITE verwendet.
     */
    suspend fun sendTorMessageDirect(
        onionAddress: String,
        type:         String,
        messageId:    String,
        payload:      String
    ): Boolean = sendTorMessage(onionAddress, TorMessage(
        version          = PROTO_V,
        type             = type,
        senderId         = keyManager.onionAddress,
        recipientId      = onionAddress,
        messageId        = messageId,
        encryptedPayload = payload,
        signature        = "",
        senderPublicKey  = keyManager.publicKeyBase64,
        senderDisplayName = keyManager.displayName
    ))

    /**
     * Sendet GROUP_INVITE an alle Mitglieder.
     * Wird aufgerufen wenn eine neue Gruppe erstellt wird.
     */
    suspend fun sendGroupInvite(
        group:   com.torchat.app.model.Contact,
        members: List<com.torchat.app.model.GroupMember>
    ) {
        if (members.isEmpty()) return

        // Vollständige Mitglieder-Info für Payload zusammenstellen
        // Ersteller (self) ebenfalls einfügen falls noch nicht enthalten
        val creatorAlreadyIncluded = members.any { it.onionAddress == keyManager.onionAddress }
        val allMembers = if (creatorAlreadyIncluded) members else {
            listOf(
                com.torchat.app.model.GroupMember(
                    groupId      = group.id,
                    contactId    = "self",
                    onionAddress = keyManager.onionAddress,
                    displayName  = keyManager.transmittedDisplayName,
                    isAdmin      = true
                )
            ) + members
        }
        val memberInfoList = allMembers.map { m ->
            com.torchat.app.model.GroupMemberInfo(
                contactId    = m.contactId,
                onionAddress = m.onionAddress,
                displayName  = m.displayName,
                isAdmin      = m.isAdmin
            )
        }

        val payload = gson.toJson(com.torchat.app.model.GroupInvitePayload(
            groupId      = group.id,
            groupName    = group.name,
            avatarColor  = group.avatarColor,
            creatorOnion = keyManager.onionAddress,
            creatorName  = keyManager.transmittedDisplayName,
            members      = memberInfoList,
            createdAt    = group.addedAt
        ))

        // messageType-Schlüssel für Queue: "GROUP_INVITE:<groupId>"
        // Payload wird als plaintext gespeichert damit flushQueue ihn erneut senden kann
        val queueType = "GROUP_INVITE:${group.id}"

        var successCount = 0
        // Nur an echte Empfänger senden (nicht an sich selbst)
        val recipients = members.filter { it.onionAddress != keyManager.onionAddress && it.contactId != "self" }
        recipients.forEach { member ->
            try {
                val msgId = UUID.randomUUID().toString()
                val ok = sendTorMessage(member.onionAddress, TorMessage(
                    version          = PROTO_V,
                    type             = "GROUP_INVITE",
                    senderId         = keyManager.onionAddress,
                    recipientId      = member.onionAddress,
                    messageId        = msgId,
                    encryptedPayload = payload,
                    signature        = "",
                    senderPublicKey  = keyManager.publicKeyBase64,
                    senderDisplayName = keyManager.displayName
                ))
                if (ok) {
                    successCount++
                    TorChatLogger.d(TAG, "GROUP_INVITE → ${member.displayName}: ✅")
                } else {
                    // Offline → in Queue einstellen
                    messageQueue?.enqueue(MessageQueue.QueuedMessage(
                        messageId    = msgId,
                        contactId    = member.onionAddress,  // onionAddress als contactId für flush-Lookup
                        onionAddress = member.onionAddress,
                        plaintext    = payload,          // JSON-Payload als plaintext
                        messageType  = queueType
                    ))
                    TorChatLogger.w(TAG, "GROUP_INVITE → ${member.displayName}: offline, in Queue")
                }
            } catch (e: Exception) {
                TorChatLogger.w(TAG, "GROUP_INVITE an ${member.displayName}: ${e.message}")
            }
        }
        TorChatLogger.i(TAG, "GROUP_INVITE gesendet: $successCount/${recipients.size} Mitglieder erreicht")
    }

    // ── Gruppe-Einladung empfangen ──────────────────────────────────

    /**
     * Verarbeitet eine eingehende GROUP_INVITE Nachricht.
     * Legt Gruppe + Mitglieder in der lokalen DB an.
     */
    private suspend fun handleGroupInvite(msg: TorMessage, writer: PrintWriter) {
        TorChatLogger.i(TAG, "📨 GROUP_INVITE empfangen von ${msg.senderId.take(20)}")
        try {
            // ── Payload parsen ────────────────────────────────────────
            if (msg.encryptedPayload.isBlank()) {
                TorChatLogger.e(TAG, "GROUP_INVITE: leerer Payload")
                return
            }

            val payload = runCatching {
                gson.fromJson(msg.encryptedPayload,
                    com.torchat.app.model.GroupInvitePayload::class.java)
            }.getOrNull()

            if (payload == null) {
                TorChatLogger.e(TAG, "GROUP_INVITE: JSON-Parse fehlgeschlagen: ${msg.encryptedPayload.take(80)}")
                return
            }

            val groupId   = payload.groupId?.trim()   ?: ""
            val groupName = payload.groupName?.trim()  ?: ""

            if (groupId.isEmpty()) {
                TorChatLogger.e(TAG, "GROUP_INVITE: groupId fehlt")
                return
            }
            if (groupName.isEmpty()) {
                TorChatLogger.e(TAG, "GROUP_INVITE: groupName fehlt")
                return
            }

            TorChatLogger.i(TAG, "GROUP_INVITE: Gruppe=\"$groupName\" id=${groupId.take(12)} " +
                "Mitglieder=${payload.members?.size ?: 0}")

            // ── Gruppe bereits vorhanden? ─────────────────────────────
            val existing = repository.getContactById(groupId)
            if (existing != null && existing.isGroup) {
                TorChatLogger.d(TAG, "GROUP_INVITE: Gruppe bereits vorhanden → ACK")
                writer.println(gson.toJson(TorMessage(
                    version = PROTO_V, type = "ACK",
                    senderId = keyManager.onionAddress, recipientId = msg.senderId,
                    messageId = msg.messageId
                )))
                return
            }

            // ── Gruppe anlegen ────────────────────────────────────────
            val memberCount = payload.members?.size ?: 0
            val group = com.torchat.app.model.Contact(
                id           = groupId,
                name         = groupName,
                onionAddress = "group-${groupId.take(12)}.onion",
                isGroup      = true,
                memberCount  = memberCount,
                avatarColor  = payload.avatarColor?.ifEmpty { "#a855f7" } ?: "#a855f7",
                addedAt      = payload.createdAt
            )
            repository.addContact(group)
            TorChatLogger.i(TAG, "✅ Gruppe \"$groupName\" in DB angelegt")

            // ── Mitglieder anlegen ────────────────────────────────────
            // WICHTIG: Es werden KEINE neuen Einzelkontakte angelegt.
            // Jedes Mitglied erhält nur einen group_members-Eintrag.
            // Eigene Onion-Adresse → contactId = "self"
            val myOwnOnion = keyManager.onionAddress
            val members = payload.members ?: emptyList()
            val groupMemberList = members.mapNotNull { info ->
                val onion = info.onionAddress?.trim() ?: return@mapNotNull null
                if (onion.isEmpty()) return@mapNotNull null

                // Eigene Adresse: als "self" markieren, kein Kontakt-Lookup nötig
                val contactId = if (onion == myOwnOnion) {
                    "self"
                } else {
                    // Nur bereits vorhandene Kontakte zuordnen – KEINEN neuen anlegen
                    repository.getContactByOnion(onion)?.id
                        ?: ("grp_" + java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(onion.toByteArray())
                            .take(12).joinToString("") { "%02x".format(it) })
                }

                com.torchat.app.model.GroupMember(
                    groupId      = groupId,
                    contactId    = contactId,
                    onionAddress = onion,
                    displayName  = info.displayName?.ifEmpty { "Mitglied" } ?: "Mitglied",
                    isAdmin      = info.isAdmin
                )
            }

            if (groupMemberList.isNotEmpty()) {
                repository.addGroupMembers(groupMemberList)
                TorChatLogger.i(TAG, "${groupMemberList.size} Mitglieder angelegt")
            }

            // ── System-Nachricht ──────────────────────────────────────
            val creatorName = payload.creatorName?.ifEmpty { "Unbekannt" } ?: "Unbekannt"
            val sysMsg = Message(
                id         = java.util.UUID.randomUUID().toString(),
                contactId  = groupId,
                content    = "$creatorName hat die Gruppe \"$groupName\" erstellt",
                isOutgoing = false,
                type       = MessageType.SYSTEM,
                status     = MessageStatus.READ
            )
            repository.saveMessage(sysMsg)

            // ── Absender-Key speichern ────────────────────────────────
            val inlineKey = msg.senderPublicKey.trim()
            if (inlineKey.isNotEmpty()) {
                val sender = repository.getContactByOnion(msg.senderId)
                if (sender != null && sender.publicKeyBase64.isEmpty()) {
                    repository.updateContactPublicKey(sender.id, inlineKey)
                }
            }

            // ── UI-Flow triggern → Chatliste aktualisiert sich ────────
            _incoming.emit(sysMsg)
            TorChatLogger.i(TAG, "✅ GROUP_INVITE vollständig verarbeitet: \"$groupName\"")

            // ── ACK ───────────────────────────────────────────────────
            writer.println(gson.toJson(TorMessage(
                version = PROTO_V, type = "ACK",
                senderId = keyManager.onionAddress, recipientId = msg.senderId,
                messageId = msg.messageId
            )))

        } catch (e: Exception) {
            TorChatLogger.e(TAG, "handleGroupInvite FEHLER: ${e.message}", e)
        }
    }
}

data class FilePayload(
    val fileName: String,
    val mimeType: String,
    val data:     String,   // Base64
    val size:     Long,
    val msgType:  String    // "IMAGE" oder "FILE"
)
