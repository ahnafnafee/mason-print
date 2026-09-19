package dev.ahnafnafee.masonprint.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The Mason palette, as specified by `design/mason-palette/Mason Print Spec.dc.html`.
 *
 * Three brands are in play, and the design settles them into a strict hierarchy:
 *
 *  * **Mason Green `#006633`** (PMS 349) is `primary`, at full saturation. It is not the pastel
 *    Material baseline blue the first redesign used — the campus requires the real green, and white
 *    on it measures 7.1 : 1, so it can carry a filled button's label without a compromise.
 *  * **Mason Gold `#FFCC33`** (PMS 116) is `tertiary` and the held/warning family, and it lives in
 *    *containers only*. Full-saturation gold behind white type is 1.5 : 1, which is why there is no
 *    filled gold button anywhere in the app: gold is what a cost centre looks like, not what a
 *    verb looks like.
 *  * **Pharos charcoal `#333F48`** is inherited from the vendor and is the *source of the neutral
 *    ramp* rather than a painted colour — which is why the surfaces read cool-grey rather than
 *    green-tinted, and why a green app still looks like a print system rather than a shrill.
 *
 * Charcoal is `secondary` in the tonal sense, and deliberately so: the selection bar, the tonal
 * "Release at a printer" button, and the list avatars are charcoal containers because *selection is a
 * state, not an outcome*. If selecting jobs turned them green, the green would stop meaning
 * "the campus is paying".
 */
object MasonBrand {
    /** PMS 349. Full saturation, because the brand requires it. */
    val Green = Color(0xFF006633)

    /** PMS 116. Accents and containers only — never behind white type. */
    val Gold = Color(0xFFFFCC33)

    /** Inherited from Pharos. Source of the neutral ramp, not painted directly. */
    val Charcoal = Color(0xFF333F48)
}

/**
 * The light scheme, spelled out slot by slot.
 *
 * Expressive leans far harder on container colours than 2021 Material did — a selected card, a
 * headline figure, and a status surface are all container-coloured rather than tinted text on a
 * card — so every container in the ramp is stated rather than left to the platform default. The
 * `…Fixed` tones are not in the design's table; they are filled in from the same three ramps
 * because [lightColorScheme] seeds them with the M3 *baseline* purple, and any component that uses
 * a fixed tone (a date picker, a nav drawer's selected row) would otherwise show a colour that
 * appears nowhere in this app.
 */
private val MasonLightScheme = lightColorScheme(
    primary = Color(0xFF006633),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9F1D4),
    onPrimaryContainer = Color(0xFF00391A),
    primaryFixed = Color(0xFFC9F1D4),
    primaryFixedDim = Color(0xFFACD0BA),
    onPrimaryFixed = Color(0xFF00210F),
    onPrimaryFixedVariant = Color(0xFF004E26),

    secondary = Color(0xFF545F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6DEE6),
    onSecondaryContainer = Color(0xFF121C24),
    secondaryFixed = Color(0xFFD6DEE6),
    secondaryFixedDim = Color(0xFFBAC2CA),
    onSecondaryFixed = Color(0xFF121C24),
    onSecondaryFixedVariant = Color(0xFF2E363D),

    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE3A1),
    onTertiaryContainer = Color(0xFF271900),
    tertiaryFixed = Color(0xFFFFE3A1),
    tertiaryFixedDim = Color(0xFFF2C878),
    onTertiaryFixed = Color(0xFF271900),
    onTertiaryFixedVariant = Color(0xFF5D4200),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDEE1E6),
    onSurfaceVariant = Color(0xFF42474E),
    surfaceDim = Color(0xFFDAD9DE),
    surfaceBright = Color(0xFFFAF9FD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F7),
    surfaceContainer = Color(0xFFEEEDF1),
    surfaceContainerHigh = Color(0xFFE8E8EC),
    surfaceContainerHighest = Color(0xFFE2E2E6),
    surfaceTint = Color(0xFF006633),

    outline = Color(0xFF72777F),
    outlineVariant = Color(0xFFC2C7CF),

    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFF80DBA0),
    scrim = Color(0xFF000000),
)

/**
 * The dark scheme. The two themes are designed as equals rather than one being an inversion of the
 * other: the surfaces are the same cool charcoal ramp at low luminance, and the container tones swap
 * roles (`primaryContainer` goes dark-green so the hero stays a *container* rather than becoming a
 * glowing block in a dark room at 2 a.m. in a library).
 */
private val MasonDarkScheme = darkColorScheme(
    primary = Color(0xFF80DBA0),
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFFC9F1D4),
    primaryFixed = Color(0xFFC9F1D4),
    primaryFixedDim = Color(0xFFACD0BA),
    onPrimaryFixed = Color(0xFF00210F),
    onPrimaryFixedVariant = Color(0xFF004E26),

    secondary = Color(0xFFC0C7CE),
    onSecondary = Color(0xFF232C33),
    secondaryContainer = Color(0xFF3C444C),
    onSecondaryContainer = Color(0xFFD6DEE6),
    secondaryFixed = Color(0xFFD6DEE6),
    secondaryFixedDim = Color(0xFFBAC2CA),
    onSecondaryFixed = Color(0xFF121C24),
    onSecondaryFixedVariant = Color(0xFF2E363D),

    tertiary = Color(0xFFF2C33F),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFE3A1),
    tertiaryFixed = Color(0xFFFFE3A1),
    tertiaryFixedDim = Color(0xFFF2C878),
    onTertiaryFixed = Color(0xFF271900),
    onTertiaryFixedVariant = Color(0xFF5D4200),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceTint = Color(0xFF80DBA0),

    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),

    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF006633),
    scrim = Color(0xFF000000),
)

