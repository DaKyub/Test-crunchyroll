package com.dakyub.crunchymal.data

import android.content.Context
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Plateformes de streaming d'une fiche MAL (sérialisable pour le cache). */
@Serializable
data class WatchPlatforms(
    val names: List<String> = emptyList(),
    /** "TMDB" (disponible en France), "MAL" (liste mondiale de la fiche MAL) ou "" si rien trouvé. */
    val source: String = "",
    val checkedAt: Long = 0,
)

@Serializable
private data class TmdbHit(
    val id: Int = 0,
    val name: String? = null,
    val title: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
) {
    val names: List<String> get() = listOfNotNull(name, title, originalName, originalTitle).filter { it.isNotBlank() }
}

@Serializable
private data class TmdbHits(val results: List<TmdbHit> = emptyList())

@Serializable
private data class TmdbProvider(@SerialName("provider_name") val name: String = "")

@Serializable
private data class TmdbCountry(
    val flatrate: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList(),
    val ads: List<TmdbProvider> = emptyList(),
)

@Serializable
private data class TmdbProviders(val results: Map<String, TmdbCountry> = emptyMap())

@Serializable
private data class TmdbSeasonInfo(
    @SerialName("season_number") val number: Int = 0,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
private data class TmdbTvDetails(val seasons: List<TmdbSeasonInfo> = emptyList())

/**
 * Où regarder une série de la liste MAL : rubrique « Où regarder » de TMDB pour la France (données
 * JustWatch, clé TMDB nécessaire) ; si TMDB ne trouve pas la série, section « Streaming Platforms »
 * de la page MAL (liste mondiale). Recherches en arrière-plan, cache disque d'une semaine.
 */
class WatchPlatformsRepository(context: Context, private val settings: Settings) {
    // v2 : plateformes par saison (l'ancien cache donnait celles de toute la franchise).
    private val file = File(context.filesDir, "watch_platforms_cache_v2.json").also {
        File(context.filesDir, "watch_platforms_cache.json").delete()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val semaphore = Semaphore(3)
    private val writeLock = Mutex()

    private val _found = MutableStateFlow(load())
    val found: StateFlow<Map<Int, WatchPlatforms>> = _found

    // Noms réharmonisés au chargement : le cache peut venir d'une version qui en reconnaissait moins.
    private fun load(): Map<Int, WatchPlatforms> = runCatching {
        if (file.exists()) Http.json.decodeFromString<Map<Int, WatchPlatforms>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }.mapValues { (_, v) -> v.copy(names = v.names.map(::normalize).distinct()) }

    fun request(anime: MalAnime) {
        val existing = _found.value[anime.malId]
        if (existing != null && System.currentTimeMillis() - existing.checkedAt < TTL) return
        if (!pending.add(anime.malId)) return
        scope.launch {
            try {
                semaphore.withPermit { lookup(anime) }?.let { result ->
                    _found.update { it + (anime.malId to result) }
                    writeLock.withLock { runCatching { file.writeText(Http.json.encodeToString(_found.value)) } }
                }
            } finally {
                pending.remove(anime.malId)
            }
        }
    }

    /** null = les deux sources ont échoué (réseau…) : rien n'est mis en cache, on réessaiera. */
    private suspend fun lookup(anime: MalAnime): WatchPlatforms? {
        val now = System.currentTimeMillis()
        val tmdb = if (settings.tmdbKey.isNotBlank()) runCatching { fromTmdb(anime) } else null
        tmdb?.getOrNull()?.let { return WatchPlatforms(it, "TMDB", now) }
        val mal = runCatching { fromMalPage(anime.malId) }
        mal.getOrNull()?.let { return WatchPlatforms(it, if (it.isEmpty()) "" else "MAL", now) }
        // TMDB a répondu « introuvable » mais la page MAL est illisible : on retient l'absence.
        return if (tmdb?.isSuccess == true) WatchPlatforms(emptyList(), "", now) else null
    }

    /** Plateformes en France d'après TMDB, ou null si la série n'y est pas trouvée. */
    private suspend fun fromTmdb(anime: MalAnime): List<String>? {
        val kind = if (anime.type?.lowercase() == "movie") "movie" else "tv"
        val titles = listOfNotNull(anime.titleEnglish, anime.title).filter { it.isNotBlank() }
        val queries = titles.flatMap { listOf(it, TitleMatcher.baseTitle(it)) }.filter { it.length >= 2 }.distinct().take(4)
        for (query in queries) {
            val hits = Http.json.decodeFromString<TmdbHits>(tmdb("search/$kind") { addQueryParameter("query", query) }).results
            fun similarity(hit: TmdbHit) = hit.names.maxOfOrNull { n -> (titles + queries).maxOf { TitleMatcher.similarity(it, n) } } ?: 0.0
            val best = hits.take(8).maxByOrNull { hit ->
                similarity(hit) + (if (16 in hit.genreIds) 0.2 else 0.0) + (if ("JP" in hit.originCountry) 0.1 else 0.0)
            } ?: continue
            if (similarity(best) < 0.5) continue
            // Titre MAL ≠ titre TMDB : la fiche MAL est sans doute une saison d'une série TMDB plus large
            // (Steel Ball Run dans JoJo) ; on prend alors les plateformes de cette saison.
            val showSimilarity = best.names.maxOfOrNull { n -> titles.maxOf { TitleMatcher.similarity(it, n) } } ?: 0.0
            if (kind == "tv" && showSimilarity < 0.9) seasonPlatforms(best.id, anime, titles)?.let { return it }
            val france = Http.json.decodeFromString<TmdbProviders>(tmdb("$kind/${best.id}/watch/providers") {}).results["FR"]
            return france?.platforms().orEmpty()
        }
        return null
    }

    private fun TmdbCountry.platforms() = (flatrate + free + ads).map { normalize(it.name) }.distinct()

    /** Plateformes en France de la saison TMDB qui correspond à la fiche MAL, ou null (on garde celles de la série). */
    private suspend fun seasonPlatforms(tvId: Int, anime: MalAnime, titles: List<String>): List<String>? {
        val seasons = runCatching { Http.json.decodeFromString<TmdbTvDetails>(tmdb("tv/$tvId") {}).seasons }.getOrNull() ?: return null
        val season = matchSeason(anime, titles, seasons) ?: return null
        val france = runCatching {
            Http.json.decodeFromString<TmdbProviders>(tmdb("tv/$tvId/season/${season.number}/watch/providers") {}).results["FR"]
        }.getOrNull()
        return france?.platforms()?.takeIf { it.isNotEmpty() }
    }

    /** Saison par son nom ("Steel Ball Run", "Season 3"…) contenu dans un titre MAL, sinon par l'année de diffusion. */
    private fun matchSeason(anime: MalAnime, titles: List<String>, seasons: List<TmdbSeasonInfo>): TmdbSeasonInfo? {
        val candidates = seasons.filter { it.number > 0 }
        fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val titleWords = (titles + anime.allTitles).map { words(it).toSet() }
        val parts = titles.flatMap { listOf(it, it.substringAfter(':', ""), it.substringBefore(':')) }
            .map { it.trim() }.filter { it.length >= 3 }
        val byName = candidates.map { season ->
            val seasonWords = words(season.name)
            val contained = seasonWords.isNotEmpty() && titleWords.any { it.containsAll(seasonWords) }
            val similarity = parts.maxOfOrNull { TitleMatcher.similarity(it, season.name) } ?: 0.0
            season to (if (contained) maxOf(similarity, 0.9) else similarity)
        }.maxByOrNull { it.second }
        if (byName != null && byName.second >= 0.6) return byName.first
        val year = anime.startYear ?: return null
        return candidates.firstOrNull { it.airDate?.take(4)?.toIntOrNull() == year }
    }

    /** Section « Streaming Platforms » de la page MAL ; null si la page n'a pas pu être lue. */
    private suspend fun fromMalPage(malId: Int): List<String>? {
        val html = Http.client.fetch(
            Request.Builder()
                .url("https://myanimelist.net/anime/$malId")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
        )
        if (!html.contains("myanimelist", ignoreCase = true)) return null
        val start = html.indexOf("Streaming Platforms")
        if (start < 0) return emptyList()
        val next = html.indexOf("<h2", start + 20)
        val section = html.substring(start, if (next > start) next else minOf(html.length, start + 20_000))
        val captions = Regex("""class="caption"[^>]*>\s*([^<]+?)\s*<""").findAll(section).map { it.groupValues[1] }.toList()
        val titles = Regex("""title="([^"]+)"""").findAll(section).map { it.groupValues[1] }.toList()
        return (captions.ifEmpty { titles })
            .map { decode(it).trim() }
            .filter { it.isNotBlank() }
            .map(::normalize)
            .distinct()
    }

    private fun decode(text: String) = text
        .replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    /** Noms de plateformes harmonisés (TMDB et MAL n'écrivent pas tous pareil). */
    private fun normalize(name: String): String {
        val n = name.lowercase()
        return when {
            "crunchyroll" in n -> "Crunchyroll"
            // TMDB : « Anime Digital Network » ; ailleurs « Animation Digital Network », « ADN Amazon Channel »…
            "digital network" in n || n == "adn" || n.startsWith("adn ") -> "ADN"
            "netflix" in n -> "Netflix"
            "disney" in n -> "Disney+"
            "prime video" in n || n == "amazon video" || n == "amazon" -> "Prime Video"
            "apple tv" in n -> "Apple TV+"
            "canal" in n -> "Canal+"
            "paramount" in n -> "Paramount+"
            n == "max" || "hbo" in n -> "Max"
            "hidive" in n -> "HIDIVE"
            "hulu" in n -> "Hulu"
            else -> name.substringBefore(" Amazon Channel").substringBefore(" Apple TV Channel").substringBefore(" with Ads").trim()
        }
    }

    /** La clé TMDB peut être une clé API v3 (courte) ou un jeton de lecture v4 (long, en-tête Bearer). */
    private suspend fun tmdb(path: String, params: HttpUrl.Builder.() -> Unit): String {
        val key = settings.tmdbKey
        val bearer = key.length > 40
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en-US")
            .apply { if (!bearer) addQueryParameter("api_key", key) }
            .apply(params)
            .build()
        val request = Request.Builder().url(url)
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .build()
        return Http.client.fetch(request)
    }

    suspend fun clear() {
        _found.value = emptyMap()
        writeLock.withLock { file.delete() }
    }

    private companion object {
        const val TTL = 7L * 24 * 3600 * 1000
    }
}
