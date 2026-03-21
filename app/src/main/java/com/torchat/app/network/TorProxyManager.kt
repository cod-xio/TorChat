package com.torchat.app.network

import android.util.Log
import com.torchat.app.TorChatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class TorProxyManager(
    private val context: android.content.Context,
    private val embeddedTor: EmbeddedTorManager
) {
    companion object {
        private const val TAG             = "TorProxyManager"
        private const val CONNECT_TIMEOUT = 30_000L  // 30s — Tor-Circuits brauchen Zeit
        private const val READ_TIMEOUT    = 20_000L
    }

    private val _torStatus = MutableStateFlow(TorStatus.DISCONNECTED)
    val torStatus: StateFlow<TorStatus> = _torStatus

    val usingEmbeddedTor: Boolean get() = embeddedTor.isRunning
    val activePort: Int           get() = embeddedTor.socksPort

    val torProxy: Proxy get() = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress(TorChatApp.TOR_PROXY_HOST, activePort)
    )

    val torHttpClient: OkHttpClient get() = OkHttpClient.Builder()
        .proxy(torProxy)
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()

    suspend fun checkTorConnectivity(): Boolean = withContext(Dispatchers.IO) {
        _torStatus.value = TorStatus.CONNECTING

        // 1. Eingebettetes Tor starten falls noch nicht
        val started = embeddedTor.isRunning || embeddedTor.start()
        if (!started) {
            _torStatus.value = TorStatus.ERROR
            Log.e(TAG, "Tor konnte nicht gestartet werden")
            return@withContext false
        }

        // 2. SOCKS5-Port prüfen
        val port = embeddedTor.socksPort
        return@withContext if (tryPort(port)) {
            _torStatus.value = TorStatus.CONNECTED
            Log.d(TAG, "✅ Tor SOCKS5:$port")
            true
        } else {
            // Tor läuft laut Status aber Port noch nicht offen — kurz warten
            kotlinx.coroutines.delay(3000)
            val retry = tryPort(port)
            _torStatus.value = if (retry) TorStatus.CONNECTED else TorStatus.ERROR
            Log.d(TAG, if (retry) "✅ Tor SOCKS5:$port (retry)" else "❌ SOCKS5:$port nicht erreichbar")
            retry
        }
    }

    private fun tryPort(port: Int): Boolean = try {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 3000); close() }
        true
    } catch (_: Exception) { false }

    suspend fun connectToOnion(onionAddress: String, port: Int): Socket? =
        withContext(Dispatchers.IO) {
            val socket = Socket(torProxy)
            try {
                socket.connect(InetSocketAddress(onionAddress, port), CONNECT_TIMEOUT.toInt())
                // Erfolgreiche Verbindung → Tor läuft definitiv
                _torStatus.value = TorStatus.CONNECTED
                Log.d(TAG, "Verbunden: $onionAddress:$port")
                socket
            } catch (e: Exception) {
                Log.d(TAG, "Verbindung zu ${onionAddress.take(20)} fehlgeschlagen: ${e.message}")
                runCatching { socket.close() }
                // connectToOnion setzt NIEMALS TorStatus.ERROR.
                // Ob Tor selbst kaputt ist, prüft checkTorConnectivity() im Heartbeat.
                null
            }
        }

    fun updateStatus(status: TorStatus) { _torStatus.value = status }

    /** Neuen Tor-Circuit anfordern (NEWNYM) */
    fun newCircuit() = try { embeddedTor.newCircuit() } catch (_: Exception) {}
}

enum class TorStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
