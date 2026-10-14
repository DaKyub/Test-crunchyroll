package com.dakyub.crunchymal.data.mal

import android.content.Context
import com.dakyub.crunchymal.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Anime recommandé d'après les séries notées 8+ de la liste MAL. */
@Serializable
data class ForYouItem(
    val malId: Int,
    val title: String,
    val titleEnglish: String? = null,
    val picture: String? = null,
    val score: Double? = null,
    val type: String? = null,
    val startYear: Int? = null,
    val airing: String? = null,
    val genres: List<String> = emptyList(),
    /** Séries de la liste qui le recommandent (la plus recommandante d'abord). */
    val because: List<String> = emptyList(),
) {
    fun toAnime() = MalAnime(
        malId = malId, title = title, titleEnglish = titleEnglish, titleJapanese = null, synonyms = emptyList(),
        score = score, scoredBy = null, type = type, episodes = null, startYear = startYear,
        genres = genres, pictureUrl = picture, airing = airing,
    )
}

@Serializable
private data class ForYouFile(val fetchedAt: Long = 0, val items: List<ForYouItem> = emptyList())

/**
 * « Recommandé pour toi » : recommandations des membres MAL pour tes séries notées 8+ (goûts récents et
 * préférées de toujours), regroupées, sans ce que tu as déjà vu. Calculé au plus une fois par jour.
 */
class ForYouRepository(context: Context, private val mal: MalRepository) {
    private val file = File(context.filesDir, "for_you.json")
    private val mutex = Mutex()
    @Volatile private var data: ForYouFile? = null

    suspend fun items(force: Boolean = false): List<ForYouItem> = mutex.withLock {
        val current = data ?: withContext(Dispatchers.IO) {
            runCatching { Http.json.decodeFromString<ForYouFile>(file.readText()) }.getOrNull()
        }
        data = current
        if (!force && current != null && current.items.isNotEmpty() && System.currentTimeMillis() - current.fetchedAt < TTL) {
            return@withLock current.items
        }
        // MAL injoignable : on garde l'ancienne liste.
        val computed = runCatching { compute() }.getOrNull() ?: return@withLock current?.items.orEmpty()
        val fresh = ForYouFile(System.currentTimeMillis(), computed)
        data = fresh
        withContext(Dispatchers.IO) { runCatching { file.writeText(Http.json.encodeToString(fresh)) } }
        computed
    }

    private suspend fun compute(): List<ForYouItem> = coroutineScope {
        val list = mal.myListCached(maxAgeMs = 30 * 60_000)
        val statuses = list.associate { it.anime.malId to it.status.status }
        val liked = list.filter { it.status.score >= 8 && it.status.status in LIKED }
        val seeds = (
            liked.sortedByDescending { it.status.updatedAt.orEmpty() }.take(RECENT_SEEDS) +
                liked.sortedWith(compareByDescending<MalListEntry> { it.status.score }.thenByDescending { it.anime.score ?: 0.0 }).take(TOP_SEEDS)
            ).distinctBy { it.anime.malId }
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(3)
        val recs = seeds.map { seed ->
            async {
                semaphore.withPermit {
                    seed to runCatching { mal.recommendationCounts(seed.anime.malId) }.getOrNull()?.take(10)
                }
            }
        }.awaitAll()
        if (recs.all { it.second == null }) throw IllegalStateException("Recommandations MAL indisponibles")
        // Fiche recommandée → séries qui la recommandent, avec le nombre de recommandations.
        val votes = HashMap<Int, MutableList<Pair<String, Int>>>()
        recs.forEach { (seed, list) ->
            list.orEmpty().forEach { (id, count) ->
                // Déjà vue ou commencée : on ne la propose pas (« À voir » reste proposé, en rappel).
                val status = statuses[id]
                if (status == null || status == "plan_to_watch") {
                    votes.getOrPut(id) { mutableListOf() } += (seed.anime.titleEnglish ?: seed.anime.title) to count
                }
            }
        }
        val ranked = votes.entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<Pair<String, Int>>>> { it.value.size }.thenByDescending { it.value.sumOf { v -> v.second } })
            .take(MAX_ITEMS)
        ranked.map { (id, from) ->
            async {
                semaphore.withPermit {
                    val anime = runCatching { mal.details(id) }.getOrNull() ?: return@withPermit null
                    ForYouItem(
                        malId = id,
                        title = anime.title,
                        titleEnglish = anime.titleEnglish,
                        picture = anime.pictureUrl,
                        score = anime.score,
                        type = anime.type,
                        startYear = anime.startYear,
                        airing = anime.airing,
                        genres = anime.genres,
                        because = from.sortedByDescending { it.second }.map { it.first },
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private companion object {
        const val TTL = 24L * 3600 * 1000
        const val RECENT_SEEDS = 10
        const val TOP_SEEDS = 6
        const val MAX_ITEMS = 24
        val LIKED = setOf("completed", "watching", "on_hold")
    }
}
