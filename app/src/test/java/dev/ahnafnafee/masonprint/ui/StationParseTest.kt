package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.Device
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StationParseTest {
    private val host = "mobileprint.gmu.edu"
    private fun device(name: String?, location: String = "/devices/1") = Device(
        location = location, name = name, make = null, model = null, assetTag = null,
        serialNumber = null, server = null, description = null, deviceGroups = emptyList(),
        duplexSupported = false, colorSupported = false,
    )
    private fun station(name: String) = stationOf(device(name), host)
    private fun location(name: String) = buildingLocation(station(name).building)!!

    @Test fun `every observed printer resolves to its researched campus and building`() {
        val rows = javaClass.getResourceAsStream("/gmu-printer-stations.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(302, rows.size)
        rows.forEach { row ->
            val (name, key) = row.split('\t')
            assertEquals(name, key, station(name).building)
        }
        assertEquals(80, rows.map { station(it.substringBefore('\t')).building }.distinct().size)
        assertEquals(92, GmuBuildings.sumOf { it.codes.size })
        assertEquals(92, GmuBuildings.flatMap { b -> b.codes.map { "${b.campus}-$it" } }.distinct().size)
    }

    @Test fun `campus distinguishes Founders Hall from Field House and library aliases`() {
        assertEquals("Van Metre Hall", location("AR-FH2-217-3930").name)
        assertEquals("3351 Fairfax Drive, Arlington, VA", location("AR-FHLIB2-211A-3935").address)
        assertEquals("Field House", location("FX-FH1-129-4935").name)
        assertEquals("Fenwick Library", location("FX-FL2-2400-3935").name)
        assertNotEquals(station("AR-FH2-217-3930").building, station("FX-FH1-129-4935").building)
    }

    @Test fun `renamed buildings and floor aliases share a filter group`() {
        assertEquals(station("FX-BH2-D205-4935").building, station("FX-MH1-D150-5860").building)
        assertEquals("James Buchanan Hall", location("FX-MHG-D9-3930").name)
        assertEquals("Alan and Sally Merten Hall", location("FX-MRT4-4123-478").name)
        assertEquals("Katherine G. Johnson Hall", location("ST-BRH2-220A-568").name)
        assertEquals(station("FX-KH2-202-359").building, station("FX-KRUG1-110-359").building)
    }

    @Test fun `user confirmed Fuse and Activities have physical addresses`() {
        assertEquals("Fuse at Mason Square", location("AR-FSB6-6318-5800").name)
        assertEquals("3401 Fairfax Drive, Arlington, VA", location("AR-FSB6-6318-5800").address)
        assertEquals("Activities Building", location("FX-ACT-LOBBY-3935").name)
        assertEquals("4530 Global Lane, Fairfax, VA", location("FX-ACT-LOBBY-3935").address)
        assertEquals(LocationConfidence.Confirmed, location("AR-FSB6-6318-5800").confidence)
        assertEquals(LocationConfidence.Confirmed, location("FX-ACT-LOBBY-3935").confidence)
    }

    @Test fun `numeric building and street codes do not lose their last digit`() {
        assertEquals(Station("gmu:FX-SUB1", "2", "2142"), station("FX-SUB12-2142-5840"))
        assertEquals(Station("gmu:FX-NEM2", "1", "125D"), station("FX-NEM21-125D-359"))
        assertEquals(Station("gmu:FX-NEM2", "1", "125D"), station("FX-NEM2-125D-359"))
        assertEquals(Station("gmu:FX-4260CBR", "5", "A501B"), station("FX-4260CBR-A501B-4935"))
        assertEquals(Station("gmu:FX-9900MS", "4", "423"), station("FX-9900MS4-423-4935"))
        assertEquals(Station("gmu:ST-9485INOVDR", "1", "120"), station("ST-9485INOVDR-120-478"))
    }

    @Test fun `ground lower and basement floors keep their meaning`() {
        assertEquals("G", station("FX-JCG-G10A-568").floor)
        assertEquals("G", station("FX-ENTG-017A-3930").floor)
        assertEquals("B", station("AR-FHB-122-568").floor)
        assertEquals("L", station("FX-ABL-LOBBY-568").floor)
        assertEquals("L", station("FX-TL-LOBBY-4935").floor)
        assertEquals("1", station("AR-HHB1-FLD-568").floor)
        assertEquals("2", station("AR-FH-217-3930").floor)
        assertEquals("Ground floor · Room G10A", stationRoom(station("FX-JCG-G10A-568")))
        assertEquals(listOf("B", "L", "G", "1", "2", "10", null),
            listOf("10", "G", "1", null, "B", "2", "L").sortedBy(::floorOrder))
    }

    @Test fun `uncertain mappings are labelled and unknown addresses are never invented`() {
        val unresolved = GmuBuildings.filter { it.confidence == LocationConfidence.Unresolved }
        assertEquals(setOf("gmu:FX-FCPT", "gmu:FX-FMHR", "gmu:ST-FTP"), unresolved.map { it.key }.toSet())
        unresolved.forEach { assertNull(it.address); assertEquals("Address not verified", it.addressLabel) }
        assertEquals(10, GmuBuildings.count { it.confidence == LocationConfidence.Inferred })
        assertTrue(location("FX-NEM1-105-3930").addressLabel.startsWith("Location to confirm"))
    }

    @Test fun `search includes new old and accentless names codes cities addresses and rooms`() {
        fun matches(name: String, q: String) = matchesPrinterQuery(device(name), station(name), q)
        assertTrue(matches("AR-FSB6-6318-5800", "Fuse 6318"))
        assertTrue(matches("AR-FSB6-6318-5800", "3401 Fairfax Arlington"))
        assertTrue(matches("AR-FH2-217-3930", "Founders"))
        assertTrue(matches("AR-FH2-217-3930", "Van Metre"))
        assertTrue(matches("FX-BH2-D205-4935", "Mason Hall"))
        assertTrue(matches("FX-MG1-1315-4935", "Angel Cabrera"))
        assertTrue(matches("FX-JCG-G10A-568", "Johnson ground"))
        assertTrue(matchesBuildingQuery(station("AR-FH2-217-3930").building, "AR-FHLIB"))
        assertTrue(matchesBuildingQuery(station("ST-BRH2-220A-568").building, "Manassas Johnson"))
        assertFalse(matches("AR-FH2-217-3930", "Field House"))
        assertFalse(matches("AR-FSB6-6318-5800", "Fuse 9999"))
    }

    @Test fun `catalog never leaks to another Pharos host and unknown labels remain reachable`() {
        for (otherHost in listOf("", "example.edu", "mobileprint.gmu.edu.example.org")) {
            val s = stationOf(device("FX-ENG4-LOBBY-5840"), otherHost)
            assertNull(buildingLocation(s.building))
            assertFalse(matchesPrinterQuery(device("FX-ENG4-LOBBY-5840"), s, "Nguyen"))
        }
        assertEquals(Station("FX-NEW", "3", "301"), station("FX-NEW3-301-1234"))
        assertEquals(Station("Other", null), station("printer42"))
        assertEquals("Other", stationOf(device(null), host).building)
        assertEquals("FX-NEW · Floor 3", filterSummary("FX-NEW", "3"))
    }

    @Test fun `parsing is independent of the phone locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("gmu:FX-FINLEY", station("fx-finley2-208d-568").building)
        } finally { Locale.setDefault(previous) }
    }
}
