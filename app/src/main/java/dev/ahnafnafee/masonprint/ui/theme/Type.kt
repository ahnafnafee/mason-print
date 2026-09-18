package dev.ahnafnafee.masonprint.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ahnafnafee.masonprint.R

/**
 * DM Sans, variable, at 240 KB.
 *
 * A geometric sans with the open counters and low contrast the Expressive look asks for. The point
 * of taking the variable cut is that this scale asks for four weights: every one of them is drawn
 * by the font here, where a single static cut would have the renderer synthesise the other three by
 * smearing the one it has.
 *
 * `opsz` is pinned rather than left at its default. The axis is the designer's compensation for
 * size, tighter spacing and higher contrast as text grows, and DM Sans defaults it to 9, the small
 * end. Android does not track it automatically the way a browser's `font-optical-sizing: auto`
 * does, so leaving the default would set the 45 sp balance in a face drawn for footnotes. 14 is the
 * middle of where this app actually sets text.
 */
private const val OpticalSize = 14f

@OptIn(ExperimentalTextApi::class)
private fun dmSans(weight: FontWeight) = Font(
    R.font.dm_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("opsz", OpticalSize),
    ),
)

val DmSans = FontFamily(
    dmSans(FontWeight.Normal),
    dmSans(FontWeight.Medium),
    dmSans(FontWeight.SemiBold),
    dmSans(FontWeight.Bold),
)

/**
 * The four monospace roles, plus the two money roles.
 *
 * M3 has no monospace slot at all, and this app is unusually mono-hungry: the wire detail (host,
 * API version, model numbers, session log) and the SHA-256 fingerprint are the text a student reads
 * aloud to the service desk, and a cost-centre code like `BUSD-CHEM-UG` has to be legible character
 * by character.
 *
 * Money is the exception, and it used to be wrong. A price is read, never dictated, so the only
 * thing it needs from a monospace face is that the digits share a width, and `tnum` on the
 * proportional face gives exactly that. Actual monospace gave it the rest of the bargain too:
 * `$0.20` set in a mono face puts a full character cell around the decimal point, so a two-digit
 * price reads as three loose glyphs and a column of them looks torn rather than aligned.
 */
object MasonType {
    /** Hosts, API version, model numbers, session log, capability values. 11 / 17 · 400. */
    val monoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 17.sp,
    )

    /** SHA-256 fingerprints, base URLs, the "what moved" ledger. Selectable everywhere. 11.5 / 19. */
    val monoBlock = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 19.sp,
    )

    /** A cost-centre code, or anything the student may have to read out loud. 14 / 20 · 700. */
    val monoLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /**
     * Per-job cost on a card and every total on the confirm screen. 19 / 21 · 700.
     *
     * `tnum` is not decoration: without tabular figures a right-aligned column of costs rags, and
     * the hero's amount re-triggers a layout pass every time the server answers with a different
     * number.
     */
    val money = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 21.sp,
        fontFeatureSettings = "tnum",
    )

    /** The live cost readout in the upload sheet's cost dock. 24 / 32 · 700. */
    val monoCost = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum",
    )

    /** The station-code keypad. 34 / 34 · 700, wide tracking so digits cannot be misread. */
    val keypad = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.32.em,
    )

    /** The status chip, purse chip, and tag face. 11.5 / 16 · 700. */
    val chip = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    )
}

/**
 * The type scale of `design/mason-palette/Mason Print Spec.dc.html` §3, written onto M3's slots so
 * components pick it up by themselves.
 *
 * Two things about the type table that survive into this file as deliberate choices:
 *
 *  * **Weights of 420 / 480 / 520 are `wght` axis positions, not named weights.** [DmSans] is
 *    variable and pins `wght` per weight, so each role below takes the nearest named weight and an
 *    in-between position is one `FontVariation.weight` away. The *relationships* in the scale, hero
 *    above title above body above caption, are what make the hierarchy readable at a printer, and
 *    they hold at either resolution.
 *
 *  * **The design puts the live cost readout on `headlineSmall`, which it does not own.** Every
 *    `AlertDialog` title in Material 3 renders in `headlineSmall`, so honouring that row literally
 *    would set every dialog title in monospace. The readout therefore gets the identical metrics on
 *    [MasonType.monoCost] and `headlineSmall` keeps the proportional face at the same size. This is
 *    the one place the implementation and the design's slot column disagree, and it is written down
 *    in `design/REDESIGN-SPEC.md` §2a rather than left as a silent substitution.
 *
 * Nothing on any screen is below 11 sp, and nothing below 12 sp carries meaning a student needs.
 * The sub-12 roles are all wire detail and timestamps, which is exactly what the diagnostics screen
 * is for.
 */
private val MasonFontFamily = DmSans

val MasonTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    /** The balance, on the queue hero and on Account. 45 / 52 · w520. */
    displayMedium = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontFeatureSettings = "tnum",
    ),
    /** The same amount at 200 % font scale: one step down so it never wraps. 36 / 44 · w520. */
    displaySmall = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    /** Top app bar on every destination. 22 / 28 · w480. */
    titleLarge = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    /** Bottom-sheet titles, dialog titles, list headlines. 16 / 24 · 500. */
    titleMedium = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    /** Job card and confirm rows. Wraps; never truncates a file name. 14.5 / 20 · 600. */
    titleSmall = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
    ),
    /** Explanatory copy on connect, certificate, add funds. 16 / 24 · 400. */
    bodyLarge = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    /** Supporting text, server-quoted sentences, banners. 14 / 20 · 400. */
    bodyMedium = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    /** Timings, expiry, footnotes, state labels. 12 / 17 · 400. */
    bodySmall = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    /** All pills. Never wraps — copy is written to fit 328 dp. 15 / 20 · 600. */
    labelLarge = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    /**
     * Uppercase section eyebrows: "HELD JOBS", "CHARGE TO". 11 / 16 · 700 · .07 em.
     *
     * The spec asks for `textCase = Uppercase`; the Compose artifacts this build resolves offline have
     * no `TextCase` type, so the eyebrows are written in literal caps at the call site — which matches
     * the spec's own examples anyway.
     */
    labelMedium = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.07.em,
    ),
    labelSmall = TextStyle(
        fontFamily = MasonFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.05.em,
    ),
)

/** Local for the handful of roles M3 has no slot for, so no screen re-declares a `TextStyle`. */
val LocalMasonType = staticCompositionLocalOf { MasonType }
