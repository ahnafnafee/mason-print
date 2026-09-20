package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.ui.theme.MasonPrintTheme
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/**
 * The component gallery — step 1 of the Spec's build order (`design/mason-palette/Mason Print
 * Spec.dc.html` §10): before any screen is written, every shared component has to be visible once, in
 * both schemes, so the acceptance list can be read off one picture instead of fifteen screens.
 *
 * These two previews *are* that list. If a component changes shape, tone, or typography, it changes
 * here first; a screen that disagrees with this gallery is the bug.
 *
 * What the gallery is asserting, in the order the Spec lists it:
 *
 *  - status is **icon + words**, never a colour swatch, and the four states use the print system's own
 *    vocabulary (`Received`, `Held`, `Released`, `Problem`) — `MasonJobStatus`;
 *  - fact chips look like chips and are not buttons — no ripple, and they collapse into one spoken
 *    label for TalkBack;
 *  - money is mono, tabular, and right-aligned, and a `.NET` format string is rendered before it gets
 *    here (so `$-1.25` is a *correct* rendering of one section);
 *  - the two surfaces that carry GMU's own words verbatim: `MonoDetail` for a host or API version,
 *    `MonoBlock` for a server sentence nobody should be paraphrased into;
 *  - `NoteCard` is the non-modal explanation, `EmptyState` the designed nothing;
 *  - green is an outcome, gold is never behind white type, and charcoal `secondaryContainer` — not
 *    green — is what a *selection* looks like.
 */
@Preview(name = "Mason components. Light", showBackground = true, widthDp = 420, heightDp = 1100)
@Composable
private fun GalleryLight() = MasonComponentGallery(dark = false)

@Preview(name = "Mason components. Dark", showBackground = true, widthDp = 420, heightDp = 1100)
@Composable
private fun GalleryDark() = MasonComponentGallery(dark = true)

@Composable
private fun MasonComponentGallery(dark: Boolean) {
    MasonPrintTheme(darkTheme = dark) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("Job status. Icon and words, never colour alone")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(MasonJobStatus.ReceivedIcon, MasonJobStatus.Received, MasonTone.Neutral)
                StatusChip(MasonJobStatus.HeldIcon, MasonJobStatus.Held, MasonTone.Warn)
                StatusChip(MasonJobStatus.ReleasedIcon, MasonJobStatus.Released, MasonTone.Ok)
                StatusChip(MasonJobStatus.ProblemIcon, MasonJobStatus.Problem, MasonTone.Error)
            }

            SectionLabel("Facts about a job. Chips you cannot tap")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FactChip(null, "12 pages")
                FactChip(Icons.Filled.Print, "Simplex")
                FactChip(null, "Letter", tone = MasonTone.Money)
                FactChip(Icons.Filled.AccountBalanceWallet, "Mason Money", tone = MasonTone.Grant)
            }

            SectionLabel("Money")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                MoneyText("$12.40")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("One-section format string", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                MoneyText("$-1.25", color = currentMasonColors.warn)
            }

            SectionLabel("The server's own words")
            MonoDetail("mobileprint.gmu.edu · API 4.11.24.1")
            MonoBlock(
                "Improper data. (Cost Center information is invalid because " +
                    "invalid separator used ).",
            )

            SectionLabel("Explanations that do not block the screen")
            NoteCard(
                title = "Showing jobs from a few minutes ago",
                body = "This list may be out of date. The queue refreshes when you pull down.",
                icon = Icons.Filled.Info,
                tone = MasonTone.Warn,
            )
            NoteCard(
                title = "Mason Print cannot take a payment",
                body = "GMU turns add-funds off in the print system, so money arrives as Mason Money.",
                icon = Icons.Filled.AccountBalanceWallet,
                tone = MasonTone.Neutral,
            ) {
                Button(onClick = {}) { Text("Open the Print Center") }
            }

            SectionLabel("A row, and the thing that fills while you wait")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowAvatar(Icons.Filled.Print, tone = MasonTone.Neutral)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("mp262-relnote.pdf", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "12 pages · 12 B&W · $1.20 · not charged yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyText("$1.20")
            }
            LinearProgressIndicator(progress = { 0.42f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(2.dp))
            MasonHairline()

            SectionLabel("The designed nothing")
            EmptyState(
                title = "Nothing waiting to print",
                body = "Jobs you upload from your phone or a public computer show up here.",
                icon = Icons.Filled.Print,
            )
        }
    }
}
