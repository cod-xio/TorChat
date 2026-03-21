package com.torchat.app.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fängt Crashes ab, speichert den kompletten Stack Trace
 * und zeigt ihn beim nächsten App-Start auf dem Bildschirm an.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val crashFile = File(context.filesDir, "last_crash.txt")

    companion object {
        const val TAG = "CrashHandler"

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.d(TAG, "CrashHandler installiert")
        }

        fun getLastCrash(context: Context): String? {
            val file = File(context.filesDir, "last_crash.txt")
            return if (file.exists()) file.readText().trim().ifEmpty { null }
            else null
        }

        fun clearLastCrash(context: Context) {
            File(context.filesDir, "last_crash.txt").delete()
        }

        fun getDeviceInfo(): String = buildString {
            appendLine("═══ GERÄT ═══════════════════════════")
            appendLine("Hersteller : ${Build.MANUFACTURER}")
            appendLine("Modell     : ${Build.MODEL}")
            appendLine("Android    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Brand      : ${Build.BRAND}")
            appendLine("Device     : ${Build.DEVICE}")
            appendLine("Build      : ${Build.DISPLAY}")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN)
                .format(Date())

            val report = buildString {
                appendLine("TorChat Crash-Report")
                appendLine("Zeit: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine(getDeviceInfo())
                appendLine()
                appendLine("═══ FEHLER ══════════════════════════")
                appendLine(stackTrace)
            }

            crashFile.writeText(report)
            Log.e(TAG, "Crash gespeichert:\n$report")
        } catch (e: Exception) {
            Log.e(TAG, "CrashHandler selbst gecrasht: ${e.message}")
        }

        // Original-Handler aufrufen (damit Android den Crash-Dialog zeigt)
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
