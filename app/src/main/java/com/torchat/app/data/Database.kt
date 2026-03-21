package com.torchat.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.torchat.app.model.Contact
import com.torchat.app.model.Message
import com.torchat.app.model.MessageStatus
import com.torchat.app.model.MessageType
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────
// Type Converters
// ─────────────────────────────────────────────
class Converters {
    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus =
        MessageStatus.valueOf(value)

    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType =
        MessageType.valueOf(value)
}

// ─────────────────────────────────────────────
// Contact DAO
// ─────────────────────────────────────────────
@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isBlocked = 0 AND isGroup = 0 ORDER BY name ASC")
    suspend fun getAllContactsOnce(): List<Contact>

    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY lastSeen DESC, addedAt DESC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE isBlocked = 1 AND isGroup = 0 ORDER BY name ASC")
    fun getBlockedContacts(): Flow<List<Contact>>

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE id = :contactId")
    suspend fun setBlocked(contactId: String, blocked: Boolean)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContactById(id: String): Contact?

    @Query("SELECT * FROM contacts WHERE onionAddress = :onion")
    suspend fun getContactByOnion(onion: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Update
    suspend fun updateContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("UPDATE contacts SET lastSeen = :timestamp WHERE id = :contactId")
    suspend fun updateLastSeen(contactId: String, timestamp: Long)

    @Query("UPDATE contacts SET publicKeyBase64 = :key WHERE id = :contactId")
    suspend fun updatePublicKey(contactId: String, key: String)

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int
}

// ─────────────────────────────────────────────
// Message DAO
// ─────────────────────────────────────────────
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId AND isDeleted = 0 ORDER BY timestamp ASC")
    fun getMessagesForContact(contactId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(contactId: String): Message?

    @Query("SELECT * FROM messages WHERE contactId = :contactId AND status = 'SENDING' AND encryptedContent != '' AND isOutgoing = 0")
    suspend fun getPendingMessages(contactId: String): List<Message>

    @Query("SELECT COUNT(*) FROM messages WHERE contactId = :contactId AND isOutgoing = 0 AND status != 'READ'")
    fun getUnreadCount(contactId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Update
    suspend fun updateMessage(message: Message)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET status = 'READ' WHERE contactId = :contactId AND isOutgoing = 0 AND status != 'READ'")
    suspend fun markAllRead(contactId: String)

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :messageId")
    suspend fun softDeleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteAllMessagesForContact(contactId: String)

    @Query("DELETE FROM messages WHERE disappearsAt IS NOT NULL AND disappearsAt < :now")
    suspend fun deleteExpiredMessages(now: Long)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): Message?

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)
}

// ─────────────────────────────────────────────
// GroupMember DAO
// ─────────────────────────────────────────────
@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembersForGroup(groupId: String): List<com.torchat.app.model.GroupMember>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getMembersForGroupFlow(groupId: String): Flow<List<com.torchat.app.model.GroupMember>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isBlockedInGroup = 0")
    suspend fun getActiveMembersForGroup(groupId: String): List<com.torchat.app.model.GroupMember>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND contactId = :contactId LIMIT 1")
    suspend fun getMember(groupId: String, contactId: String): com.torchat.app.model.GroupMember?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: com.torchat.app.model.GroupMember)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<com.torchat.app.model.GroupMember>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembersForGroup(groupId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND contactId = :contactId")
    suspend fun removeMember(groupId: String, contactId: String)

    @Query("UPDATE group_members SET isAdmin = :isAdmin WHERE groupId = :groupId AND contactId = :contactId")
    suspend fun setAdmin(groupId: String, contactId: String, isAdmin: Boolean)

    @Query("UPDATE group_members SET isBlockedInGroup = :blocked WHERE groupId = :groupId AND contactId = :contactId")
    suspend fun setBlockedInGroup(groupId: String, contactId: String, blocked: Boolean)

    @Query("UPDATE group_members SET onionAddress = :newOnion WHERE onionAddress = :oldOnion")
    suspend fun updateOnionAddress(oldOnion: String, newOnion: String)

    @Query("UPDATE group_members SET displayName = :name WHERE onionAddress = :onion")
    suspend fun updateDisplayNameByOnion(onion: String, name: String)

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberCount(groupId: String): Int
}

