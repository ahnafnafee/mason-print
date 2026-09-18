@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ahnafnafee.masonprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import dev.ahnafnafee.masonprint.ui.theme.ThemeChoice
import dev.ahnafnafee.masonprint.ui.theme.isDark
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.ahnafnafee.masonprint.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ahnafnafee.masonprint.BuildConfig
import dev.ahnafnafee.masonprint.core.AppState
import dev.ahnafnafee.masonprint.data.model.Sso
import dev.ahnafnafee.masonprint.ui.theme.MasonPillShape
import dev.ahnafnafee.masonprint.data.net.PharosFailure

/**
 * The three screens that exist before a queue does: what server, do I trust it, and who are you.
 *
 * All three are *destinations*, not dialogs. That is deliberate and it is the design spec's rule
 * (§2.0.6, "Not an `AlertDialog`: the fingerprint must be selectable, copyable and comparable
 * against a published value") — the vendor app's silent `OnReceivedSslError → Proceed()` is the
 * single security decision this clone exists to undo, and a dialog whose only affordance is "OK"
 * does not exactly invite comparison.
 */

/** Boot and mid-flight wait. Expressive makes a wait something to look at, not something to stare through. */
@Composable
internal fun Waiting(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 34.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 4.dp)
                Spacer(Modifier.height(16.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ------------------------------------------------------------------ 1. connect

@Composable
internal fun ConnectScreen(
    state: AppState,
    onConnect: (String) -> Unit,
    onOpenPrintCenter: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(state.host.ifBlank { BuildConfig.GMU_HOST }) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        // Wordmark in a filled, generously rounded container: the first cue that this is a designed
        // surface and not the vendor's Honeycomb-era ActionBar hosting a 2013 web skin.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Text("Mason Print", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            "Campus print queue, without the browser inside the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Print server") },
            supportingText = {
                Text("You can paste a full https:// URL or a /myprintcenter link. Both are reduced to the API address.")
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { onConnect(host) }),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onConnect(host) },
            shape = MasonPillShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(if (state.busy != null) "Contacting…" else "Connect")
        }

        // The stock app hard-coded its server with no way to change it, which is why a student whose
        // campus used a different host could not use the app at all.
        Text(
            "Suggestions: " + BuildConfig.SUGGESTED_HOSTS.split(',').joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FailureSummary(state.failure)

        OutlinedButton(onClick = onOpenPrintCenter, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in through the Print Center instead")
        }
        Text(
            "Needed only if your campus requires single sign-on (CAS/MFA) that this app cannot complete natively.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 2. certificate

/**
 * The certificate prompt, as a destination in the back stack.
 *
 * Everything on this screen exists so the SHA-256 can be *read and compared*: the fingerprint is a
 * selectable mono block, and the copy says plainly that a captive portal can forge one — the thing
 * the vendor's unconditional `Proceed()` never mentioned to anyone.
 */
@Composable
internal fun CertPrompt(
    failure: PharosFailure,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tls = failure as? PharosFailure.TlsNotTrusted
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unrecognised certificate") },
                navigationIcon = {
                    // Back = "not now": the same decision, one fewer target to find.
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Not now")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(MasonScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                failure.headline(),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "A captive portal, or a network sitting in the middle, can present a certificate that " +
                    "looks like this one. Compare the fingerprint below with the one your campus publishes " +
                    "before trusting it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (tls != null) {
                tls.subject?.let {
                    SectionLabel("Issued to")
                    MonoBlock(it)
                }
                SectionLabel("SHA-256 fingerprint")
                MonoBlock(tls.fingerprint)
                SectionLabel("Server")
                MonoBlock(tls.host)
            } else {
                // Defensive: the route is only pushed for a TLS failure, but the state machine is
                // asynchronous and a later failure must not crash the screen that opened for it.
                FailureSummary(failure)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "Mason Print asks once per fingerprint, remembers your answer in app storage, and " +
                    "lets you revoke it later from Diagnostics. The vendor's app accepted any " +
                    "certificate for this host without asking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onTrust,
                shape = MasonPillShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Trust this certificate") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Not now")
            }
        }
    }
}

// ------------------------------------------------------------------ 3. sign in

