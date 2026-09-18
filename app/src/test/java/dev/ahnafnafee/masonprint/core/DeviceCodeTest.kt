package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR payload cut.
 *
 * The vendor publishes no label format (`docs/FINDINGS.md` §8.5), and its own parser is
 * `text.Split('#')[1].Substring(5)` — i.e. it requires the literal `#code=` and throws away
 * everything else, which is CLONE-PLAN risk R8: a sticker in some other shape is rejected by the
 * stock app even though the device id in it is perfectly usable. So the accepted shapes are pinned
 * here one by one, together with the invariant that actually matters — every shape must land on the
 * same token, because the token is what [Device.matchesToken] and then `GET /devices/{id}` use.
 */
class DeviceCodeTest {

    @Test
    fun bareDeviceIdPassesThroughUnchanged() {
        assertEquals("LIBRARY-02", deviceTokenFromQr("LIBRARY-02"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("LIBRARY-02", deviceTokenFromQr("  LIBRARY-02\n"))
    }

    @Test
    fun vendorFormIsCutTheWayMainActivityCsCutsIt() {
        // The exact shape the stock app accepted; `Split('#')[1].Substring(5)` yields LIBRARY-02.
        assertEquals(
            "LIBRARY-02",
            deviceTokenFromQr("https://mobileprint.gmu.edu/MyPrintCenter/#code=LIBRARY-02"),
        )
    }

    @Test
    fun slashBeforeCodeIsAccepted() {
        // `#/code=` is the fragment the SPA's own routing uses, and what a generated sticker of
        // GMU's web release page would carry.
        assertEquals("1234", deviceTokenFromQr("https://printservices.gmu.edu/pharosprint/#/code=1234"))
    }

    @Test
    fun queryParameterFormIsAccepted() {
        assertEquals("4821", deviceTokenFromQr("https://printservices.gmu.edu/pharosprint/?code=4821"))
    }

    @Test
    fun codeKeyIsMatchedRegardlessOfCase() {
        assertEquals("4821", deviceTokenFromQr("https://x/print?CODE=4821"))
    }

    @Test
    fun trailingSlashIsNotPartOfTheToken() {
        assertEquals("LIBRARY-02", deviceTokenFromQr("https://x/#/code=LIBRARY-02/"))
    }

    @Test
    fun parametersAfterTheCodeAreDropped() {
        assertEquals("ABC", deviceTokenFromQr("https://x/#code=ABC&station=4"))
        assertEquals("ABC", deviceTokenFromQr("https://x/#code=ABC#extra"))
    }

    @Test
    fun urlEndingInDeviceLocationIsKeptWholeForTheTolerantMatcher() {
        // No `code=` anywhere: the whole URL comes back, because Device.matchesToken already takes
        // the last path segment. Cutting it here would duplicate that rule in two places.
        assertEquals(
            "https://mobileprint.gmu.edu/PharosAPI/Devices/39",
            deviceTokenFromQr("https://mobileprint.gmu.edu/PharosAPI/Devices/39"),
        )
    }

    @Test
    fun nothingUsableYieldsNoToken() {
        assertNull(deviceTokenFromQr(null))
        assertNull(deviceTokenFromQr(""))
        assertNull(deviceTokenFromQr("   "))
        assertNull(deviceTokenFromQr("https://x/print?code="))
        assertNull(deviceTokenFromQr("https://x/print?code=   "))
    }

    @Test
    fun everyStickerShapeLandsOnTheSamePrinter() {
        // The invariant R8 is really about: however the sticker wraps it, the scan must reach the
        // same row a hand-typed code reaches.
        val devices = listOf(device("LIBRARY-02", assetTag = "ASSET-4471"), device("SCIENCE-11"))
        val shapes = listOf(
            "LIBRARY-02",
            "library-02",
            "https://mobileprint.gmu.edu/MyPrintCenter/#code=LIBRARY-02",
            "https://printservices.gmu.edu/pharosprint/#/code=LIBRARY-02",
            "https://printservices.gmu.edu/pharosprint/?code=LIBRARY-02",
            "https://printservices.gmu.edu/PharosAPI/Devices/LIBRARY-02",
            "ASSET-4471",
        )
        for (shape in shapes) {
            val token = deviceTokenFromQr(shape)
            assertTrue("no token cut from $shape", token != null)
            val hit = devices.firstOrNull { it.matchesToken(token!!) }
            assertEquals("scan of $shape picked the wrong printer", "LIBRARY-02", hit?.location)
        }
    }

    @Test
    fun shortPanelCodeIsPlausible() {
        // GMU's station screen prints a four-digit code under "Release code".
        assertTrue(isPlausibleDeviceToken("4821"))
        assertTrue(isPlausibleDeviceToken("LIBRARY-02"))
        assertTrue(isPlausibleDeviceToken("39"))
    }

    @Test
    fun emptyAndNonAlphanumericTokensAreNotPlausible() {
        assertFalse(isPlausibleDeviceToken(""))
        assertFalse(isPlausibleDeviceToken("   "))
        assertFalse(isPlausibleDeviceToken("---"))
        assertFalse(isPlausibleDeviceToken("···"))
    }

    @Test
    fun tokenWithSpacesOrControlCharactersIsNotPlausible() {
        // A Wi-Fi QR payload ("WIFI:S:GMU-Wireless;;") or a poster sentence, not a station code.
        assertFalse(isPlausibleDeviceToken("GMU Wireless Guest"))
        assertFalse(isPlausibleDeviceToken("LIBRARY-02\nSCIENCE-11"))
    }

    @Test
    fun absurdlyLongTokenIsNotPlausible() {
        assertTrue(isPlausibleDeviceToken("a".repeat(128)))
        assertFalse(isPlausibleDeviceToken("a".repeat(129)))
    }

    @Test
    fun misfireSentenceNamesWhatTheCameraSaw() {
        val sentence = unreadableScanSentence("WIFI:S:GMU-Wireless;;")
        assertTrue(sentence, sentence.contains("WIFI:S:GMU-Wireless;;"))
        assertTrue(sentence, sentence.contains("sticker"))
        assertTrue(sentence, sentence.contains("Type the release code"))
    }

    @Test
    fun misfireSentenceCopesWithNothingBeingRead() {
        assertTrue(unreadableScanSentence(null).contains("no usable code"))
        assertTrue(unreadableScanSentence("  ").contains("no usable code"))
    }

    @Test
    fun misfireSentenceTruncatesALongPayload() {
        // A Wi-Fi or vCard payload can be hundreds of characters; the sentence quotes only enough
        // of it for the student to see what the camera actually looked at.
        val long = "https://example.com/" + "x".repeat(120)
        val sentence = unreadableScanSentence(long)
        assertTrue(sentence, sentence.contains(long.take(60)))
        assertFalse(sentence, sentence.contains(long))
    }

    private fun device(location: String, assetTag: String? = null) = Device(
        location = location,
        name = null,
        make = null,
        model = null,
        assetTag = assetTag,
        serialNumber = null,
        server = null,
        description = null,
        deviceGroups = emptyList(),
        duplexSupported = false,
        colorSupported = false,
    )
}
