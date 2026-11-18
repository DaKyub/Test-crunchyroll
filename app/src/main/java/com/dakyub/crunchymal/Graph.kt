package com.dakyub.crunchymal

import com.dakyub.crunchymal.data.DateUtils
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import com.dakyub.crunchymal.data.mal.SequelsRepository
import com.dakyub.crunchymal.data.mal.ForYouRepository
import com.dakyub.crunchymal.data.mal.MalCompletion
import com.dakyub.crunchymal.data.SettingsFields
import com.dakyub.crunchymal.data.plex.PlexLibrary
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.dakyub.crunchymal.data.ArrivalWatcher
import com.dakyub.crunchymal.data.AvailabilityRepository
import com.dakyub.crunchymal.data.CompanionsRepository
import com.dakyub.crunchymal.data.SettingsBackup
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
import com.dakyub.crunchymal.data.mal.MalSync
import com.dakyub.crunchymal.data.plex.PlexApi
import com.dakyub.crunchymal.server.TagServer
import com.dakyub.crunchymal.tv.HomeChannel
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
    val adnProgress = AdnProgressRepository(adn, progress, java.io.File(context.filesDir, "adn_trees")).also { repo -> progress.onClear = repo::clearCache }
    val adnWatchlist = AdnWatchlistRepository(adn)
    val ratings = RatingsRepository(settings)
    val availability = AvailabilityRepository(context, api, adn)
    val watchPlatforms = WatchPlatformsRepository(context, settings)
    val diagnostics = DiagnosticsUploader(context, settings)
    val history = HistoryRepository(context, api)
    val started = StartedSeriesRepository(api, history, adn, adnWatchlist)
    val companions = CompanionsRepository(settings, mal)
    val malSync = MalSync(mal, settings, companions)
    val malCompletion = MalCompletion(settings, mal)
    val homeChannel = HomeChannel(context, settings) { key -> PlexApp.intent(plex.machineId, key, settings.plexLinkFormat) }
    val arrivals = ArrivalWatcher(context, api, adn, mal)
    val sequels = SequelsRepository(context, mal, availability)
    val forYou = ForYouRepository(context, mal)
    val plex = PlexApi(context)
    val plexLibrary = PlexLibrary(context, plex, mal, watchPlatforms, malSync)
    val settingsFields = SettingsFields(settings, companions, adn, mal, ratings, watchPlatforms, diagnostics)
    val tagServer = TagServer(context, mal, companions, SettingsBackup(context, mal), diagnostics, settingsFields)

    /** Dernier visionnage de chaque série d'après les historiques (ms), relu au plus toutes les 10 min ; null = inconnu. */
    private class Plays(val at: Long, val byKey: Map<String, Long>?)
    @Volatile private var plays: Plays? = null
    private val playsLock = Mutex()

    private suspend fun lastPlays(): Map<String, Long>? = playsLock.withLock {
        // Un échec est gardé 1 min (pas une nouvelle tentative par série), un succès 10 min.
        plays?.takeIf { System.currentTimeMillis() - it.at < (if (it.byKey == null) 60_000 else 10 * 60_000) }
            ?.let { return@withLock it.byKey }
        val merged = runCatching {
            val cr = if (auth.loggedIn.value) {
                history.lastPlayed().mapNotNull { (id, date) -> DateUtils.parse(date)?.time?.let { id to it } }
            } else emptyList()
            val adnPlays = if (adn.loggedIn.value) {
                adn.viewingHistory().mapNotNull { video ->
                    val show = video.show?.id ?: return@mapNotNull null
                    DateUtils.parse(video.user?.watchDate)?.time?.let { "adn:$show" to it }
                }
            } else emptyList()
            // Plusieurs épisodes d'une série : le plus récent.
            (cr + adnPlays).groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        }.getOrNull()
        plays = Plays(System.currentTimeMillis(), merged)
        merged
    }

    /**
     * Calcule (si besoin) la progression d'une série, quel que soit son service. Une série terminée de la
     * watchlist n'est pas recalculée tant que son empreinte [signal] (épisode proposé, nombre d'épisodes et de
     * saisons : voir WatchlistEntry.signal) n'a pas changé et que l'historique ne montre aucun visionnage.
     */
    suspend fun ensureProgress(ref: SeriesRef, force: Boolean = false, signal: String? = null) {
        val key = ref.progressKey
        // Historique inconnu (hors ligne, erreur) : on recalcule comme avant. Marge de 5 min sur les horloges.
        val playedSince: suspend (Long) -> Boolean = { since -> lastPlays()?.let { (it[key] ?: 0L) > since - 5 * 60_000 } ?: true }
        when (ref.provider) {
            Provider.CRUNCHYROLL -> progress.ensureSummary(ref.id, force, signal, playedSince)
            Provider.ADN -> adnProgress.ensureSummary(ref.id, force, signal, playedSince)
        }
    }

    /** Progression calculable pour cette série (ADN : il faut être connecté). */
    fun progressAvailable(ref: SeriesRef): Boolean =
        ref.id.isNotBlank() && (ref.provider == Provider.CRUNCHYROLL || adn.loggedIn.value)
}

val LocalGraph = staticCompositionLocalOf<Graph> { error("Graph non fourni") }
