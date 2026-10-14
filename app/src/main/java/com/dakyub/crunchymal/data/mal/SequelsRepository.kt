package com.dakyub.crunchymal.data.mal

import android.content.Context
import com.dakyub.crunchymal.data.AvailabilityRepository
import com.dakyub.crunchymal.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    val aired: Boolean get() = airing == "currently_airing" || airing == "finished_airing"

    fun toAnime() = MalAnime(
        malId = malId, title = title, titleEnglish = titleEnglish, titleJapanese = null, synonyms = emptyList(),
        score = score, scoredBy = null, type = type, episodes = null, startYear = startYear,
        pictureUrl = picture, airing = airing,
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
)

/**
 * Suites des animes « Terminé » de la liste MAL qui n'y sont pas encore : rangée de l'accueil et alerte
 * quand l'une d'elles arrive sur Crunchyroll / ADN. Les relations MAL sont gardées 2 semaines, les fiches
 * des suites 3 jours (diffusion qui change).
 */
class SequelsRepository(context: Context, private val mal: MalRepository, private val availability: AvailabilityRepository) {
    private val file = File(context.filesDir, "sequels.json")
    private val mutex = Mutex()
    private var data = runCatching { Http.json.decodeFromString<SequelFile>(file.readText()) }.getOrElse { SequelFile() }

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
            data = data.copy(related = data.related + (entry.anime.malId to ids), relatedAt = data.relatedAt + (entry.anime.malId to now))
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
        data = data.copy(sequels = infos.associateBy { it.malId })
        save()
        _sequels.value = sorted(infos)
        // Disponibilité sur Crunchyroll / ADN des suites déjà diffusées (rangée et alertes).
        infos.filter { it.aired }.forEach { availability.request(it.toAnime(), crunchyrollEnabled) }
    }

    /**
     * Suites diffusées trouvées sur Crunchyroll ou ADN depuis le dernier appel. Au tout premier appel, les
     * suites déjà disponibles sont seulement mémorisées.
     */
    suspend fun newlyAvailable(): List<SequelInfo> = mutex.withLock {
        val found = availability.found.value
        val available = _sequels.value.filter { it.aired && found[it.malId]?.let { a -> a.crunchyroll != null || a.adn != null } == true }
        val notified = data.notified
        data = data.copy(notified = notified.orEmpty() + available.map { it.malId })
        save()
        if (notified == null) emptyList() else available.filter { it.malId !in notified }
    }

    private fun save() {
        runCatching { file.writeText(Http.json.encodeToString(data)) }
    }

    private companion object {
        const val RELATED_TTL = 14L * 24 * 3600 * 1000
        const val DETAILS_TTL = 3L * 24 * 3600 * 1000
        const val MAX_PER_RUN = 120
    }
}
