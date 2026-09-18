package dev.ahnafnafee.masonprint

import android.app.Application
import dev.ahnafnafee.masonprint.core.AppGraph
import dev.ahnafnafee.masonprint.core.MpLog

/**
 * Process entry point. Its only job is to own [AppGraph], so the dependency graph and the session
 * outlive every Activity.
 */
class MasonPrintApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Deliberately not StrictMode or a crash handler: the useful failure information for this
        // app is which request happened in which order, and MpLog already carries that.
        MpLog.info("app", "Mason Print ${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")
    }
}
