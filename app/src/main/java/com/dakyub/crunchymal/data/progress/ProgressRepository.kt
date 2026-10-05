package com.dakyub.crunchymal.data.progress

import android.content.Context
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.CrEpisode
import com.dakyub.crunchymal.data.crunchyroll.CrSeason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class WatchStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }

@Serializable
data class ProgressSummary(
    val total: Int,
    val watched: Int,
    val started: Boolean,
    val nextEpisodeId: String? = null,
    val nextLabel: String? = null,
    val computedAt: Long = 0,
) {
    val status: WatchStatus
        get() = when {
            watched == 0 && !started -> WatchStatus.NOT_STARTED
            total > 0 && watched >= total -> WatchStatus.COMPLETED
            else -> WatchStatus.IN_PROGRESS
        }
}

data class EpisodeNode(val episode: CrEpisode, val watched: Boolean, val playheadSec: Long) {
    val progress: Float
        get() = if (watched) 1f else if (episode.durationMs > 0) {
            (playheadSec * 1000f / episode.durationMs).coerceIn(0f, 1f)
        } else 0f
}

data class SeasonNode(val season: CrSeason, val episodes: List<EpisodeNode>)

data class SeriesTree(val seriesId: String, val seasons: List<SeasonNode>, val summary: ProgressSummary)

/**
 * Calcule la progression réelle d'une série : saisons (VO uniquement) → épisodes → playheads.
 * Un épisode est vu si l'une de ses versions (VO, VF, VA…) est marquée "fully_watched".
 */
class ProgressRepository(context: Context, private val api: CrApi) {
    private val file = File(context.filesDir, "progress_cache.json")
    private val trees = ConcurrentHashMap<String, SeriesTree>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val semaphore = Semaphore(3)
    private val writeLock = Mutex()

    private val _summaries = MutableStateFlow(load())
    val summaries: StateFlow<Map<String, ProgressSummary>> = _summaries

    private fun load(): Map<String, ProgressSummary> = runCatching {
        if (file.exists()) Http.json.decodeFromString<Map<String, ProgressSummary>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    private fun isFresh(s: ProgressSummary) = System.currentTimeMillis() - s.computedAt < TTL

    /** S'assure qu'un résumé récent existe (utilisé par la watchlist pour les filtres). */
    suspend fun ensureSummary(seriesId: String, force: Boolean = false) {
        val existing = _summaries.value[seriesId]
        if (!force && existing != null && isFresh(existing)) return
        tree(seriesId, force = true)
    }

    suspend fun tree(seriesId: String, force: Boolean = false): SeriesTree {
        if (!force) trees[seriesId]?.let { if (isFresh(it.summary)) return it }
        return locks.getOrPut(seriesId) { Mutex() }.withLock {
            if (!force) trees[seriesId]?.let { if (isFresh(it.summary)) return@withLock it }
            semaphore.withPermit { compute(seriesId) }.also { tree ->
                trees[seriesId] = tree
                _summaries.update { it + (seriesId to tree.summary) }
                persist()
            }
        }
    }

    private suspend fun compute(seriesId: String): SeriesTree = coroutineScope {
        val seasons = originalSeasons(api.seasons(seriesId))
        val episodesBySeason = seasons.map { season -> async { season to api.episodes(season.id) } }.awaitAll()

        val allIds = episodesBySeason.flatMap { (_, eps) -> eps.flatMap { ep -> listOf(ep.id) + ep.versions.map { it.guid } } }
        val playheads = api.playheads(allIds).associateBy { it.contentId }

        val nodes = episodesBySeason.map { (season, eps) ->
            SeasonNode(
                season = season,
                episodes = eps.sortedBy { it.sequenceNumber }.map { ep ->
                    val ids = (listOf(ep.id) + ep.versions.map { it.guid }).distinct()
                    val heads = ids.mapNotNull { playheads[it] }
                    EpisodeNode(
                        episode = ep,
                        watched = heads.any { it.fullyWatched },
                        playheadSec = heads.maxOfOrNull { it.playhead } ?: 0,
                    )
                },
            )
        }

        val flat = nodes.flatMap { it.episodes }
        val lastWatched = flat.indexOfLast { it.watched }
        val next = if (lastWatched >= 0) flat.getOrNull(lastWatched + 1)
        else flat.firstOrNull { it.playheadSec > 0 } ?: flat.firstOrNull()

        SeriesTree(
            seriesId = seriesId,
            seasons = nodes,
            summary = ProgressSummary(
                total = flat.size,
                watched = flat.count { it.watched },
                started = flat.any { it.watched || it.playheadSec > 0 },
                nextEpisodeId = next?.episode?.id,
                nextLabel = next?.episode?.label,
                computedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Garde une seule version (l'originale) de chaque saison : les doublages sont des saisons à part. */
    private fun originalSeasons(seasons: List<CrSeason>): List<CrSeason> {
        val originals = seasons.filter { s ->
            s.versions.isEmpty() || s.versions.none { it.original } || s.versions.any { it.guid == s.id && it.original }
        }
        return originals.ifEmpty { seasons }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.seasonSequenceNumber }, { it.seasonNumber }))
    }

    private suspend fun persist() = writeLock.withLock {
        runCatching { file.writeText(Http.json.encodeToString(_summaries.value)) }
    }

    fun invalidate(seriesId: String) {
        trees.remove(seriesId)
    }

    suspend fun clear() {
        trees.clear()
        _summaries.value = emptyMap()
        writeLock.withLock { file.delete() }
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
    }
}
