package com.torchat.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.torchat.app.debug.TorChatLogger
import com.torchat.app.model.Identity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeyManager(private val context: Context) {

    companion object {
        private const val TAG            = "KeyManager"
        private const val PREF_FILE      = "torchat_keys"
        private const val KEY_PRIVATE    = "rsa_private_key"
        private const val KEY_PUBLIC     = "rsa_public_key"
        private const val KEY_IDENTITY   = "identity_name"
        private const val KEY_ONION      = "onion_address"
        // RSA-2048: sicher für Key-Exchange, 10x schneller als 4096 auf Mobile
        // RSA-4096 dauert auf Samsung 3-8 Sekunden → blockiert Tor-Heartbeat
        private const val RSA_KEY_SIZE   = 2048
        private const val GCM_IV_LENGTH  = 12
        private const val GCM_TAG_LENGTH = 128

        // BouncyCastle-Provider korrekt registrieren:
        // Android hat einen eingebauten "BC"-Provider der AES nicht unterstützt.
        // Wir entfernen ihn und fügen den echten BouncyCastle an Position 1 ein.
        private var providerInstalled = false

        init {
            if (!providerInstalled) {
                try { Security.removeProvider("BC") } catch (_: Exception) {}
                try {
                    Security.insertProviderAt(BouncyCastleProvider(), 1)
                    providerInstalled = true
                    TorChatLogger.i(TAG, "BouncyCastle Provider registriert an Position 1")
                } catch (e: Exception) {
                    TorChatLogger.e(TAG, "BouncyCastle Provider: ${e.message}", e)
                }
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    private var _privateKey: PrivateKey? = null
    private var _publicKey:  PublicKey?  = null

    val publicKeyBase64: String  get() = prefs.getString(KEY_PUBLIC,   "") ?: ""
    val onionAddress:    String  get() = prefs.getString(KEY_ONION,    "") ?: ""
    /** Anzeigename für die eigene UI — immer befüllt (Fallback: "User N") */
    val displayName: String get() {
        val stored = prefs.getString(KEY_IDENTITY, null)
        if (!stored.isNullOrEmpty()) return stored
        // Kein Name gesetzt → "User N" als lokalen Fallback generieren
        val n = prefs.getInt("user_counter", 0) + 1
        val generated = "User $n"
        prefs.edit()
            .putInt("user_counter", n)
            .putString(KEY_IDENTITY, generated)
            .apply()
        return generated
    }

    /** Name der beim Senden übertragen wird — nur wenn der User ihn explizit gesetzt hat, sonst leer */
    val transmittedDisplayName: String get() = displayName

    val isInitialized:   Boolean get() = publicKeyBase64.isNotEmpty()

    val identity: Identity get() = Identity(
        onionAddress    = onionAddress,
        publicKeyBase64 = publicKeyBase64,
        displayName     = displayName
    )

    // ── Initialisierung ───────────────────────────

    suspend fun initializeKeys() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Prüfen ob vorhandener Key die richtige Größe hat
            // Bei Upgrade von RSA-4096 → RSA-2048 neuen Key generieren
            val existingPriv = prefs.getString(KEY_PRIVATE, null)
            val needsRegen = if (existingPriv != null) {
                val privBytes = Base64.decode(existingPriv, Base64.NO_WRAP)
                // RSA-4096 Private Keys sind typisch > 2400 Bytes, RSA-2048 < 1300 Bytes
                privBytes.size > 2000
            } else true

            if (needsRegen) {
                TorChatLogger.i(TAG, "Generiere neuen RSA-$RSA_KEY_SIZE Schlüssel...")
                generateNewIdentity()
            } else {
                loadKeys()
            }
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "initializeKeys: ${e.message}", e)
            try { generateNewIdentity() } catch (e2: Exception) {
                TorChatLogger.e(TAG, "generateNewIdentity fehlgeschlagen: ${e2.message}", e2)
            }
        }
    }

    private fun generateNewIdentity() {
        TorChatLogger.i(TAG, "Generiere RSA-$RSA_KEY_SIZE Identität...")
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(RSA_KEY_SIZE, SecureRandom())
        val kp = kpg.generateKeyPair()

        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64  = Base64.encodeToString(kp.public.encoded,  Base64.NO_WRAP)

        // WICHTIG: Keine fake Onion-Adresse generieren!
        // Die echte .onion-Adresse kommt vom EmbeddedTorManager (Tor Hidden Service).
        // updateOnionAddress() wird aufgerufen sobald Tor bereit ist.
        val existingOnion = onionAddress  // bestehende Adresse beibehalten falls vorhanden

        prefs.edit()
            .putString(KEY_PRIVATE, privB64)
            .putString(KEY_PUBLIC,  pubB64)
            .apply()

        _privateKey = kp.private
        _publicKey  = kp.public
        TorChatLogger.i(TAG, "✅ RSA-Schlüssel generiert (Onion-Adresse kommt von Tor)")
    }

    private fun loadKeys() {
        val privB64 = prefs.getString(KEY_PRIVATE, null) ?: return
        val pubB64  = prefs.getString(KEY_PUBLIC,  null) ?: return
        val factory = KeyFactory.getInstance("RSA", "BC")
        _privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
        _publicKey  = factory.generatePublic( X509EncodedKeySpec(Base64.decode(pubB64,  Base64.NO_WRAP)))
        TorChatLogger.d(TAG, "Schlüssel geladen")
    }

    fun setDisplayName(name: String) { prefs.edit().putString(KEY_IDENTITY, name).apply() }

    fun updateOnionAddress(address: String) {
        if (address.isBlank()) return  // Niemals leere Adresse speichern
        val normalized = address.trim().let { if (it.endsWith(".onion")) it else "$it.onion" }
        // Nur aktualisieren wenn Adresse sich wirklich geändert hat
        val current = onionAddress
        if (current == normalized) {
            TorChatLogger.d(TAG, "Onion-Adresse unverändert: $normalized")
            return
        }
        prefs.edit().putString(KEY_ONION, normalized).apply()
        TorChatLogger.i(TAG, "Onion-Adresse gespeichert: $normalized")
    }

    /** Löscht die gespeicherte Onion-Adresse → TorService generiert neue Keys beim nächsten Start */
    fun resetOnionAddress() {
        prefs.edit().remove(KEY_ONION).apply()
        TorChatLogger.i(TAG, "Onion-Adresse zurückgesetzt — neue Adresse beim nächsten Tor-Start")
    }

    // ── Verschlüsselung ───────────────────────────
    // Explizit auf Dispatchers.IO → blockiert nie den Tor-Heartbeat
    // AES/GCM:  Android Standard-JCE  (kein "BC" Provider-String)
    // RSA/OAEP: BouncyCastle          (explizit "BC")

    suspend fun encryptMessage(plaintext: String, recipientPubKeyB64: String): EncryptedMessage =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 1. AES-256-Schlüssel + IV
            val aesKey = KeyGenerator.getInstance("AES").apply {
                init(256, SecureRandom())
            }.generateKey()
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }

            // 2. AES/GCM verschlüsseln
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val ciphertext = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 3. AES-Key mit RSA-OAEP verschlüsseln
            val recipientKey = KeyFactory.getInstance("RSA", "BC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(recipientPubKeyB64, Base64.NO_WRAP)))
            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "BC")
            rsaCipher.init(Cipher.ENCRYPT_MODE, recipientKey)
            val encryptedKey = rsaCipher.doFinal(aesKey.encoded)

            // 4. Signatur
            val signature = _privateKey?.let { pk ->
                Signature.getInstance("SHA256withRSA", "BC").run {
                    initSign(pk); update(ciphertext); sign()
                }
            } ?: ByteArray(0)

            EncryptedMessage(
                ciphertext   = Base64.encodeToString(ciphertext,   Base64.NO_WRAP),
                encryptedKey = Base64.encodeToString(encryptedKey, Base64.NO_WRAP),
                iv           = Base64.encodeToString(iv,           Base64.NO_WRAP),
                signature    = Base64.encodeToString(signature,    Base64.NO_WRAP),
                senderPubKey = publicKeyBase64
            )
        }

    suspend fun decryptMessage(encrypted: EncryptedMessage, senderPubKeyB64: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ciphertext   = Base64.decode(encrypted.ciphertext,   Base64.NO_WRAP)
            val encryptedKey = Base64.decode(encrypted.encryptedKey, Base64.NO_WRAP)
            val iv           = Base64.decode(encrypted.iv,           Base64.NO_WRAP)

            // 1. AES-Key mit RSA entschlüsseln
            val privKey = _privateKey ?: throw IllegalStateException("Private Key nicht geladen")
            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "BC")
            rsaCipher.init(Cipher.DECRYPT_MODE, privKey)
            val aesKeyBytes = rsaCipher.doFinal(encryptedKey)
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")

            // 2. AES/GCM entschlüsseln
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(aesCipher.doFinal(ciphertext), Charsets.UTF_8)
        }
}

data class EncryptedMessage(
    val ciphertext:   String,
    val encryptedKey: String,
    val iv:           String,
    val signature:    String,
    val senderPubKey: String = ""
)
