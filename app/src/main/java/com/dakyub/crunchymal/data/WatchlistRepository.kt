package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.best
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WatchlistEntry(
    val series: SeriesRef,
    val neverWatched: Boolean,
    val nextEpisodeId: String?,
    /** Position dans la watchlist (0 = ajout / mise à jour la plus récente). */
    val order: Int,
)

class WatchlistRepository(private val api: CrApi) {
    private val mutex = Mutex()
    private var cache: List<WatchlistEntry>? = null

    suspend fun get(force: Boolean = false): List<WatchlistEntry> = mutex.withLock {
        cache?.takeIf { !force } ?: load().also { cache = it }
    }

    fun invalidate() {
        cache = null
    }

    private suspend fun load(): List<WatchlistEntry> {
        val items = api.watchlist()
        val seriesIds = items.map { item ->
            item.panel.episodeMetadata?.seriesId?.takeIf { it.isNotBlank() } ?: item.panel.id
        }
        // Les éléments de la watchlist sont souvent des épisodes : on récupère les affiches des séries.
        val objects = runCatching { api.objects(seriesIds) }.getOrDefault(emptyList()).associateBy { it.id }

        return items.mapIndexed { index, item ->
            val panel = item.panel
            val meta = panel.episodeMetadata
            val seriesId = seriesIds[index]
            val obj = objects[seriesId]
            WatchlistEntry(
                series = SeriesRef(
                    id = seriesId,
                    title = obj?.title?.takeIf { it.isNotBlank() } ?: meta?.seriesTitle?.takeIf { it.isNotBlank() } ?: panel.title,
                    slug = obj?.slugTitle?.takeIf { it.isNotBlank() } ?: meta?.seriesSlugTitle ?: panel.slugTitle,
                    posterUrl = obj?.images?.posterTall?.best(240) ?: panel.images.posterTall.best(240),
                    wideUrl = obj?.images?.posterWide?.best(400) ?: panel.images.thumbnail.best(400),
                ),
                neverWatched = item.neverWatched,
                nextEpisodeId = if (panel.type == "episode") panel.id else null,
                order = index,
            )
        }.distinctBy { it.series.id }
    }
}
