package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.PrintJob
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A release acknowledgement is retained even when the server removes its queue record. */
@Serializable
data class ReleaseRecord(
    val id: String,
    val jobLocation: String,
    val name: String,
    val printerLocation: String,
    val printerName: String,
    val requestedAt: Long,
    val accepted: Boolean,
    val serverState: String? = null,
    val checkedAt: Long? = null,
) {
    val status: String get() = when (serverState?.lowercase()) {
        "printed", "completed" -> "Completed · reported by server"
        "cancelled", "canceled" -> "Cancelled · reported by server"
        "aborted", "error" -> "Failed · reported by server"
        "queued" -> "Still waiting to be released · reported by server"
        "printing" -> "Printing · reported by server"
        else -> if (accepted) "Sent · completion unknown" else "Release unconfirmed"
    }
}

internal fun mergeReleaseHistory(existing: List<ReleaseRecord>, additions: List<ReleaseRecord>): List<ReleaseRecord> =
    (additions + existing).distinctBy { it.id }.sortedByDescending { it.requestedAt }.take(100)

/** An absent row proves neither completion nor cancellation. */
internal fun updateReleaseHistory(history: List<ReleaseRecord>, jobs: List<PrintJob>, checkedAt: Long): List<ReleaseRecord> {
    val byLocation = jobs.associateBy { it.location }
    val newest = history.groupBy { it.jobLocation }.mapValues { (_, records) -> records.maxBy { it.requestedAt }.id }
    return history.map { record ->
        if (record.id != newest[record.jobLocation] || record.serverState?.lowercase() in
            setOf("printed", "completed", "cancelled", "canceled", "aborted", "error")) return@map record
        val job = byLocation[record.jobLocation] ?: return@map record
        record.copy(serverState = job.printState, checkedAt = checkedAt)
    }
}

internal fun printActivityKey(server: String, account: String): String = "print_activity_" +
    MessageDigest.getInstance("SHA-256").digest("$server\n$account".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
