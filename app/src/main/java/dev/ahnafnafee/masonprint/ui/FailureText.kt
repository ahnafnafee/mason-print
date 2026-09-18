package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.humanBytes
import dev.ahnafnafee.masonprint.data.net.PharosFailure

/**
 * Failure → sentences, in one pure function.
 *
 * Pure and free of Compose so it can be unit-tested against captured server bodies: the point of
 * this file is that the user reads the server's own words, and the only way to keep that true as
 * the API moves is to assert on it.
 *
 * The stock app did the opposite — `ExpectedExcetpion` looked up a string resource by *exception
 * class name*, so every 413 said "Upload failed" regardless of the body (docs/FINDINGS.md §11).
 * Nothing here is a canned string when the server said something instead.
 */
fun PharosFailure.headline(): String = when (this) {
    is PharosFailure.Unauthenticated -> error.userText("Your username or password was not accepted").substringBefore('\n')
    is PharosFailure.UploadDenied -> why?.substringBefore('\n')
        ?: error.userText("This account is not allowed to upload documents to the queue")
    is PharosFailure.TooLarge -> "That file is larger than this server accepts"
    is PharosFailure.UnsupportedType -> "The queue will not print that file type"
    is PharosFailure.Server -> error.userText("The print server returned an error")
    is PharosFailure.NotFound -> "Not found: $path"
    is PharosFailure.Offline -> "Nothing answered at that address"
    is PharosFailure.Timeout -> if (writePhase) "The upload is taking too long" else "The print server took too long to answer"
    is PharosFailure.TlsNotTrusted -> "Unrecognised certificate for $host"
    is PharosFailure.NotAPharosServer -> "That address is not a Pharos print server"
    is PharosFailure.Unknown -> "Something unexpected happened"
}

/**
 * The line under the headline: whatever the server actually said, or the technical detail when it
 * said nothing useful. Monospace in the UI because it is usually JSON or an exception name the
 * service desk will recognise.
 */
fun PharosFailure.detailLine(): String? = when (this) {
    is PharosFailure.Unauthenticated -> error.detail
    is PharosFailure.UploadDenied -> error.detail.ifBlank { why.orEmpty() }
    is PharosFailure.TooLarge -> listOfNotNull(
        limitBytes?.let { "limit ${humanBytes(it)}" },
        serverText?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ").ifBlank { null }

    is PharosFailure.UnsupportedType -> listOfNotNull(
        extension?.let { ".$it" },
        serverText?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ").ifBlank { null }

    is PharosFailure.Server -> error.detail.ifBlank { error.status?.let { "HTTP $it" } ?: "" }
    is PharosFailure.NotFound -> "Pharos answers 401 rather than 404 for unknown paths, so this usually means the route is not enabled on this deployment."
    is PharosFailure.Offline ->
        "Nothing came back from the address you entered. Check the spelling, and check that you are " +
            "on the campus network or VPN. This server is usually not reachable from outside."
    is PharosFailure.Timeout -> if (writePhase) null else "The server may be busy; your document was probably not accepted."
    is PharosFailure.TlsNotTrusted -> "$fingerprint\n${subject ?: ""}".trim()
    is PharosFailure.NotAPharosServer -> detail
    is PharosFailure.Unknown -> throwable.toString()
}?.takeIf { it.isNotBlank() }

/** The status code, when the failure carries one — shown in Diagnostics, never in a headline. */
val PharosFailure.statusForLog: Int?
    get() = when (this) {
        is PharosFailure.Unauthenticated -> error.status
        is PharosFailure.UploadDenied -> error.status
        is PharosFailure.Server -> error.status
        is PharosFailure.TooLarge -> 413
        is PharosFailure.UnsupportedType -> 415
        else -> null
    }
