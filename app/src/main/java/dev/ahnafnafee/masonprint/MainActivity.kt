package dev.ahnafnafee.masonprint

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.MpLog
import dev.ahnafnafee.masonprint.core.Session
import dev.ahnafnafee.masonprint.core.UploadFactory
import dev.ahnafnafee.masonprint.core.deviceTokenFromQr
import dev.ahnafnafee.masonprint.core.isPlausibleDeviceToken
import dev.ahnafnafee.masonprint.ui.AppRoot
import dev.ahnafnafee.masonprint.ui.Route
import dev.ahnafnafee.masonprint.ui.Router
import dev.ahnafnafee.masonprint.ui.DiagnosticsScreen
import dev.ahnafnafee.masonprint.ui.PrintCenterWebView
import dev.ahnafnafee.masonprint.ui.theme.MasonPrintTheme
import dev.ahnafnafee.masonprint.ui.theme.ThemeChoice
import dev.ahnafnafee.masonprint.ui.theme.ThemeMode
import dev.ahnafnafee.masonprint.ui.theme.isDark

/**
 * One Activity, because there is one thing to do.
 *
 * The stock app used three Activities and a fragment stack whose only real job was to move a
 * session cookie from the native login form into a WebView (docs/FINDINGS.md §8.2). Here the
 * session lives in [Session], which the Activity does not own, so rotation and backgrounding cannot
 * lose an in-flight upload — the failure the vendor's UI produced most often.
 */
