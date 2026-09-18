package dev.ahnafnafee.masonprint.data.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The printer cache is only useful if a [Device] survives a JSON round-trip through [PharosJson].
 * Devices are normally hand-parsed from the wire ([Device.from]), so the `@Serializable` path the
 * cache relies on is exercised nowhere else — this pins it, including a row with null optionals.
 */
class DeviceCacheTest {

    private val serializer = ListSerializer(Device.serializer())

    @Test
    fun `device list round-trips through PharosJson`() {
        val devices = listOf(
            Device(
                location = "/devices/162",
                name = "AR-FH2-217-3930",
                make = "CANON",
                model = "iR-ADV C3930",
                assetTag = "FH2-217",
                serialNumber = "SN123",
                server = "mps",
                description = "Lobby colour",
                deviceGroups = listOf("Mobile Print Group"),
                duplexSupported = true,
                colorSupported = true,
            ),
            Device(
                location = "/devices/28",
                name = "AR-FH7-702-568",
                make = "CANON",
                model = "iR-ADV C568",
                assetTag = null,
                serialNumber = null,
                server = null,
                description = null,
                deviceGroups = emptyList(),
                duplexSupported = false,
                colorSupported = false,
            ),
        )

        val restored = PharosJson.decodeFromString(serializer, PharosJson.encodeToString(serializer, devices))

        assertEquals(devices, restored)
        // Computed helpers are not serialized, but must still work on a restored row.
        assertEquals("AR-FH2-217-3930", restored[0].label)
        assertTrue(restored[0].matchesToken("/devices/162"))
    }
}
