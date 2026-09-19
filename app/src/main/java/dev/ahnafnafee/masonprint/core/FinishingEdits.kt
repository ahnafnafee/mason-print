package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.FinishingPayload
import dev.ahnafnafee.masonprint.data.model.PrintJob

/** Null means keep this field on each document, including a mixed selection. */
data class FinishingEdits(
    val mono: Boolean? = null,
    val duplex: Boolean? = null,
    val copies: Long? = null,
) {
    fun optionsFor(job: PrintJob): FinishingPayload {
        val current = FinishingPayload.from(job.finishing)
        return current.copy(
            mono = mono?.takeUnless { job.supportedFinishing.color == false } ?: current.mono,
            duplex = duplex?.takeUnless { job.supportedFinishing.duplex == false } ?: current.duplex,
            copies = copies?.takeUnless { job.supportedFinishing.copies == false } ?: current.copies,
        )
    }

    fun unsupportedFor(job: PrintJob): List<String> = buildList {
        val current = FinishingPayload.from(job.finishing)
        if (mono != null && mono != current.mono && job.supportedFinishing.color == false) add("colour")
        if (duplex != null && duplex != current.duplex && job.supportedFinishing.duplex == false) add("sides")
        if (copies != null && copies != current.copies && job.supportedFinishing.copies == false) add("copies")
    }
}