class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private lateinit var session: Session

    /** Documents handed to us by another app, waiting for a signed-in session. */
    private var sharedUris: List<Uri> = emptyList()

    /**
     * Android's own document picker, in multiple mode.
     *
     * Long-press the first file and tap the rest, then the picker returns the whole selection at
     * once. This is the one place the clone goes past the supplied prototype, which only ever sent
     * one document (§3.6): printing four handouts one picker-open at a time was the complaint the
     * redesign exists to fix, and the wire can carry it — [Session.uploadAll] sends them one
     * `POST …/printjobs` after another, because Pharos has no batch upload.
     *
     * The filter handed to the picker is the wildcard rather than a list of extensions, deliberately:
     * the client's extension list is a hint about what a deployment prints, not authority
     * (docs/CLONE-PLAN.md §5), so the picker offers everything and the server refuses what it will
     * not take.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        submit(uris.orEmpty(), "picked")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AppGraph.of(this)
        session = graph.session
        // Before the first frame, so the app never paints the wrong theme and corrects itself.
        ThemeChoice.restore(graph.prefs)
        // The platform theme's windowBackground is a fixed light colour, so a dark-themed app would
        // flash white for the frame before Compose draws. Paint it to match the resolved theme.
        window.setBackgroundDrawable(
            ColorDrawable(if (darkAtStartup()) START_BACKGROUND_DARK else START_BACKGROUND_LIGHT),
        )

        setContent {
            val dark = ThemeChoice.mode.isDark()
            /*
             * Edge-to-edge has to follow *this app's* choice, not the phone's.
             *
             * `enableEdgeToEdge()` with no arguments decides the system-bar glyph colour from the
             * system configuration. Force Dark in the app while the phone is Light and it draws black
             * glyphs for a light background over a #111318 surface: the clock and the battery icon
             * disappear. Passing `detectDarkMode` makes the bars follow the theme actually on screen,
             * and re-running it on change keeps them in step with the toggle.
             */
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark },
                )
            }

            MasonPrintTheme(darkTheme = dark) {
                val state by session.state.collectAsState()
                val router = remember { Router(Route.Campus) }

                // One back stack for the whole app. The phase resets its root (AppRoot does that);
                // everything else — the certificate prompt, Print Center, Diagnostics, the flow
                // screens — is a push, so the system Back button walks them in the order the user
                // arrived.
                BackHandler(enabled = router.canGoBack) { router.pop() }

                AppRoot(
                    session = session,
                    state = state,
                    graph = graph,
                    router = router,
                    onPickDocument = { picker.launch(arrayOf("*/*")) },
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (sharedUris.isNotEmpty() && session.state.value.signedIn) {
            val pending = sharedUris
            sharedUris = emptyList()
            submit(pending, "shared")
        }
    }

    /**
     * The two entry points the vendor app supported, kept because they are how students actually
     * use it: "Print" from another app's share sheet, and a QR code at the printer.
     *
     * The QR path is the deep link an external camera app lands on. The stock app accepted only the
     * `#code=<payload>` form (`MainActivity.cs`: `Split('#')[1].Substring(5)`); [deviceTokenFromQr]
     * also takes `#/code=` and `?code=`, because the vendor publishes no label format and a sticker
     * generated by a different release of the Print Center SPA uses the SPA's own routing fragment.
     * The cut is deliberately restricted to URLs that actually carry `code=`: a bare
     * `/myprintcenter` link is somebody opening the portal, and the handler below *releases* the
     * oldest held job, which is not what a bookmark deserves.
     *
     * In-app scanning is now the primary path (`ui/PrinterCodeScanner.kt`); this link stays because
     * Google Lens and the camera app already on the phone work today, and because it is the only
     * route that reaches the app from a code scanned by somebody else's device.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = sharedStreams(intent)
                if (uris.isEmpty()) return
                if (session.state.value.signedIn) {
                    submit(uris, "shared")
                } else {
                    // The share arrives before the user has signed in. Hold the handles, not the
                    // bytes: content:// URIs survive sign-in, and the whole point of the stock app's
                    // most common complaint was a pending upload vanishing mid-flow.
                    sharedUris = uris
                    MpLog.info("share", "${uris.size} document(s) received while signed out; queued for after sign-in")
                }
            }

            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString() ?: return
                if (!url.contains("code=", ignoreCase = true)) return
                val code = deviceTokenFromQr(url) ?: return
                if (isPlausibleDeviceToken(code)) session.resolveDeviceToken(code)
            }
        }
    }

    /**
     * What another app actually handed over, as documents this app can open again later.
     *
     * `ACTION_SEND` carries one `Uri` *or* a `ParcelFileDescriptor`, and `ACTION_SEND_MULTIPLE` an
     * array of either; Google Files and Gmail use URIs, some readers use descriptors. Only URIs are
     * kept, because a descriptor is a one-shot handle that [UploadFactory] cannot re-open after a
     * rotation, and a batch must survive one. Anything that is not a `Uri` is named in the log
     * rather than silently dropped, and the read itself is guarded: `getParcelableExtra` with a type
     * throws on a share that carried a descriptor instead, and a foreign app's oddity is not a
     * reason to crash the print queue.
     */
    private fun sharedStreams(intent: Intent): List<Uri> {
        val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
        val single: Uri? = runCatching {
            if (multiple) {
                null
            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
        }.getOrNull()
        val many: List<Uri> = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableArrayExtra(Intent.EXTRA_STREAM)
            }?.filterIsInstance<Uri>()
        }.getOrNull().orEmpty()
        val uris = (listOfNotNull(single) + many).distinct()
        if (uris.isEmpty() && intent.hasExtra(Intent.EXTRA_STREAM)) {
            MpLog.warn("share", "the shared extra carried no readable document (a raw stream, not a content URI)")
        }
        return uris
    }

    /**
     * Turn handles into documents and hand the batch to the session.
     *
     * A handle that will not open is named in the log and left out; the rest still go, so one dead
     * provider in a six-file selection does not cost the user the other five.
     */
    private fun submit(uris: List<Uri>, what: String) {
        if (uris.isEmpty()) return
        uris.forEach(::persist)
        val sources = uris.mapNotNull { uri -> UploadFactory.fromUri(this, uri) }
        sources.forEach { MpLog.info("upload", "$what: ${it.fileName} ${it.sizeBytes}B as ${it.mimeType}") }
        if (sources.isEmpty()) {
            MpLog.warn("upload", "could not open ${uris.size} shared URI(s)")
            return
        }
        if (sources.size < uris.size) {
            MpLog.warn("upload", "${uris.size - sources.size} of ${uris.size} could not be opened and were skipped")
        }
        session.uploadAll(sources)
    }

    private fun persist(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Which theme is in force before Compose runs.
     *
     * [ThemeMode.System] is the only one that has to ask the platform, and at this point that means
     * reading the configuration directly: `isSystemInDarkTheme()` is a composable and there is no
     * composition yet.
     */
    private fun darkAtStartup(): Boolean = when (ThemeChoice.mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        /** `MasonDarkScheme.background` and `MasonLightScheme.background`, for the first frame only. */
        const val START_BACKGROUND_DARK = 0xFF111318.toInt()
        const val START_BACKGROUND_LIGHT = 0xFFFAF9FD.toInt()
    }
}
