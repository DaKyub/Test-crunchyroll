package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.mal.TitleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Série correspondante trouvée sur un service (sérialisable pour le cache). */
@Serializable
data class FoundSeries(val id: String, val title: String, val posterUrl: String? = null, val slug: String = "")

/** Disponibilité d'une fiche MAL sur Crunchyroll / ADN. */
@Serializable
data class Availability(
    val crunchyroll: FoundSeries? = null,
    val adn: FoundSeries? = null,
    val checkedAt: Long = 0,
    /** Version de la recherche (2 : essaie aussi le titre sans numéro de saison). */
    val version: Int = 0,
) {
    /**
     * Fiche à ouvrir : sur un service affiché de préférence, sinon sur celui où la série a été trouvée
     * (Crunchyroll seulement si le compte est connecté : [crunchyrollUsable]).
     */
    fun refFor(providers: Set<Provider>, crunchyrollUsable: Boolean): SeriesRef? {
        val cr = crunchyroll?.takeIf { crunchyrollUsable }
            ?.let { SeriesRef(it.id, it.title, it.slug, it.posterUrl, provider = Provider.CRUNCHYROLL) }
        val adnRef = adn?.let { SeriesRef(it.id, it.title, posterUrl = it.posterUrl, provider = Provider.ADN) }
        return when {
            Provider.CRUNCHYROLL in providers && cr != null -> cr
            Provider.ADN in providers && adnRef != null -> adnRef
            else -> cr ?: adnRef
        }
    }
}

/**
 * Cherche si une série MAL est disponible sur Crunchyroll et ADN (par titre), avec cache disque.
 * Les recherches sont faites en arrière-plan, quelques-unes à la fois.
 */
class AvailabilityRepository(context: Context, private val cr: CrApi, private val adn: AdnApi) {
    private val file = File(context.filesDir, "availability_cache.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val semaphore = Semaphore(3)
    private val writeLock = Mutex()

    private val _found = MutableStateFlow(load())
    val found: StateFlow<Map<Int, Availability>> = _found

    private fun load(): Map<Int, Availability> = runCatching {
        if (file.exists()) Http.json.decodeFromString<Map<Int, Availability>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    fun request(anime: MalAnime, crunchyrollEnabled: Boolean) {
        val existing = _found.value[anime.malId]
        // Ancienne recherche incomplète : on la refait avec les titres de base (saisons 2, 3…).
        val outdated = existing != null && existing.version < VERSION && (existing.crunchyroll == null || existing.adn == null)
        if (existing != null && !outdated && System.currentTimeMillis() - existing.checkedAt < TTL) return
        if (!pending.add(anime.malId)) return
        scope.launch {
            try {
                semaphore.withPermit {
                    val titles = anime.allTitles.filter { it.length >= 3 }.take(4)
                    val crFound = if (crunchyrollEnabled) runCatching { searchCrunchyroll(titles) }.getOrNull() else existing?.crunchyroll
                    val adnFound = runCatching { searchAdn(titles) }.getOrNull()
                    val result = Availability(crFound, adnFound, System.currentTimeMillis(), VERSION)
                    _found.update { it + (anime.malId to result) }
                    writeLock.withLock { runCatching { file.writeText(Http.json.encodeToString(_found.value)) } }
                }
            } finally {
                pending.remove(anime.malId)
            }
        }
    }

    /**
     * Requêtes : les deux premiers titres, puis leurs versions sans numéro de saison (les saisons
     * suivantes sont en général dans la même fiche sur Crunchyroll / ADN).
     */
    private fun queries(titles: List<String>): List<String> =
        (titles.take(2) + titles.take(2).map { TitleMatcher.withoutSeason(it) }).filter { it.length >= 3 }.distinct()

    private fun withBases(titles: List<String>) = (titles + titles.map { TitleMatcher.withoutSeason(it) }).filter { it.length >= 3 }.distinct()

    private suspend fun searchCrunchyroll(original: List<String>): FoundSeries? {
        val titles = withBases(original)
        for (title in queries(original)) {
            val best = cr.search(title, n = 10).maxByOrNull { panel ->
                listOf(panel.title, panel.slugTitle.replace('-', ' ')).maxOf { t -> titles.maxOf { TitleMatcher.similarity(it, t) } }
            } ?: continue
            val sim = listOf(best.title, best.slugTitle.replace('-', ' ')).maxOf { t -> titles.maxOf { TitleMatcher.similarity(it, t) } }
            if (sim >= MATCH) return FoundSeries(best.id, best.title, best.images.posterTall.best(240), best.slugTitle)
        }
        return null
    }

    private suspend fun searchAdn(original: List<String>): FoundSeries? {
        val titles = withBases(original)
        for (title in queries(original)) {
            val best = adn.catalog(search = title, limit = 10).maxByOrNull { show ->
                listOfNotNull(show.title, show.originalTitle, show.shortTitle).maxOf { t -> titles.maxOf { TitleMatcher.similarity(it, t) } }
            } ?: continue
            val sim = listOfNotNull(best.title, best.originalTitle, best.shortTitle).maxOf { t -> titles.maxOf { TitleMatcher.similarity(it, t) } }
            if (sim >= MATCH) return FoundSeries(best.id.toString(), best.title, best.image2x ?: best.image)
        }
        return null
    }

    private companion object {
        const val TTL = 7L * 24 * 3600 * 1000
        const val MATCH = 0.8
        const val VERSION = 2
    }
}
