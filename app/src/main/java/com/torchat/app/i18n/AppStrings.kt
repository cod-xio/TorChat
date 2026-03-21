package com.torchat.app.i18n

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppLanguage { DE, EN }

/**
 * All UI strings in one place — DE and EN.
 * Switch language without Activity restart via StateFlow.
 */
interface Strings {
    val appName: String; val chats: String; val settings: String
    val contacts: String; val groups: String; val back: String
    val save: String; val cancel: String; val delete: String
    val confirm: String; val yes: String; val no: String
    val close: String; val edit: String; val search: String
    val send: String; val ok: String
    val messageHint: String; val encryptedMsg: String
    val noMessages: String; val noMessagesHint: String
    val typeMessage: String; val photoLabel: String; val audioLabel: String
    val fileLabel: String; val msgDeleted: String; val sending: String
    val newChat: String; val addContact: String; val createGroup: String
    val noContacts: String; val noContactsHint: String
    val onlineLabel: String; val offlineLabel: String; val groupLabel: String
    val onionLabel: String; val members: String; val block: String; val unblock: String
    val addContactTitle: String; val contactName: String; val contactNameHint: String
    val onionAddress: String; val onionHint: String; val scanQr: String; val add: String
    val createGroupTitle: String; val groupName: String; val groupNameHint: String
    val selectMembers: String; val create: String; val groupCreated: String
    val tabGeneral: String; val tabNetwork: String; val tabPrivacy: String
    val tabContacts: String; val tabLog: String
    val sectionSecurity: String; val sectionAnonymity: String; val sectionAppInfo: String
    val pin: String; val pinSub: String; val pinActive: String; val pinInactive: String
    val setPin: String; val changePin: String; val removePin: String
    val torNetwork: String; val torNetworkSub: String
    val keyManagement: String; val keyManagementSub: String
    val version: String; val encryption: String; val license: String; val licenseValue: String
    val diagnostics: String; val diagnosticsSub: String; val language: String; val languageSub: String
    val sectionIdentity: String; val myOnion: String; val torConnecting: String
    val torAddressHint: String; val showQr: String; val manualInput: String
    val enterOnion: String; val onionAutoHint: String; val sectionTorStatus: String
    val torStatus: String; val bootstrapPct: String; val socksPort: String
    val controlPort: String; val hiddenServicePort: String
    val newRoute: String; val newRouteSub: String; val restartTor: String; val restartTorSub: String
    val sectionPorts: String; val connectedVia: String; val embeddedTor: String
    val sectionPrivacy: String; val e2eEncryption: String; val e2eSub: String
    val disappearing: String; val disappearingSub: String; val stealth: String; val stealthSub: String
    val notifications: String; val notificationsSub: String; val cloudBackup: String; val cloudBackupSub: String
    val sectionBlocklist: String; val noBlocked: String; val deleteContact: String; val deleteContactMsg: String
    val logEntries: String; val clearLog: String
    val diagTitle: String; val diagRefresh: String; val diagSend: String
    val open: String; val closed: String; val available: String; val notAvailable: String; val nocrash: String
    val pinSetTitle: String; val pinChangeTitle: String; val pinRemoveTitle: String
    val pinEnter: String; val pinConfirm: String; val pinCurrent: String; val pinNew: String
    val pinWrong: String; val pinMismatch: String; val pinTooShort: String; val pinSuccess: String
    val torStarting: String; val noNetwork: String; val torError: String; val newMessage: String
    val encryptionActive: String; val newAddressMsg: String
}

