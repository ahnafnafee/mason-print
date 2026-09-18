package dev.ahnafnafee.masonprint.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.core.MpLog

/**
 * The escape hatch: the server-hosted Print Center SPA, in a WebView, with the app's own cookies
 * handed over — which is precisely what the stock app *was*, kept here as a fallback rather than
 * as the product.
 *
 * Three deliberate differences from the vendor's WebView:
 *
 * 1. **The certificate decision is made once, for the API, by [dev.ahnafnafee.masonprint.data.net.TofuTrustManager].**
 *    The stock app had two trust stores — Mono's `btls` for `/PharosAPI` and Android's system store
 *    for the WebView — and approved everything in both (docs/FINDINGS.md §10 S1/S2). Here the SPA is
 *    loaded only after the same host has already been pinned through the OkHttp path, so the WebView
 *    inherits a decision instead of making its own.
 * 2. **Cookies are copied from the app's jar**, so signing in natively signs in the SPA. The vendor
 *    did this too (`GetAuthWebUrl`) — the reason it works is that Pharos authenticates by cookie,
 *    not by anything the WebView has to negotiate.
 * 3. **`NoHeaderMode=1` and the cache-buster are preserved** (see [dev.ahnafnafee.masonprint.core.Session.printCenterUrl]),
 *    so the page renders inside this app the way it renders inside the vendor's.
 *
 * This is also the answer to the one thing a native client cannot promise: if GMU ever turns on
 * MFA at `login.gmu.edu` (risk R1), CAS completes here and the native screens stay usable for
 * everything that does not need the IdP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PrintCenterWebView(
    url: String,
    cookies: List<String>,
    onBack: () -> Unit,
) {
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = true) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print Center (server UI)", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                            // Hand the native session over before the first request goes out.
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cookies.forEach { cookie -> cm.setCookie(url, cookie) }
                            cm.flush()

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, finishedUrl: String) {
                                    MpLog.info("web", "Print Center loaded $finishedUrl")
                                }

                                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                    if (request.isForMainFrame) {
                                        MpLog.error("web", "Print Center failed: ${error.description}")
                                    }
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webView = this
                            loadUrl(url)
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.destroy()
                    },
                )
            }
        }
    }
}
