package com.dakyub.crunchymal.data.progress

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.CacheLoads
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

@Serializable
data class AdnEpisodeNode(val video: AdnVideo, val watched: Boolean, val stoptime: Int, val available: Boolean) {
    val progress: Float
        get() = if (watched) 1f else if (video.duration > 0) (stoptime.toFloat() / video.duration).coerceIn(0f, 1f) else 0f
}

/** [summary] est null quand la progression est inconnue (pas connecté à ADN, ou historique injoignable). */
@Serializable
data class AdnSeriesTree(
    val showId: String,
    val episodes: List<AdnEpisodeNode>,
    val summary: ProgressSummary?,
    val computedAt: Long = System.currentTimeMillis(),
    /** Fiche de la série au moment du calcul, pour rouvrir la fiche sans attendre (cache disque). */
    val show: AdnShow? = null,
)

/**
 * Progression ADN d'une série : liste des épisodes + dernier épisode regardé (historique ADN).
 * Si l'API renvoie la progression de chaque épisode, elle est utilisée telle quelle ; sinon tous les
 * épisodes avant le dernier regardé sont considérés comme vus. Les résumés sont rangés avec ceux de
 * Crunchyroll sous la clé "adn:<id>" (voir [com.dakyub.crunchymal.data.SeriesRef.progressKey]).
 */
class AdnProgressRepository(private val adn: AdnApi, private val store: ProgressRepository, private val dir: File) {
    private val trees = ConcurrentHashMap<String, AdnSeriesTree>()
    /** Arbres retirés par [invalidate] : encore bons à afficher en attendant le recalcul. */
    private val stale = ConcurrentHashMap<String, AdnSeriesTree>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun treeFile(showId: String) = File(dir, showId.filter { it.isLetterOrDigit() } + ".json")

    /**
     * Dernier état connu de la série, même ancien (mémoire, sinon disque), pour afficher tout de suite la
     * fiche et ses épisodes pendant que [tree] recalcule. null si la fiche n'a jamais été ouverte.
     */
    suspend fun cachedTree(showId: String): AdnSeriesTree? = trees[showId] ?: stale[showId] ?: withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString<AdnSeriesTree>(treeFile(showId).readText()) }.getOrNull()
    }

    /** Garde sur disque l'arbre d'une fiche ouverte, avec la fiche [show] de la série. */
    fun remember(tree: AdnSeriesTree, show: AdnShow?) {
        scope.launch {
            runCatching {
                dir.mkdirs()
                val file = treeFile(tree.showId)
                val tmp = File(file.path + ".tmp")
                tmp.writeText(Http.json.encodeToString(tree.copy(show = show)))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_TREES)?.forEach { it.delete() }
            }
        }
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val semaphore = Semaphore(3)

    private fun key(showId: String) = "adn:$showId"

    suspend fun ensureSummary(showId: String, force: Boolean = false) {
        if (!adn.loggedIn.value) return
        CacheLoads.awaitAll()
        val existing = store.summaries.value[key(showId)]
        if (!force && existing != null && store.isFresh(existing)) return
        tree(showId, force = true)
    }

    private fun isFresh(tree: AdnSeriesTree) =
        System.currentTimeMillis() - tree.computedAt < TTL && (tree.summary != null || !adn.loggedIn.value)

    /** [priority] : fiche ouverte par l'utilisateur, sans attendre les calculs de fond (3 à la fois). */
    suspend fun tree(showId: String, force: Boolean = false, priority: Boolean = false): AdnSeriesTree {
        if (!force) trees[showId]?.let { if (isFresh(it)) return it }
        suspend fun locked(): AdnSeriesTree = locks.getOrPut(showId) { Mutex() }.withLock {
            if (!force) trees[showId]?.let { if (isFresh(it)) return@withLock it }
            compute(showId).also { tree ->
                trees[showId] = tree
                stale.remove(showId)
                tree.summary?.let { store.put(key(showId), it) }
            }
        }
        // La place dans la file est prise avant le verrou de la série (voir ProgressRepository.tree).
        return if (priority) locked() else semaphore.withPermit { locked() }
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
                exact -> AdnEpisodeNode(video, video.watched, video.user?.stoptime ?: 0, available)
                i < lastIndex -> AdnEpisodeNode(video, watched = true, stoptime = 0, available = available)
                i == lastIndex -> AdnEpisodeNode(video, last?.watched == true, last?.user?.stoptime ?: 0, available)
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
                airing = isAiring(nodes.any { !it.available }, flat.lastOrNull()?.video?.releaseDate),
            )
        }
        AdnSeriesTree(showId, nodes, summary)
    }

    fun invalidate(showId: String) {
        trees.remove(showId)?.let { stale[showId] = it }
    }

    fun clearCache() {
        trees.clear()
        stale.clear()
        scope.launch { runCatching { dir.deleteRecursively() } }
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
        /** Fiches ouvertes gardées sur disque (les plus récentes). */
        const val MAX_TREES = 150
    }
}
