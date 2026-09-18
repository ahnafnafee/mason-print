package dev.ahnafnafee.masonprint.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * The Expressive shape scale, taken from the design's §4 shape table.
 *
 * Expressive resolves the shapes of its own components (a button becomes a stadium, a FAB morphs
 * between two corner radii) once [androidx.compose.material3.MaterialExpressiveTheme] is active, so
 * what this scale really controls are the *containers* — and in this design the containers carry
 * meaning: a job card that becomes selected grows its corner radius from 20 dp to 26 dp along with
 * its fill, so selection survives a screenshot, a greyscale printout, and a colour-blind reading
 * instead of living only in a checkmark.
 *
 * `largeIncreased` is the Expressive addition to the scale, and the design puts the job card, the
 * printer card, and the file card on it (20 dp) while ordinary rows stay on `large` (16 dp). The
 * card is the object the student actually picks up off the screen, so it gets the softer corner.
 */
val MasonShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(36.dp),
)

/**
 * Two corner radii the design names but that are not slots in any scale.
 *
 * A selected job card is `largeIncreased` grown to 26 dp, and the FAB animates between 18 dp and
 * 28 dp as it collapses. Both are animated with `animateDpAsState`, so they have to be reachable as
 * values rather than inlined at the call site — a magic number inside `AnimatedVisibility` is
 * exactly where a shape token goes to die.
 */
val SelectedCardCorner = RoundedCornerShape(26.dp)
val SelectionBarCorner = RoundedCornerShape(24.dp)
val FabCollapsedCorner = RoundedCornerShape(18.dp)
val FabExtendedCorner = RoundedCornerShape(28.dp)

/** Pill geometry: buttons, the FAB, and the segmented controls are all full stadium. */
val MasonPillShape = CircleShape

/** Local so a composable that wants the *scale* rather than the theme default can ask for it. */
val LocalMasonShapes = staticCompositionLocalOf { MasonShapes }
