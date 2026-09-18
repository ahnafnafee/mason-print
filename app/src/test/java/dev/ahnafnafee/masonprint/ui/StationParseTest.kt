package dev.ahnafnafee.masonprint.ui

import dev.ahnafnafee.masonprint.data.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The building/floor parser behind the printer filter. It is a heuristic over GMU station names, so
 * the shapes that must work are pinned here — including the fallbacks that keep an odd name grouped
 * under "Other" instead of hidden.
 */
class StationParseTest {

    private fun device(name: String?, location: String = "/devices/1") =
        Device(
            location = location, name = name, make = null, model = null, assetTag = null,
            serialNumber = null, server = null, description = null, deviceGroups = emptyList(),
            duplexSupported = false, colorSupported = false,
        )

    @Test fun `floor from the building token`() {
        assertEquals(Station("ENG", "4"), stationOf(device("FX-ENG4-LOBBY-5840")))
        assertEquals(Station("FH", "2"), stationOf(device("AR-FH2-217-3930")))
        assertEquals(Station("FH", "7"), stationOf(device("AR-FH7-702-568")))
        assertEquals(Station("JC", "1"), stationOf(device("FX-JC1-135-568")))
        assertEquals(Station("AFC", "1"), stationOf(device("FX-AFC1-105-3930")))
    }

    /**
     * Only ONE trailing digit is the floor. `SUB12` is Student Union I, floor 2 — and its room
     * `2142` agrees. Taking every trailing digit read it as "SUB", floor 12, which invented a
     * twelve-storey building and split SUB1 away from its own stations.
     */
    @Test fun `a building whose name ends in a digit keeps that digit`() {
        assertEquals(Station("SUB1", "2"), stationOf(device("FX-SUB12-2142-5840")))
    }

    @Test fun `floor falls back to the first digit of the room when the token has none`() {
        // Building token "FH" carries no floor digit, so the room "217" supplies floor 2.
        assertEquals(Station("FH", "2"), stationOf(device("AR-FH-217-3930")))
    }

    @Test fun `an unparseable name lands in Other with no floor, never hidden`() {
        val s = stationOf(device("printer42"))
        assertEquals("Other", s.building)
        assertNull(s.floor)
    }

    @Test fun `a null name uses the location label and does not crash`() {
        assertEquals("Other", stationOf(device(name = null, location = "/devices/9")).building)
    }
}
