package com.dakyub.crunchymal.data.progress

import com.dakyub.crunchymal.data.crunchyroll.CrPlayhead
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.dakyub.crunchymal.data.Perf
import com.dakyub.crunchymal.data.CacheLoads
import com.dakyub.crunchymal.data.CacheWriter
import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.adn.WATCHED_RATIO
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

/**
 * NOT_STARTED : rien vu · IN_PROGRESS : commencée, dernier épisode pas vu ·
 * UP_TO_DATE : dernier épisode disponible vu (trous avant, ou tout vu d'une série encore en diffusion) ·
 * COMPLETED : tous les épisodes vus, diffusion finie.
 */
enum class WatchStatus { NOT_STARTED, IN_PROGRESS, UP_TO_DATE, COMPLETED }

@Serializable
data class ProgressSummary(
    val total: Int,
    val watched: Int,
    val started: Boolean,
    val nextEpisodeId: String? = null,
    val nextLabel: String? = null,
    val computedAt: Long = 0,
    /** Dernier épisode disponible vu (null = résumé calculé par une ancienne version, à recalculer). */
    val lastWatched: Boolean? = null,
    /** Épisodes disponibles après le dernier vu (null = ancienne version du cache). */
    val remainingAfterLast: Int? = null,
    /** Date de sortie (ISO) de l'épisode suivant. */
    val nextReleased: String? = null,
    /** Encore en diffusion (épisodes annoncés, ou dernier sorti il y a peu) ; null = ancienne version du cache. */
    val airing: Boolean? = null,
) {
    val status: WatchStatus
        get() = when {
            watched == 0 && !started -> WatchStatus.NOT_STARTED
            // Tout vu mais encore en diffusion : « À jour », pas « Terminé ».
            total > 0 && watched >= total -> if (airing == true) WatchStatus.UP_TO_DATE else WatchStatus.COMPLETED
            lastWatched == true -> WatchStatus.UP_TO_DATE
            else -> WatchStatus.IN_PROGRESS
        }
}

@Serializable
data class EpisodeNode(val episode: CrEpisode, val watched: Boolean, val playheadSec: Long, val available: Boolean = true) {
    val progress: Float
        get() = if (watched) 1f else if (episode.durationMs > 0) {
            (playheadSec * 1000f / episode.durationMs).coerceIn(0f, 1f)
        } else 0f
}

@Serializable
data class SeasonNode(val season: CrSeason, val episodes: List<EpisodeNode>)

