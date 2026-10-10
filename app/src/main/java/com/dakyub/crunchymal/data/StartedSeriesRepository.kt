package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.adn.AdnWatchlistRepository
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.best
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Toutes les séries commencées (onglet « En cours ») : historique Crunchyroll complet et historique +
 * watchlist ADN, du plus récemment regardé au plus ancien, au format des entrées de watchlist.
 */
class StartedSeriesRepository(
    private val api: CrApi,
    private val history: HistoryRepository,
    private val adn: AdnApi,
    private val adnWatchlist: AdnWatchlistRepository,
) {
    private val mutex = Mutex()
    private var cache: Pair<Set<Provider>, List<WatchlistEntry>>? = null

    suspend fun get(providers: Set<Provider>, crunchyrollUsable: Boolean, force: Boolean = false): List<WatchlistEntry> = mutex.withLock {
        cache?.takeIf { !force && it.first == providers }?.second?.let { return it }
        val list = coroutineScope {
            val cr = async { if (Provider.CRUNCHYROLL in providers && crunchyrollUsable) crunchyroll(force) else emptyList() }
            val adnShows = async { if (Provider.ADN in providers && adn.loggedIn.value) adnSeries(force) else emptyList() }
            (cr.await() + adnShows.await())
                .sortedByDescending { it.second }
                .mapIndexed { index, (series, _) -> WatchlistEntry(series, neverWatched = false, nextEpisodeId = null, order = index) }
        }
        cache = providers to list
        list
    }

    /** Séries Crunchyroll de l'historique (date du dernier visionnage), avec titre et affiches. */
    private suspend fun crunchyroll(force: Boolean): List<Pair<SeriesRef, String>> {
        val played = history.lastPlayed(force)
        val objects = runCatching { api.objects(played.keys.toList()) }.getOrDefault(emptyList()).associateBy { it.id }
        return played.mapNotNull { (id, date) ->
            val obj = objects[id] ?: return@mapNotNull null
            SeriesRef(
                id = id,
                title = obj.title,
                slug = obj.slugTitle,
                posterUrl = obj.images.posterTall.best(240),
                wideUrl = obj.images.posterWide.best(400),
            ) to date
        }
    }

    /** Séries ADN de l'historique, plus celles de la watchlist déjà commencées. */
    private suspend fun adnSeries(force: Boolean): List<Pair<SeriesRef, String>> {
        val fromHistory = runCatching { adn.viewingHistory() }.getOrDefault(emptyList())
            .mapNotNull { video -> video.show?.let { it.toRef() to video.user?.watchDate.orEmpty() } }
        val fromWatchlist = runCatching { adnWatchlist.get(force) }.getOrDefault(emptyList())
            .filter { !it.neverWatched }
            .map { it.series to "" }
        return (fromHistory + fromWatchlist)
            .groupBy { it.first.id }
            .map { (_, entries) -> entries.maxBy { it.second } }
    }
}