object DE : Strings {
    override val appName           = "TorChat"
    override val chats             = "Chats"
    override val settings          = "Einstellungen"
    override val contacts          = "Kontakte"
    override val groups            = "Gruppen"
    override val back              = "Zurück"
    override val save              = "Speichern"
    override val cancel            = "Abbrechen"
    override val delete            = "Löschen"
    override val confirm           = "Bestätigen"
    override val yes               = "Ja"
    override val no                = "Nein"
    override val close             = "Schließen"
    override val edit              = "Bearbeiten"
    override val search            = "Suchen"
    override val send              = "Senden"
    override val ok                = "OK"
    override val messageHint       = "Nachricht..."
    override val encryptedMsg      = "🔒 (Key-Exchange läuft...)"
    override val noMessages        = "Noch keine Nachrichten"
    override val noMessagesHint    = "Sende die erste Nachricht — Ende-zu-Ende verschlüsselt via Tor."
    override val typeMessage       = "Nachricht schreiben..."
    override val photoLabel        = "📷"
    override val audioLabel        = "🎤"
    override val fileLabel         = "📎"
    override val msgDeleted        = "🗑 Gelöscht"
    override val sending           = "Wird gesendet..."
    override val newChat           = "Neuer Chat"
    override val addContact        = "Kontakt hinzufügen"
    override val createGroup       = "Gruppe erstellen"
    override val noContacts        = "Noch keine Kontakte"
    override val noContactsHint    = "Füge einen Kontakt hinzu um loszulegen."
    override val onlineLabel       = "ONLINE"
    override val offlineLabel      = "OFFLINE"
    override val groupLabel        = "GRUPPE"
    override val onionLabel        = ".onion"
    override val members           = "Mitglieder"
    override val block             = "Blockieren"
    override val unblock           = "Entsperren"
    override val addContactTitle   = "Kontakt hinzufügen"
    override val contactName       = "Name"
    override val contactNameHint   = "Max Mustermann"
    override val onionAddress      = "Onion-Adresse"
    override val onionHint         = "abc123...xyz.onion"
    override val scanQr            = "QR scannen"
    override val add               = "Hinzufügen"
    override val createGroupTitle  = "Gruppe erstellen"
    override val groupName         = "Gruppenname"
    override val groupNameHint     = "Meine Gruppe"
    override val selectMembers     = "Mitglieder auswählen"
    override val create            = "Erstellen"
    override val groupCreated      = "Gruppe \"%s\" erstellt mit %d Mitgliedern."
    override val tabGeneral        = "Allgemein"
    override val tabNetwork        = "Netzwerk"
    override val tabPrivacy        = "Datenschutz"
    override val tabContacts       = "Kontakte"
    override val tabLog            = "Log"
    override val sectionSecurity   = "SICHERHEIT"
    override val sectionAnonymity  = "ANONYMITÄT"
    override val sectionAppInfo    = "APP-INFO"
    override val pin               = "PIN-Schutz"
    override val pinSub            = "App mit PIN sichern"
    override val pinActive         = "PIN aktiv"
    override val pinInactive       = "Kein PIN gesetzt"
    override val setPin            = "PIN setzen"
    override val changePin         = "PIN ändern"
    override val removePin         = "PIN entfernen"
    override val torNetwork        = "Tor-Netzwerk"
    override val torNetworkSub     = "Alle Verbindungen laufen über Tor.\nKeine IP-Adressen werden übertragen.\nKein DNS – nur .onion-Routing."
    override val keyManagement     = "Schlüsselverwaltung"
    override val keyManagementSub  = "RSA-2048 Private Key lokal gespeichert.\nNiemals übertragen."
    override val version           = "Version"
    override val encryption        = "Verschlüsselung"
    override val license           = "Lizenz"
    override val licenseValue      = "TorChat Freie Lizenz v1.0"
    override val diagnostics       = "Diagnose"
    override val diagnosticsSub    = "System-Check & Fehleranalyse"
    override val language          = "Sprache"
    override val languageSub       = "Deutsch / English"
    override val sectionIdentity   = "IDENTITÄT"
    override val myOnion           = "MEINE ONION-ADRESSE"
    override val torConnecting     = "⏳ Tor verbindet..."
    override val torAddressHint    = "Adresse erscheint wenn Tor verbunden ist."
    override val showQr            = "QR-Code"
    override val manualInput       = "Manuell"
    override val enterOnion        = "Onion-Adresse eingeben"
    override val onionAutoHint     = "Adresse erscheint automatisch sobald Tor verbunden ist."
    override val sectionTorStatus  = "TOR-STATUS"
    override val torStatus         = "Tor-Status"
    override val bootstrapPct      = "Bootstrap"
    override val socksPort         = "SOCKS5-Port"
    override val controlPort       = "Control-Port"
    override val hiddenServicePort = "Hidden Service Port"
    override val newRoute          = "Neue Route"
    override val newRouteSub       = "Neue Tor-Verbindung aufbauen"
    override val restartTor        = "Tor neu starten"
    override val restartTorSub     = "Service komplett neu starten"
    override val sectionPorts      = "PORTS"
    override val connectedVia      = "Verbunden via"
    override val embeddedTor       = "Eingebettetes Tor"
    override val sectionPrivacy    = "DATENSCHUTZ"
    override val e2eEncryption     = "E2E-Verschlüsselung"
    override val e2eSub            = "AES-256-GCM + RSA-2048"
    override val disappearing      = "Nachrichten löschen"
    override val disappearingSub   = "Automatisch nach 24 Stunden"
    override val stealth           = "Stealth-Modus"
    override val stealthSub        = "Online-Status ausblenden"
    override val notifications     = "Benachrichtigungen"
    override val notificationsSub  = "Push-Nachrichten"
    override val cloudBackup       = "Cloud-Backup"
    override val cloudBackupSub    = "Deaktiviert – Daten bleiben lokal"
    override val sectionBlocklist  = "BLOCKLISTE"
    override val noBlocked         = "Keine blockierten Kontakte"
    override val deleteContact     = "Kontakt löschen?"
    override val deleteContactMsg  = "%s und alle Nachrichten werden endgültig gelöscht."
    override val logEntries        = "Einträge"
    override val clearLog          = "Log löschen"
    override val diagTitle         = "Diagnose"
    override val diagRefresh       = "Aktualisieren"
    override val diagSend          = "Screenshot machen & als Fehlerbericht senden"
    override val open              = "offen"
    override val closed            = "geschlossen"
    override val available         = "verfügbar"
    override val notAvailable      = "nicht verfügbar"
    override val nocrash           = "(kein Crash gespeichert)"
    override val pinSetTitle       = "PIN setzen"
    override val pinChangeTitle    = "PIN ändern"
    override val pinRemoveTitle    = "PIN entfernen"
    override val pinEnter          = "PIN eingeben"
    override val pinConfirm        = "PIN bestätigen"
    override val pinCurrent        = "Aktueller PIN"
    override val pinNew            = "Neuer PIN"
    override val pinWrong          = "Falscher PIN"
    override val pinMismatch       = "PINs stimmen nicht überein"
    override val pinTooShort       = "Mindestens 4 Ziffern"
    override val pinSuccess        = "PIN gesetzt ✅"
    override val torStarting       = "🧅 Tor startet..."
    override val noNetwork         = "⏳ Kein Netzwerk..."
    override val torError          = "❌ Tor konnte nicht gestartet werden"
    override val newMessage        = "Neue verschlüsselte Nachricht"
    override val encryptionActive  = "🔒 Ende-zu-Ende-Verschlüsselung aktiv"
    override val newAddressMsg     = "📍 %s hat eine neue Adresse:\n%s"
}

