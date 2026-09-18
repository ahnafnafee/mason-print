package dev.ahnafnafee.masonprint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ahnafnafee.masonprint.data.prefs.AppPrefs

/** Follow the phone, or override it. */
enum class ThemeMode(val id: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    companion object {
        fun of(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}

/** Whether this choice means dark right now. Only [ThemeMode.System] has to ask the phone. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * The app's light/dark choice.
 *
 * Held here rather than in `AppState` because it is not session state: it belongs to the phone, not
 * to the account, and it has to survive sign-out and outlive any server. It is deliberately *not*
 * per account either. Switching accounts on a shared phone must not restyle the app, and someone who
 * needs dark for their eyes needs it whoever is signed in.
 *
 * `mutableStateOf` so a change repaints the whole tree at once: `MainActivity` reads it to build the
 * theme, so setting it is all that a settings control has to do.
 */
@Stable
object ThemeChoice {

    var mode by mutableStateOf(ThemeMode.System)
        private set

    /** Load the saved choice. Called once, before the first frame. */
    fun restore(prefs: AppPrefs) {
        mode = ThemeMode.of(prefs.themeMode)
    }

    fun set(prefs: AppPrefs, next: ThemeMode) {
        mode = next
        prefs.themeMode = next.id
    }
}
