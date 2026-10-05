package com.dakyub.crunchymal

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.data.WatchlistRepository
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.CrAuth
import com.dakyub.crunchymal.data.mal.MalRepository
import com.dakyub.crunchymal.data.progress.ProgressRepository
import com.dakyub.crunchymal.update.UpdateManager

/** Conteneur de dépendances (pas besoin de Hilt pour une app de cette taille). */
class Graph(context: Context) {
    val settings = Settings(context)
    val auth = CrAuth(context, settings)
    val api = CrApi(auth, settings)
    val mal = MalRepository(context) { settings.malClientId }
    val progress = ProgressRepository(context, api)
    val watchlist = WatchlistRepository(api)
    val updates = UpdateManager(context)
}

val LocalGraph = staticCompositionLocalOf<Graph> { error("Graph non fourni") }
