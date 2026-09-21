package dev.ahnafnafee.masonprint.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.Phase
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.SnackAction
import dev.ahnafnafee.masonprint.data.net.PharosFailure
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * The root of the app: one back stack, one snackbar host, and a switch over [Route].
 *
 * **Why a hand-written router instead of Navigation Compose.** The design spec lays the app out as
 * fourteen *screens*, several of which are steps of one flow (release → confirm → result) that must
 * share the state already sitting in [AppState], and one of which — the certificate prompt — has to
 * be a destination rather than a dialog so its fingerprint can be selected and copied. That is still
 * not a navigation *graph*: there are no arguments to serialise, no deep links to declare (the one
 * real deep link, a printer-station code from a QR payload, is resolved by [Session] into a device
 * before any screen is drawn), and no scene restoration to win, because every route here renders
 * from a `StateFlow` that already survives process death. A `NavHost` would add the navigation
 * library, a `Route`/`Directions` vocabulary, and a second copy of "where am I" — which is exactly
 * the two-sources-of-truth mistake that left the stock app showing the login page inside a
 * signed-out WebView header (docs/FINDINGS.md §8.2). [Router] is 111 lines and one `when`.
 *
 * The one thing this file does that a `NavHost` would do for free is keep the snackbar host above
 * every route. Notices go there rather than into an `AlertDialog` because a dialog makes the user
 * acknowledge a fact before they can act on it: "Signed in as A. Annafee" used to block the queue
 * until OK was pressed, and a release result used to block the balance that had just changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    session: Session,
    state: AppState,
    graph: AppGraph,
    router: Router,
    onPickDocument: () -> Unit,
    preparingDocuments: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current

    LaunchedEffect(state.signedIn, state.host, state.user?.accountKey, lifecycle) {
        if (state.signedIn) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.watchQueueChanges()
        }
    }
    LaunchedEffect(state.host, state.user?.accountKey) { PrinterJobsHandoff.clear() }

    // Building keys belong to a server; a previous campus must not hide another server's printers.
    LaunchedEffect(state.host) { ReleaseHandoff.clearFilter() }

    // ---- notices: a message with an action is a snackbar, never a modal ----------------------
    // `state.notice` is written by Session at the moments that used to deserve a dialog; the action
    // turns the acknowledgement into a navigation or a retry, which a dialog could not offer.
    LaunchedEffect(state.notice) {
        val text = state.notice ?: return@LaunchedEffect
        val action = state.noticeAction
        val result = snackbar.showSnackbar(
                message = text,
                actionLabel = when (action) {
                    SnackAction.ViewQueue -> "View queue"
                    SnackAction.RetryRelease -> "Try again"
                    null -> null
                },
                duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        session.dismissNotice(text)
        when {
            result == SnackbarResult.ActionPerformed && action == SnackAction.ViewQueue ->
                router.reset(Route.Queue)

            result == SnackbarResult.ActionPerformed && action == SnackAction.RetryRelease ->
                router.push(Route.Confirm)
        }
    }

    // ---- the phase decides the root of the stack; the user decides what sits above it --------
    // `reset` on a phase change, not `push`: pressing Back out of the queue must not land on a
    // sign-in screen that no longer describes reality, and Back from the certificate prompt must not
    // return to a connection attempt that already failed.
    LaunchedEffect(state.phase) {
        val root = when (state.phase) {
            Phase.Connect -> Route.Campus
            Phase.SignedOut -> Route.SignIn
            Phase.Ready -> Route.Queue
            Phase.Boot, Phase.Working -> return@LaunchedEffect
        }
        keyboard?.hide()
        router.reset(root)
    }

    // ---- the certificate prompt is pushed and popped by the failure it describes -------------
    LaunchedEffect(state.failure) {
        if (state.failure is PharosFailure.TlsNotTrusted) {
            if (router.current != Route.Certificate) router.push(Route.Certificate)
        } else if (router.current == Route.Certificate) {
            router.pop()
        }
    }

    LaunchedEffect(state.codeResolved) {
        if (state.codeResolved) {
            ReleaseHandoff.openedFromCode = true
            router.push(Route.Confirm)
            session.consumeResolvedCode()
        }
    }

    if (state.phase == Phase.Boot || state.busy == "Signing out") {
        Waiting(state.busy ?: "Opening Mason Print")
        return
    }

    /*
     * The queue hosts the snackbar in its own Scaffold, where Material anchors it above the bottom
     * bar; everywhere else this Scaffold hosts it. Exactly one host is composed at a time, so a
     * notice never shows twice, and never again lands on top of the Release pill.
     */
    Box(Modifier.fillMaxSize()) {
        when (val route = router.current) {
            Route.Campus -> ConnectScreen(
                state = state,
                onConnect = { host ->
                    keyboard?.hide()
                    session.connect(host)
                },
                onOpenPrintCenter = { router.push(Route.MasonLogin) },
                canOpenPrintCenter = session.printCenterUrl() != null,
            )

            Route.Certificate -> CertPrompt(
                failure = state.failure ?: PharosFailure.Unknown(IllegalStateException("no failure")),
                onTrust = {
                    session.trustPendingCertificate()
                    router.pop()
                },
                onDismiss = {
                    session.dismissFailure()
                    router.pop()
                },
            )

            Route.SignIn -> SignInScreen(
                state = state,
                onSignIn = { user, pass, remember ->
                    keyboard?.hide()
                    session.signIn(user, pass, remember)
                },
                onOpenPrintCenter = { router.push(Route.MasonLogin) },
                onHelp = { router.push(Route.Help) },
                // Only offered when there is somewhere to go: the route renders NotReady otherwise,
                // and a button that leads to "not available" is worse than no button.
                canOpenPrintCenter = session.printCenterUrl() != null,
            )

            Route.MasonLogin, Route.PrintCenter -> {
                val url = session.printCenterUrl()
                if (url == null) {
                    NotReady(
                        "This server did not report a Print Center address.",
                        onBack = { router.pop() },
                    )
                } else {
                    PrintCenterWebView(
                        url = url,
                        cookies = session.printCenterCookies(),
                        onBack = { router.pop() },
                    )
                }
            }

            Route.Queue -> JobsScreen(
                state = state,
                session = session,
                router = router,
                snackbar = snackbar,
                onPickDocument = onPickDocument,
                preparingDocuments = preparingDocuments,
            )

            Route.Diagnostics -> DiagnosticsScreen(
                state = state,
                graph = graph,
                session = session,
                onBack = { router.pop() },
            )

            Route.Release -> ReleaseScreen(state, session, router)
            Route.PrinterFilter -> PrinterFilterScreen(state, onBack = { router.pop() })
            Route.Help -> HelpScreen(onBack = { router.pop() })
            Route.Preview -> PreviewScreen(state, session, onBack = { router.pop() })
            Route.Confirm -> ConfirmRelease(state, session, router)
            Route.Result -> ReleaseResult(state, session, router)
            Route.ReleasedJobs -> ReleasedJobsScreen(state, session, router)
            Route.PrinterJobs -> PrinterJobsScreen(state, graph, onBack = { router.pop() })

            Route.Account -> AccountScreen(state, session, graph, router)
            Route.CostCenters -> CostCentersScreen(state, session, router)
            Route.AddFunds -> AddFundsScreen(state, session, router)
        }
        if (router.current != Route.Queue) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding())
        }
    }
}

/** Honest placeholder for a route whose screen has not been ported yet — not a blank frame. */
@Composable
internal fun NotReady(what: String, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Print Center unavailable", style = MaterialTheme.typography.titleLarge)
            Text(
                what,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
