package dev.ahnafnafee.masonprint

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    private var pendingCode by mutableStateOf<String?>(null)
    private var preparingShare by mutableStateOf(false)

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
        queueDocuments(uris.orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AppGraph.of(this)
        session = graph.session
        sharedUris = savedInstanceState?.getStringArrayList("pendingDocuments")?.map(Uri::parse).orEmpty()
        pendingCode = savedInstanceState?.getString("pendingPrinterCode")
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
                LaunchedEffect(state.phase, state.busy, state.upload, sharedUris, pendingCode, preparingShare) {
                    if (state.signedIn && state.busy == null && state.upload == null && !preparingShare) {
                        if (sharedUris.isNotEmpty()) {
                            router.reset(Route.Queue)
                            submit(sharedUris)
                        } else {
                            pendingCode?.let { token ->
                                pendingCode = null
                                session.resolveDeviceToken(token)
                            }
                        }
                    }
                }

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
                    preparingDocuments = preparingShare,
                    onPickDocument = {
                        picker.launch(arrayOf("*/*"))
                    },
                )
            }
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pendingDocuments", ArrayList(sharedUris.map(Uri::toString)))
        outState.putString("pendingPrinterCode", pendingCode)
        super.onSaveInstanceState(outState)
    }

    /** Keep incoming work until sign-in completes, including across activity recreation. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = sharedStreams(intent)
                if (uris.isEmpty()) return
                queueDocuments(uris)
            }

            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString() ?: return
                if (!url.contains("code=", ignoreCase = true)) return
                val code = deviceTokenFromQr(url) ?: return
                if (isPlausibleDeviceToken(code)) pendingCode = code
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
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
            }?.filterIsInstance<Uri>()
        }.getOrNull().orEmpty()
        val clips = intent.clipData?.let { data ->
            (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }
        }.orEmpty()
        val uris = (listOfNotNull(single) + many + clips).distinct()
        if (uris.isEmpty() && intent.hasExtra(Intent.EXTRA_STREAM)) {
            MpLog.warn("share", "the shared extra carried no readable document (a raw stream, not a content URI)")
        }
        return uris
    }

    private fun queueDocuments(uris: List<Uri>) {
        uris.forEach(::persist)
        sharedUris = (sharedUris + uris).distinct()
    }

    /** Provider I/O can block; keep the form responsive while files are opened. */
    private fun submit(uris: List<Uri>) {
        if (uris.isEmpty() || preparingShare) return
        preparingShare = true
        lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.mapNotNull { UploadFactory.fromUri(this@MainActivity, it) }
                }
                // A sign-out or another upload may have started while the provider was opening.
                val current = session.state.value
                if (!current.signedIn || current.busy != null || current.upload != null) return@launch
                if (sources.isNotEmpty()) session.uploadAll(sources)
                sharedUris = sharedUris - uris.toSet()
                if (sources.size != uris.size) {
                    session.notify("${uris.size - sources.size} document(s) could not be opened. Share them again from the original app.")
                }
            } finally {
                preparingShare = false
            }
        }
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
