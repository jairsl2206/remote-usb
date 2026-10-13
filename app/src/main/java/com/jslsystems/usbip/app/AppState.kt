package com.jslsystems.usbip.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jslsystems.usbip.core.LogLevel
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Event log and change notifications shared by the service and the status screen (F3). */
object AppState {
    private const val TAG = "UsbIp"
    private const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var showDebug = false

    fun log(level: LogLevel, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
        if (level == LogLevel.DEBUG && !showDebug) return
        val prefix = when (level) {
            LogLevel.WARN -> "⚠ "
            LogLevel.ERROR -> "✖ "
            else -> ""
        }
        synchronized(lines) {
            lines.addLast("${time.format(Date())}  $prefix$message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        changed()
    }

    fun logLines(): List<String> = synchronized(lines) { lines.toList() }

    fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_AUTOSTART = "autostart"

    fun autostart(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART, value).apply()
    }
}
