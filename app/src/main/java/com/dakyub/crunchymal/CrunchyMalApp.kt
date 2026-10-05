package com.dakyub.crunchymal

import android.app.Application
import android.content.Context

class CrunchyMalApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        installCrashRecorder()
    }

    /** Mémorise le dernier plantage pour l'afficher dans les paramètres au prochain lancement. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_crash", error.stackTraceToString().take(2000))
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_PREFS = "crash"

        fun lastCrash(context: Context): String? =
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).getString("last_crash", null)

        fun clearCrash(context: Context) {
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
