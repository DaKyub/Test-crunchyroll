package com.dakyub.crunchymal.data.mal

import android.content.Context
import com.dakyub.crunchymal.data.AvailabilityRepository
import com.dakyub.crunchymal.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Suite (nouvelle saison, film…) d'un anime terminé sur MAL, absente de la liste MAL. */
@Serializable
data class SequelInfo(
    val malId: Int,
    val title: String,
    val titleEnglish: String? = null,
    val picture: String? = null,
    val airing: String? = null,
    val type: String? = null,
    val startYear: Int? = null,
    val score: Double? = null,
    /** Titre de l'anime terminé dont c'est la suite. */
    val prequelTitle: String = "",
    val checkedAt: Long = 0,
    /** Début de diffusion complet (« 2019-07-05 »), pour l'ordre chronologique de la licence. */
    val startDate: String? = null,
) {
    val aired: Boolean get() = airing == "currently_airing" || airing == "finished_airing"

    fun toAnime() = MalAnime(
        malId = malId, title = title, titleEnglish = titleEnglish, titleJapanese = null, synonyms = emptyList(),
        score = score, scoredBy = null, type = type, episodes = null, startYear = startYear,
        pictureUrl = picture, airing = airing, startDate = startDate,
    )
}

@Serializable
private data class SequelFile(
    /** Anime terminé → ses suites directes, et date de la vérification. */
    val related: Map<Int, List<Int>> = emptyMap(),
    val relatedAt: Map<Int, Long> = emptyMap(),
    val sequels: Map<Int, SequelInfo> = emptyMap(),
    /** Suites déjà signalées comme disponibles (null = premier passage : on mémorise sans alerter). */
    val notified: Set<Int>? = null,
    /** Fiches des animes affichés dans les frises de licence des pages, toutes séries confondues. */
    val known: Map<Int, SequelInfo> = emptyMap(),
    /** Anime → animes de la même histoire (suites, préquelles, histoires parallèles…), et date de la vérification. */
    val franchise: Map<Int, List<Int>> = emptyMap(),
    val franchiseAt: Map<Int, Long> = emptyMap(),
)

/**
 * Suites des animes « Terminé » de la liste MAL qui n'y sont pas encore : rangée de l'accueil et alerte
 * quand l'une d'elles arrive sur Crunchyroll / ADN. Les relations MAL sont gardées 2 semaines, les fiches
 * des suites 3 jours (diffusion qui change).
 */
class SequelsRepository(context: Context, private val mal: MalRepository, private val availability: AvailabilityRepository) {
    private val file = File(context.filesDir, "sequels.json")
    private val mutex = Mutex()
    @Volatile private var data = runCatching { Http.json.decodeFromString<SequelFile>(file.readText()) }.getOrElse { SequelFile() }

    private val _sequels = MutableStateFlow(sorted(data.sequels.values))
    val sequels: StateFlow<List<SequelInfo>> = _sequels

    /** En diffusion d'abord, puis à venir, puis déjà sorties (les plus récentes d'abord). */
    private fun sorted(list: Collection<SequelInfo>): List<SequelInfo> = list.sortedWith(
        compareBy<SequelInfo>({ when (it.airing) { "currently_airing" -> 0; "not_yet_aired" -> 1; else -> 2 } }, { -(it.startYear ?: 0) })
    )

    /** Met à jour les suites (appels MAL limités : au plus 120 animes terminés vérifiés par passage). */
    suspend fun refresh(crunchyrollEnabled: Boolean) = mutex.withLock {
        if (!mal.auth.loggedIn.value) return@withLock
        val list = runCatching { mal.myListCached(maxAgeMs = 30 * 60_000) }.getOrNull() ?: return@withLock
        val inList = list.map { it.anime.malId }.toSet()
        val completed = list.filter { it.status.status == "completed" }
        val now = System.currentTimeMillis()
        var checked = 0
        for (entry in completed.filter { now - (data.relatedAt[it.anime.malId] ?: 0) > RELATED_TTL }.take(MAX_PER_RUN)) {
            val ids = runCatching { mal.sequelIds(entry.anime.malId) }.getOrNull() ?: continue
            update { it.copy(related = it.related + (entry.anime.malId to ids), relatedAt = it.relatedAt + (entry.anime.malId to now)) }
            if (++checked % 25 == 0) save()
        }
        val candidates = completed.flatMap { e ->
            data.related[e.anime.malId].orEmpty().map { it to (e.anime.titleEnglish ?: e.anime.title) }
        }.filter { it.first !in inList }.distinctBy { it.first }
        val infos = candidates.mapNotNull { (id, from) ->
            val existing = data.sequels[id]
            if (existing != null && now - existing.checkedAt < DETAILS_TTL) return@mapNotNull existing.copy(prequelTitle = from)
            val anime = runCatching { mal.details(id) }.getOrNull() ?: return@mapNotNull existing
            SequelInfo(id, anime.title, anime.titleEnglish, anime.pictureUrl, anime.airing, anime.type, anime.startYear, anime.score, from, now)
        }
        update { it.copy(sequels = infos.associateBy { info -> info.malId }) }
        save()
        _sequels.value = sorted(infos)
        // Disponibilité sur Crunchyroll / ADN des suites déjà diffusées (rangée et alertes).
        infos.filter { it.aired }.forEach { availability.request(it.toAnime(), crunchyrollEnabled, background = true) }
    }

