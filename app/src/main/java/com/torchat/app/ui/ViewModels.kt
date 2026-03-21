package com.torchat.app.ui

import android.app.Application
import androidx.lifecycle.*
import com.torchat.app.TorChatApp
import com.torchat.app.data.ChatRepository
import com.torchat.app.debug.TorChatLogger
import com.torchat.app.model.*
import com.torchat.app.network.TorMessagingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

// ─────────────────────────────────────────────
// Chat List ViewModel
// ─────────────────────────────────────────────
class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    // lazy → kein Crash wenn app noch nicht vollständig initialisiert
    private val app by lazy { application as TorChatApp }
    private val repository by lazy { ChatRepository(app.database) }

    val contacts: StateFlow<List<Contact>> by lazy {
        repository.getAllContacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    val torStatus get() = try { app.torProxyManager.torStatus }
                          catch (_: Exception) { MutableStateFlow(com.torchat.app.network.TorStatus.DISCONNECTED) }

    val myOnionAddress: String get() = try { app.keyManager.onionAddress } catch (_: Exception) { "" }

    fun startService() = try {
        TorMessagingService.start(getApplication())
    } catch (e: Exception) {
        TorChatLogger.w("ChatListVM", "Service start: ${e.message}")
    }

    val blockedContacts: StateFlow<List<Contact>> by lazy {
        repository.blockedContacts
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun blockContact(contact: Contact) {
        viewModelScope.launch {
            try { repository.blockContact(contact.id)
                  TorChatLogger.i("ChatListVM", "Kontakt blockiert: ${contact.name}") }
            catch (e: Exception) { TorChatLogger.e("ChatListVM", "block: ${e.message}", e) }
        }
    }

    fun unblockContact(contact: Contact) {
        viewModelScope.launch {
            try { repository.unblockContact(contact.id)
                  TorChatLogger.i("ChatListVM", "Kontakt entsperrt: ${contact.name}") }
            catch (e: Exception) { TorChatLogger.e("ChatListVM", "unblock: ${e.message}", e) }
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            try {
                repository.deleteContactChat(contact.id)
                if (contact.isGroup) repository.deleteGroupMembers(contact.id)
                repository.deleteContact(contact)
                TorChatLogger.i("ChatListVM", "Kontakt gelöscht: ${contact.name}")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "delete: ${e.message}", e) }
        }
    }

    // ── Gruppenverwaltung ─────────────────────────────────────────

    fun getGroupMembers(groupId: String, callback: (List<com.torchat.app.model.GroupMember>) -> Unit) {
        viewModelScope.launch {
            try { callback(repository.getGroupMembers(groupId)) }
            catch (e: Exception) { TorChatLogger.e("ChatListVM", "getGroupMembers: ${e.message}"); callback(emptyList()) }
        }
    }

    fun getGroupMembersFlow(groupId: String) = repository.getGroupMembersFlow(groupId)

    /** Kontakt zur Gruppe hinzufügen */
    fun addMemberToGroup(group: Contact, contact: Contact) {
        viewModelScope.launch {
            try {
                val existing = repository.getGroupMember(group.id, contact.id)
                if (existing != null) {
                    TorChatLogger.w("ChatListVM", "${contact.name} ist bereits Mitglied")
                    return@launch
                }
                val newMember = com.torchat.app.model.GroupMember(
                    groupId      = group.id,
                    contactId    = contact.id,
                    onionAddress = contact.onionAddress,
                    displayName  = contact.name,
                    isAdmin      = false
                )
                repository.addGroupMember(newMember)
                val count = repository.getGroupMembers(group.id).size
                val updatedGroup = group.copy(memberCount = count)
                repository.updateContact(updatedGroup)
                repository.saveMessage(com.torchat.app.model.Message(
                    contactId  = group.id,
                    content    = "${contact.name} wurde zur Gruppe hinzugefügt",
                    isOutgoing = false,
                    type       = com.torchat.app.model.MessageType.SYSTEM,
                    status     = com.torchat.app.model.MessageStatus.READ
                ))
                // GROUP_INVITE mit allen aktuellen Mitgliedern an das neue Mitglied senden
                val allMembers = repository.getGroupMembers(group.id)
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (engine != null) {
                    try {
                        engine.sendGroupInvite(updatedGroup, allMembers)
                    } catch (e: Exception) {
                        TorChatLogger.w("ChatListVM", "GROUP_INVITE für neues Mitglied: ${e.message}")
                    }
                }
                TorChatLogger.i("ChatListVM", "${contact.name} zu Gruppe ${group.name} hinzugefügt")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "addMember: ${e.message}", e) }
        }
    }

    /** Mitglied aus Gruppe entfernen */
    fun removeMemberFromGroup(group: Contact, member: com.torchat.app.model.GroupMember) {
        viewModelScope.launch {
            try {
                repository.removeGroupMember(group.id, member.contactId)
                val count = repository.getGroupMembers(group.id).size
                repository.updateContact(group.copy(memberCount = count))
                repository.saveMessage(com.torchat.app.model.Message(
                    contactId  = group.id,
                    content    = "${member.displayName.ifEmpty { "Mitglied" }} wurde aus der Gruppe entfernt",
                    isOutgoing = false,
                    type       = com.torchat.app.model.MessageType.SYSTEM,
                    status     = com.torchat.app.model.MessageStatus.READ
                ))
                TorChatLogger.i("ChatListVM", "${member.displayName} aus Gruppe ${group.name} entfernt")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "removeMember: ${e.message}", e) }
        }
    }

    /** Mitglied in Gruppe blockieren */
    fun blockMemberInGroup(group: Contact, member: com.torchat.app.model.GroupMember) {
        viewModelScope.launch {
            try {
                repository.setGroupMemberBlocked(group.id, member.contactId, true)
                repository.saveMessage(com.torchat.app.model.Message(
                    contactId  = group.id,
                    content    = "${member.displayName.ifEmpty { "Mitglied" }} wurde in der Gruppe blockiert",
                    isOutgoing = false,
                    type       = com.torchat.app.model.MessageType.SYSTEM,
                    status     = com.torchat.app.model.MessageStatus.READ
                ))
                TorChatLogger.i("ChatListVM", "${member.displayName} in Gruppe ${group.name} blockiert")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "blockMember: ${e.message}", e) }
        }
    }

    /** Mitglied in Gruppe entsperren */
    fun unblockMemberInGroup(group: Contact, member: com.torchat.app.model.GroupMember) {
        viewModelScope.launch {
            try {
                repository.setGroupMemberBlocked(group.id, member.contactId, false)
                TorChatLogger.i("ChatListVM", "${member.displayName} in Gruppe ${group.name} entsperrt")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "unblockMember: ${e.message}", e) }
        }
    }

    /** Admin-Status setzen/entfernen */
    fun setMemberAdmin(group: Contact, member: com.torchat.app.model.GroupMember, isAdmin: Boolean) {
        viewModelScope.launch {
            try {
                repository.setGroupMemberAdmin(group.id, member.contactId, isAdmin)
                val action = if (isAdmin) "zum Admin ernannt" else "Admin-Status entfernt"
                repository.saveMessage(com.torchat.app.model.Message(
                    contactId  = group.id,
                    content    = "${member.displayName.ifEmpty { "Mitglied" }} wurde $action",
                    isOutgoing = false,
                    type       = com.torchat.app.model.MessageType.SYSTEM,
                    status     = com.torchat.app.model.MessageStatus.READ
                ))
                TorChatLogger.i("ChatListVM", "${member.displayName} $action in ${group.name}")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "setAdmin: ${e.message}", e) }
        }
    }

    fun renameContact(contact: Contact, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.updateContact(contact.copy(name = trimmed))
                TorChatLogger.i("ChatListVM", "Kontakt umbenannt: ${contact.name} → $trimmed")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "rename: ${e.message}", e) }
        }
    }

    fun addContact(name: String, onionAddress: String) {
        viewModelScope.launch {
            try {
                val normalized = onionAddress.trim().lowercase()
                    .let { if (it.endsWith(".onion")) it else "$it.onion" }
                // Selbst-Eintrag verhindern
                val myOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                if (normalized == myOnion || normalized.removeSuffix(".onion") == myOnion.removeSuffix(".onion")) {
                    TorChatLogger.w("ChatListVM", "addContact: Selbst-Eintrag verhindert")
                    return@launch
                }
                // Duplikat-Check: existiert Adresse schon?
                val existing = repository.getContactByOnion(normalized)
                if (existing != null) {
                    TorChatLogger.w("ChatListVM", "addContact: ${existing.name} bereits vorhanden")
                    return@launch
                }
                val contact = Contact(
                    name = name.trim().ifBlank { "Kontakt" },
                    onionAddress = normalized,
                    isGroup = false
                )
                repository.addContact(contact)
                TorChatLogger.i("ChatListVM", "Kontakt hinzugefügt: ${contact.name}")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "addContact: ${e.message}", e) }
        }
    }

    fun createGroup(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            try {
                // Kontakte der Mitglieder laden
                val memberContacts = memberIds.mapNotNull { id ->
                    try { repository.getContactById(id) } catch (_: Exception) { null }
                }.filter { !it.isGroup }

                val myOnion       = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                val myDisplayName = try { app.keyManager.displayName }  catch (_: Exception) { "Admin" }

                val group = Contact(
                    name         = name,
                    onionAddress = "group-${UUID.randomUUID().toString().take(12)}.onion",
                    isGroup      = true,
                    memberCount  = memberContacts.size + 1,   // +1 für Ersteller
                    avatarColor  = "#a855f7"
                )
                repository.addContact(group)

                // Mitglieder in group_members Tabelle speichern
                // Ersteller (ich) = Admin, alle anderen = normale Mitglieder
                val creatorMember = com.torchat.app.model.GroupMember(
                    groupId      = group.id,
                    contactId    = "self",
                    onionAddress = myOnion,
                    displayName  = myDisplayName,
                    isAdmin      = true
                )
                val members = memberContacts.map { contact ->
                    com.torchat.app.model.GroupMember(
                        groupId      = group.id,
                        contactId    = contact.id,
                        onionAddress = contact.onionAddress,
                        displayName  = contact.name,
                        isAdmin      = false
                    )
                }
                repository.addGroupMembers(listOf(creatorMember) + members)

                // System-Nachricht lokal
                val names = memberContacts.joinToString(", ") { it.name }
                repository.saveMessage(Message(
                    contactId  = group.id,
                    content    = "Gruppe \"$name\" erstellt mit ${memberContacts.size} Mitgliedern: $names",
                    isOutgoing = false,
                    type       = MessageType.SYSTEM,
                    status     = MessageStatus.READ
                ))

                // GROUP_INVITE an alle Mitglieder senden
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (engine != null && members.isNotEmpty()) {
                    try {
                        engine.sendGroupInvite(group, members)
                    } catch (e: Exception) {
                        TorChatLogger.w("ChatListVM", "GROUP_INVITE Fehler: ${e.message}")
                    }
                }

                // KEY_EXCHANGE mit allen Mitgliedern starten (falls noch kein Key)
                memberContacts.forEach { contact ->
                    if (contact.publicKeyBase64.isEmpty()) {
                        try { engine?.initiateKeyExchangeIfNeeded(contact) }
                        catch (_: Exception) {}
                    }
                }

                TorChatLogger.i("ChatListVM", "✅ Gruppe \"$name\" erstellt und GROUP_INVITE gesendet")
            } catch (e: Exception) { TorChatLogger.e("ChatListVM", "createGroup: ${e.message}", e) }
        }
    }
}

