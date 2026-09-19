package dev.ahnafnafee.masonprint.data.net

import org.junit.Assert.*
import org.junit.Test

class TargetRestoreTest {
    @Test fun `server origin survives saving including nondefault ports and scheme`() {
        for (address in listOf("https://print.example.edu:8443/myprintcenter", "http://localhost:8080", "https://[::1]:8443")) {
            val target = PharosTarget.parse(address)!!
            assertEquals(target.root, PharosTarget.parse(target.savedAddress)!!.root)
        }
    }
}
