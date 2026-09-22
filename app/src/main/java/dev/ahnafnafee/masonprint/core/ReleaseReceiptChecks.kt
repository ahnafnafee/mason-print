package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.JobOperationResult
import dev.ahnafnafee.masonprint.data.model.Page
import dev.ahnafnafee.masonprint.data.model.Transaction
import dev.ahnafnafee.masonprint.data.model.boolIn
import dev.ahnafnafee.masonprint.data.model.cleanSentence
import dev.ahnafnafee.masonprint.data.model.dbl
import dev.ahnafnafee.masonprint.data.model.strIn
import dev.ahnafnafee.masonprint.data.model.strCI
import dev.ahnafnafee.masonprint.data.net.ApiResult
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import kotlinx.coroutines.CancellationException

/** At most 300 recent ledger rows; repeated pages and missing links cannot create an endless poll. */
internal suspend fun readReleaseTransactions(fetch: suspend (Int, Int) -> ApiResult<Page<Transaction>>): ApiResult<List<Transaction>> {
    val rows = mutableListOf<Transaction>()
    val seenPages = mutableSetOf<List<Transaction>>()
    try {
        repeat(3) {
            when (val response = fetch(rows.size, 100)) {
                is ApiResult.Err -> return response
                is ApiResult.Ok -> {
                    val page = response.value
                    if (!seenPages.add(page.items)) return ApiResult.Ok(rows, 200)
                    rows += page.items.take(300 - rows.size)
                    if (rows.size == 300 || !page.hasMoreAfter(rows.size)) return ApiResult.Ok(rows, 200)
                }
            }
        }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) { return ApiResult.Err(PharosFailure.Unknown(error)) }
    return ApiResult.Ok(rows, 200)
}

/** HTTP 200 alone is not a verdict. Only the requested Location's explicit per-job answer counts. */
internal fun cancellationVerdict(result: JobOperationResult, location: String, attemptedAt: Long): ReleaseCancellation {
    val row = result.responses.filter { it.strIn("Location", "JobLocation") == location }.singleOrNull()
        ?: return ReleaseCancellation(CancelVerdict.Unconfirmed, "The server did not return a clear cancellation result for this job.", attemptedAt)
    val status = row.dbl("Status")?.toInt()
    val error = row.strIn("ErrorCode", "Error")
    val success = row.boolIn("Success", "Succeeded", "IsSuccess")
    val sentence = sequenceOf("UserMessage", "Message", "DeveloperMessage")
        .mapNotNull { cleanSentence(row.strCI(it)) }.firstOrNull()
    return when {
        !error.isNullOrBlank() || success == false || (status != null && status !in 200..299) ->
            ReleaseCancellation(CancelVerdict.Refused, sentence ?: "The server refused cancellation without giving a reason.", attemptedAt)
        status in 200..299 || success == true ->
            ReleaseCancellation(CancelVerdict.Accepted, sentence ?: "The server accepted cancellation. Check your statement for any billing adjustment.", attemptedAt)
        else -> ReleaseCancellation(CancelVerdict.Unconfirmed, "The server did not confirm whether cancellation succeeded.", attemptedAt)
    }
}

internal fun unconfirmedCancellation(failure: PharosFailure, attemptedAt: Long): ReleaseCancellation {
    val serverMessage = when (failure) {
        is PharosFailure.Server -> failure.error.userText("")
        is PharosFailure.Unauthenticated -> failure.error.userText("")
        else -> ""
    }.takeIf(String::isNotBlank)
    return ReleaseCancellation(CancelVerdict.Unconfirmed,
        serverMessage ?: "No cancellation acknowledgement was received. The job may still print; check at the printer before trying again.", attemptedAt)
}
