@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState

/**
 * Narrow the printer list by building and floor.
 *
 * Its own screen on purpose. GMU publishes 302 stations across roughly sixty buildings, and every
 * inline control for that many — a chip row, a dropdown — either fills the screen or becomes a
 * scroll of its own on top of the list it is meant to narrow. Here the buildings are a searchable
 * list with their printer counts, which is the only shape that stays usable at that size.
 *
 * Buildings lead with the **code**, because the code is what is printed on the machine and on the
 * QR sticker; the plain name is a hint beside it (see [buildingName]).
 */
@Composable
internal fun PrinterFilterScreen(state: AppState, onBack: () -> Unit) {
    val building = ReleaseHandoff.building
    val floor = ReleaseHandoff.floor
    var search by rememberSaveable { mutableStateOf("") }

    val stations = remember(state.devices) { state.devices.map(::stationOf) }
    val buildings = remember(stations) {
        stations.groupingBy { it.building }.eachCount().toList()
            .sortedWith(compareBy({ it.first == "Other" }, { it.first }))
    }
    // Only the floors the chosen building actually has — an empty floor filter is a dead end.
    val floors = remember(stations, building) {
        stations.filter { building == null || it.building == building }
            .mapNotNull { it.floor }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    }
    val q = search.trim()
    val shown = remember(buildings, q) {
        if (q.isEmpty()) buildings
        else buildings.filter { (code, _) ->
            code.contains(q, ignoreCase = true) || buildingName(code)?.contains(q, ignoreCase = true) == true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filter printers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (building != null || floor != null) {
                        TextButton(onClick = { ReleaseHandoff.clearFilter() }) { Text("Clear") }
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
            item(key = "search") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search a building") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
            }

            if (floors.isNotEmpty()) {
                item(key = "floor-label") { SectionLabel("Floor") }
                item(key = "floors") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = floor == null,
                            onClick = { ReleaseHandoff.floor = null },
                            label = { Text("Any floor") },
                        )
                        floors.forEach { f ->
                            FilterChip(
                                selected = floor == f,
                                onClick = { ReleaseHandoff.floor = if (floor == f) null else f },
                                label = { Text("Floor $f") },
                            )
                        }
                    }
                }
            }

            item(key = "building-label") { SectionLabel("Building") }
            item(key = "all-buildings") {
                FilterRow(
                    title = "All buildings",
                    supporting = "${state.devices.size} printers",
                    selected = building == null,
                    onClick = { ReleaseHandoff.clearFilter() },
                )
            }
            items(shown) { (code, count) ->
                FilterRow(
                    title = buildingName(code)?.let { "$code · $it" } ?: code,
                    supporting = if (count == 1) "1 printer" else "$count printers",
                    selected = building == code,
                    onClick = {
                        // Changing building drops the floor: floor 3 of one building says nothing
                        // about another, and a stale floor would show an empty list.
                        ReleaseHandoff.building = code
                        ReleaseHandoff.floor = null
                    },
                )
            }
            item(key = "spacer") { Spacer(Modifier.height(MasonScrollSpacer)) }
        }
    }
}

/** A pickable row: charcoal when chosen, outlined when not — selection is a state, not an outcome. */
@Composable
private fun FilterRow(
    title: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(20.dp))
        }
    }
}
