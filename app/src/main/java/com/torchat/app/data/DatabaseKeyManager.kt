package com.torchat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.torchat.app.debug.TorChatLogger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verwaltet den SQLCipher-Datenbankschlüssel mit Android Keystore.
 *
 * Sicherheitsarchitektur (2 Schichten):
 *
 *   Schicht 1 — Android Keystore (Hardware TEE / Secure Enclave):
 *     • AES-256-GCM Wrapping-Schlüssel
 *     • Nie im RAM, nie im Dateisystem extrahierbar
 *     • Root-sicher: selbst root kann ihn nicht lesen
 *
 *   Schicht 2 — Verschlüsselter DB-Schlüssel:
 *     • 256-bit Zufallsschlüssel (der eigentliche SQLCipher-Key)
 *     • Mit Keystore-Schlüssel AES-GCM verschlüsselt
 *     • Ciphertext in SharedPreferences gespeichert
 *
 *   Angriffsvektoren eliminiert:
 *     • Root: SharedPreferences lesbar, aber Ciphertext ohne Keystore nutzlos
 *     • Forensik: Keystore-Schlüssel bleibt in Hardware gebunden
 *     • ADB Backup: Schlüssel nicht exportierbar
 */
object DatabaseKeyManager {

    private const val TAG              = "DBKeyManager"
    private const val KEYSTORE_ALIAS   = "torchat_db_master_v2"
    private const val PREF_FILE        = "torchat_dbkey_v2"
    private const val KEY_WRAPPED      = "db_key_wrapped"
    private const val KEY_WRAPPED_IV   = "db_key_iv"
    // Fallback-Key (nur wenn Keystore nicht verfügbar)
    private const val KEY_FALLBACK     = "db_key_fallback"
    private const val AES_GCM_SPEC     = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS     = 128

    // ── Öffentliche API ────────────────────────────────────────────────

    /**
     * Gibt die SQLCipher-Passphrase zurück.
     * Beim ersten Aufruf: Schlüssel generieren, mit Keystore verschlüsseln, speichern.
     * Danach: Keystore entschlüsselt den gespeicherten Schlüssel.
     */
    fun getOrCreatePassphrase(context: Context): ByteArray {
        return try {
            getOrCreateWithKeystore(context)
        } catch (e: Exception) {
            TorChatLogger.w(TAG, "Keystore nicht verfügbar, Fallback: ${e.message}")
            getOrCreateFallback(context)
        }
    }

    /**
     * Löscht alle gespeicherten Schlüsseldaten.
     * ACHTUNG: DB danach nicht mehr lesbar!
     */
    fun clearKey(context: Context) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {}
        getPrefs(context).edit().clear().apply()
        TorChatLogger.w(TAG, "DB-Schlüssel gelöscht")
    }

    // ── Keystore-Implementierung ───────────────────────────────────────

    private fun getOrCreateWithKeystore(context: Context): ByteArray {
        val prefs = getPrefs(context)

        // Keystore-Wrapping-Schlüssel holen oder erstellen
        val masterKey = getOrCreateKeystoreKey()

        // Gespeicherten verschlüsselten DB-Schlüssel laden
        val wrappedB64 = prefs.getString(KEY_WRAPPED, null)
        val ivB64      = prefs.getString(KEY_WRAPPED_IV, null)

        return if (wrappedB64 != null && ivB64 != null) {
            // Entschlüsseln mit Keystore-Schlüssel
            val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
            val iv      = Base64.decode(ivB64, Base64.NO_WRAP)
            val plain   = aesGcmDecrypt(masterKey, wrapped, iv)
            TorChatLogger.d(TAG, "✅ DB-Schlüssel via Keystore entschlüsselt")
            plain
        } else {
            // Erster Start: neuen DB-Schlüssel generieren und verschlüsseln
            TorChatLogger.i(TAG, "Generiere neuen DB-Schlüssel (Android Keystore)...")
            val dbKey    = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val iv       = ByteArray(12).also  { SecureRandom().nextBytes(it) }
            val wrapped  = aesGcmEncrypt(masterKey, dbKey, iv)

            prefs.edit()
                .putString(KEY_WRAPPED,    Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putString(KEY_WRAPPED_IV, Base64.encodeToString(iv,      Base64.NO_WRAP))
                .apply()

            TorChatLogger.i(TAG, "✅ DB-Schlüssel erstellt und in Keystore gesichert")
            dbKey
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        // Existierender Schlüssel
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        // Neuen Keystore-Schlüssel generieren
        TorChatLogger.i(TAG, "Erstelle Android Keystore Schlüssel...")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Schlüssel ist NICHT exportierbar — verlässt den Keystore nie
            .setRandomizedEncryptionRequired(false)  // wir liefern eigene IV
            .build()

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(spec)
        val key = kg.generateKey()
        TorChatLogger.i(TAG, "✅ Keystore-Schlüssel erstellt (TEE/Hardware)")
        return key
    }

    private fun aesGcmEncrypt(key: SecretKey, plain: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_SPEC)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_SPEC)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    // ── Fallback (wenn Keystore nicht verfügbar) ──────────────────────
    // Gerätespezifischer Zufallsschlüssel in SharedPreferences.
    // Schlechter als Keystore, aber besser als gar keine Verschlüsselung.

    private fun getOrCreateFallback(context: Context): ByteArray {
        val prefs  = getPrefs(context)
        val stored = prefs.getString(KEY_FALLBACK, null)
        return if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit()
                .putString(KEY_FALLBACK, Base64.encodeToString(key, Base64.NO_WRAP))
                .apply()
            TorChatLogger.w(TAG, "Fallback-Schlüssel in SharedPreferences gespeichert")
            key
        }
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}