@Composable
internal fun SignInScreen(
    state: AppState,
    onSignIn: (username: String, password: String, remember: Boolean) -> Unit,
    onOpenPrintCenter: () -> Unit,
    onHelp: () -> Unit,
    canOpenPrintCenter: Boolean,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    // Not rememberSaveable: a revealed password must not survive the app going to the background.
    var showPass by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /*
             * The university's mark, on the page itself rather than on a white plate.
             *
             * The wordmark is #333333, which disappears against the dark theme's #111318, so dark
             * mode gets the reversed artwork instead: identical geometry with the wordmark in white,
             * and the green and gold left exactly as the university drew them.
             *
             * Chosen from the app's own theme rather than a `-night` resource qualifier. The
             * qualifier follows the phone, and this app's light/dark setting is allowed to disagree
             * with it, which is the same mismatch that left the status bar drawing black on black.
             */
            Image(
                painter = painterResource(
                    if (ThemeChoice.mode.isDark()) {
                        R.drawable.mason_logo_vector_dark
                    } else {
                        R.drawable.mason_logo_vector
                    },
                ),
                contentDescription = "George Mason University",
                modifier = Modifier.width(212.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Mason Print", style = MaterialTheme.typography.titleLarge)
            Text(
                state.host + if (state.apiVersion != null) "  ·  API ${state.apiVersion}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        /*
         * Two sentences of orientation. This is the first screen a student ever sees, and until now
         * it asked for a university password without once saying what the app was for. Kept short on
         * purpose: the full explanation is one tap away rather than printed here.
         */
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("What this app is for", style = MaterialTheme.typography.titleMedium)
                Text(
                    "It holds documents you upload, then prints them when you release one at a campus " +
                        "printer. You pay at the printer, not when you upload.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onHelp, contentPadding = PaddingValues(0.dp)) {
                    Text("How this works")
                }
            }
        }

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Mason username") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        /*
         * `KeyboardType.Password` only tells the keyboard what to do. Without a visualTransformation
         * the field drew the characters, so a university password sat on screen in the clear for
         * anyone standing behind the student. It is masked by default now, and the eye reveals it
         * deliberately — which is the point of the control.
         */
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPass) "Hide the password" else "Show the password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
            keyboardActions = KeyboardActions(
                onDone = { if (user.isNotBlank() && pass.isNotEmpty()) onSignIn(user, pass, rememberMe) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text("Keep me signed in", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            // Was: "sends KeepMeLoggedIn=yes, exactly like the vendor app". A student is not choosing
            // a query parameter, and has never seen the vendor app. What they are choosing is whether
            // to type the password again next week.
            "Your password is kept in the phone's encrypted storage, so you are not asked for it every " +
                "time the server signs you out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onSignIn(user.trim(), pass, rememberMe) },
            enabled = user.isNotBlank() && pass.isNotEmpty() && state.busy == null,
            shape = MasonPillShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(if (state.busy != null) "Signing in…" else "Sign in") }

        FailureSummary(state.failure)

        /*
         * The web portal, offered up front rather than only after a failure.
         *
         * It used to appear solely when a sign-in had already been rejected *and* the server reported
         * CAS, so the one route that works when the app's own login will not was invisible to anyone
         * who had not failed yet. It is the university's own page and it can do things this app
         * cannot, so it belongs on screen whenever there is an address to open.
         */
        if (canOpenPrintCenter) {
            val single = state.capabilities?.sso is Sso.Cas
            OrDivider()
            FilledTonalButton(
                onClick = onOpenPrintCenter,
                shape = MasonPillShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                ButtonGlyph(Icons.Filled.Language)
                Text(if (single) "Sign in with Mason single sign-on" else "Use the web Print Center")
            }
            Text(
                if (single) {
                    "Opens the university's own sign-in page inside this app. Use it if the form above " +
                        "will not take your password."
                } else {
                    "Opens the university's own print page inside this app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ shared bits

/**
 * A rule with a word set into it, for a genuine either/or.
 *
 * Used between signing in here and signing in on the university's own page: they are two ways to do
 * the same thing, not a primary action and an afterthought, and a plain stack of two buttons does
 * not say that.
 */
@Composable
private fun OrDivider(label: String = "or") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Rule(Modifier.weight(1f))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Rule(Modifier.weight(1f))
    }
}

/** One hairline, in the colour the design reserves for exactly this. */
@Composable
private fun Rule(modifier: Modifier) {
    Box(
        modifier
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * One place that turns a [PharosFailure] into sentences.
 *
 * Every branch quotes what the server said rather than a canned string. The stock app resolved
 * failures by *exception class name* to a fixed Android string resource, so a 413 that arrived with
 * "the document exceeds the 50 MB limit" in the body was shown as "Upload failed"
 * (docs/FINDINGS.md §11) — the single change with the biggest effect on support load.
 */
@Composable
fun FailureSummary(failure: PharosFailure?) {
    if (failure == null) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(failure.headline(), style = MaterialTheme.typography.titleSmall)
            failure.detailLine()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (failure.retryable) {
                Text(
                    "This one is usually temporary. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
