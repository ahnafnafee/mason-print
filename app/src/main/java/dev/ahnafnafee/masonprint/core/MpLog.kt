package dev.ahnafnafee.masonprint.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A ring buffer that is also the app's log.
 *
 * The stock app's `DebugTrace.LogMessage()` is an **empty method** (docs/FINDINGS.md §12), so
 * there is nothing to capture from `logcat` and support has to reach for a proxy. An app that
 * cannot be diagnosed by a student in a dorm cannot be supported, so every request outcome,
 * capability read, and TLS decision lands here — shown in-app under Account → Diagnostics, and
 * exportable as the text file the FileProvider path in `xml/file_paths.xml` was added for.
 *
 * Deliberately tiny: 400 entries, no file I/O on the caller's thread, no third-party dependency.
 */
object MpLog {

    enum class Level { Debug, Info, Warn, Error }

    data class Entry(val at: Long, val level: Level, val tag: String, val message: String) {
        val stamp: String get() = TIME.format(Date(at))
        override fun toString(): String = "$stamp $level/$tag: $message"
    }

    private const val CAPACITY = 400
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun debug(tag: String, message: String) = log(Level.Debug, tag, message)
    fun info(tag: String, message: String) = log(Level.Info, tag, message)
    fun warn(tag: String, message: String, t: Throwable? = null) =
        log(Level.Warn, tag, message + (t?.let { ". ${it.javaClass.simpleName}: ${it.message}" } ?: ""), t)

    fun error(tag: String, message: String, t: Throwable? = null) =
        log(Level.Error, tag, message + (t?.let { ". ${it.javaClass.simpleName}: ${it.message}" } ?: ""), t)

    private fun log(level: Level, tag: String, message: String, t: Throwable? = null) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        val tagged = "MasonPrint/$tag"
        // The throwable goes to logcat as well as the one-line summary. A failure inside a
        // minified third-party library is otherwise unreadable: the class name in the summary is
        // whatever R8 renamed it to, and the frames — the only thing that says *which* library —
        // exist only in the trace.
        when (level) {
            Level.Debug -> android.util.Log.d(tagged, message, t)
            Level.Info -> android.util.Log.i(tagged, message, t)
            Level.Warn -> android.util.Log.w(tagged, message, t)
            Level.Error -> android.util.Log.e(tagged, message, t)
        }
    }

    fun dump(): String = synchronized(lock) { buffer.joinToString("\n") }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
