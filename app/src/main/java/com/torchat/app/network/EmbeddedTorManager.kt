package com.torchat.app.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.torchat.app.debug.TorChatLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService
import java.io.File
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Eingebettetes Tor via tor-android (Guardian Project).
 *
 * WICHTIG für Hidden Service (eingehende Verbindungen):
 *  - tor-android liest eine torrc aus context.filesDir/tor/torrc
 *  - Diese torrc muss HiddenServiceDir + HiddenServicePort enthalten
 *  - Nach Tor-Start: filesDir/tor/hs/hostname enthält die .onion-Adresse
 *
 * Ablauf:
 *  1. torrc schreiben mit HiddenServiceDir
 *  2. TorService binden + starten
 *  3. Auf STATUS_ON warten
 *  4. hostname-Datei lesen → eigene .onion-Adresse
 */
class EmbeddedTorManager(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddedTor"
        private const val TOR_NOTIFICATION_CHANNEL = "TorService"
        const val DEFAULT_SOCKS_PORT   = 9050
        const val DEFAULT_CONTROL_PORT = 9052
        const val DEFAULT_HS_PORT      = 11009
    }

    private val prefs = context.getSharedPreferences("torchat_tor_ports", Context.MODE_PRIVATE)

    var socksPort:     Int get() = prefs.getInt("socks_port", DEFAULT_SOCKS_PORT)
                           set(v) { prefs.edit().putInt("socks_port", v).apply() }
    var controlPort:   Int get() = prefs.getInt("ctrl_port",  DEFAULT_CONTROL_PORT)
                           set(v) { prefs.edit().putInt("ctrl_port",  v).apply() }
    var hiddenSvcPort: Int get() = prefs.getInt("hs_port",    DEFAULT_HS_PORT)
                           set(v) { prefs.edit().putInt("hs_port",    v).apply() }

    private val _status           = MutableStateFlow(EmbeddedTorStatus.STOPPED)
    val status: StateFlow<EmbeddedTorStatus> = _status

    private val _onionAddress     = MutableStateFlow<String?>(null)
    val onionAddress: StateFlow<String?> = _onionAddress

    private val _bootstrapPercent = MutableStateFlow(0)
    val bootstrapPercent: StateFlow<Int> = _bootstrapPercent

    val isRunning get() = _status.value == EmbeddedTorStatus.RUNNING

    // TorService liest torrc aus context.getDir("TorService", MODE_PRIVATE)
    // Quelle: TorService.java → getAppTorServiceDir()
    private val torServiceDir = context.getDir("TorService", Context.MODE_PRIVATE)
    private val torDataDir    = File(torServiceDir, "data").also { it.mkdirs() }
    private val hsDir         = File(torDataDir, "hs").also  { it.mkdirs() }
    private val torrcFile     = File(torServiceDir, "torrc")

    private var torService: TorService? = null
    private var isBound    = false
    private var receiverRegistered = false

    // ── ServiceConnection ─────────────────────────

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            TorChatLogger.i(TAG, "TorService gebunden")
            try {
                torService = (binder as? TorService.LocalBinder)?.service
                isBound = true
                torService?.socksPort?.takeIf { it > 0 }?.let {
                    socksPort = it
                    TorChatLogger.i(TAG, "SOCKS-Port vom Binder: $it")
                }
            } catch (e: Exception) {
                TorChatLogger.e(TAG, "onServiceConnected: ${e.message}", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            TorChatLogger.w(TAG, "TorService getrennt")
            torService = null; isBound = false
            if (_status.value == EmbeddedTorStatus.RUNNING)
                _status.value = EmbeddedTorStatus.STOPPED
        }
    }

    // ── Status-Broadcast ───────────────────────────

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            val st = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            TorChatLogger.i(TAG, "STATUS=$st")
            when (st) {
                TorService.STATUS_ON -> {
                    torService?.socksPort?.takeIf { it > 0 }?.let { socksPort = it }
                    _status.value = EmbeddedTorStatus.RUNNING
                    _bootstrapPercent.value = 100
                    TorChatLogger.i(TAG, "✅ Tor VERBUNDEN — SOCKS5:$socksPort")
                    readOnionAddress()
                }
                TorService.STATUS_STARTING -> {
                    _status.value = EmbeddedTorStatus.STARTING
                    TorChatLogger.d(TAG, "Tor startet...")
                }
                TorService.STATUS_OFF -> {
                    _status.value = EmbeddedTorStatus.STOPPED
                    _bootstrapPercent.value = 0
                    TorChatLogger.w(TAG, "Tor STATUS_OFF — starte neu...")
                    // Automatisch neu starten nach kurzer Pause
                    internalScope.launch {
                        delay(3000)
                        if (_status.value == EmbeddedTorStatus.STOPPED) {
                            TorChatLogger.i(TAG, "Auto-Restart nach STATUS_OFF")
                            start()
                        }
                    }
                }
                else -> TorChatLogger.d(TAG, "Status: $st")
            }
        }
    }

    // ── Start ──────────────────────────────────────

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            TorChatLogger.d(TAG, "Tor bereits gestartet")
            return@withContext true
        }
        _status.value = EmbeddedTorStatus.STARTING
        _bootstrapPercent.value = 0
        TorChatLogger.i(TAG, "═══ Starte Tor ═══")

        // Bereits bekannte Onion-Adresse sofort laden (aus letztem Start)
        val cachedAddress = File(hsDir, "hostname").takeIf { it.exists() }?.readText()?.trim()
        if (!cachedAddress.isNullOrEmpty()) {
            _onionAddress.value = cachedAddress
            TorChatLogger.i(TAG, "Bekannte Onion-Adresse: $cachedAddress")
        }

        // torrc schreiben
        writeTorrc()

        // 2. Notification Channel
        ensureNotificationChannel()

        // 3. Status Receiver registrieren
        if (!receiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        statusReceiver,
                        IntentFilter(TorService.ACTION_STATUS),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    context.registerReceiver(
                        statusReceiver,
                        IntentFilter(TorService.ACTION_STATUS)
                    )
                }
                receiverRegistered = true
                TorChatLogger.d(TAG, "Receiver registriert")
            } catch (e: Exception) { TorChatLogger.e(TAG, "Receiver", e) }
        }

        val intent = Intent(context, TorService::class.java)

        // 4. bindService
        try {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            TorChatLogger.d(TAG, "bindService: $ok")
        } catch (e: Exception) { TorChatLogger.e(TAG, "bindService", e) }

        // 5. startForegroundService
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            TorChatLogger.d(TAG, "startForegroundService gesendet")
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "start Fehler: ${e.javaClass.simpleName}: ${e.message}", e)
            _status.value = EmbeddedTorStatus.ERROR
            return@withContext false
        }

        // 6. Warten auf STATUS_ON
        TorChatLogger.i(TAG, "Warte auf Tor... (max 120s)")
        val deadline = System.currentTimeMillis() + 120_000L
        var lastLog  = 0L
        while (System.currentTimeMillis() < deadline) {
            if (_status.value == EmbeddedTorStatus.RUNNING) {
                TorChatLogger.i(TAG, "✅ Tor bereit!")
                return@withContext true
            }
            if (_status.value == EmbeddedTorStatus.ERROR) return@withContext false
            val elapsed = System.currentTimeMillis() - (deadline - 120_000L)
            _bootstrapPercent.value = ((elapsed / 1200L).toInt()).coerceIn(1, 99)
            if (System.currentTimeMillis() - lastLog > 15_000) {
                TorChatLogger.d(TAG, "Warte ${elapsed/1000}s — ${_status.value} bound=$isBound")
                lastLog = System.currentTimeMillis()
            }
            delay(500)
        }
        TorChatLogger.e(TAG, "⚠️ Timeout — ${_status.value} bound=$isBound port=$socksPort")
        _status.value = EmbeddedTorStatus.ERROR
        false
    }

    // ── Stop ───────────────────────────────────────

    fun stop() {
        runCatching { if (receiverRegistered) { context.unregisterReceiver(statusReceiver); receiverRegistered = false } }
        runCatching { if (isBound) { context.unbindService(connection); isBound = false } }
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
        torService = null
        _status.value = EmbeddedTorStatus.STOPPED
        _bootstrapPercent.value = 0
        TorChatLogger.i(TAG, "Tor gestoppt")
    }

    fun newCircuit() {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", controlPort), 2000)
                PrintWriter(s.getOutputStream(), true).apply {
                    println("AUTHENTICATE \"\""); println("SIGNAL NEWNYM"); println("QUIT")
                }
            }
            TorChatLogger.i(TAG, "Neue Route")
        } catch (e: Exception) { TorChatLogger.w(TAG, "NEWNYM: ${e.message}") }
    }

    /**
     * Löscht Hidden-Service-Keys → neue .onion-Adresse beim nächsten Tor-Start.
     * ACHTUNG: Bestehende Kontakte müssen die neue Adresse erhalten.
     */
    fun deleteHiddenServiceKeys() {
        try {
            hsDir.listFiles()?.forEach { it.delete() }
            _onionAddress.value = null
            TorChatLogger.i(TAG, "✅ HS-Keys gelöscht — neue Adresse beim nächsten Tor-Start")
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "deleteHiddenServiceKeys: ${e.message}", e)
        }
    }

    // ── torrc schreiben ────────────────────────────

    fun rewriteTorrc() = writeTorrc()

    private fun writeTorrc() {
        try {
            hsDir.mkdirs()

            // Alte torrc löschen (könnte DataDirectory-Konflikt enthalten)
            if (torrcFile.exists()) torrcFile.delete()

            // WICHTIG: DataDirectory NICHT setzen —
            // TorService verwaltet sein DataDirectory intern (getAppTorServiceDataDir).
            // Wenn wir DataDirectory überschreiben → Konflikt → sofortiges STOPPING.
            val torrc = buildString {
                appendLine("SocksPort $socksPort")
                appendLine("ControlPort $controlPort")
                appendLine("CookieAuthentication 0")
                appendLine("HiddenServiceDir ${hsDir.absolutePath}")
                appendLine("HiddenServicePort $hiddenSvcPort 127.0.0.1:$hiddenSvcPort")
            }
            torrcFile.writeText(torrc)
            TorChatLogger.i(TAG, "torrc → ${torrcFile.absolutePath}")
            TorChatLogger.d(TAG, torrc)
        } catch (e: Exception) {
            TorChatLogger.e(TAG, "torrc Fehler: ${e.message}", e)
        }
    }

    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Onion-Adresse lesen ────────────────────────

    private fun readOnionAddress() {
        val hostname = File(hsDir, "hostname")

        // Sofort lesen wenn Datei vorhanden (Normalfall nach Reconnect)
        if (hostname.exists()) {
            val addr = hostname.readText().trim()
            if (addr.isNotEmpty()) {
                _onionAddress.value = addr
                TorChatLogger.i(TAG, "🧅 Onion-Adresse (sofort): $addr")
                return
            }
        }

        // Datei noch nicht da → im Hintergrund warten (Erststart)
        internalScope.launch {
            TorChatLogger.d(TAG, "Warte auf hostname in: ${hsDir.absolutePath}")
            val deadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < deadline) {
                if (hostname.exists()) {
                    val addr = hostname.readText().trim()
                    if (addr.isNotEmpty()) {
                        _onionAddress.value = addr
                        TorChatLogger.i(TAG, "🧅 Onion-Adresse: $addr")
                        return@launch
                    }
                }
                delay(2000)
            }
            TorChatLogger.w(TAG, "hostname nicht gefunden nach 120s in: ${hsDir.absolutePath}")
            hsDir.parentFile?.listFiles()?.forEach {
                TorChatLogger.d(TAG, "  ${it.name} (${it.length()}b)")
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(TOR_NOTIFICATION_CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(
                        TOR_NOTIFICATION_CHANNEL, "Tor Service",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Tor network daemon"; setShowBadge(false)
                    })
                    TorChatLogger.d(TAG, "Notification Channel erstellt")
                }
            } catch (e: Exception) { TorChatLogger.w(TAG, "Channel: ${e.message}") }
        }
    }
}

enum class EmbeddedTorStatus { STOPPED, STARTING, RUNNING, ERROR, NOT_AVAILABLE }