// ─────────────────────────────────────────────
// Chat ViewModel
// ─────────────────────────────────────────────
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app by lazy { application as TorChatApp }
    private val repository by lazy { ChatRepository(app.database) }

    private val _contactId = MutableStateFlow<String?>(null)
    val contactId: StateFlow<String?> = _contactId

    // Alle Kontakte (für Weiterleiten-Dialog)
    val contacts: StateFlow<List<com.torchat.app.model.Contact>> by lazy {
        repository.getAllContacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    // Text der ins Eingabefeld eingefügt werden soll
    fun setPasteText(text: String) {
        viewModelScope.launch { _inputAppend.emit(text) }
    }
    fun clearPasteText() {} // no-op: SharedFlow auto-clears

    val messages: StateFlow<List<Message>> by lazy {
        _contactId
            .filterNotNull()
            .flatMapLatest { id -> repository.getMessages(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun setContact(contactId: String) {
        _contactId.value = contactId
        viewModelScope.launch {
            try { repository.markAllRead(contactId) }
            catch (e: Exception) { TorChatLogger.w("ChatVM", "markAllRead: ${e.message}") }
        }
    }

    /** Nachricht an anderen Kontakt weiterleiten */
    fun forwardMessage(target: com.torchat.app.model.Contact, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                val messageId = UUID.randomUUID().toString()
                val ctx = getApplication<Application>()
                val disappearing = com.torchat.app.data.SettingsManager.isDisappearingEnabled(ctx)
                val disappearsAt = if (disappearing)
                    System.currentTimeMillis() +
                    com.torchat.app.data.SettingsManager.getDisappearingSeconds(ctx) * 1000L
                else null
                val fwdText = "↪ $text"
                repository.saveMessage(Message(
                    id = messageId, contactId = target.id,
                    content = fwdText, isOutgoing = true,
                    status = MessageStatus.SENDING,
                    disappearsAt = disappearsAt
                ))
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (target.isGroup) {
                    // Gruppenweiterleitung: an alle Mitglieder senden
                    val myOwnOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                    val members = repository.getActiveGroupMembers(target.id)
                        .filter { it.contactId != "self" && it.onionAddress != myOwnOnion }
                    members.forEach { member ->
                        if (engine == null) return@forEach
                        val memberContact = repository.getContactById(member.contactId)
                            ?: repository.getContactByOnion(member.onionAddress)
                            ?: com.torchat.app.model.Contact(
                                id = member.contactId, name = member.displayName,
                                onionAddress = member.onionAddress)
                        try {
                            engine.sendGroupMessage(memberContact, fwdText,
                                "${messageId}_${member.contactId}", target.id)
                        } catch (_: Exception) {}
                    }
                    val sent = members.isNotEmpty()
                    repository.updateMessageStatus(messageId,
                        if (sent) MessageStatus.SENT else MessageStatus.FAILED)
                } else {
                    val ok = engine?.sendMessage(target, fwdText, messageId) ?: false
                    if (!ok) repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                }
                TorChatLogger.i("ChatVM", "Weitergeleitet an ${target.name}")
            } catch (e: Exception) {
                TorChatLogger.e("ChatVM", "forwardMessage: ${e.message}", e)
            }
        }
    }

    fun sendMessage(contact: Contact, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                val messageId = UUID.randomUUID().toString()
                val ctx = getApplication<Application>()
                val disappearing = com.torchat.app.data.SettingsManager.isDisappearingEnabled(ctx)
                val disappearsAt = if (disappearing)
                    System.currentTimeMillis() +
                    com.torchat.app.data.SettingsManager.getDisappearingSeconds(ctx) * 1000L
                else null

                // Nachricht im Gruppenchat speichern
                repository.saveMessage(Message(
                    id = messageId, contactId = contact.id,
                    content = text, isOutgoing = true,
                    status = MessageStatus.SENDING,
                    disappearsAt = disappearsAt
                ))

                val engine = try { app.messagingEngine } catch (_: Exception) { null }

                if (contact.isGroup) {
                    // Gruppe: nur aktive (nicht blockierte) Mitglieder, eigene Adresse ausschließen
                    val myOwnOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                    val members = repository.getActiveGroupMembers(contact.id)
                        .filter { it.contactId != "self" && it.onionAddress != myOwnOnion }
                    if (members.isEmpty()) {
                        TorChatLogger.w("ChatVM", "Gruppe ${contact.name} hat keine Mitglieder")
                        repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                        return@launch
                    }
                    var sentCount = 0
                    members.forEach { member ->
                        if (engine == null) return@forEach
                        // Kontakt aus DB holen falls vorhanden, sonst synthetischen Contact aus GroupMember bauen
                        val memberContact = repository.getContactById(member.contactId)
                            ?: repository.getContactByOnion(member.onionAddress)
                            ?: com.torchat.app.model.Contact(
                                id           = member.contactId,
                                name         = member.displayName,
                                onionAddress = member.onionAddress
                            )
                        val memberMsgId = "${messageId}_${member.contactId}"
                        try {
                            engine.sendGroupMessage(
                                recipient = memberContact,
                                text      = text,
                                messageId = memberMsgId,
                                groupId   = contact.id
                            )
                            sentCount++
                        } catch (e: Exception) {
                            TorChatLogger.w("ChatVM", "Gruppe: Senden an ${memberContact.name} fehlgeschlagen: ${e.message}")
                        }
                    }
                    // Status: DELIVERED wenn alle erreicht, SENT wenn teilweise, FAILED wenn keine
                    val finalStatus = when {
                        sentCount == members.size -> MessageStatus.DELIVERED
                        sentCount > 0             -> MessageStatus.SENT
                        else                      -> MessageStatus.FAILED
                    }
                    repository.updateMessageStatus(messageId, finalStatus)
                    TorChatLogger.i("ChatVM", "Gruppennachricht an $sentCount/${members.size} Mitglieder gesendet")

                } else {
                    // ── Einzelchat: wie bisher ────────────────────────────────
                    if (engine != null) {
                        val ok = engine.sendMessage(contact, text, messageId)
                        if (!ok) repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    } else {
                        TorChatLogger.w("ChatVM", "MessagingEngine nicht verfügbar")
                        repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    }
                }
            } catch (e: Exception) {
                TorChatLogger.e("ChatVM", "sendMessage: ${e.message}", e)
            }
        }
    }

    /** Nachricht nur lokal löschen */
    fun deleteForMe(messageId: String) {
        viewModelScope.launch {
            try { repository.deleteMessageById(messageId) }
            catch (e: Exception) { TorChatLogger.e("ChatVM", "deleteForMe: ${e.message}", e) }
        }
    }

    /** Nachricht lokal und beim Empfänger löschen */
    fun deleteForAll(contact: Contact, messageId: String) {
        viewModelScope.launch {
            try {
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (engine != null) {
                    if (contact.isGroup) {
                        // Bei Gruppen: MSG_DELETE an alle Mitglieder senden, dann lokal löschen
                        val myOwnOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                        val members = repository.getActiveGroupMembers(contact.id)
                            .filter { it.contactId != "self" && it.onionAddress != myOwnOnion }
                        members.forEach { member ->
                            val memberContact = repository.getContactById(member.contactId)
                                ?: repository.getContactByOnion(member.onionAddress)
                                ?: com.torchat.app.model.Contact(
                                    id = member.contactId, name = member.displayName,
                                    onionAddress = member.onionAddress)
                            try { engine.sendDeleteForAll(memberContact, messageId, deleteLocal = false) }
                            catch (_: Exception) {}
                        }
                        // Lokal nur einmal löschen (nicht per sendDeleteForAll)
                        repository.deleteMessageById(messageId)
                    } else {
                        engine.sendDeleteForAll(contact, messageId)
                    }
                } else {
                    repository.deleteMessageById(messageId)
                }
            } catch (e: Exception) {
                TorChatLogger.e("ChatVM", "deleteForAll: ${e.message}", e)
            }
        }
    }

    /** Flow für "Einfügen"-Aktion: Text ins Eingabefeld übertragen */
    private val _inputAppend = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 0)
    val inputAppend: kotlinx.coroutines.flow.SharedFlow<String> = _inputAppend

    fun appendToInput(text: String) {
        viewModelScope.launch { _inputAppend.emit(text) }
    }

    fun sendPhoto(contact: Contact, filePath: String, fileName: String) {
        viewModelScope.launch {
            try {
                val messageId = UUID.randomUUID().toString()
                repository.saveMessage(Message(
                    id = messageId, contactId = contact.id,
                    content = "📷 $fileName", isOutgoing = true,
                    type = MessageType.IMAGE, filePath = filePath,
                    fileSize = java.io.File(filePath).length(), fileName = fileName,
                    status = MessageStatus.SENDING
                ))
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (engine == null) {
                    repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    TorChatLogger.w("ChatVM", "Engine nicht verfügbar für Foto")
                    return@launch
                }
                if (contact.isGroup) {
                    // Foto an alle Gruppenmitglieder senden
                    val myOwnOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                    val members = repository.getActiveGroupMembers(contact.id)
                        .filter { it.contactId != "self" && it.onionAddress != myOwnOnion }
                    var sentCount = 0
                    members.forEach { member ->
                        val memberContact = repository.getContactById(member.contactId)
                            ?: repository.getContactByOnion(member.onionAddress)
                            ?: com.torchat.app.model.Contact(
                                id = member.contactId, name = member.displayName,
                                onionAddress = member.onionAddress)
                        try {
                            val ok = engine.sendFile(memberContact, filePath, fileName,
                                MessageType.IMAGE, "${messageId}_${member.contactId}",
                                groupId = contact.id)
                            if (ok) sentCount++
                        } catch (_: Exception) {}
                    }
                    repository.updateMessageStatus(messageId, when {
                        sentCount == members.size -> MessageStatus.DELIVERED
                        sentCount > 0             -> MessageStatus.SENT
                        else                      -> MessageStatus.FAILED
                    })
                } else {
                    engine.sendFile(contact, filePath, fileName, MessageType.IMAGE, messageId)
                }
            } catch (e: Exception) { TorChatLogger.e("ChatVM", "sendPhoto: ${e.message}", e) }
        }
    }

    fun sendAudio(contact: Contact, filePath: String, durationMs: Long) {
        viewModelScope.launch {
            try {
                val messageId = UUID.randomUUID().toString()
                val fileName  = "audio_${System.currentTimeMillis()}.m4a"
                repository.saveMessage(Message(
                    id = messageId, contactId = contact.id,
                    content = "🎤 Sprachnachricht (${durationMs/1000}s)", isOutgoing = true,
                    type = MessageType.FILE, filePath = filePath,
                    fileSize = java.io.File(filePath).length(), fileName = fileName,
                    status = MessageStatus.SENDING
                ))
                val engine = try { app.messagingEngine } catch (_: Exception) { null }
                if (engine == null) {
                    repository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    TorChatLogger.w("ChatVM", "Engine nicht verfügbar für Audio")
                    return@launch
                }
                if (contact.isGroup) {
                    val myOwnOnion = try { app.keyManager.onionAddress } catch (_: Exception) { "" }
                    val members = repository.getActiveGroupMembers(contact.id)
                        .filter { it.contactId != "self" && it.onionAddress != myOwnOnion }
                    var sentCount = 0
                    members.forEach { member ->
                        val memberContact = repository.getContactById(member.contactId)
                            ?: repository.getContactByOnion(member.onionAddress)
                            ?: com.torchat.app.model.Contact(
                                id = member.contactId, name = member.displayName,
                                onionAddress = member.onionAddress)
                        try {
                            val ok = engine.sendFile(memberContact, filePath, fileName,
                                MessageType.FILE, "${messageId}_${member.contactId}",
                                groupId = contact.id)
                            if (ok) sentCount++
                        } catch (_: Exception) {}
                    }
                    repository.updateMessageStatus(messageId, when {
                        sentCount == members.size -> MessageStatus.DELIVERED
                        sentCount > 0             -> MessageStatus.SENT
                        else                      -> MessageStatus.FAILED
                    })
                } else {
                    engine.sendFile(contact, filePath, fileName, MessageType.FILE, messageId)
                }
            } catch (e: Exception) { TorChatLogger.e("ChatVM", "sendAudio: ${e.message}", e) }
        }
    }
}

