package com.torchat.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// ─────────────────────────────────────────────
// Contact Model
// ─────────────────────────────────────────────
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val onionAddress: String,           // e.g. "abc123.onion"
    val publicKeyBase64: String = "",    // RSA public key for E2E encryption
    val avatarColor: String = "#00ff9d",
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0L,
    val isBlocked: Boolean = false,
    val remoteDisplayName: String = ""  // selbst gesetzter Name des Gegenübers (via KEY_EXCHANGE)
) {
    val shortOnion: String get() = onionAddress.take(16) + "..."
    val initials: String get() = name.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { name.take(2).uppercase() }
}

// ─────────────────────────────────────────────
// Message Model
// ─────────────────────────────────────────────
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val contactId: String,              // FK to contacts
    val content: String,                // Decrypted content (stored encrypted in DB)
    val encryptedContent: String = "",  // Wire format (encrypted)
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENDING,
    val type: MessageType = MessageType.TEXT,
    val filePath: String? = null,
    val fileSize: Long? = null,
    val fileName: String? = null,
    val isDeleted: Boolean = false,
    val disappearsAt: Long? = null,     // For disappearing messages
    val senderOnion: String = ""        // Absender-Onion (für Gruppenchat-Anzeige)
)

enum class MessageStatus {
    SENDING,    // Wird gesendet / in Queue
    SENT,       // Gesendet ins Tor-Netz
    DELIVERED,  // Vom Empfänger bestätigt (ACK) — 2 blaue Häkchen
    READ,       // Vom Empfänger gelesen — 2 blaue Häkchen (gefüllt)
    FAILED,     // Endgültig fehlgeschlagen
    OFFLINE     // Empfänger offline — rotes Häkchen, in Queue
}

enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    SYSTEM     // System messages (e.g. "Verschlüsselung aktiviert")
}

// ─────────────────────────────────────────────
// GroupMember Model
// ─────────────────────────────────────────────
@Entity(tableName = "group_members")
data class GroupMember(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String,               // FK → Contact.id (isGroup = true)
    val contactId: String,             // FK → Contact.id (Mitglied)
    val onionAddress: String,          // Onion-Adresse (Kopie für schnellen Zugriff)
    val displayName: String = "",
    val isAdmin: Boolean = false,      // Admin kann Mitglieder verwalten
    val isBlockedInGroup: Boolean = false, // In Gruppe blockiert (empfängt keine Nachrichten mehr)
    val addedAt: Long = System.currentTimeMillis()
)
data class ChatSession(
    val contact: Contact,
    val lastMessage: Message?,
    val unreadCount: Int,
    val isOnline: Boolean
)

// ─────────────────────────────────────────────
// P2P Wire Protocol
// ─────────────────────────────────────────────
data class TorMessage(
    val version:           Int    = 1,
    val type:              String = "",
    val senderId:          String = "",
    val recipientId:       String = "",
    val messageId:         String = "",
    val encryptedPayload:  String = "",
    val signature:         String = "",
    val timestamp:         Long   = System.currentTimeMillis(),
    val nonce:             String = "",
    val groupId:           String = "",
    val senderPublicKey:   String = "",  // Public Key des Senders — für sofortige Entschlüsselung
    val senderDisplayName: String = ""   // Anzeigename des Senders — für sofortigen Kontaktnamen
)

data class AddressUpdatePayload(
    val oldOnionAddress: String,
    val newOnionAddress: String,
    val displayName:     String,
    val publicKey:       String   // Für Verifizierung
)

data class KeyExchangePayload(
    val publicKey: String,        // Base64 RSA public key
    val onionAddress: String,
    val displayName: String,
    val protocolVersion: Int = 1
)

// ─────────────────────────────────────────────
// Group Invite Payload — vollständige Gruppeninfo
// wird an alle Mitglieder gesendet wenn Gruppe erstellt wird
// ─────────────────────────────────────────────
data class GroupMemberInfo(
    val contactId:    String? = null,
    val onionAddress: String? = null,
    val displayName:  String? = null,
    val isAdmin:      Boolean = false
)

data class GroupInvitePayload(
    val groupId:      String? = null,
    val groupName:    String? = null,
    val avatarColor:  String? = "#a855f7",
    val creatorOnion: String? = null,
    val creatorName:  String? = null,
    val members:      List<GroupMemberInfo>? = null,
    val createdAt:    Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────
// Identity (our own keys & onion address)
// ─────────────────────────────────────────────
data class Identity(
    val onionAddress: String,
    val publicKeyBase64: String,
    val displayName: String,
    val createdAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────
// App Settings
// ─────────────────────────────────────────────
data class AppSettings(
    val displayName: String = "Anonym",
    val torEnabled: Boolean = true,
    val disappearingMessages: Boolean = false,
    val disappearingTimeSeconds: Int = 86400, // 24h
    val stealthMode: Boolean = false,         // Hide online status
    val notificationsEnabled: Boolean = true,
    val theme: String = "dark"
)
