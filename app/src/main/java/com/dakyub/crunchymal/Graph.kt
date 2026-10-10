package com.dakyub.crunchymal

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.dakyub.crunchymal.data.AvailabilityRepository
import com.dakyub.crunchymal.data.DiagnosticsUploader
import com.dakyub.crunchymal.data.HistoryRepository
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.ProviderSelection
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.StartedSeriesRepository
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.data.WatchPlatformsRepository
import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.adn.AdnWatchlistRepository
import com.dakyub.crunchymal.data.ratings.RatingsRepository
import com.dakyub.crunchymal.data.WatchlistRepository
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.CrAuth
import com.dakyub.crunchymal.data.mal.MalAuth
import com.dakyub.crunchymal.data.mal.MalRepository
import com.dakyub.crunchymal.data.progress.AdnProgressRepository
import com.dakyub.crunchymal.data.progress.ProgressRepository
import com.dakyub.crunchymal.update.UpdateManager

/** Conteneur de dépendances (pas besoin de Hilt pour une app de cette taille). */
class Graph(context: Context) {
    val settings = Settings(context)
    val auth = CrAuth(context, settings)
    val api = CrApi(auth, settings)
    val malAuth = MalAuth(context, { settings.malClientId }, { settings.malClientSecret })
    val mal = MalRepository(context, { settings.malClientId }, malAuth)
    val progress = ProgressRepository(context, api)
    val watchlist = WatchlistRepository(api)
    val updates = UpdateManager(context)
    val providers = ProviderSelection(settings)
    val adn = AdnApi(context)
    val adnProgress = AdnProgressRepository(adn, progress).also { repo -> progress.onClear = repo::clearCache }
    val adnWatchlist = AdnWatchlistRepository(adn)
    val ratings = RatingsRepository(settings)
    val availability = AvailabilityRepository(context, api, adn)
    val watchPlatforms = WatchPlatformsRepository(context, settings)
    val diagnostics = DiagnosticsUploader(context, settings)
    val history = HistoryRepository(context, api)
    val started = StartedSeriesRepository(api, history, adn, adnWatchlist)

    /** Calcule (si besoin) la progression d'une série, quel que soit son service. */
    suspend fun ensureProgress(ref: SeriesRef, force: Boolean = false) = when (ref.provider) {
        Provider.CRUNCHYROLL -> progress.ensureSummary(ref.id, force)
        Provider.ADN -> adnProgress.ensureSummary(ref.id, force)
    }

    /** Progression calculable pour cette série (ADN : il faut être connecté). */
    fun progressAvailable(ref: SeriesRef): Boolean =
        ref.id.isNotBlank() && (ref.provider == Provider.CRUNCHYROLL || adn.loggedIn.value)
}

val LocalGraph = staticCompositionLocalOf<Graph> { error("Graph non fourni") }
