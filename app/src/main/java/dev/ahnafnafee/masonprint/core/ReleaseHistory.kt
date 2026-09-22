package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.htmlUnescaped
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class ReleaseCharge(
    val transactionId: String,
    val time: Long,
    val amount: Double?,
    val chargedTo: String?,
    val printer: String?,
    val pages: Long?,
)

@Serializable
enum class CancelVerdict { Accepted, Refused, Unconfirmed }

@Serializable
data class ReleaseCancellation(val verdict: CancelVerdict, val message: String, val attemptedAt: Long)

/** Release acknowledgement, billing evidence and cancellation are independent facts. */
@Serializable
data class ReleaseRecord(
    val id: String,
    val jobLocation: String,
    val name: String,
    val printerLocation: String,
    val printerName: String,
    val requestedAt: Long,
    val accepted: Boolean,
    val charge: ReleaseCharge? = null,
    val billingCheckedAt: Long? = null,
    val cancellation: ReleaseCancellation? = null,
) {
    // Legacy serverState/checkedAt fields are intentionally ignored on deserialization: GMU's
    // post-release job resource can still say Queued after the server has started printing.
    val status: String get() = when {
        cancellation?.verdict == CancelVerdict.Accepted -> "Cancellation accepted"
        charge != null -> "Print charge found"
        accepted -> "Release accepted"
        else -> "Release unconfirmed"
    }
}

internal fun mergeReleaseHistory(existing: List<ReleaseRecord>, additions: List<ReleaseRecord>): List<ReleaseRecord> =
    (additions + existing).distinctBy { it.id }.sortedByDescending { it.requestedAt }.take(100)

private fun documentName(raw: String): String = raw.htmlUnescaped().lineSequence().firstOrNull().orEmpty().trim()

/** A heuristic billing match, never a physical-completion verdict or a cancellation target. */
internal fun matchReleaseCharges(history: List<ReleaseRecord>, transactions: List<Transaction>, checkedAt: Long): List<ReleaseRecord> {
    val used = history.mapNotNull { it.charge?.transactionId }.toSet()
    val available = transactions.filter {
        !it.identifier.isNullOrBlank() && it.identifier !in used && it.transactionType.equals("Print", true) && it.at != null
    }.distinctBy { it.identifier }
    // Matched receipts still block an ambiguous later charge for the same document.
    val candidates = history.associate { record ->
        record.id to available.filter { txn ->
            val at = requireNotNull(txn.at).toEpochMilli()
            val name = txn.jobName?.takeIf(String::isNotBlank) ?: txn.descriptionHead.orEmpty()
            val printers = listOfNotNull(txn.printer, txn.device).filter(String::isNotBlank)
            documentName(record.name).isNotBlank() && documentName(name) == documentName(record.name) &&
                at in (record.requestedAt - 5_000)..(record.requestedAt + 120_000) &&
                (printers.isEmpty() || printers.any { it.equals(record.printerName, true) || it == record.printerLocation })
        }
    }
    // Both directions must be unique. Never greedily assign one charge to repeated same-name jobs.
    val uses = candidates.values.flatten().groupingBy { it.identifier }.eachCount()
    return history.map { record ->
        val txn = candidates[record.id]?.singleOrNull()?.takeIf { uses[it.identifier] == 1 }
        record.copy(billingCheckedAt = checkedAt, charge = record.charge ?: txn?.let {
            ReleaseCharge(requireNotNull(it.identifier), requireNotNull(it.at).toEpochMilli(), it.amount,
                it.chargedTo, it.printer ?: it.device, it.pages)
        })
    }
}

/** An old receipt must not cancel a later release of the same Pharos job location. */
internal fun canAttemptCancellation(history: List<ReleaseRecord>, record: ReleaseRecord): Boolean =
    record.cancellation?.verdict != CancelVerdict.Accepted && record.jobLocation.isNotBlank() &&
        history.filter { it.jobLocation == record.jobLocation }.maxByOrNull { it.requestedAt }?.id == record.id

internal fun printActivityKey(server: String, account: String): String = "print_activity_" +
    MessageDigest.getInstance("SHA-256").digest("$server\n$account".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
