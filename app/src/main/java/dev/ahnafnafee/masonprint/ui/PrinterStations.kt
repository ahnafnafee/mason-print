package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.Device
import java.text.Normalizer
import java.util.Locale

/** Campus-qualified group, floor and room. The server currently leaves its location fields empty. */
internal data class Station(val building: String, val floor: String?, val room: String? = null)

/**
 * Resolve known GMU aliases before separating the one-digit floor suffix. Exact aliases win:
 * SUB12 is SUB1 / floor 2, NEM21 is NEM2 / floor 1, and 4260CBR is a street-number code.
 * Keep unknown stations reachable and never apply this university's addresses to another host.
 */
internal fun stationOf(device: Device, host: String): Station {
    val parts = (device.name ?: device.label).split('-', ' ').filter { it.isNotBlank() }
        .map { it.uppercase(Locale.ROOT) }
    val campus = parts.firstOrNull()
    val token = parts.getOrNull(1)
    if (campus == null || token == null) return Station("Other", null)
    val room = parts.getOrNull(2)
    val isGmu = host.equals("mobileprint.gmu.edu", ignoreCase = true)
    val exact = if (isGmu) gmuBuildingForCode("$campus-$token") else null
    val hasFloor = exact == null && token.last().isDigit()
    val code = if (hasFloor) token.dropLast(1) else token
    val location = exact ?: if (isGmu) gmuBuildingForCode("$campus-$code") else null
    val level = if (location != null) when ("$campus-$code") {
        "AR-FHB", "AR-HHB" -> "B"
        "FX-JCG", "FX-ENTG", "FX-MHG", "FX-TG" -> "G"
        "FX-ABL", "FX-TL", "FX-HOLL" -> "L"
        else -> null
    } else null
    val floor = if (hasFloor) token.last().toString() else level ?: room?.let {
        if (it.startsWith("G") && it.drop(1).firstOrNull()?.isDigit() == true) "G"
        else it.firstOrNull { ch -> ch.isDigit() }?.toString()
    }
    return Station(location?.key ?: "$campus-$code", floor, room)
}

internal fun floorLabel(floor: String): String = when (floor) {
    "B" -> "Basement"
    "G" -> "Ground floor"
    "L" -> "Lower level"
    else -> "Floor $floor"
}

internal fun floorOrder(floor: String?): Int = when (floor) {
    "B" -> -3
    "L" -> -2
    "G" -> -1
    else -> floor?.toIntOrNull() ?: Int.MAX_VALUE
}

internal fun stationRoom(station: Station): String? {
    val room = station.room?.let {
        when (it) {
            "LOBBY" -> "Lobby"
            "HALL" -> "Hallway"
            "LIB" -> "Library"
            "LIBFRNT" -> "Library entrance"
            "LOCKERS" -> "Lockers"
            "LAB" -> "Lab"
            "BOXOFF" -> "Box office"
            "PRODOFF" -> "Production office"
            "PARTS" -> "Parts"
            else -> if (it.any(Char::isDigit)) "Room $it" else it
        }
    }
    return listOfNotNull(station.floor?.let(::floorLabel), room).joinToString(" · ").ifBlank { null }
}

internal fun buildingName(key: String): String? = buildingLocation(key)?.name
internal fun buildingLabel(key: String): String = buildingName(key) ?: key

internal fun filterSummary(building: String?, floor: String?): String =
    listOfNotNull(building?.let(::buildingLabel) ?: "All buildings", floor?.let(::floorLabel)).joinToString(" · ")

private fun searchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

internal fun matchesBuildingQuery(key: String, query: String): Boolean =
    matchesLocationQuery(listOf(key, buildingLocation(key)?.searchText.orEmpty()), query)

internal fun matchesPrinterQuery(device: Device, station: Station, query: String): Boolean =
    matchesLocationQuery(
        listOfNotNull(device.name, device.model, device.make, device.location, device.assetTag,
            device.serialNumber, device.description, stationRoom(station),
            buildingLocation(station.building)?.searchText) + device.deviceGroups + station.building,
        query,
    )

private fun matchesLocationQuery(fields: List<String>, query: String): Boolean {
    val haystack = searchText(fields.joinToString(" "))
    return searchText(query).trim().split(Regex("\\s+")).all(haystack::contains)
}
