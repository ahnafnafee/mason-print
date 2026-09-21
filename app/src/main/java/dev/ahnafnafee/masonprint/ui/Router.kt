package dev.ahnafnafee.masonprint.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


/**
 * The destinations in the redesign.
 *
 * Ids are the redesign prototype's own route ids, kept verbatim so a screen name in a bug report can
 * be grepped against `design/REDESIGN-SPEC.md` §3 and land on the same screen. The prototype drives
 * its UI from a single `stack: ['queue']` array — screens are pushed, popped, and sometimes reset —
 * and this enum plus [Router] is that array rendered into Kotlin.
 *
 * Deliberately **not** Navigation Compose. Nothing here has URL-shaped arguments: the only things a
 * destination needs are the selection and the session, both of which already live in
 * [dev.ahnafnafee.masonprint.core.Session], and a nav graph would give them a second, stringly-typed home. The
 * stock app is the cautionary example — it routed by `Fragment` class name and lost the upload
 * result whenever Android rebuilt the fragment (docs/FINDINGS.md §14 U3).
 */
enum class Route(val id: String) {
    /** Enter/pick the print server and see what it allows. Prototype §3.1. */
    Campus("connect"),

    /** Trust-on-first-use decision for an unknown certificate. Prototype §3.2. */
    Certificate("cert"),

    /** Username and password. Prototype §3.3. */
    SignIn("signin"),

    /** The campus CAS page, when the server insists on it. Prototype §3.4. */
    MasonLogin("sso"),

    /** The held-job queue. Prototype §3.5. */
    Queue("queue"),

    /** Look at a held document before spending money on it. */
    Preview("preview"),

    /** Which printer, reached by list, station code, or camera. Prototype §3.7. */
    Release("release"),

    /**
     * Narrow the 302-station printer list by building and floor. Its own screen rather than controls
     * on the list: there are ~60 buildings, and any inline picker for that many is a wall.
     */
    PrinterFilter("printer-filter"),

    /** Last stop before money moves. Prototype §3.8. */
    Confirm("confirm"),

    /** What actually happened, per job. Prototype §3.9. */
    Result("result"),

    /** Local release receipts and optional direct printer status. */
    ReleasedJobs("released-jobs"),
    PrinterJobs("printer-jobs"),

    /** Balance, purses, transactions, what the server allows. Prototype §3.10. */
    Account("account"),

    /** Browse and search the cost centres this account may charge. Prototype §3.11. */
    CostCenters("costcenter"),

    /** Hand off to wherever the campus actually takes money. Prototype §3.12. */
    AddFunds("addfunds"),

    /** One copyable artifact for the service desk. Prototype §3.13. */
    Diagnostics("diagnostics"),

    /**
     * Plain-English help for the vendor's vocabulary — "release", "held", "at release", cost
     * centres. The words are shared with the service desk so they cannot be renamed; they can be
     * explained.
     */
    Help("help"),

    /** The vendor web Print Center, as an escape hatch. Prototype §3.14. */
    PrintCenter("printcenter");

    companion object {
        fun of(id: String?): Route? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A depth-limited back stack, mirroring the prototype's `stack`.
 *
 * Two operations matter beyond push/pop. `reset` empties the stack on a phase change, which is what
 * stops Android's back button from walking the user back through the sign-in form they have already
 * left. `replaceTop` swaps the top frame — used by Sign in → Certificate, where trusting the
 * certificate should return you to the form, not stack a screen under it.
 *
 * The stack is snapshot state, so a composable that reads [current] recomposes when it changes. That
 * is the whole reason this class exists instead of a `MutableStateFlow`: navigation is a UI concern
 * with exactly one reader, and a flow would put a coroutine between the button and the screen it is
 * supposed to change.
 */
@Stable
class Router(start: Route) {

    private var stack: List<Route> by mutableStateOf(listOf(start))

    val current: Route get() = stack.last()
    val depth: Int get() = stack.size
    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: Route) {
        if (route == stack.last()) return
        stack = stack + route
    }

    /** Push only when the user is not already looking at it from below, e.g. re-opening Diagnostics. */
    fun pushUnique(route: Route) {
        if (route in stack) return
        push(route)
    }

    fun pop(): Route? {
        if (stack.size <= 1) return null
        stack = stack.dropLast(1)
        return stack.last()
    }

    fun replaceTop(route: Route) {
        stack = stack.dropLast(1) + route
    }

    /** Drop the whole stack — a sign-in, a sign-out, or a host change. */
    fun reset(route: Route) {
        stack = listOf(route)
    }

    /** Every frame below the current one, deepest first. Used for a breadcrumb, if one is ever wanted. */
    fun trail(): List<Route> = stack.dropLast(1)

    override fun toString(): String = stack.joinToString(" › ") { it.id }
}
