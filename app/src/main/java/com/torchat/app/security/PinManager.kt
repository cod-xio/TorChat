package com.torchat.app.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN-Schutz für TorChat.
 * PIN wird mit PBKDF2+SHA256 + Salt gehasht (niemals Klartext).
 */
class PinManager(context: Context) {

    private val prefs = context.getSharedPreferences("torchat_pin", Context.MODE_PRIVATE)

    companion object {
        private const val TAG            = "PinManager"
        private const val KEY_PIN_HASH   = "pin_hash"
        private const val KEY_PIN_SALT   = "pin_salt"
        private const val KEY_PIN_ACTIVE = "pin_active"
        private const val ITERATIONS     = 100_000
        private const val KEY_LENGTH     = 256
        const        val MIN_PIN_LENGTH  = 4
    }

    val isPinActive: Boolean
        get() = prefs.getBoolean(KEY_PIN_ACTIVE, false)

    fun setPin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH) return false
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_HASH,   hash)
            .putString(KEY_PIN_SALT,   Base64.encodeToString(salt, Base64.NO_WRAP))
            .putBoolean(KEY_PIN_ACTIVE, true)
            .apply()
        Log.d(TAG, "PIN gesetzt")
        return true
    }

    fun checkPin(pin: String): Boolean {
        if (!isPinActive) return true
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored  = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin, Base64.decode(saltB64, Base64.NO_WRAP)) == stored
    }

    fun removePin(currentPin: String): Boolean {
        if (!checkPin(currentPin)) return false
        prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT)
            .putBoolean(KEY_PIN_ACTIVE, false).apply()
        return true
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!checkPin(oldPin)) return false
        return setPin(newPin)
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec    = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }
}
