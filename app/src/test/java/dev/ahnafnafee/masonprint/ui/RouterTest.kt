package dev.ahnafnafee.masonprint.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back stack is now the app's navigation, so its rules are worth pinning down. These are the
 * behaviours that keep the *system* Back button honest — the reason the router exists at all rather
 * than being an exercise in re-implementing Navigation Compose.
 */
class RouterTest {

    @Test
    fun `starts at the given route with nothing to go back to`() {
        val r = Router(Route.Campus)
        assertEquals(Route.Campus, r.current)
        assertFalse("a single frame must not consume the Back button", r.canGoBack)
        assertEquals(1, r.depth)
    }

    @Test
    fun `push then pop walks the stack in order`() {
        val r = Router(Route.Queue)
        r.push(Route.Release)
        r.push(Route.Confirm)
        assertEquals(Route.Confirm, r.current)
        assertEquals(3, r.depth)
        assertEquals(Route.Release, r.pop())
        assertEquals(Route.Queue, r.pop())
        assertNull("popping the root frame returns null instead of emptying the stack", r.pop())
        assertEquals(Route.Queue, r.current)
    }

    @Test
    fun `pushing the route you are already on does not duplicate it`() {
        val r = Router(Route.Queue)
        r.push(Route.Account)
        r.push(Route.Account)
        assertEquals(2, r.depth)
    }

    @Test
    fun `pushUnique skips a route that is already anywhere in the stack`() {
        val r = Router(Route.Queue)
        r.push(Route.Diagnostics)
        r.push(Route.Account)
        r.pushUnique(Route.Diagnostics)
        assertEquals(3, r.depth)
        assertEquals(Route.Account, r.current)
        // Unlike push, pushUnique will not re-open a screen that sits *below* the current one, so
        // Diagnostics cannot appear twice in one trail.
        r.push(Route.Diagnostics)
        assertEquals(4, r.depth)
    }

    @Test
    fun `replaceTop swaps the current frame without changing depth`() {
        val r = Router(Route.SignIn)
        r.push(Route.Certificate)
        assertEquals(2, r.depth)
        r.replaceTop(Route.SignIn)
        assertEquals(2, r.depth)
        assertEquals(Route.SignIn, r.current)
    }

    @Test
    fun `reset drops the whole stack so Back cannot walk into a dead phase`() {
        val r = Router(Route.Campus)
        r.push(Route.Certificate)
        r.push(Route.SignIn)
        r.reset(Route.Queue)
        assertEquals(Route.Queue, r.current)
        assertEquals(1, r.depth)
        assertFalse(r.canGoBack)
    }

    @Test
    fun `trail lists the frames below the current one`() {
        val r = Router(Route.Queue)
        r.push(Route.Release)
        r.push(Route.Confirm)
        assertEquals(listOf(Route.Queue, Route.Release), r.trail())
    }

    @Test
    fun `route ids are the prototype's own and stay resolvable`() {
        // Upload is an operation on the queue and must not remain a navigable destination.
        assertEquals(16, Route.entries.size)
        assertNull(Route.of("upload"))
        assertEquals(Route.Help, Route.of("help"))
        assertEquals(Route.Preview, Route.of("preview"))
        assertEquals(Route.Queue, Route.of("queue"))
        assertEquals(Route.CostCenters, Route.of("costcenter"))
        assertEquals(Route.PrintCenter, Route.of("printcenter"))
        assertEquals(Route.PrinterFilter, Route.of("printer-filter"))
        assertNull(Route.of("nope"))
        assertNull(Route.of(null))
        for (route in Route.entries) assertEquals(route, Route.of(route.id))
    }

    @Test
    fun `toString renders the trail the way a diagnostic line should`() {
        val r = Router(Route.Queue)
        r.push(Route.Release)
        assertEquals("queue › release", r.toString())
    }
}
