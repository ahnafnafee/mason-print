package dev.ahnafnafee.masonprint.ui

/** Physical locations checked against GMU sources on 2026-09-19; see docs/PRINTER-LOCATIONS.md. */
internal enum class LocationConfidence { Confirmed, Inferred, Unresolved }

internal data class BuildingLocation(
    val campus: String,
    val codes: List<String>,
    val name: String,
    val street: String?,
    val confidence: LocationConfidence = LocationConfidence.Confirmed,
    val aliases: String = "",
) {
    val key: String get() = "gmu:$campus-${codes.first()}"
    val campusName: String get() = when (campus) {
        "AR" -> "Mason Square"
        "FX" -> "Fairfax"
        "ST" -> "Science and Technology"
        "WB" -> "Woodbridge"
        else -> campus
    }
    private val city: String get() = when (campus) {
        "AR" -> "Arlington"
        "ST" -> "Manassas"
        "WB" -> "Woodbridge"
        else -> "Fairfax"
    }
    val address: String? get() = street?.let { "$it, $city, VA" }
    val addressLabel: String get() = when (confidence) {
        LocationConfidence.Confirmed -> address ?: "Address not verified"
        LocationConfidence.Inferred -> "Location to confirm · ${address ?: "Address not verified"}"
        LocationConfidence.Unresolved -> "Address not verified"
    }
    val searchText: String get() = "$name $aliases $campusName ${address.orEmpty()} " +
        codes.joinToString(" ") { "$campus-$it" }
}

