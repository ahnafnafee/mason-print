package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.ui.theme.LocalMasonType
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/**
 * The shared vocabulary of the fourteen screens: the small components the design spec names but
 * Material does not ship, plus the two that used to live in `AppRoot.kt`.
 *
 * The rules this file exists to enforce, all of them from
 * `phrarosprint/design/REDESIGN-SPEC.md` §2.0:
 *
 *  - **Status is never colour alone.** Every status chip is an icon plus a word, and the word is the
 *    print system's own vocabulary — the same word the service desk reads back over the phone.
 *  - **A status chip is not a disabled `SuggestionChip`.** A disabled suggestion chip greys its own
 *    label, which reads as "this control does not work" on a screen where nothing is a control.
 *  - **Money is monospaced and tabular**, so a balance that changes does not shuffle the row.
 *  - **Green is an outcome, charcoal is a state.** Chips that report what happened use the
 *    [MasonColors] ok/warn pair; selection uses `secondaryContainer`, and the two never mix.
 */

/** Which container/ink pair a chip or note card wears. */
enum class MasonTone { Neutral, Ok, Warn, Error, Money, Grant }

@Composable
internal fun MasonToneSurfaces(tone: MasonTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val mc = currentMasonColors
    return when (tone) {
        MasonTone.Neutral -> cs.secondaryContainer to cs.onSecondaryContainer
        MasonTone.Ok -> mc.okContainer to mc.onOkContainer
        MasonTone.Warn -> mc.warnContainer to mc.onWarnContainer
        MasonTone.Error -> cs.errorContainer to cs.onErrorContainer
        // "The money container" and the gold cost-centre container.
        MasonTone.Money -> cs.primaryContainer to cs.onPrimaryContainer
        MasonTone.Grant -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
}

/**
 * A status chip: `Surface(shape = small) + Row`, icon then word.
 *
 * Deliberately not `SuggestionChip(enabled = false)`, which is the obvious M3 choice and greys the
 * label out — the spec calls that out by name (§2.0.6, "which greys out and reads as disabled").
 */
@Composable
internal fun StatusChip(
    icon: ImageVector,
    label: String,
    tone: MasonTone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = MasonToneSurfaces(tone)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/** The four job states, in the vocabulary the print system itself uses. */
object MasonJobStatus {
    val ReceivedIcon: ImageVector get() = Icons.Filled.Inbox
    const val Received = "Received"
    val HeldIcon: ImageVector get() = Icons.Filled.Schedule
    const val Held = "Held"
    val ReleasedIcon: ImageVector get() = Icons.Filled.CheckCircle
    const val Released = "Released"
    val ProblemIcon: ImageVector get() = Icons.Filled.Error
    const val Problem = "Problem"
}

/**
 * A non-interactive fact chip: a purse, a finishing option, a page size.
 *
 * These look like chips and are not buttons, so the whole thing collapses into one spoken label —
 * otherwise TalkBack reads four tappable-sounding chips before the balance.
 */
@Composable
internal fun FactChip(
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
    tone: MasonTone = MasonTone.Neutral,
) {
    val (bg, fg) = MasonToneSurfaces(tone)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        shape = MasonPillShape,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(15.dp), tint = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/** The uppercase eyebrow over a section: "HELD JOBS", "CHARGE TO". */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Money: monospaced, tabular, right-aligned. 19 / 21 · 700. */
@Composable
internal fun MoneyText(
    amount: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        amount,
        modifier = modifier,
        style = LocalMasonType.current.money,
        color = color,
        textAlign = TextAlign.End,
    )
}

/** Wire detail — hosts, API versions, model numbers. 11 / 17 mono. */
@Composable
internal fun MonoDetail(text: String, modifier: Modifier = Modifier, selectable: Boolean = true) {
    Text(
        text,
        modifier = modifier,
        style = LocalMasonType.current.monoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * A fingerprint, a base URL, a "what moved" ledger. Always selectable: the certificate screen's whole
 * purpose is that the user can compare this string against a published one.
 */
@Composable
internal fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = LocalMasonType.current.monoBlock,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A card that explains something: an offline banner, a capability the server refused, a warning.
 * Radius `large` (16) — smaller than a job card, because it is not a thing you act on.
 */
@Composable
internal fun NoteCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: MasonTone = MasonTone.Neutral,
    action: @Composable (() -> Unit)? = null,
) {
    val (bg, fg) = MasonToneSurfaces(tone)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, modifier = Modifier.size(22.dp), tint = fg)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = fg)
                if (action != null) {
                    Spacer(Modifier.height(6.dp))
                    action()
                }
            }
        }
    }
}

