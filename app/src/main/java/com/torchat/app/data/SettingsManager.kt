package com.torchat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistente App-Einstellungen in SharedPreferences.
 * Stealth-Modus, automatisch löschende Nachrichten etc.
 */
object SettingsManager {

    private const val PREF_FILE              = "torchat_settings"
    private const val KEY_DISAPPEARING       = "disappearing_messages"
    private const val KEY_DISAPPEARING_SECS  = "disappearing_seconds"
    private const val KEY_STEALTH            = "stealth_mode"
    private const val KEY_NOTIFICATIONS      = "notifications_enabled"
    private const val KEY_DISPLAY_NAME       = "display_name"
    private const val KEY_BG_TYPE            = "bg_type"       // "color" | "image"
    private const val KEY_BG_COLOR           = "bg_color"      // ARGB hex z.B. "#FF060810"
    private const val KEY_BG_IMAGE_URI       = "bg_image_uri"  // content:// URI

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── Disappearing Messages ───────────────
    fun isDisappearingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISAPPEARING, false)

    fun setDisappearing(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_DISAPPEARING, enabled).apply()

    fun getDisappearingSeconds(context: Context): Int =
        prefs(context).getInt(KEY_DISAPPEARING_SECS, 86400) // Default 24h

    fun setDisappearingSeconds(context: Context, seconds: Int) =
        prefs(context).edit().putInt(KEY_DISAPPEARING_SECS, seconds).apply()

    // ── Stealth Mode ────────────────────────
    fun isStealthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STEALTH, false)

    fun setStealth(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_STEALTH, enabled).apply()

    // ── Notifications ───────────────────────
    fun isNotificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS, true)

    fun setNotifications(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()

    // ── App-Hintergrund ─────────────────────────
    fun getBgType(context: Context): String =
        prefs(context).getString(KEY_BG_TYPE, "color") ?: "color"

    fun getBgColor(context: Context): String =
        prefs(context).getString(KEY_BG_COLOR, "#FF060810") ?: "#FF060810"

    fun getBgImageUri(context: Context): String =
        prefs(context).getString(KEY_BG_IMAGE_URI, "") ?: ""

    fun setBgColor(context: Context, colorHex: String) {
        prefs(context).edit()
            .putString(KEY_BG_TYPE, "color")
            .putString(KEY_BG_COLOR, colorHex)
            .apply()
    }

    fun setBgImage(context: Context, uri: String) {
        prefs(context).edit()
            .putString(KEY_BG_TYPE, "image")
            .putString(KEY_BG_IMAGE_URI, uri)
            .apply()
    }

    fun resetBg(context: Context) {
        prefs(context).edit()
            .putString(KEY_BG_TYPE, "color")
            .putString(KEY_BG_COLOR, "#FF060810")
            .putString(KEY_BG_IMAGE_URI, "")
            .apply()
    }
}
