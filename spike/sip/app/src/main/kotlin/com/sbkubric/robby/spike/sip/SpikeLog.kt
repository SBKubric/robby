package com.sbkubric.robby.spike.sip

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two logs, both in memory, both shareable from the UI:
 *  - the measurement log: short lines with wall-clock time and "+N мс" since the button that started the operation;
 *  - the raw PJSIP log (tail only), with digest auth headers redacted.
 * The password is never passed here.
 */
object SpikeLog {
    private const val MAX_EVENTS = 4000
    private const val MAX_PJ_CHARS = 400_000

    private val events = ArrayDeque<String>()
    private val pj = StringBuilder()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Wall-clock time and, if [since] is given (an elapsedRealtime), the offset from it. */
    fun event(text: String, since: Long? = null) {
        val line = buildString {
            synchronized(clock) { append(clock.format(Date())) }
            if (since != null) append("  +").append(SystemClock.elapsedRealtime() - since).append(" мс")
            append("  ").append(text)
        }
        synchronized(this) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        android.util.Log.i("RobbySipSpike", line)
        notifyListeners()
    }

    fun pjsip(line: String) {
        val clean = redact(line)
        synchronized(pj) {
            pj.append(clean)
            if (!clean.endsWith('\n')) pj.append('\n')
            if (pj.length > MAX_PJ_CHARS) pj.delete(0, pj.length - MAX_PJ_CHARS)
        }
    }

    fun measurementText(): String = synchronized(this) { events.joinToString("\n") }

    fun pjsipText(): String = synchronized(pj) { pj.toString() }

    fun tail(lines: Int): String = synchronized(this) { events.toList().takeLast(lines).joinToString("\n") }

    fun clear() {
        synchronized(this) { events.clear() }
        synchronized(pj) { pj.setLength(0) }
        notifyListeners()
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        if (copy.isNotEmpty()) main.post { copy.forEach { it() } }
    }

    /** Digest responses are not the password, but there is no reason to share them either. */
    private val authHeader = Regex("(?im)^((?:Proxy-)?Authorization:).*$")
    private fun redact(s: String): String = authHeader.replace(s, "$1 <скрыто>")
}
