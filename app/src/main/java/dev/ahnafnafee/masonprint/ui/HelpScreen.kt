@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One piece of the app's vocabulary, in the words a student would use. */
private data class HelpEntry(val term: String, val meaning: String)

/**
 * What the app's words mean.
 *
 * This screen exists because the vocabulary is the vendor's, not the student's: "release", "held"
 * and "cost center" are Pharos terms that appear all over the UI, and someone reading the queue for
 * the first time has no way to work out that nothing prints until they walk to a machine. Renaming
 * them across the app would break the shared language with the service desk — who read the same
 * words back over the phone — so they are explained here instead.
 */
private val HelpEntries = listOf(
    HelpEntry("Release", "Printing it, at the machine."),
    HelpEntry("Held", "Waiting on the server for you."),
    HelpEntry("At release", "What it will cost when you print it."),
    HelpEntry("Priced nowhere", "The server could not price it, so it will not print."),
    HelpEntry("Charged to", "Whose money pays: yours, or a department's."),
    HelpEntry("Station code", "The four digits on the printer's own screen."),
)

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How this works") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { bar ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(bar),
            contentPadding = MasonScreenPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "steps") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Printing here takes three steps", style = MaterialTheme.typography.titleMedium)
                        Step(1, "Upload a document. It is sent to the print server, not to a printer.")
                        Step(2, "It waits in the queue, marked Held, for about a week.")
                        Step(
                            3,
                            "Go to any campus printer and release it. Scan the sticker or type the " +
                                "code on its panel. It prints, and only then are you charged.",
                        )
                    }
                }
            }

            item(key = "words") { SectionLabel("What the words mean") }

            // One card, one line per word. Eight bordered paragraphs read as an info dump, and a
            // glossary nobody finishes is a glossary that taught nobody anything.
            item(key = "glossary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        HelpEntries.forEachIndexed { index, entry ->
                            if (index > 0) MasonHairline()
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    entry.term,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(0.42f),
                                )
                                Text(
                                    entry.meaning,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.58f),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
        }
    }
}

/** A numbered step: the number is a filled disc, so the order reads before the words do. */
@Composable
private fun Step(number: Int, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            contentColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$number", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