/**
 * Status colours M3 has no slot for.
 *
 * Material 3 has `error` but no success and no warning, and this app needs both constantly: a
 * released job and a held job are the two states a student is actually trying to tell apart while
 * standing at a machine, and neither is an error. Folding "released" into `primary` would collide
 * with "the campus is paying", and folding "held" into `error` would make an ordinary queue look
 * like a failure list, so they ship beside the scheme instead of inside it.
 *
 * Passed down next to `MaterialTheme` so a status chip never reaches for a literal hex and a
 * screenshot taken by a student matches one taken by the service desk.
 */
@Immutable
data class MasonColors(
    val ok: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    /** Colour printing uses blue so it cannot be mistaken for a success or a funding source. */
    val colourPrintContainer: Color,
    val onColourPrintContainer: Color,
    /** Full-saturation Mason Gold: accents and containers only, never behind white type. */
    val brandGold: Color,
    /**
     * The ink that pairs with [brandGold] — the same near-black brown as `onTertiaryContainer`,
     * fixed rather than theme-flipped because the gold it sits on is fixed. 11.4 : 1, so a gold
     * surface can carry real text (a white label on gold is 1.5 : 1, which is why the pair exists).
     */
    val brandGoldInk: Color,
    /** Pharos charcoal — the source of the neutral ramp, not painted directly. */
    val brandCharcoal: Color,
    /**
     * The queue's action pair: full-saturation Mason Green behind white — the palette's own filled
     * button pairing (7.1 : 1) — for the controls that *act* on the queue, the Release pill and the
     * Upload FAB. In dark the scheme's light green takes over, because a `#006633` pill in a dark
     * room would be a hole rather than a control.
     *
     * That is the *only* hue the bar area carries: the bar itself is neutral `surface`, like the
     * top bar — chrome does not wear colour in this app. The screen can already show a gold
     * cost-centre strip and green status chips; a tinted bar as well is three hues fighting, and
     * one accent on quiet chrome is what keeps them from turning into a fight.
     */
    val barAction: Color,
    val onBarAction: Color,
    /**
     * The Release pill (and the selection bar's Release button): the control that sits on the
     * grey-blue bar.
     *
     * Light wants a white pill with Mason-green ink — a saturated green pill on `secondaryContainer`
     * read as a muddy dark lump — and dark wants the reverse emphasis, the mint of the FAB family
     * with dark ink, because a `surface` pill in dark is near-black on near-dark and disappears.
     */
    val barPill: Color,
    val onBarPill: Color,
)

val MasonColorsLight = MasonColors(
    ok = Color(0xFF006633),
    okContainer = Color(0xFF7FE7A2),
    onOkContainer = Color(0xFF00250F),
    warn = Color(0xFF7C5800),
    warnContainer = Color(0xFFFFE3A1),
    onWarnContainer = Color(0xFF271900),
    colourPrintContainer = Color(0xFFDDF3F8),
    onColourPrintContainer = Color(0xFF174D59),
    brandGold = MasonBrand.Gold,
    brandGoldInk = Color(0xFF271900),
    brandCharcoal = MasonBrand.Charcoal,
    barAction = MasonBrand.Green,
    onBarAction = Color(0xFFFFFFFF),
    barPill = Color(0xFFFFFFFF),
    onBarPill = MasonBrand.Green,
)

val MasonColorsDark = MasonColors(
    ok = Color(0xFF7FE7A2),
    okContainer = Color(0xFF14663A),
    onOkContainer = Color(0xFFC9F1D4),
    warn = Color(0xFFF2C33F),
    warnContainer = Color(0xFF5D4200),
    onWarnContainer = Color(0xFFFFE3A1),
    colourPrintContainer = Color(0xFF183E48),
    onColourPrintContainer = Color(0xFFACE5F0),
    brandGold = MasonBrand.Gold,
    brandGoldInk = Color(0xFF271900),
    brandCharcoal = MasonBrand.Charcoal,
    barAction = Color(0xFF80DBA0),
    onBarAction = Color(0xFF003919),
    barPill = Color(0xFF80DBA0),
    onBarPill = Color(0xFF003919),
)

val LocalMasonColors = staticCompositionLocalOf { MasonColorsLight }

/** Convenience for the status chips and the transaction rows. */
val currentMasonColors: MasonColors
    @Composable get() = LocalMasonColors.current

/**
 * The app's theme: the Mason palette on Material 3 **Expressive**.
 *
 * `MaterialExpressiveTheme` is the entry point that puts the components onto their expressive
 * tokens (stadium buttons, a FAB that morphs as the selection takes over the screen,
 * container-coloured selection) and [MotionScheme.expressive] is the partner half — springs with a
 * little overshoot, so a balance that just changed moves like an object rather than cross-fading.
 * That matters here rather than being decoration: the vendor client is a Honeycomb-era ActionBar
 * hosting a 2013 web skin, and this clone has to make state the original never showed — which
 * purse, which department, what it will cost — legible at a glance, in motion, one-handed outside a
 * printer.
 *
 * @param dynamicColor stays off. Material You would derive the palette from the student's wallpaper,
 *   and then a student's screenshot and a service-desk agent's screenshot would be two different
 *   pictures of the same queue — which is how "it looks fine on my phone" starts. The parameter is
 *   kept for callers that want to try it; no UI in the app offers it.
 */
@Composable
fun MasonPrintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }

        darkTheme -> MasonDarkScheme
        else -> MasonLightScheme
    }
    val extra = if (darkTheme) MasonColorsDark else MasonColorsLight

    CompositionLocalProvider(LocalMasonColors provides extra, LocalMasonType provides MasonType) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = MasonShapes,
            typography = MasonTypography,
            content = content,
        )
    }
}