/** A leading glyph in a row, sized to the 48 dp touch target the spec asks for. */
@Composable
internal fun RowAvatar(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: MasonTone = MasonTone.Neutral,
    size: Int = 44,
) {
    val (bg, fg) = MasonToneSurfaces(tone)
    Surface(
        modifier = modifier.size(size.dp),
        shape = MaterialTheme.shapes.medium,
        color = bg,
        contentColor = fg,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size((size / 2).dp)) }
    }
}

/**
 * Upload progress: bytes moved, per cent, and the fraction. A 4 dp hairline next to a 50 MB upload
 * crawling over campus Wi-Fi does not answer the only question being asked.
 */
@Composable
internal fun UploadProgressBar(fraction: Float, detail: String? = null) {
    if (fraction <= 0f) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            detail ?: "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The empty state of a section. The icon sits in a large rounded container — Expressive's way of
 * saying "this is a designed nothing" instead of the blank white ListView the vendor app leaves on
 * screen when a student has no jobs.
 */
@Composable
internal fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PaddingValues(horizontal = 24.dp, vertical = 40.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(78.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(34.dp)) }
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Reduced motion, read once per composition.
 *
 * The spec's rule: `ANIMATOR_DURATION_SCALE == 0f` swaps every spatial spring for a snap, because a
 * 550 ms overshoot on the cost readout is not information for someone who turned animation off — it
 * is a delay.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The one leading-glyph treatment for any labelled button: 18 dp icon, 8 dp gap.
 *
 * Before this existed every call site hand-rolled `Icon(size = 16 or 18) + Spacer(width = 6 or 8)`,
 * which is how a screen ends up with buttons whose icons do not sit on one line.
 */
@Composable
internal fun ButtonGlyph(icon: ImageVector) {
    Icon(icon, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** "47 minutes", "3 hours", "6 days" — no seconds, because nobody reads them. */
internal fun humanDuration(millis: Long): String {
    val minutes = (millis / 1000L).coerceAtLeast(0L) / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes minute" + if (minutes == 1L) "" else "s"
        hours < 24 -> "$hours hour" + if (hours == 1L) "" else "s"
        else -> "$days day" + if (days == 1L) "" else "s"
    }
}

/**
 * A small ⓘ that explains one word, where that word is.
 *
 * The vendor's vocabulary ("release", "held", "at release") cannot be renamed — the service desk
 * reads the same words back over the phone — so it has to be explained. A separate glossary screen
 * is the wrong place: help you have to go and find is help that has already failed. This puts one
 * sentence a tap away from the term it defines, and keeps it out of the layout until asked for.
 */
@Composable
internal fun InfoTip(term: String, meaning: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "What “$term” means",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                Modifier.widthIn(max = 260.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(term, style = MaterialTheme.typography.titleSmall)
                Text(
                    meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 16 dp of screen padding, the one number every screen starts with. */
val MasonScreenPadding = PaddingValues(horizontal = 16.dp)

/** The gap under the last card: 148 dp, enough to clear a 76 dp bar and a floating button. */
val MasonScrollSpacer = 148.dp

/** One section break. */
@Composable
internal fun SectionGap() = Spacer(Modifier.height(20.dp))

/** A hairline between rows, in `outlineVariant`, which the spec reserves for exactly this. */
@Composable
internal fun MasonHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