@Serializable
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

    /** Saisons et épisodes de chaque série ouverte, gardés sur disque : la fiche s'affiche avant la mise à jour. */
    private val treeDir = File(context.filesDir, "series_trees")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun treeFile(seriesId: String) = File(treeDir, seriesId.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json")

    /**
     * Dernier arbre connu de la série, même ancien (mémoire, sinon disque), pour afficher tout de suite les
     * saisons et les épisodes pendant que [tree] recalcule. null si la fiche n'a jamais été ouverte.
     */
    suspend fun cachedTree(seriesId: String): SeriesTree? = trees[seriesId] ?: stale[seriesId] ?: withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString<SeriesTree>(treeFile(seriesId).readText()) }.getOrNull()
    }

    /** Arbres retirés par [invalidate] : encore bons à afficher en attendant le recalcul. */
    private val stale = ConcurrentHashMap<String, SeriesTree>()

    private fun saveTree(tree: SeriesTree) {
        scope.launch {
            runCatching {
                treeDir.mkdirs()
                val file = treeFile(tree.seriesId)
                val tmp = File(file.path + ".tmp")
                tmp.writeText(Http.json.encodeToString(tree))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
                // Les fiches les plus récentes seulement.
                treeDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_TREES)?.forEach { it.delete() }
            }
        }
    }

    /** Appelé par [clear] pour vider aussi les caches liés (progression ADN). */
    var onClear: () -> Unit = {}

    private val _summaries = MutableStateFlow<Map<String, ProgressSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, ProgressSummary>> = _summaries
    private val loaded = CacheLoads.into(_summaries, ::load)

    private fun load(): Map<String, ProgressSummary> = runCatching {
        if (file.exists()) Http.json.decodeFromString<Map<String, ProgressSummary>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    fun isFresh(s: ProgressSummary) =
        s.remainingAfterLast != null && s.airing != null && System.currentTimeMillis() - s.computedAt < TTL

    /** Enregistre un résumé calculé ailleurs (progression ADN, clé "adn:<id>"). */
    suspend fun put(key: String, summary: ProgressSummary) {
        _summaries.update { it + (key to summary) }
        persist()
    }

    /** S'assure qu'un résumé récent existe (utilisé par la watchlist pour les filtres). */
    suspend fun ensureSummary(seriesId: String, force: Boolean = false) {
        loaded.join()
        val existing = _summaries.value[seriesId]
        if (!force && existing != null && isFresh(existing)) return
        tree(seriesId, force = true)
    }

    /**
     * [priority] : fiche ouverte par l'utilisateur. Elle ne fait pas la queue derrière les calculs de fond
     * (progression des cartes de Parcourir, de la watchlist…), limités à 3 à la fois.
     */
    suspend fun tree(
        seriesId: String,
        force: Boolean = false,
        priority: Boolean = false,
        /** Reçoit les saisons et épisodes dès qu'ils sont connus, avant la progression (rien de vu, non enregistré). */
        onEpisodes: ((SeriesTree) -> Unit)? = null,
    ): SeriesTree {
        if (!force) trees[seriesId]?.let { if (isFresh(it.summary)) return it }
        suspend fun locked(): SeriesTree = locks.getOrPut(seriesId) { Mutex() }.withLock {
            if (!force) trees[seriesId]?.let { if (isFresh(it.summary)) return@withLock it }
            compute(seriesId, onEpisodes).also { tree ->
                trees[seriesId] = tree
                stale.remove(seriesId)
                saveTree(tree)
                _summaries.update { it + (seriesId to tree.summary) }
                persist()
            }
        }
        // Calcul de fond : la place dans la file (3 à la fois) est prise AVANT le verrou de la série. Sinon un
        // calcul en attente gardait ce verrou, et la fiche ouverte entre-temps attendait derrière toute la file.
        return if (priority) locked() else semaphore.withPermit { locked() }
    }

    private suspend fun compute(seriesId: String, onEpisodes: ((SeriesTree) -> Unit)? = null): SeriesTree = coroutineScope {
        val t0 = Perf.now()
        val seasons = originalSeasons(api.seasons(seriesId))
        val episodesBySeason = seasons.map { season -> async { season to episodesOf(season) } }.awaitAll()
        Perf.record("Fiche Crunchyroll", "saisons + épisodes", Perf.now() - t0, "${seasons.size} saisons")
        // Épisodes affichables tout de suite ; les positions de lecture suivent.
        onEpisodes?.invoke(assemble(seriesId, episodesBySeason, emptyMap()))

        val t1 = Perf.now()
        val allIds = episodesBySeason.flatMap { (_, eps) -> eps.flatMap { ep -> listOf(ep.id) + ep.versions.map { it.guid } } }
        val playheads = api.playheads(allIds).associateBy { it.contentId }
        Perf.record("Fiche Crunchyroll", "positions de lecture", Perf.now() - t1, "${allIds.distinct().size} identifiants")
        assemble(seriesId, episodesBySeason, playheads)
    }

    /** Arbre de la série à partir de ses épisodes et des positions de lecture connues ([playheads]). */
    private fun assemble(
        seriesId: String,
        episodesBySeason: List<Pair<CrSeason, List<CrEpisode>>>,
        playheads: Map<String, CrPlayhead>,
    ): SeriesTree {
        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val nodes = episodesBySeason.map { (season, eps) ->
            SeasonNode(
                season = season,
                episodes = eps.sortedBy { it.sequenceNumber }.map { ep ->
                    val ids = (listOf(ep.id) + ep.versions.map { it.guid }).distinct()
                    val heads = ids.mapNotNull { playheads[it] }
                    // Vu : marqué « fini », ou regardé à 85 % au moins (épisode quitté pendant le générique).
                    val playhead = heads.maxOfOrNull { it.playhead } ?: 0
                    EpisodeNode(
                        episode = ep,
                        watched = heads.any { it.fullyWatched } || (ep.durationMs > 0 && playhead * 1000 >= ep.durationMs * WATCHED_RATIO),
                        playheadSec = heads.maxOfOrNull { it.playhead } ?: 0,
                        available = ep.isAvailable(nowIso),
                    )
                },
            )
        }

        // Les épisodes annoncés mais pas encore sortis ne comptent pas dans la progression.
        val flat = nodes.flatMap { it.episodes }.filter { it.available }
        val lastWatchedIndex = flat.indexOfLast { it.watched }
        val next = if (lastWatchedIndex >= 0) flat.getOrNull(lastWatchedIndex + 1)
        else flat.firstOrNull { it.playheadSec > 0 } ?: flat.firstOrNull()

        return SeriesTree(
            seriesId = seriesId,
            seasons = nodes,
            summary = ProgressSummary(
                total = flat.size,
                watched = flat.count { it.watched },
                started = flat.any { it.watched || it.playheadSec > 0 },
                nextEpisodeId = next?.episode?.id,
                nextLabel = next?.episode?.label,
                computedAt = System.currentTimeMillis(),
                lastWatched = flat.lastOrNull()?.watched == true,
                remainingAfterLast = if (lastWatchedIndex >= 0) flat.size - lastWatchedIndex - 1 else flat.size,
                nextReleased = next?.episode?.releaseDate?.takeIf { it.isNotBlank() },
                airing = isAiring(nodes.flatMap { it.episodes }.any { !it.available }, flat.lastOrNull()?.episode?.releaseDate),
            ),
        )
    }

    /**
     * Épisodes d'une saison. Si sa version originale en a moins que prévu (VO absente de la région : Fairy Tail
     * n'a qu'un épisode en VO en France), on prend la version (doublage) qui en a le plus, de préférence dans
     * la langue audio choisie.
     */
    private suspend fun episodesOf(season: CrSeason): List<CrEpisode> = coroutineScope {
        val own = runCatching { api.episodes(season.id) }.getOrElse { if (season.versions.isEmpty()) throw it else emptyList() }
        val expected = season.numberOfEpisodes
        if (own.isNotEmpty() && (expected == 0 || own.size >= expected)) return@coroutineScope own
        val others = season.versions.filter { it.guid.isNotBlank() && it.guid != season.id }
        if (others.isEmpty()) return@coroutineScope own
        val candidates = others.map { version ->
            async { version to runCatching { api.episodes(version.guid) }.getOrDefault(emptyList()) }
        }.awaitAll()
        val preferred = api.preferredAudio
        val best = candidates.maxWithOrNull(compareBy({ it.second.size }, { it.first.audioLocale == preferred }, { it.first.audioLocale == "fr-FR" }))
        if (best != null && best.second.size > own.size) best.second else own
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

    // Écritures groupées : une watchlist calcule des dizaines de progressions d'affilée.
    private val summariesWriter = CacheWriter(file, ready = loaded) { Http.json.encodeToString(_summaries.value) }

    private fun persist() = summariesWriter.schedule()

    /** Retire les résumés dont la clé correspond (ex. ceux d'ADN à la déconnexion). */
    suspend fun removeKeys(predicate: (String) -> Boolean) {
        _summaries.update { map -> map.filterKeys { !predicate(it) } }
        persist()
    }

    fun invalidate(seriesId: String) {
        trees.remove(seriesId)?.let { stale[seriesId] = it }
    }

    suspend fun clear() {
        trees.clear()
        stale.clear()
        withContext(Dispatchers.IO) { runCatching { treeDir.deleteRecursively() } }
        onClear()
        _summaries.value = emptyMap()
        writeLock.withLock { file.delete() }
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
        /** Fiches gardées sur disque (les plus récentes). */
        const val MAX_TREES = 300
    }
}

/** En diffusion : épisodes annoncés pas encore sortis, ou dernier épisode sorti il y a moins de 10 jours. */
fun isAiring(hasUpcoming: Boolean, lastReleased: String?): Boolean =
    hasUpcoming || (lastReleased?.takeIf { it.length >= 19 }?.take(19)?.let { it >= DateUtils.isoDaysAgo(10) } == true)
