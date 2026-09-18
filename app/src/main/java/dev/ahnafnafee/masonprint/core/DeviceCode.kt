package dev.ahnafnafee.masonprint.core

/**
 * What a station QR code actually carries, and how much of it is a printer.
 *
 * The vendor app did the least possible work here. `MainActivity.cs` takes the scanned string,
 * requires it to contain `#code=`, cuts it with `Split('#')[1].Substring(5)` — i.e. drop
 * everything through the literal `code=` — and hands the remainder to the Print Center SPA, which
 * drops it straight into `GET /devices/{id}` (`docs/FINDINGS.md` §8.5). So a sticker is a **device
 * identifier wearing a URL**, and the vendor publishes no label format at all, which is exactly why
 * this cuts several wrappers instead of one (CLONE-PLAN risk R8).
 *
 * Matching the cut token to a real printer is [dev.ahnafnafee.masonprint.data.model.Device.matchesToken]'s job:
 * it already accepts a bare `Location`, a URL ending in it, an asset tag or a serial. This file
 * only gets the payload out of the envelope, so a scanned sticker and a hand-typed code land on the
 * same row — and the same code path — which is the whole point.
 */
internal fun deviceTokenFromQr(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val marker = text.indexOf("code=", ignoreCase = true)
    val cut = if (marker >= 0) text.substring(marker + "code=".length) else text
    val token = cut
        .substringBefore('#')
        .substringBefore('?')
        .substringBefore('&')
        .trim()
        .trimEnd('/')
    return token.takeIf { it.isNotEmpty() }
}

/**
 * Whether a cut token is worth treating as a printer at all.
 *
 * A camera in a print room pointed at the wrong thing still returns *something* — a toner cartridge
 * label, a Wi-Fi setup code, the URL on a poster — and GMU answers a device lookup it cannot serve
 * with 401/404 and no useful sentence (docs/FINDINGS.md §"unknown paths return 401 not 404"). So
 * the app decides from the text alone that this was a misfire, says so, and leaves the camera
 * running, rather than spending a request and a navigation on a shelf label.
 *
 * The shapes that must pass are deliberately different sizes: a sticker's device id, a URL that ends
 * in one, and the four-digit panel code GMU prints under "Release code"
 * (`printservices.gmu.edu/pharosprint/`).
 */
internal fun isPlausibleDeviceToken(token: String): Boolean {
    val t = token.trim()
    if (t.isEmpty() || t.length > MAX_TOKEN_LENGTH) return false
    if (t.any { it.isWhitespace() || it < ' ' }) return false
    return t.any { it.isLetterOrDigit() }
}

/** A token this long is not a station code, however plausible it looks otherwise. */
private const val MAX_TOKEN_LENGTH = 128

/**
 * What to say when the camera read something that is not a code, in the app's own voice.
 *
 * Naming the two codes on a GMU station matters: the sticker on the panel carries the device id this
 * app can use, while the QR on the station's own touchscreen is only a link to GMU's web release
 * page and carries no code at all (docs/FINDINGS.md §8.5). A student who scans the screen one is
 * not doing anything wrong — they scanned the wrong one — and the sentence should let them tell
 * which.
 */
internal fun unreadableScanSentence(raw: String?): String {
    val seen = raw?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
    val what = if (seen == null) "no usable code" else "“$seen”"
    return "That code reads $what, which is not a printer this server knows. " +
        "The sticker on the panel carries the printer's code; the QR on the station's own screen is " +
        "only a link to GMU's web page. Type the release code or pick the printer from the list."
}
