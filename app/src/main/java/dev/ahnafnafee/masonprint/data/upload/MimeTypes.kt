package dev.ahnafnafee.masonprint.data.upload

/**
 * The file types a Pharos deployment accepts, transcribed from the stock client.
 *
 * This is `PharosPrint.Adaptor.Utils.MimeAssistant.MimeTypesDictionary` — all 58 entries, verbatim
 * (docs/FINDINGS.md §7.2). It is kept as a *local hint* only: the authoritative answer is the
 * server's 415, which is why [dev.ahnafnafee.masonprint.data.net.PharosFailure.UnsupportedType] exists. The
 * clone uses the table for two things the stock app also needed it for — choosing a
 * `Content-Type` when the share intent supplies none, and warning before a 60-second upload that
 * the extension is not going to work.
 *
 * Two transcriptions worth noting, because they are the vendor's own bugs and are preserved here
 * on purpose: `dot` maps to the *document* type rather than the template type, and `mht`/`eml`
 * (real MIME types exist for both) are flattened to `text/plain`. A Pharos print queue rasterises
 * by extension on the server, so the value mostly names the file rather than selecting a parser —
 * changing it would be a behaviour change with no evidence behind it.
 */
object MimeTypes {

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "dot" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "rtf" to "application/rtf",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlt" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "xltm" to "application/vnd.ms-excel.template.macroEnabled.12",
        "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "csv" to "text/csv",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "pot" to "application/vnd.ms-powerpoint",
        "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
        "pps" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "vsd" to "application/vnd.visio",
        "vss" to "application/vnd.visio",
        "vst" to "application/vnd.visio",
        "vdx" to "application/vnd.visio",
        "vsx" to "application/vnd.visio",
        "vtx" to "application/vnd.visio",
        "vdw" to "application/vnd.visio",
        "vsdx" to "application/vnd.visio",
        "vstx" to "application/vnd.visio",
        "vssx" to "application/vnd.visio",
        "vsdm" to "application/vnd.visio",
        "vssm" to "application/vnd.visio",
        "vstm" to "application/vnd.visio",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "bmp" to "image/bmp",
        "gif" to "image/gif",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "txt" to "text/plain",
        "mht" to "text/plain",
        "eml" to "text/plain",
        "ini" to "text/plain",
        "cfg" to "text/plain",
        "pub" to "application/x-mspublisher",
        "svg" to "image/svg+xml",
        "xbm" to "image/x-xbitmap",
        "xht" to "application/xhtml+xml",
        "xhtml" to "application/xhtml+xml",
        "xsl" to "application/xml",
        "xml" to "application/xml",
        "log" to "text/plain",
        "dwg" to "application/acad",
        "htm" to "text/html",
        "xslt" to "application/xslt+xml",
    )

    /** The stock client's own behaviour: an unknown extension yields an empty type, which the
     *  server answers with 415 → `ExpectedUnSupportedFileType`. We surface it before sending. */
    fun forExtension(extension: String): String? =
        BY_EXTENSION[extension.lowercase().removePrefix(".")]

    fun forFile(fileName: String): String? =
        forExtension(fileName.substringAfterLast('.', ""))

    fun isSupported(extension: String): Boolean = forExtension(extension) != null

    val supportedExtensions: Set<String> get() = BY_EXTENSION.keys
}
