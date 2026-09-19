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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState

/** Edit a draft filter; only the pinned Show printers action applies it to the printer list. */
@Composable
internal fun PrinterFilterScreen(state: AppState, onBack: () -> Unit) {
    var building by rememberSaveable(state.host) { mutableStateOf(ReleaseHandoff.building) }
    var floor by rememberSaveable(state.host) { mutableStateOf(ReleaseHandoff.floor) }
    var search by rememberSaveable { mutableStateOf("") }
    var showAllFloors by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    val stations = remember(state.devices, state.host) { state.devices.map { stationOf(it, state.host) } }
    val buildings = remember(stations) {
        stations.groupingBy { it.building }.eachCount().toList()
            .sortedWith(compareBy({ it.first == "Other" }, { buildingLocation(it.first)?.campusName.orEmpty() }, { buildingLabel(it.first) }))
    }
    // Only the floors the chosen building actually has — an empty floor filter is a dead end.
    val floors = remember(stations, building) {
        stations.filter { building == null || it.building == building }
            .mapNotNull { it.floor }
            .distinct()
            .sortedBy(::floorOrder)
    }
    val q = search.trim()
    val shown = remember(buildings, q) {
        if (q.isEmpty()) buildings
        else buildings.filter { (code, _) ->
            matchesBuildingQuery(code, q)
        }
    }
    val matchingCount = remember(stations, building, floor) {
        stations.count { (building == null || it.building == building) && (floor == null || it.floor == floor) }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Filter printers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel filter changes")
                    }
                },
                actions = {
                    if (building != null || floor != null) {
                        TextButton(onClick = { building = null; floor = null }) { Text("Clear") }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        filterSummary(building, floor),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            ReleaseHandoff.building = building
                            ReleaseHandoff.floor = floor
                            keyboard?.hide()
                            onBack()
                        },
                        enabled = matchingCount > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (matchingCount == 1) "Show 1 printer" else "Show $matchingCount printers")
                    }
                }
            }
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
                    placeholder = { Text("Building, campus or address") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
            }

            if (q.isEmpty() && building == null && floor == null && floors.isNotEmpty()) {
                item(key = "floor-toggle") {
                    TextButton(onClick = { showAllFloors = !showAllFloors }) {
                        Text(if (showAllFloors) "Hide floors" else "Filter by floor")
                    }
                }
            }
            if (q.isEmpty() && floors.isNotEmpty() && (building != null || floor != null || showAllFloors)) {
                item(key = "floor-label") { SectionLabel("Floor") }
                item(key = "floors") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = floor == null,
                            onClick = { floor = null },
                            label = { Text("Any floor") },
                        )
                        floors.forEach { f ->
                            FilterChip(
                                selected = floor == f,
                                onClick = { floor = if (floor == f) null else f },
                                label = { Text(floorLabel(f)) },
                            )
                        }
                    }
                }
            }

            item(key = "building-label") { SectionLabel("Building") }
            if (q.isEmpty()) {
                item(key = "all-buildings") {
                    FilterRow(
                        title = "All buildings",
                        supporting = "${state.devices.size} printers",
                        selected = building == null,
                        onClick = { building = null; floor = null },
                    )
                }
            }
            items(shown, key = { it.first }) { (code, count) ->
                val location = buildingLocation(code)
                FilterRow(
                    title = buildingLabel(code),
                    supporting = listOfNotNull(location?.campusName, if (count == 1) "1 printer" else "$count printers").joinToString(" · "),
                    location = location,
                    selected = building == code,
                    onClick = {
                        // Changing building drops the floor: floor 3 of one building says nothing
                        // about another, and a stale floor would show an empty list.
                        if (building != code) {
                            building = code
                            floor = null
                        }
                        search = ""
                        keyboard?.hide()
                    },
                )
            }
            if (shown.isEmpty()) {
                item(key = "no-match") {
                    Text("No building matches this search. Try a name, campus, street or label code.",
                        style = MaterialTheme.typography.bodyMedium)
                }
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
    location: BuildingLocation? = null,
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                location?.let { PrinterAddress(it) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(20.dp))
        }
    }
}
