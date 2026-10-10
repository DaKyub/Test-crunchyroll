package com.dakyub.crunchymal.data.adn

import com.dakyub.crunchymal.data.WatchlistEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Watchlist ADN de l'utilisateur (vide si non connecté), au même format que celle de Crunchyroll. */
class AdnWatchlistRepository(private val adn: AdnApi) {
    private val mutex = Mutex()
    private var cache: List<WatchlistEntry>? = null

    suspend fun get(force: Boolean = false): List<WatchlistEntry> = mutex.withLock {
        if (!adn.loggedIn.value) return@withLock emptyList()
        cache?.takeIf { !force } ?: load().also { cache = it }
    }

    fun invalidate() {
        cache = null
    }

    private suspend fun load(): List<WatchlistEntry> = adn.watchlist().mapIndexedNotNull { index, video ->
        val show = video.show ?: return@mapIndexedNotNull null
        WatchlistEntry(
            series = show.toRef(),
            // La vidéo proposée est celle où l'on en est : sans progression et en tête de série = jamais commencée.
            neverWatched = video.user == null && (video.order ?: 0) <= 1,
            nextEpisodeId = video.id.toString(),
            order = index,
        )
    }.distinctBy { it.series.id }
}
