package dev.ahnafnafee.masonprint.core

import android.content.Context
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.PharosUser
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.SettingsDocument
import dev.ahnafnafee.masonprint.data.model.toJsonObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The offline snapshot: the last *wire bodies* the queue is built from, kept on disk so a cold
 * start with no network still shows the held jobs and the balance instead of a spinner the student
 * cannot act on (Spec §2.0.8: "airplane mode gives the vendor user a modal and an empty app").
 *
 * Raw bodies, not parsed models, on purpose: the parsers in `data/model` are the one place that
 * knows the API's casing games, and caching their *output* would fork that knowledge into a second
 * shape that drifts. A cached body re-parses through exactly the code a live body goes through, so
 * a parser fix fixes the cache too.
 *
 * What is in it is the minimum the queue screen needs to be readable offline — the first jobs page,
 * the user (name, alias, balance, cost centres), the settings document (money formats, upload
 * limits, capability switches), and the `savedAt` stamp the "cached N minutes ago" line reads.
 * Cleared on sign-out: the queue and the balance are account data and do not outlive the session
 * on a shared device.
 */
class SnapshotCache(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "queue_snapshot.json"))

    /** What a save captured, in wire terms. */
    data class Snapshot(
        val savedAt: Long,
        val host: String,
        val apiVersion: String?,
        val jobsBody: String,
        val userBody: String,
        val settingsBody: String?,
    )

    /** The snapshot re-parsed into the models the UI reads. Null pieces mean "not cached". */
    data class Restored(
        val snapshot: Snapshot,
        val jobsPage: Page<PrintJob>?,
        val user: PharosUser?,
        val capabilities: dev.ahnafnafee.masonprint.data.model.Capabilities?,
    )

    fun save(snapshot: Snapshot) {
        runCatching {
            val json = buildJsonObject {
                put("savedAt", snapshot.savedAt)
                put("host", snapshot.host)
                snapshot.apiVersion?.let { put("apiVersion", it) }
                put("jobsBody", snapshot.jobsBody)
                put("userBody", snapshot.userBody)
                snapshot.settingsBody?.let { put("settingsBody", it) }
            }.toString()
            // Temp + rename, so a process death mid-write can never leave a half snapshot that
            // parses as empty and silently erases a good one.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun load(): Restored? {
        val snap = readRaw() ?: return null
        val jobsPage = runCatching {
            Page.from(snap.jobsBody.toJsonObject(), PrintJob::from)
        }.getOrNull() ?: return null
        val user = runCatching { PharosUser.from(snap.userBody.toJsonObject()) }.getOrNull()
        val caps = snap.settingsBody?.let { body ->
            runCatching {
                SettingsDocument(body.toJsonObject()).capabilities(snap.apiVersion, user)
            }.getOrNull()
        }
        return Restored(snap, jobsPage, user, caps)
    }

    private fun readRaw(): Snapshot? = runCatching {
        if (!file.exists()) return null
        val o: JsonObject = file.readText().toJsonObject()
        Snapshot(
            savedAt = o["savedAt"]?.jsonPrimitive?.long ?: return null,
            host = o["host"]?.jsonPrimitive?.content ?: return null,
            apiVersion = o["apiVersion"]?.jsonPrimitive?.content,
            jobsBody = o["jobsBody"]?.jsonPrimitive?.content ?: return null,
            userBody = o["userBody"]?.jsonPrimitive?.content ?: return null,
            settingsBody = o["settingsBody"]?.jsonPrimitive?.content,
        )
    }.getOrNull()

    fun clear() {
        runCatching {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
    }
}

/** Suspend wrapper: the snapshot is small but it is still disk, and boot is not blocking on it. */
internal suspend fun SnapshotCache.loadAsync(): SnapshotCache.Restored? =
    withContext(Dispatchers.IO) { load() }

internal suspend fun SnapshotCache.saveAsync(snapshot: SnapshotCache.Snapshot): Unit =
    withContext(Dispatchers.IO) { save(snapshot) }

internal suspend fun SnapshotCache.clearAsync(): Unit =
    withContext(Dispatchers.IO) { clear() }
