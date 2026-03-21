package com.torchat.app.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Zentrales In-App-Log — alle wichtigen Ereignisse werden hier gesammelt
 * und im Log-Tab der Einstellungen angezeigt.
 */
object TorChatLogger {

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        enum class Level(val symbol: String, val color: Long) {
            DEBUG  ("🔵", 0xFF6B9AD4),
            INFO   ("🟢", 0xFF00FF9D),
            WARN   ("🟡", 0xFFF59E0B),
            ERROR  ("🔴", 0xFFEF4444)
        }
        val formatted get() = "$timestamp [${level.symbol}] $tag: $message"
    }

    private const val MAX_ENTRIES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)
    private val _entries = CopyOnWriteArrayList<LogEntry>()

    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _flow

    fun d(tag: String, msg: String) { add(LogEntry.Level.DEBUG, tag, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { add(LogEntry.Level.INFO,  tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { add(LogEntry.Level.WARN,  tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { add(LogEntry.Level.ERROR, tag, msg); Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) {
        add(LogEntry.Level.ERROR, tag, "$msg — ${t.javaClass.simpleName}: ${t.message}")
        Log.e(tag, msg, t)
    }

    private fun add(level: LogEntry.Level, tag: String, msg: String) {
        val entry = LogEntry(fmt.format(Date()), level, tag, msg)
        _entries.add(entry)
        if (_entries.size > MAX_ENTRIES) _entries.removeAt(0)
        _flow.value = _entries.toList()
    }

    fun clear() { _entries.clear(); _flow.value = emptyList() }

    fun getAll(): String = _entries.joinToString("\n") { it.formatted }
}
