package com.torchat.app.debug

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Führt beim App-Start Diagnosen durch und speichert die Ergebnisse.
 * Hilft Samsung-spezifische Probleme zu identifizieren ohne Logcat.
 */
object StartupDiagnostics {

    private const val TAG = "Diagnostics"
    private const val DIAG_FILE = "startup_diag.txt"

    fun run(context: Context): String {
        val sb = StringBuilder()

        sb.appendLine("═══ STARTUP DIAGNOSTICS ════════════")
        sb.appendLine(CrashHandler.getDeviceInfo())
        sb.appendLine()

        // App-Info
        sb.appendLine("═══ APP ══════════════════════════════")
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("Package  : ${context.packageName}")
            sb.appendLine("Version  : ${pi.versionName} (${pi.longVersionCode})")
        } catch (e: Exception) { sb.appendLine("App-Info: ${e.message}") }

        // Speicher
        sb.appendLine()
        sb.appendLine("═══ SPEICHER ═════════════════════════")
        try {
            val filesDir = context.filesDir
            sb.appendLine("filesDir : ${filesDir.absolutePath}")
            sb.appendLine("  Vorhanden: ${filesDir.exists()}")
            sb.appendLine("  Schreibbar: ${filesDir.canWrite()}")
            sb.appendLine("  Freier Speicher: ${filesDir.freeSpace / 1024 / 1024} MB")
        } catch (e: Exception) { sb.appendLine("Speicher: ${e.message}") }

        // Netzwerk
        sb.appendLine()
        sb.appendLine("═══ NETZWERK ════════════════════════")
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            sb.appendLine("Verbunden: ${net != null}")
            if (caps != null) {
                sb.appendLine("WIFI: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}")
                sb.appendLine("Mobil: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
            }
        } catch (e: Exception) { sb.appendLine("Netzwerk: ${e.message}") }

        // Ports prüfen
        sb.appendLine()
        sb.appendLine("═══ PORTS ════════════════════════════")
        for (port in listOf(9050, 9052, 9151, 11009)) {
            val open = isPortOpen(port)
            sb.appendLine("Port $port: ${if (open) "✅ offen" else "❌ geschlossen"}")
        }

        // Tor-Binary
        sb.appendLine()
        sb.appendLine("═══ TOR-BINARY ══════════════════════")
        try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            sb.appendLine("nativeLibraryDir: ${nativeDir.absolutePath}")
            val files = nativeDir.listFiles()
            if (files != null) {
                sb.appendLine("Dateien (${files.size}):")
                files.forEach { f ->
                    sb.appendLine("  ${f.name} (${f.length() / 1024} KB)")
                }
            } else {
                sb.appendLine("  (leer oder nicht lesbar)")
            }

            val libtor = File(nativeDir, "libtor.so")
            sb.appendLine("libtor.so vorhanden: ${libtor.exists()}")
            if (libtor.exists()) {
                sb.appendLine("libtor.so Größe: ${libtor.length() / 1024} KB")
                sb.appendLine("libtor.so ausführbar: ${libtor.canExecute()}")
            }
        } catch (e: Exception) { sb.appendLine("Tor-Binary: ${e.message}") }

        // TorService verfügbar?
        sb.appendLine()
        sb.appendLine("═══ TOR SERVICE ══════════════════════")
        try {
            Class.forName("org.torproject.jni.TorService")
            sb.appendLine("TorService (JNI): ✅ verfügbar")
        } catch (_: ClassNotFoundException) {
            sb.appendLine("TorService (JNI): ❌ nicht gefunden")
            sb.appendLine("  → tor-android AAR nicht geladen")
        }

        // Permissions
        sb.appendLine()
        sb.appendLine("═══ DATENBANK ════════════════════════")
        try {
            val dbFile = context.getDatabasePath("torchat_db")
            sb.appendLine("DB vorhanden: ${dbFile.exists()}")
            sb.appendLine("DB-Größe: ${dbFile.length() / 1024} KB")
            val migrated = context.getSharedPreferences("torchat_dbkey_v2", Context.MODE_PRIVATE)
                .getBoolean("encrypted_v2", false)
            sb.appendLine("Verschlüsselung: ${if (migrated || !dbFile.exists()) "✅ SQLCipher AES-256" else "⚠ Ausstehend"}")

            // Keystore-Status prüfen
            val keystoreOk = try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.containsAlias("torchat_db_master_v2")
            } catch (_: Exception) { false }
            sb.appendLine("Schlüssel-Speicher: ${if (keystoreOk) "🔐 Android Keystore (Hardware)" else "🎲 Fallback (SharedPrefs)"}")
        } catch (e: Exception) { sb.appendLine("DB: ${e.message}") }

        sb.appendLine()
        sb.appendLine("═══ PERMISSIONS ══════════════════════")
        val perms = listOf(
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        )
        for (perm in perms) {
            val granted = context.checkSelfPermission(perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val short = perm.removePrefix("android.permission.")
            sb.appendLine("$short: ${if (granted) "✅" else "❌"}")
        }

        // Letzter Crash
        sb.appendLine()
        sb.appendLine("═══ LETZTER CRASH ═══════════════════")
        val lastCrash = CrashHandler.getLastCrash(context)
        if (lastCrash != null) {
            sb.appendLine(lastCrash)
        } else {
            sb.appendLine("(kein Crash gespeichert)")
        }

        val result = sb.toString()
        Log.d(TAG, result)
        File(context.filesDir, DIAG_FILE).writeText(result)
        return result
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: Exception) { false }
}