// ─────────────────────────────────────────────
// Database
// ─────────────────────────────────────────────
@Database(
    entities = [Contact::class, Message::class, com.torchat.app.model.GroupMember::class],
    version  = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun groupMemberDao(): GroupMemberDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Marker-Keys in SharedPreferences
        private const val PREF_FILE          = "torchat_dbkey_v2"  // sync mit DatabaseKeyManager
        private const val KEY_ENCRYPTED      = "encrypted_v2"
        private const val KEY_FIRST_START    = "first_start_done"

        // Migration 1→2: SQLCipher aktiviert
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) { /* no-op */ }
        }

        // Migration 2→3: group_members Tabelle hinzugefügt
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        id TEXT NOT NULL PRIMARY KEY,
                        groupId TEXT NOT NULL,
                        contactId TEXT NOT NULL,
                        onionAddress TEXT NOT NULL,
                        displayName TEXT NOT NULL DEFAULT '',
                        addedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_group_members_groupId ON group_members(groupId)"
                )
            }
        }

        // Migration 3→4: isAdmin und isBlockedInGroup Felder hinzugefügt
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_members ADD COLUMN isAdmin INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE group_members ADD COLUMN isBlockedInGroup INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 4→5: senderOnion in messages hinzugefügt
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN senderOnion TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN remoteDisplayName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val prefs  = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val dbFile = context.getDatabasePath("torchat_db")

            // SQLCipher-Bibliothek immer zuerst laden
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context)

            // Passphrase holen (wird beim ersten Aufruf generiert)
            val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)

            // ── Erster Start: DB existiert noch nicht ──────────────────────
            // Room + SQLCipher erstellt sie direkt verschlüsselt → kein extra Schritt nötig
            if (!dbFile.exists()) {
                com.torchat.app.debug.TorChatLogger.i("AppDatabase",
                    "Erster Start: neue SQLCipher-DB wird erstellt")
                prefs.edit()
                    .putBoolean(KEY_ENCRYPTED, true)
                    .putBoolean(KEY_FIRST_START, true)
                    .apply()
            }

            // ── Bestehende unverschlüsselte DB migrieren ───────────────────
            // Nur wenn: DB existiert UND noch nicht als verschlüsselt markiert
            val isAlreadyEncrypted = prefs.getBoolean(KEY_ENCRYPTED, false)
            if (dbFile.exists() && !isAlreadyEncrypted) {
                migrateToEncrypted(context, dbFile, passphrase, prefs)
            }

            val factory = net.sqlcipher.database.SupportFactory(passphrase)

            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "torchat_db"
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()

            // Passphrase sofort aus RAM löschen
            passphrase.fill(0)

            return db
        }

        /**
         * Verschlüsselt eine bestehende Klartext-SQLite-DB mit SQLCipher.
         * Wird nur einmalig ausgeführt (beim Update von unverschlüsselter Version).
         */
        private fun migrateToEncrypted(
            context:    Context,
            dbFile:     java.io.File,
            passphrase: ByteArray,
            prefs:      android.content.SharedPreferences
        ) {
            com.torchat.app.debug.TorChatLogger.i("AppDatabase",
                "Migriere bestehende DB zu SQLCipher (Größe: ${dbFile.length() / 1024}KB)...")
            try {
                val encFile = context.getDatabasePath("torchat_db_enc")
                encFile.parentFile?.mkdirs()
                encFile.delete()

                // Klartext-DB öffnen
                val clearDb = net.sqlcipher.database.SQLiteDatabase
                    .openOrCreateDatabase(dbFile, "", null)

                // Verschlüsselte Kopie erstellen
                val hexKey = passphrase.joinToString("") { "%02x".format(it) }
                clearDb.rawExecSQL(
                    "ATTACH DATABASE '${encFile.absolutePath}' AS encrypted KEY '$hexKey';")
                clearDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                clearDb.rawExecSQL("DETACH DATABASE encrypted;")
                clearDb.close()

                // Ersetzen: Klartext → Verschlüsselt
                dbFile.delete()
                encFile.renameTo(dbFile)

                // WAL/SHM-Dateien der alten DB ebenfalls löschen
                context.getDatabasePath("torchat_db-wal").delete()
                context.getDatabasePath("torchat_db-shm").delete()

                prefs.edit().putBoolean(KEY_ENCRYPTED, true).apply()
                com.torchat.app.debug.TorChatLogger.i("AppDatabase",
                    "✅ DB-Migration abgeschlossen")

            } catch (e: Exception) {
                com.torchat.app.debug.TorChatLogger.e("AppDatabase",
                    "DB-Migration fehlgeschlagen: ${e.message}", e)
                // Fallback: DB löschen → beim nächsten Start neu (verschlüsselt) erstellen
                dbFile.delete()
                context.getDatabasePath("torchat_db-wal").delete()
                context.getDatabasePath("torchat_db-shm").delete()
                // Als verschlüsselt markieren damit nächster Start sauber ist
                prefs.edit().putBoolean(KEY_ENCRYPTED, true).apply()
            }
        }
    }
}

// ─────────────────────────────────────────────
// Repository
// ─────────────────────────────────────────────
class ChatRepository(private val db: AppDatabase) {

    suspend fun getAllContactsOnce(): List<Contact> =
        db.contactDao().getAllContactsOnce()

    suspend fun getContactById(id: String): Contact? =
        db.contactDao().getContactById(id)

    fun getAllContacts(): kotlinx.coroutines.flow.Flow<List<Contact>> =
        db.contactDao().getAllContacts()

    val blockedContacts = db.contactDao().getBlockedContacts()