internal val GmuBuildings: List<BuildingLocation> = listOf(
    BuildingLocation("AR", listOf("FH", "FHB", "FHLIB"), "Van Metre Hall", "3351 Fairfax Drive", aliases = "Founders Hall Mason Square Library Arlington Library"),
    BuildingLocation("AR", listOf("FSB"), "Fuse at Mason Square", "3401 Fairfax Drive"),
    BuildingLocation("AR", listOf("HH", "HHB", "LAWLIB"), "Hazel Hall", "3301 Fairfax Drive", aliases = "Antonin Scalia Law Library Law School"),
    BuildingLocation("AR", listOf("VSH"), "Vernon Smith Hall", "3434 Washington Boulevard"),
    BuildingLocation("FX", listOf("4260CBR"), "University Park", "4260 Chain Bridge Road"),
    BuildingLocation("FX", listOf("9900MS"), "9900 Main Street building", "9900 Main Street"),
    BuildingLocation("FX", listOf("AB", "ABL"), "Art and Design Building", "4515 Patriot Circle"),
    BuildingLocation("FX", listOf("ACT"), "Activities Building", "4530 Global Lane"),
    BuildingLocation("FX", listOf("AFC"), "Aquatic and Fitness Center", "4520 Patriot Circle"),
    BuildingLocation("FX", listOf("AQ"), "Aquia Building", "4461 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("BH", "MH", "MHG"), "James Buchanan Hall", "4379 Mason Pond Drive", aliases = "Mason Hall"),
    BuildingLocation("FX", listOf("BL"), "Blue Ridge Hall", "4343A Chesapeake River Way"),
    BuildingLocation("FX", listOf("CAROW"), "Carow Hall", "4460 Rockfish Creek Lane"),
    BuildingLocation("FX", listOf("CDC"), "Child Development Center", "4402 University Drive"),
    BuildingLocation("FX", listOf("CFA"), "Center for the Arts", "4373 Mason Pond Drive"),
    BuildingLocation("FX", listOf("CH"), "College Hall", "4379 Mason Pond Drive"),
    BuildingLocation("FX", listOf("CO"), "Commonwealth Hall", "10408 Rivanna River Way"),
    BuildingLocation("FX", listOf("DK"), "David J. King Hall", "10428 Rivanna River Way"),
    BuildingLocation("FX", listOf("DO"), "Dominion Hall", "10410 Rivanna River Way"),
    BuildingLocation("FX", listOf("E"), "East Building", "4405 Patriot Circle"),
    BuildingLocation("FX", listOf("EA"), "Eastern Shore Hall", "4403 Patriot Circle"),
    BuildingLocation("FX", listOf("EBA"), "EagleBank Arena", "4500 Patriot Circle"),
    BuildingLocation("FX", listOf("EI"), "Eisenhower Hall", "10445 Presidents Park Drive"),
    BuildingLocation("FX", listOf("ENG"), "Long and Kimmy Nguyen Engineering Building", "4511 Patriot Circle", aliases = "Engineering ENGR"),
    BuildingLocation("FX", listOf("ENT", "ENTG"), "Enterprise Hall", "10443 Sandy Creek Lane"),
    BuildingLocation("FX", listOf("EXPL"), "Exploratory Hall", "10431 Rivanna River Way"),
    BuildingLocation("FX", listOf("FARCH"), "Facilities Management Archives and Shops", "10398 Rivanna River Way", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("FCPT"), "Facilities (FCPT)", null, confidence = LocationConfidence.Unresolved),
    BuildingLocation("FX", listOf("FH"), "Field House", "4501 University Drive"),
    BuildingLocation("FX", listOf("FINLEY"), "Finley Building", "4407 Patriot Circle"),
    BuildingLocation("FX", listOf("FL"), "Fenwick Library", "4348 Chesapeake River Way"),
    BuildingLocation("FX", listOf("FM"), "Facilities Maintenance Building", "10400 Rivanna River Way", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("FMADMIN"), "Facilities Administration", "10402 Rivanna River Way"),
    BuildingLocation("FX", listOf("FMCHCP"), "Central Heating and Cooling Plant", "10403 Rivanna River Way", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("FMHR"), "Facilities Human Resources", null, confidence = LocationConfidence.Unresolved),
    BuildingLocation("FX", listOf("FMW"), "Facilities Warehouse", "10401 Rivanna River Way"),
    BuildingLocation("FX", listOf("HA"), "Hampton Roads Hall", "4401 Patriot Circle", aliases = "Pilot House"),
    BuildingLocation("FX", listOf("HOLL"), "Hanover Hall", "10418 Rivanna River Way", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("HRZ"), "Horizon Hall", "4475 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("HUB"), "The Hub", "10423 Rivanna River Way"),
    BuildingLocation("FX", listOf("IN"), "Innovation Hall", "4699 Mattaponi River Lane"),
    BuildingLocation("FX", listOf("JC", "JCG"), "Johnson Center", "4477 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("KB"), "Krasnow Institute Building", "4461 Rockfish Creek Lane"),
    BuildingLocation("FX", listOf("KH", "KRUG"), "Krug Hall", "4467 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("LI"), "Liberty Square", "10440 Presidents Park Drive"),
    BuildingLocation("FX", listOf("MG"), "Ángel Cabrera Global Center", "4352 Mason Pond Drive", aliases = "Mason Global Center Angel Cabrera"),
    BuildingLocation("FX", listOf("MRT"), "Alan and Sally Merten Hall", "4441 George Mason Boulevard"),
    BuildingLocation("FX", listOf("NA"), "Nottoway Annex", "10470 Holston Creek Court"),
    BuildingLocation("FX", listOf("NEM"), "Northeast Module", "4580 University Drive", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("NEM2"), "Northeast Module II", "4590 University Drive"),
    BuildingLocation("FX", listOf("NNH"), "Northern Neck Hall", "4335 Chesapeake River Way"),
    BuildingLocation("FX", listOf("PAB"), "Donald and Nancy de Laski Performing Arts Building", "4480 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("PFHS"), "Peterson Hall", "4408 Patriot Circle", aliases = "Peterson Family Health Sciences Hall"),
    BuildingLocation("FX", listOf("PH"), "Potomac Heights", "10350 York River Road", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("PI"), "Piedmont Hall", "4349A Chesapeake River Way"),
    BuildingLocation("FX", listOf("PLANET"), "Planetary Hall", "10430 Rivanna River Way"),
    BuildingLocation("FX", listOf("PS"), "Parking Services Building", "10420 York River Road", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("PSHQ"), "Police and Safety Headquarters", "4393 University Drive", confidence = LocationConfidence.Inferred),
    BuildingLocation("FX", listOf("RAC"), "Recreation and Athletic Complex", "4350A Banister Creek Court"),
    BuildingLocation("FX", listOf("RSCH"), "Research Hall", "10401 York River Road"),
    BuildingLocation("FX", listOf("SA"), "Sandbridge Hall", "4343B Chesapeake River Way", aliases = "Sandridge"),
    BuildingLocation("FX", listOf("SD"), "Southside Dining Hall", "4353 Chesapeake River Way"),
    BuildingLocation("FX", listOf("SUB1"), "Student Union Building I", "4469 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("T", "TG", "TL"), "Thompson Hall", "4409 Patriot Circle"),
    BuildingLocation("FX", listOf("TA"), "Taylor Hall", "10444 Presidents Park Drive"),
    BuildingLocation("FX", listOf("W"), "West Building", "4457 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("WH"), "Whitetop Hall", "4402 Aquia Creek Lane"),
    BuildingLocation("FX", listOf("WPM"), "West Physical Education Module", "4540 Global Lane"),
    BuildingLocation("ST", listOf("9485INOVDR"), "Innovation Park", "9485 Innovation Drive"),
    BuildingLocation("ST", listOf("BEH"), "Beacon Hall", "10945 George Mason Circle"),
    BuildingLocation("ST", listOf("BRH"), "Katherine G. Johnson Hall", "10890 George Mason Circle", aliases = "Bull Run Hall"),
    BuildingLocation("ST", listOf("BRL"), "Biomedical Research Laboratory", "10650 Pyramid Place", confidence = LocationConfidence.Inferred),
    BuildingLocation("ST", listOf("CCH"), "Senator Charles J. Colgan Hall", "10900 George Mason Circle"),
    BuildingLocation("ST", listOf("DH"), "Discovery Hall", "10910 George Mason Circle"),
    BuildingLocation("ST", listOf("FC"), "Freedom Aquatic and Fitness Center", "9100 Freedom Center Boulevard", aliases = "PW-FC"),
    BuildingLocation("ST", listOf("FTP"), "Facilities parts / trailer", null, confidence = LocationConfidence.Unresolved),
    BuildingLocation("ST", listOf("HPAC"), "Hylton Performing Arts Center", "10960 George Mason Circle", confidence = LocationConfidence.Inferred),
    BuildingLocation("ST", listOf("IABR"), "Institute for Advanced Biomedical Research", "10920 George Mason Circle"),
    BuildingLocation("ST", listOf("LSEB"), "Life Sciences and Engineering Building", "10930 George Mason Circle", aliases = "PW-LSEB"),
    BuildingLocation("WB", listOf("PSC"), "Potomac Science Center", "650 Mason Ferry Avenue"),
)

private val buildingsByKey = GmuBuildings.associateBy { it.key }
private val buildingsByCode = GmuBuildings.flatMap { building ->
    building.codes.map { code -> "${building.campus}-$code" to building }
}.toMap()

internal fun buildingLocation(key: String): BuildingLocation? = buildingsByKey[key]
internal fun gmuBuildingForCode(code: String): BuildingLocation? = buildingsByCode[code]
