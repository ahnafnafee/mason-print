package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.humanBytes
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import dev.ahnafnafee.masonprint.data.net.UploadSource

/**
 * A document the user asked to print, in the shape the UI can hold.
 *
 * [UploadSource] deliberately carries a re-openable file descriptor, which is what makes a retry
 * possible without asking again — but it is not a value: two resolutions of the same file are not
 * `equals`, so it cannot key a list, and a screen has no business holding an openable handle it will
 * not read. The batch this device was handed is data; the handle belongs to the upload.
 */
data class PickedFile(val name: String, val mimeType: String, val sizeBytes: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    companion object {
        fun of(source: UploadSource): PickedFile = PickedFile(source.fileName, source.mimeType, source.sizeBytes)
    }
}

/** What a multi-file pick splits into before a single byte has moved. */
internal class UploadPlan(val send: List<UploadSource>, val overLimit: List<UploadSource>) {
    val nothingToSend: Boolean get() = send.isEmpty()
}

/**
 * Hold back the files this device can already tell will be refused.
 *
 * Size is the only check that can be made per file without the server, and it is the one worth
 * making: on a five-file pick, one 80 MB scan should not cost the other four their upload *or*
 * make the user watch a progress bar fail. Everything else — extension, quotas, whether the
 * account may upload at all — stays the server's answer, because the client's own type list is only
 * a hint of what a deployment prints (docs/CLONE-PLAN.md §5).
 *
 * A file whose size the provider never reported is *sent*, not held back: "unknown" is not
 * evidence of "too big".
 */
internal fun planUpload(sources: List<UploadSource>, limitBytes: Long?): UploadPlan {
    val limit = limitBytes?.takeIf { it > 0 } ?: return UploadPlan(sources, emptyList())
    val (over, ok) = sources.partition { it.sizeBytes > limit }
    return UploadPlan(ok, over)
}

/** `2 of 4`, or null when there is no batch worth counting. */
internal fun batchPosition(index: Int, total: Int): String? =
    if (index >= 1 && total > 1) "$index of $total" else null

/** The busy line while one file is transferring. */
internal fun sendingLabel(fileName: String, index: Int, total: Int): String =
    batchPosition(index, total)?.let { "Sending $it · $fileName" } ?: "Uploading $fileName"

/**
 * The line under a progress bar while a pick of several files is going up.
 *
 * The fraction is *this file's*, because a bar across the whole batch would need every provider to
 * report a size and several report −1. So the words must say which document the bar belongs to —
 * otherwise "60%" reads as 60% of the four files the user just picked, and the fourth one arrives
 * while they thought it had already gone.
 */
internal fun uploadProgressDetail(files: List<PickedFile>, index: Int, fraction: Float): String {
    val position = batchPosition(index, files.size)
    val name = files.getOrNull(index - 1)?.name
    val which = when {
        position != null && name != null -> "file $position · $name"
        name != null -> name
        position != null -> "file $position"
        else -> "the document"
    }
    val pct = (fraction.coerceIn(0f, 1f) * 100).toInt()
    return "Uploading $which · $pct% written. Leaving this screen does not cancel it" +
        (if (position != null) ", and the rest of the pick follows this file." else ".")
}

/**
 * Failures where trying the *next* file would just burn the user's data.
 *
 * A refusal about one document (its type, its size, one job's permissions) says nothing about the
 * others, so the batch continues. A failure about the connection or the account says the same thing
 * will happen eleven times, and on a metered connection that is a real cost.
 */
internal val PharosFailure.stopsBatch: Boolean
    get() = this is PharosFailure.Offline ||
        this is PharosFailure.Timeout ||
        this is PharosFailure.TlsNotTrusted ||
        this is PharosFailure.Unauthenticated ||
        this is PharosFailure.UploadDenied ||
        this is PharosFailure.NotAPharosServer

/** Why files were never sent, in the client's own words — a local check, so never quoted as the server's. */
internal fun oversizeSentence(over: List<UploadSource>, limitBytes: Long?): String {
    val limit = limitBytes?.takeIf { it > 0 }?.let(::humanBytes)
    val only = over.firstOrNull() ?: return "Nothing could be sent."
    val sizeOf = { s: UploadSource -> if (s.sizeBytes > 0) humanBytes(s.sizeBytes) else "an unknown size" }
    return if (over.size == 1) {
        "${only.fileName} is ${sizeOf(only)}; this server accepts $limit"
    } else {
        val names = over.joinToString(", ") { "${it.fileName} (${sizeOf(it)})" }
        "$names are over the $limit limit this server publishes, so none of them were sent"
    }
}

/**
 * The one honest sentence about a send that involved more than one file.
 *
 * "Uploaded" is avoided on purpose: a partial batch is the common case this exists for (one file
 * over the limit, one refused for its type), and a snackbar that says "Sent" after two of four
 * documents arrived is how a student finds out at the printer that two assignments did not. Names
 * are listed rather than counted when the batch got refused, because the user has to know *which*.
 */
internal fun batchSummary(
    sent: List<String>,
    refused: List<String>,
    notSentForSize: List<String>,
    notAttempted: List<String> = emptyList(),
    stoppedEarly: Boolean = notAttempted.isNotEmpty(),
): String {
    val total = sent.size + refused.size + notSentForSize.size + notAttempted.size
    val untouched = refused.isEmpty() && notSentForSize.isEmpty() && notAttempted.isEmpty()
    return when {
        total == 0 -> "Nothing was uploaded."

        untouched -> if (total == 1) "Sent ${sent.first()}" else "Sent ${sent.size} documents"

        sent.isEmpty() && total == 1 ->
            "Nothing was uploaded from ${(refused + notSentForSize + notAttempted).first()}."

        sent.isEmpty() -> {
            val parts = buildList {
                if (refused.isNotEmpty()) add("${refused.size} refused by the server")
                if (notSentForSize.isNotEmpty()) add("${notSentForSize.size} over the size limit")
                if (notAttempted.isNotEmpty()) add("${notAttempted.size} never attempted")
            }
            "Nothing was uploaded: ${parts.joinToString(" and ")}. The files are still on your phone."
        }

        else -> buildString {
            append("Sent ${sent.size} of $total documents")
            if (refused.isNotEmpty()) append(" · not sent: ").append(refused.joinToString(", "))
            if (notSentForSize.isNotEmpty()) append(" · over the limit: ").append(notSentForSize.joinToString(", "))
            if (notAttempted.isNotEmpty()) append(" · not attempted: ").append(notAttempted.joinToString(", "))
            if (stoppedEarly) append("; the upload stopped early")
        }
    }
}
