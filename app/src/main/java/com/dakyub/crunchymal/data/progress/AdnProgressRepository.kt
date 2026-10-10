package com.dakyub.crunchymal.data.progress

import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.adn.AdnVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

data class AdnEpisodeNode(val video: AdnVideo, val watched: Boolean, val stoptime: Int, val available: Boolean) {
    val progress: Float
        get() = if (watched) 1f else if (video.duration > 0) (stoptime.toFloat() / video.duration).coerceIn(0f, 1f) else 0f
}

/** [summary] est null quand la progression est inconnue (pas connecté à ADN, ou historique injoignable). */
data class AdnSeriesTree(
    val showId: String,
    val episodes: List<AdnEpisodeNode>,
    val summary: ProgressSummary?,
    val computedAt: Long = System.currentTimeMillis(),
)

/**
 * Progression ADN d'une série : liste des épisodes + dernier épisode regardé (historique ADN).
 * Si l'API renvoie la progression de chaque épisode, elle est utilisée telle quelle ; sinon tous les
 * épisodes avant le dernier regardé sont considérés comme vus. Les résumés sont rangés avec ceux de
 * Crunchyroll sous la clé "adn:<id>" (voir [com.dakyub.crunchymal.data.SeriesRef.progressKey]).
 */
class AdnProgressRepository(private val adn: AdnApi, private val store: ProgressRepository) {
    private val trees = ConcurrentHashMap<String, AdnSeriesTree>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val semaphore = Semaphore(3)

    private fun key(showId: String) = "adn:$showId"

    suspend fun ensureSummary(showId: String, force: Boolean = false) {
        if (!adn.loggedIn.value) return
        val existing = store.summaries.value[key(showId)]
        if (!force && existing != null && store.isFresh(existing)) return
        tree(showId, force = true)
    }

    private fun isFresh(tree: AdnSeriesTree) =
        System.currentTimeMillis() - tree.computedAt < TTL && (tree.summary != null || !adn.loggedIn.value)

    suspend fun tree(showId: String, force: Boolean = false): AdnSeriesTree {
        if (!force) trees[showId]?.let { if (isFresh(it)) return it }
        return locks.getOrPut(showId) { Mutex() }.withLock {
            if (!force) trees[showId]?.let { if (isFresh(it)) return@withLock it }
            semaphore.withPermit { compute(showId) }.also { tree ->
                trees[showId] = tree
                tree.summary?.let { store.put(key(showId), it) }
            }
        }
    }

    private suspend fun compute(showId: String): AdnSeriesTree = coroutineScope {
        val loggedIn = adn.loggedIn.value
        val lastAsync = async { if (loggedIn) runCatching { adn.lastWatched(showId) } else null }
        val episodes = adn.episodes(showId)
        val lastResult = lastAsync.await()
        val last = lastResult?.getOrNull()

        val nowIso = DateUtils.isoUtc()
        val exact = episodes.any { it.user != null }
        val lastIndex = if (exact || last == null) -1 else episodes.indexOfFirst { it.id == last.id }
        val nodes = episodes.mapIndexed { i, video ->
            // Les épisodes annoncés mais pas encore sortis ne comptent pas dans la progression.
            val available = video.available && (video.releaseDate.isNullOrBlank() || video.releaseDate.take(19) <= nowIso)
            when {
                exact -> AdnEpisodeNode(video, video.user?.isFullyWatched == true, video.user?.stoptime ?: 0, available)
                i < lastIndex -> AdnEpisodeNode(video, watched = true, stoptime = 0, available = available)
                i == lastIndex -> AdnEpisodeNode(video, last?.user?.isFullyWatched == true, last?.user?.stoptime ?: 0, available)
                else -> AdnEpisodeNode(video, watched = false, stoptime = 0, available = available)
            }
        }

        val known = loggedIn && (exact || lastResult?.isSuccess == true)
        val summary = if (!known) null else {
            val flat = nodes.filter { it.available }
            val lastWatchedIndex = flat.indexOfLast { it.watched }
            val next = if (lastWatchedIndex >= 0) flat.getOrNull(lastWatchedIndex + 1)
            else flat.firstOrNull { it.stoptime > 0 } ?: flat.firstOrNull()
            ProgressSummary(
                total = flat.size,
                watched = flat.count { it.watched },
                started = last != null || flat.any { it.watched || it.stoptime > 0 },
                nextEpisodeId = next?.video?.id?.toString(),
                nextLabel = next?.video?.label?.ifBlank { null },
                computedAt = System.currentTimeMillis(),
                lastWatched = flat.lastOrNull()?.watched == true,
                remainingAfterLast = if (lastWatchedIndex >= 0) flat.size - lastWatchedIndex - 1 else flat.size,
                nextReleased = next?.video?.releaseDate?.takeIf { it.isNotBlank() },
            )
        }
        AdnSeriesTree(showId, nodes, summary)
    }

    fun invalidate(showId: String) {
        trees.remove(showId)
    }

    fun clearCache() {
        trees.clear()
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
    }
}
