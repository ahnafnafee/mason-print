package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.Device

/** Building + floor parsed from a station name. */
internal data class Station(val building: String, val floor: String?)

/**
 * Where a printer is, worked out from its name.
 *
 * This has to be parsed because the server does not say: GMU returns `Building`, `Floor`, `Area`,
 * `DeviceLocation` and `Region` on every device and leaves all five `null`, and `Description` is a
 * verbatim copy of `Name`. The name is the only location data there is.
 *
 * The shape is `<campus>-<building><floor>-<room>-<model>`: `FX-JC1-135-568`, `FX-AFC1-105-3930`,
 * `FX-SUB12-2142-5840`.
 *
 * **Exactly one trailing digit is the floor.** `SUB12` is Student Union I, floor 2 — not "SUB",
 * floor 12 — and its room `2142` agrees. Taking every trailing digit invents twelve-storey
 * buildings and splits one building into several.
 *
 * A token with no trailing digit keeps its whole name and takes the floor from the first digit of
 * the room (`AR-FH-217` → FH, floor 2). Anything that will not parse becomes "Other" with no
 * floor — grouped, never hidden, so no printer is unreachable.
 */
internal fun stationOf(device: Device): Station {
    val parts = (device.name ?: device.label).split('-', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val token = parts.getOrNull(1)
    if (token == null || !token.first().isLetter()) return Station("Other", null)

    val hasFloorDigit = token.last().isDigit()
    val building = (if (hasFloorDigit) token.dropLast(1) else token).uppercase()
    val floorFromToken = if (hasFloorDigit) token.last().toString() else null
    val floorFromRoom = parts.getOrNull(2)?.firstOrNull { it.isDigit() }?.toString()
    return Station(building.ifBlank { "Other" }, floorFromToken ?: floorFromRoom)
}

/**
 * Plain-English names for the building codes on the stickers.
 *
 * Deliberately partial. The code is what is printed on the machine and on the QR label, so the code
 * is always what the UI leads with and this map only ever *adds* a hint — a missing or imperfect
 * name can never send somebody to the wrong place, because the code beside it is authoritative.
 * Codes absent here simply show as themselves.
 */
private val BuildingNames: Map<String, String> = mapOf(
    "AFC" to "Aquatic and Fitness Center",
    "AQ" to "Aquia Building",
    "CDC" to "Child Development Center",
    "CFA" to "Center for the Arts",
    "DK" to "David King Hall",
    "ENG" to "Engineering Building",
    "EXPL" to "Exploratory Hall",
    "FHLIB" to "Fenwick Library",
    "FM" to "Facilities Management",
    "HRZ" to "Horizon Hall",
    "HUB" to "The HUB",
    "IN" to "Innovation Hall",
    "JC" to "Johnson Center",
    "KRUG" to "Krug Hall",
    "LAWLIB" to "Law Library",
    "MH" to "Merten Hall",
    "NEM" to "Nguyen Engineering Building",
    "PFHS" to "Peterson Family Health Sciences Hall",
    "PLANET" to "Planetary Hall",
    "SUB1" to "Student Union Building I",
)

/** The building's plain name, or null when this code is not one we can name. */
internal fun buildingName(code: String): String? = BuildingNames[code.uppercase()]

/** What the printer list's filter button reads: "All buildings", "JC · Johnson Center · Floor 1". */
internal fun filterSummary(building: String?, floor: String?): String {
    val head = building?.let { code -> buildingName(code)?.let { "$code · $it" } ?: code }
        ?: return if (floor == null) "All buildings" else "All buildings · Floor $floor"
    return if (floor == null) head else "$head · Floor $floor"
}
