package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.PrintJob

internal enum class QueueSettingKind { Mono, Colour, Sides, Copies, Layout }

internal data class QueuePrintSetting(
    val label: String,
    val kind: QueueSettingKind,
    val restriction: String? = null,
)

/** Show reported options without inventing defaults for jobs the server has not analysed. */
internal fun queuePrintSettings(job: PrintJob): List<QueuePrintSetting> = buildList {
    val options = job.finishing
    val colourLocked = job.pending && job.supportedFinishing.color == false
    val sidesLocked = job.pending && job.supportedFinishing.duplex == false
    val copiesLocked = job.pending && job.supportedFinishing.copies == false
    if (options?.mono != null || colourLocked) add(QueuePrintSetting(
        label = when (options?.mono) { true -> "Black & white"; false -> "Colour"; null -> "Colour setting" },
        kind = if (options?.mono == false) QueueSettingKind.Colour else QueueSettingKind.Mono,
        restriction = "colour".takeIf { colourLocked },
    ))
    if (options?.duplex != null || sidesLocked) add(QueuePrintSetting(
        label = when (options?.duplex) { true -> "Two-sided"; false -> "One-sided"; null -> "Sides" },
        kind = QueueSettingKind.Sides,
        restriction = "sides".takeIf { sidesLocked },
    ))
    if ((options?.copies ?: 1) > 1 || copiesLocked) add(QueuePrintSetting(
        label = options?.copies?.let { "$it ${if (it == 1L) "copy" else "copies"}" } ?: "Copies",
        kind = QueueSettingKind.Copies,
        restriction = "copies".takeIf { copiesLocked },
    ))
    options?.pagesPerSide?.takeIf { it > 1 }?.let {
        add(QueuePrintSetting("$it per side", QueueSettingKind.Layout))
    }
}

internal fun queuePageCount(job: PrintJob): String? {
    if (job.awaitingPageCount) return null
    return job.stats.totalPages?.takeIf { it > 0 }?.let { "$it ${if (it == 1L) "page" else "pages"}" }
}