object EN : Strings {
    override val appName           = "TorChat"
    override val chats             = "Chats"
    override val settings          = "Settings"
    override val contacts          = "Contacts"
    override val groups            = "Groups"
    override val back              = "Back"
    override val save              = "Save"
    override val cancel            = "Cancel"
    override val delete            = "Delete"
    override val confirm           = "Confirm"
    override val yes               = "Yes"
    override val no                = "No"
    override val close             = "Close"
    override val edit              = "Edit"
    override val search            = "Search"
    override val send              = "Send"
    override val ok                = "OK"
    override val messageHint       = "Message..."
    override val encryptedMsg      = "🔒 (Key exchange in progress...)"
    override val noMessages        = "No messages yet"
    override val noMessagesHint    = "Send the first message — end-to-end encrypted via Tor."
    override val typeMessage       = "Write a message..."
    override val photoLabel        = "📷"
    override val audioLabel        = "🎤"
    override val fileLabel         = "📎"
    override val msgDeleted        = "🗑 Deleted"
    override val sending           = "Sending..."
    override val newChat           = "New Chat"
    override val addContact        = "Add Contact"
    override val createGroup       = "Create Group"
    override val noContacts        = "No contacts yet"
    override val noContactsHint    = "Add a contact to get started."
    override val onlineLabel       = "ONLINE"
    override val offlineLabel      = "OFFLINE"
    override val groupLabel        = "GROUP"
    override val onionLabel        = ".onion"
    override val members           = "Members"
    override val block             = "Block"
    override val unblock           = "Unblock"
    override val addContactTitle   = "Add Contact"
    override val contactName       = "Name"
    override val contactNameHint   = "John Doe"
    override val onionAddress      = "Onion Address"
    override val onionHint         = "abc123...xyz.onion"
    override val scanQr            = "Scan QR"
    override val add               = "Add"
    override val createGroupTitle  = "Create Group"
    override val groupName         = "Group name"
    override val groupNameHint     = "My Group"
    override val selectMembers     = "Select members"
    override val create            = "Create"
    override val groupCreated      = "Group \"%s\" created with %d members."
    override val tabGeneral        = "General"
    override val tabNetwork        = "Network"
    override val tabPrivacy        = "Privacy"
    override val tabContacts       = "Contacts"
    override val tabLog            = "Log"
    override val sectionSecurity   = "SECURITY"
    override val sectionAnonymity  = "ANONYMITY"
    override val sectionAppInfo    = "APP INFO"
    override val pin               = "PIN Protection"
    override val pinSub            = "Secure the app with a PIN"
    override val pinActive         = "PIN active"
    override val pinInactive       = "No PIN set"
    override val setPin            = "Set PIN"
    override val changePin         = "Change PIN"
    override val removePin         = "Remove PIN"
    override val torNetwork        = "Tor Network"
    override val torNetworkSub     = "All connections route through Tor.\nNo IP addresses transmitted.\nNo DNS – only .onion routing."
    override val keyManagement     = "Key Management"
    override val keyManagementSub  = "RSA-2048 private key stored locally.\nNever transmitted."
    override val version           = "Version"
    override val encryption        = "Encryption"
    override val license           = "License"
    override val licenseValue      = "TorChat Free License v1.0"
    override val diagnostics       = "Diagnostics"
    override val diagnosticsSub    = "System check & error analysis"
    override val language          = "Language"
    override val languageSub       = "Deutsch / English"
    override val sectionIdentity   = "IDENTITY"
    override val myOnion           = "MY ONION ADDRESS"
    override val torConnecting     = "⏳ Tor connecting..."
    override val torAddressHint    = "Address appears when Tor is connected."
    override val showQr            = "QR Code"
    override val manualInput       = "Manual"
    override val enterOnion        = "Enter Onion Address"
    override val onionAutoHint     = "Address appears automatically once Tor is connected."
    override val sectionTorStatus  = "TOR STATUS"
    override val torStatus         = "Tor Status"
    override val bootstrapPct      = "Bootstrap"
    override val socksPort         = "SOCKS5 Port"
    override val controlPort       = "Control Port"
    override val hiddenServicePort = "Hidden Service Port"
    override val newRoute          = "New Route"
    override val newRouteSub       = "Build a new Tor connection"
    override val restartTor        = "Restart Tor"
    override val restartTorSub     = "Completely restart the service"
    override val sectionPorts      = "PORTS"
    override val connectedVia      = "Connected via"
    override val embeddedTor       = "Embedded Tor"
    override val sectionPrivacy    = "PRIVACY"
    override val e2eEncryption     = "E2E Encryption"
    override val e2eSub            = "AES-256-GCM + RSA-2048"
    override val disappearing      = "Auto-delete messages"
    override val disappearingSub   = "Automatically after 24 hours"
    override val stealth           = "Stealth Mode"
    override val stealthSub        = "Hide online status"
    override val notifications     = "Notifications"
    override val notificationsSub  = "Push messages"
    override val cloudBackup       = "Cloud Backup"
    override val cloudBackupSub    = "Disabled – data stays local"
    override val sectionBlocklist  = "BLOCKLIST"
    override val noBlocked         = "No blocked contacts"
    override val deleteContact     = "Delete contact?"
    override val deleteContactMsg  = "%s and all messages will be permanently deleted."
    override val logEntries        = "Entries"
    override val clearLog          = "Clear log"
    override val diagTitle         = "Diagnostics"
    override val diagRefresh       = "Refresh"
    override val diagSend          = "Take screenshot & send as bug report"
    override val open              = "open"
    override val closed            = "closed"
    override val available         = "available"
    override val notAvailable      = "not available"
    override val nocrash           = "(no crash stored)"
    override val pinSetTitle       = "Set PIN"
    override val pinChangeTitle    = "Change PIN"
    override val pinRemoveTitle    = "Remove PIN"
    override val pinEnter          = "Enter PIN"
    override val pinConfirm        = "Confirm PIN"
    override val pinCurrent        = "Current PIN"
    override val pinNew            = "New PIN"
    override val pinWrong          = "Wrong PIN"
    override val pinMismatch       = "PINs don't match"
    override val pinTooShort       = "At least 4 digits"
    override val pinSuccess        = "PIN set ✅"
    override val torStarting       = "🧅 Tor starting..."
    override val noNetwork         = "⏳ No network..."
    override val torError          = "❌ Tor could not be started"
    override val newMessage        = "New encrypted message"
    override val encryptionActive  = "🔒 End-to-end encryption active"
    override val newAddressMsg     = "📍 %s has a new address:\n%s"
}

object AppStrings {
    private val _language = MutableStateFlow(AppLanguage.DE)
    val language: StateFlow<AppLanguage> = _language

    fun init(context: Context) {
        val saved = context.getSharedPreferences("torchat_lang", Context.MODE_PRIVATE)
            .getString("language", "DE") ?: "DE"
        _language.value = if (saved == "EN") AppLanguage.EN else AppLanguage.DE
    }

    fun setLanguage(lang: AppLanguage, context: Context) {
        _language.value = lang
        context.getSharedPreferences("torchat_lang", Context.MODE_PRIVATE)
            .edit().putString("language", lang.name).apply()
    }

    val current: Strings get() = if (_language.value == AppLanguage.EN) EN else DE
}