    /**
     * Suites diffusées trouvées sur Crunchyroll ou ADN depuis le dernier appel. Au tout premier appel, les
     * suites déjà disponibles sont seulement mémorisées.
     */
    suspend fun newlyAvailable(): List<SequelInfo> = mutex.withLock {
        val found = availability.found.value
        val available = _sequels.value.filter { it.aired && found[it.malId]?.let { a -> a.crunchyroll != null || a.adn != null } == true }
        val notified = data.notified
        update { it.copy(notified = notified.orEmpty() + available.map { a -> a.malId }) }
        save()
        if (notified == null) emptyList() else available.filter { it.malId !in notified }
    }

    /**
     * Licence d'une fiche dans l'ordre chronologique : les fiches MAL de la série et de ses saisons ([startIds])
     * et, de proche en proche, tous les animes de la même histoire d'après MAL (suites, préquelles, histoires
     * parallèles, récapitulatifs ; pas les spin-off). [onProgress] reçoit la frise au fur et à mesure.
     * Relations gardées 2 semaines, fiches 3 jours ; requêtes MAL prioritaires (fiche ouverte). Au plus
     * [MAX_CHAIN] animes ; une fiche sans date est mise à la fin.
     */
    suspend fun chain(startIds: Collection<Int>, onProgress: (List<MalAnime>) -> Unit = {}): List<MalAnime> {
        val now = System.currentTimeMillis()
        val queue = ArrayDeque(startIds.distinct())
        val visited = queue.toMutableSet()
        val result = mutableListOf<MalAnime>()
        fun sorted() = result.sortedWith(compareBy({ it.startDate ?: it.startYear?.toString() ?: "9999" }, { it.malId }))
        startIds.distinct().forEach { id -> info(id, now)?.let { result += it.toAnime() } }
        onProgress(sorted())
        while (queue.isNotEmpty() && visited.size <= MAX_CHAIN) {
            val id = queue.removeFirst()
            val cached = data.franchise[id]?.takeIf { now - (data.franchiseAt[id] ?: 0) < RELATED_TTL }
            val related = cached ?: runCatching { mal.franchiseIds(id, priority = true) }.getOrNull()?.also { ids ->
                update { it.copy(franchise = it.franchise + (id to ids), franchiseAt = it.franchiseAt + (id to now)) }
            } ?: continue
            var added = false
            for (other in related) {
                if (visited.size > MAX_CHAIN || !visited.add(other)) continue
                queue += other
                info(other, now)?.let { result += it.toAnime(); added = true }
            }
            if (added) onProgress(sorted())
        }
        save()
        return sorted()
    }

    /** Fiche (en cache 3 jours) d'un anime de la licence ; ancienne fiche ou celle sans date redemandée. */
    private suspend fun info(id: Int, now: Long): SequelInfo? =
        data.known[id]?.takeIf { now - it.checkedAt < DETAILS_TTL && it.startDate != null }
            ?: runCatching { mal.details(id, priority = true) }.getOrNull()?.let { anime ->
                SequelInfo(id, anime.title, anime.titleEnglish, anime.pictureUrl, anime.airing, anime.type, anime.startYear, anime.score,
                    checkedAt = now, startDate = anime.startDate ?: anime.startYear?.toString())
                    .also { info -> update { it.copy(known = it.known + (id to info)) } }
            }
            ?: data.known[id]

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val franchises = ConcurrentHashMap<Set<Int>, MutableStateFlow<List<MalAnime>?>>()

    /**
     * Frise de licence de [startIds], calculée une seule fois par session et partagée par ceux qui l'affichent
     * (rangée « Toute la licence », ordre des saisons de la fiche). null tant que rien n'est connu.
     */
    fun franchise(startIds: Set<Int>): StateFlow<List<MalAnime>?> = franchises.computeIfAbsent(startIds) {
        MutableStateFlow<List<MalAnime>?>(null).also { flow ->
            scope.launch {
                flow.value = runCatching { chain(startIds) { partial -> flow.value = partial } }.getOrDefault(flow.value.orEmpty())
            }
        }
    }

    @Synchronized
    private fun update(change: (SequelFile) -> SequelFile) {
        data = change(data)
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(Http.json.encodeToString(data)) }
    }

    private companion object {
        const val RELATED_TTL = 14L * 24 * 3600 * 1000
        const val DETAILS_TTL = 3L * 24 * 3600 * 1000
        const val MAX_PER_RUN = 120
        /** Animes au plus dans une frise de licence (franchises très longues). */
        const val MAX_CHAIN = 40
    }
}