// ─────────────────────────────────────────────
// Settings ViewModel
// ─────────────────────────────────────────────
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app by lazy { application as TorChatApp }

    // Reaktive Onion-Adresse: aus EmbeddedTor-Flow + KeyManager als Fallback
    val myOnionAddressFlow: StateFlow<String> by lazy {
        try {
            app.embeddedTor.onionAddress
                .map { addr ->
                    val result = addr?.trim()?.takeIf { it.isNotEmpty() }
                        ?: app.keyManager.onionAddress
                    result
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                    app.keyManager.onionAddress)
        } catch (_: Exception) {
            MutableStateFlow(try { app.keyManager.onionAddress } catch (_: Exception) { "" })
        }
    }

    // Compat-getter für nicht-reaktive Nutzung
    val myOnionAddress: String get() = try {
        myOnionAddressFlow.value.ifEmpty { app.keyManager.onionAddress }
    } catch (_: Exception) { "" }
    val myDisplayName: String         get() = try { app.keyManager.displayName } catch (_: Exception) { "TorChat User" }
    val myPublicKey: String       get() = try { app.keyManager.publicKeyBase64 } catch (_: Exception) { "" }
    val torStatus                       get() = try { app.torProxyManager.torStatus }
                                                catch (_: Exception) { MutableStateFlow(com.torchat.app.network.TorStatus.DISCONNECTED) }
    val usingEmbeddedTor: Boolean get() = try { app.torProxyManager.usingEmbeddedTor } catch (_: Exception) { false }
    val embeddedTorStatus               get() = try { app.embeddedTor.status }
                                                catch (_: Exception) { MutableStateFlow(com.torchat.app.network.EmbeddedTorStatus.STOPPED) }
    val bootstrapPercent                get() = try { app.embeddedTor.bootstrapPercent }
                                                catch (_: Exception) { MutableStateFlow(0) }

    val pinManager                      get() = try { app.pinManager } catch (_: Exception) { null }
    val isPinActive: Boolean      get() = try { app.pinManager.isPinActive } catch (_: Exception) { false }
    fun setPin(pin: String): Boolean    = try { app.pinManager.setPin(pin) } catch (_: Exception) { false }
    fun checkPin(pin: String): Boolean  = try { app.pinManager.checkPin(pin) } catch (_: Exception) { false }
    fun removePin(cur: String): Boolean = try { app.pinManager.removePin(cur) } catch (_: Exception) { false }
    fun changePin(o: String, n: String): Boolean = try { app.pinManager.changePin(o, n) } catch (_: Exception) { false }

    var embeddedSocksPort:   Int get() = try { app.embeddedTor.socksPort } catch (_: Exception) { 9050 }
                             set(v) { try { app.embeddedTor.socksPort = v } catch (_: Exception) {} }
    var embeddedControlPort: Int get() = try { app.embeddedTor.controlPort } catch (_: Exception) { 9052 }
                             set(v) { try { app.embeddedTor.controlPort = v } catch (_: Exception) {} }
    var embeddedHsPort:      Int get() = try { app.embeddedTor.hiddenSvcPort } catch (_: Exception) { 11009 }
                             set(v) { try { app.embeddedTor.hiddenSvcPort = v } catch (_: Exception) {} }

    fun updateDisplayName(name: String)     = try { app.keyManager.setDisplayName(name) } catch (_: Exception) {}
    fun updateOnionAddress(address: String) = try { app.keyManager.updateOnionAddress(address) } catch (_: Exception) {}
    fun newCircuit()                        = try { app.embeddedTor.newCircuit() } catch (_: Exception) {}

    val currentLanguage get() = com.torchat.app.i18n.AppStrings.language.value
    fun setLanguage(lang: com.torchat.app.i18n.AppLanguage) =
        com.torchat.app.i18n.AppStrings.setLanguage(lang, getApplication())

    // ── Persistente Einstellungen ────────────
    private val ctx get() = getApplication<Application>()

    var isDisappearingEnabled: Boolean
        get() = com.torchat.app.data.SettingsManager.isDisappearingEnabled(ctx)
        set(v) { com.torchat.app.data.SettingsManager.setDisappearing(ctx, v) }

    var isStealthEnabled: Boolean
        get() = com.torchat.app.data.SettingsManager.isStealthEnabled(ctx)
        set(v) { com.torchat.app.data.SettingsManager.setStealth(ctx, v) }

    var isNotificationsEnabled: Boolean
        get() = com.torchat.app.data.SettingsManager.isNotificationsEnabled(ctx)
        set(v) { com.torchat.app.data.SettingsManager.setNotifications(ctx, v) }


    fun retryTorConnection() {
        viewModelScope.launch {
            try {
                TorChatLogger.i("SettingsVM", "Manueller Tor-Neustart...")
                TorMessagingService.stop(getApplication())
                delay(2000)
                TorMessagingService.start(getApplication())
                TorChatLogger.i("SettingsVM", "Tor-Service neu gestartet")
            } catch (e: Exception) {
                TorChatLogger.e("SettingsVM", "retryTorConnection: ${e.message}", e)
                try { TorMessagingService.start(getApplication()) } catch (_: Exception) {}
            }
        }
    }
}

// ─────────────────────────────────────────────
// ViewModelFactory
// ─────────────────────────────────────────────
class AppViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatListViewModel::class.java) -> ChatListViewModel(application) as T
        modelClass.isAssignableFrom(ChatViewModel::class.java)     -> ChatViewModel(application) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(application) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
