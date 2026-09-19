package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.ui.theme.currentMasonColors

/** Shared by grouped listings, standalone favourites and the release confirmation. */
@Composable
internal fun PrinterBuildingDetails(key: String, modifier: Modifier = Modifier) {
    val location = buildingLocation(key)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(buildingLabel(key), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        location?.let {
            Text(it.campusName, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrinterAddress(it)
        }
    }
}

@Composable
internal fun PrinterAddress(location: BuildingLocation) {
    Text(
        location.addressLabel,
        style = MaterialTheme.typography.bodySmall,
        color = if (location.confidence == LocationConfidence.Confirmed) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else currentMasonColors.warn,
    )
}