    suspend fun blockContact(contactId: String)   = db.contactDao().setBlocked(contactId, true)
    suspend fun unblockContact(contactId: String) = db.contactDao().setBlocked(contactId, false)

    fun getMessages(contactId: String) =
        db.messageDao().getMessagesForContact(contactId)

    fun getUnreadCount(contactId: String) =
        db.messageDao().getUnreadCount(contactId)

    suspend fun addContact(contact: Contact) =
        db.contactDao().insertContact(contact)

    // ── Gruppen-Mitglieder ─────────────────────────────────────────
    suspend fun addGroupMembers(members: List<com.torchat.app.model.GroupMember>) =
        db.groupMemberDao().insertMembers(members)

    suspend fun addGroupMember(member: com.torchat.app.model.GroupMember) =
        db.groupMemberDao().insertMember(member)

    suspend fun getGroupMembers(groupId: String): List<com.torchat.app.model.GroupMember> =
        db.groupMemberDao().getMembersForGroup(groupId)

    suspend fun getActiveGroupMembers(groupId: String): List<com.torchat.app.model.GroupMember> =
        db.groupMemberDao().getActiveMembersForGroup(groupId)

    suspend fun getGroupMember(groupId: String, contactId: String) =
        db.groupMemberDao().getMember(groupId, contactId)

    fun getGroupMembersFlow(groupId: String) =
        db.groupMemberDao().getMembersForGroupFlow(groupId)

    suspend fun removeGroupMember(groupId: String, contactId: String) =
        db.groupMemberDao().removeMember(groupId, contactId)

    suspend fun setGroupMemberAdmin(groupId: String, contactId: String, isAdmin: Boolean) =
        db.groupMemberDao().setAdmin(groupId, contactId, isAdmin)

    suspend fun setGroupMemberBlocked(groupId: String, contactId: String, blocked: Boolean) =
        db.groupMemberDao().setBlockedInGroup(groupId, contactId, blocked)

    suspend fun updateGroupMemberDisplayName(onion: String, name: String) =
        db.groupMemberDao().updateDisplayNameByOnion(onion, name)

    suspend fun deleteGroupMembers(groupId: String) =
        db.groupMemberDao().deleteMembersForGroup(groupId)

    /**
     * Gibt die nächste Benutzernummer zurück (User 1, User 2, ...).
     * Verwendet einen atomaren Zähler in SharedPreferences — bleibt auch nach Kontaktlöschung korrekt.
     */
    fun getNextUserNumber(context: android.content.Context): Int {
        val prefs = context.getSharedPreferences("torchat_contact_counter", android.content.Context.MODE_PRIVATE)
        val next  = prefs.getInt("counter", 0) + 1
        prefs.edit().putInt("counter", next).apply()
        return next
    }

    /**
     * Generiert einen Standardnamen für einen neuen automatisch erkannten Kontakt.
     * Format: "User N" — eindeutig, neutral, vom Benutzer umbenennenbar.
     */
    fun generateContactName(context: android.content.Context): String =
        "User ${getNextUserNumber(context)}"

    suspend fun deleteContact(contact: Contact) =
        db.contactDao().deleteContact(contact)

    suspend fun getContactByOnion(onion: String): Contact? {
        // Tor v3 Adressen sind exakt 56 Zeichen Base32 — nur exakter Match.
        // Fuzzy/Prefix-Suche führt zu Falsch-Zuordnungen wenn Adressen sich ändern.
        val trimmed = onion.trim()
        return db.contactDao().getContactByOnion(trimmed)
            ?: db.contactDao().getContactByOnion(trimmed.lowercase())
    }

    suspend fun getContactByOnionExact(onion: String) =
        db.contactDao().getContactByOnion(onion)

    suspend fun saveMessage(message: Message) =
        db.messageDao().insertMessage(message)

    suspend fun updateMessage(message: Message) =
        db.messageDao().updateMessage(message)

    suspend fun getPendingMessages(contactId: String) =
        db.messageDao().getPendingMessages(contactId)

    suspend fun deleteMessageById(messageId: String) =
        db.messageDao().deleteMessageById(messageId)

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) =
        db.messageDao().updateMessageStatus(messageId, status)

    suspend fun markAllRead(contactId: String) =
        db.messageDao().markAllRead(contactId)

    suspend fun getLastMessage(contactId: String) =
        db.messageDao().getLastMessage(contactId)

    suspend fun deleteExpiredMessages() =
        db.messageDao().deleteExpiredMessages(System.currentTimeMillis())

    suspend fun updateContact(contact: Contact) =
        db.contactDao().updateContact(contact)

    suspend fun updateContactLastSeen(contactId: String) =
        db.contactDao().updateLastSeen(contactId, System.currentTimeMillis())

    suspend fun updateContactPublicKey(contactId: String, key: String) =
        db.contactDao().updatePublicKey(contactId, key)

    suspend fun deleteContactChat(contactId: String) {
        db.messageDao().deleteAllMessagesForContact(contactId)
    }
}
