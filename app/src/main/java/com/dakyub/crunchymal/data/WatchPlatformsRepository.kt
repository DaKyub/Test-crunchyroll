package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.mal.TitleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

/** Série d'un catalogue de plateforme (découverte TMDB). */
@Serializable
data class TmdbShowLite(
    val id: Int = 0,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
) {
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

@Serializable
private data class TmdbDiscover(val results: List<TmdbShowLite> = emptyList(), @SerialName("total_pages") val totalPages: Int = 1)

/** Plateformes proposées dans Parcourir (identifiant TMDB → nom). */
val TmdbPlatforms = listOf(8 to "Netflix", 119 to "Prime Video", 337 to "Disney+")

@Serializable
private data class TmdbSeasonInfo(
    @SerialName("season_number") val number: Int = 0,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
private data class TmdbTvDetails(
    val seasons: List<TmdbSeasonInfo> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
)

@Serializable
private data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String? = null,
)

@Serializable
private data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
private data class TmdbFind(@SerialName("tv_results") val tvResults: List<TmdbShowLite> = emptyList())

/** Fiche TMDB d'une série : titre anglais, titre original et nombre de saisons (hors spéciaux). */
data class TmdbTvInfo(val name: String, val originalName: String, val seasons: Int)

/** Saison absente du serveur Plex, et où la regarder en France d'après TMDB. */
data class MissingSeason(val number: Int, val name: String, val year: Int?, val platforms: List<String>)

/**
 * Recherche TMDB : [titles] à essayer, [allTitles] (noms de saison), film ou série, année de diffusion.
 * [seasonAware] : si le titre ne correspond qu'en partie, prendre les plateformes de la saison concernée.
 */
private data class TmdbQuery(
    val titles: List<String>,
    val allTitles: List<String>,
    val movie: Boolean,
    val year: Int?,
    val seasonAware: Boolean = true,
)

private fun MalAnime.tmdbQuery() =
    TmdbQuery(listOfNotNull(titleEnglish, title).filter { it.isNotBlank() }, allTitles, type?.lowercase() == "movie", startYear)

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
    private val foundWriter = CacheWriter(file) { Http.json.encodeToString(_found.value) }

    private val catalogFile = File(context.filesDir, "catalog_platforms_cache.json")
    private val catalogPending = ConcurrentHashMap.newKeySet<String>()
    private var catalogSave: Job? = null
    private val _catalog = MutableStateFlow(
        runCatching { Http.json.decodeFromString<Map<String, WatchPlatforms>>(catalogFile.readText()) }.getOrElse { emptyMap() }
    )

    /**
     * Plateformes en France des séries des catalogues de Parcourir (TMDB), par clé : « tmdb:<id> » pour les
     * catalogues Netflix / Prime / Disney+, « CRUNCHYROLL:<id> » ou « ADN:<id> » (recherche par titre).
     * source vide = série introuvable sur TMDB.
     */
    val catalog: StateFlow<Map<String, WatchPlatforms>> = _catalog

    /** Série d'un catalogue TMDB : ses plateformes en France. */
    fun requestTmdbShow(tvId: Int) = requestCatalog("tmdb:$tvId") {
        val france = Http.json.decodeFromString<TmdbProviders>(tmdb("tv/$tvId/watch/providers") {}).results["FR"]
        WatchPlatforms(france?.platforms().orEmpty(), "TMDB", System.currentTimeMillis())
    }

    /** Série Crunchyroll / ADN : retrouvée sur TMDB par son titre. */
    fun requestByTitle(key: String, titles: List<String>) = requestCatalog(key) {
        val clean = titles.map { it.trim() }.filter { it.length >= 2 }.distinct()
        val names = fromTmdb(TmdbQuery(clean.take(3), clean, movie = false, year = null, seasonAware = false))
        WatchPlatforms(names.orEmpty(), if (names == null) "" else "TMDB", System.currentTimeMillis())
    }

    private fun requestCatalog(key: String, lookup: suspend () -> WatchPlatforms) {
        if (settings.tmdbKey.isBlank()) return
        val existing = _catalog.value[key]
        if (existing != null && System.currentTimeMillis() - existing.checkedAt < TTL) return
        if (!catalogPending.add(key)) return
        scope.launch {
            try {
                val result = semaphore.withPermit { runCatching { lookup() }.getOrNull() } ?: return@launch
                _catalog.update { it + (key to result) }
                saveCatalogSoon()
            } finally {
                catalogPending.remove(key)
            }
        }
    }

    /** Enregistrement groupé : un catalogue vérifie des centaines de séries d'affilée. */
    @Synchronized
    private fun saveCatalogSoon() {
        if (catalogSave?.isActive == true) return
        catalogSave = scope.launch {
            delay(3_000)
            writeLock.withLock { runCatching { catalogFile.writeText(Http.json.encodeToString(_catalog.value)) } }
        }
    }

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
                    foundWriter.schedule()
                }
            } finally {
                pending.remove(anime.malId)
            }
        }
    }

    /** null = les deux sources ont échoué (réseau…) : rien n'est mis en cache, on réessaiera. */
    private suspend fun lookup(anime: MalAnime): WatchPlatforms? {
        val now = System.currentTimeMillis()
        val tmdb = if (settings.tmdbKey.isNotBlank()) runCatching { fromTmdb(anime.tmdbQuery()) } else null
        tmdb?.getOrNull()?.let { return WatchPlatforms(it, "TMDB", now) }
        val mal = runCatching { fromMalPage(anime.malId) }
        mal.getOrNull()?.let { return WatchPlatforms(it, if (it.isEmpty()) "" else "MAL", now) }
        // TMDB a répondu « introuvable » mais la page MAL est illisible : on retient l'absence.
        return if (tmdb?.isSuccess == true) WatchPlatforms(emptyList(), "", now) else null
    }

    /** Plateformes en France d'après TMDB, ou null si la série n'y est pas trouvée. */
    private suspend fun fromTmdb(search: TmdbQuery): List<String>? {
        val kind = if (search.movie) "movie" else "tv"
        val titles = search.titles
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
            if (kind == "tv" && search.seasonAware && showSimilarity < 0.9) seasonPlatforms(best.id, search)?.let { return it }
            val france = Http.json.decodeFromString<TmdbProviders>(tmdb("$kind/${best.id}/watch/providers") {}).results["FR"]
            return france?.platforms().orEmpty()
        }
        return null
    }

    private fun TmdbCountry.platforms() = (flatrate + free + ads).map { normalize(it.name) }.distinct()

    /** Plateformes en France de la saison TMDB qui correspond à la fiche MAL, ou null (on garde celles de la série). */
    private suspend fun seasonPlatforms(tvId: Int, query: TmdbQuery): List<String>? {
        val seasons = runCatching { Http.json.decodeFromString<TmdbTvDetails>(tmdb("tv/$tvId") {}).seasons }.getOrNull() ?: return null
        val season = matchSeason(query, seasons) ?: return null
        val france = runCatching {
            Http.json.decodeFromString<TmdbProviders>(tmdb("tv/$tvId/season/${season.number}/watch/providers") {}).results["FR"]
        }.getOrNull()
        return france?.platforms()?.takeIf { it.isNotEmpty() }
    }

    /** Saison par son nom ("Steel Ball Run", "Season 3"…) contenu dans un titre MAL, sinon par l'année de diffusion. */
    private fun matchSeason(query: TmdbQuery, seasons: List<TmdbSeasonInfo>): TmdbSeasonInfo? {
        val titles = query.titles
        val candidates = seasons.filter { it.number > 0 }
        fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val titleWords = (titles + query.allTitles).map { words(it).toSet() }
        val parts = titles.flatMap { listOf(it, it.substringAfter(':', ""), it.substringBefore(':')) }
            .map { it.trim() }.filter { it.length >= 3 }
        val byName = candidates.map { season ->
            val seasonWords = words(season.name)
            val contained = seasonWords.isNotEmpty() && titleWords.any { it.containsAll(seasonWords) }
            val similarity = parts.maxOfOrNull { TitleMatcher.similarity(it, season.name) } ?: 0.0
            season to (if (contained) maxOf(similarity, 0.9) else similarity)
        }.maxByOrNull { it.second }
        if (byName != null && byName.second >= 0.6) return byName.first
        val year = query.year ?: return null
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

    /** Animes (séries d'animation japonaises) inclus dans l'abonnement de la plateforme TMDB [providerId], en France. */
    suspend fun animeOn(providerId: Int, pages: Int = 5): List<TmdbShowLite> {
        val out = mutableListOf<TmdbShowLite>()
        for (page in 1..pages) {
            val result = Http.json.decodeFromString<TmdbDiscover>(tmdb("discover/tv") {
                addQueryParameter("with_watch_providers", providerId.toString())
                addQueryParameter("watch_region", "FR")
                addQueryParameter("with_watch_monetization_types", "flatrate")
                addQueryParameter("with_genres", "16")
                addQueryParameter("with_original_language", "ja")
                addQueryParameter("sort_by", "popularity.desc")
                addQueryParameter("page", page.toString())
            })
            out += result.results
            if (page >= result.totalPages) break
        }
        return out.distinctBy { it.id }
    }

    /** Titre anglais, titre original et nombre de saisons d'une série TMDB ; null sans clé TMDB ou en cas d'échec. */
    suspend fun tvInfo(tvId: Int): TmdbTvInfo? {
        if (settings.tmdbKey.isBlank()) return null
        val details = runCatching { Http.json.decodeFromString<TmdbTvDetails>(tmdb("tv/$tvId") {}) }.getOrNull() ?: return null
        // Saisons déjà diffusées seulement : une saison annoncée ne doit pas faire « Plex 1/2 ».
        val today = DateUtils.isoUtc().take(10)
        val aired = details.seasons.count { it.number > 0 && it.airDate?.take(10)?.let { date -> date <= today } == true }
        return TmdbTvInfo(details.name, details.originalName, aired.takeIf { it > 0 } ?: details.numberOfSeasons)
    }

    /** Saisons diffusées de la série TMDB [tvId] absentes de [present], avec leurs plateformes en France. */
    suspend fun missingSeasons(tvId: Int, present: Set<Int>): List<MissingSeason> {
        if (settings.tmdbKey.isBlank()) return emptyList()
        val details = runCatching { Http.json.decodeFromString<TmdbTvDetails>(tmdb("tv/$tvId") {}) }.getOrNull() ?: return emptyList()
        val today = DateUtils.isoUtc().take(10)
        return details.seasons
            .filter { it.number > 0 && it.number !in present && it.airDate?.take(10)?.let { date -> date <= today } == true }
            .take(12)
            .map { season ->
                val france = runCatching {
                    Http.json.decodeFromString<TmdbProviders>(tmdb("tv/$tvId/season/${season.number}/watch/providers") {}).results["FR"]
                }.getOrNull()
                MissingSeason(season.number, season.name, season.airDate?.take(4)?.toIntOrNull(), france?.platforms().orEmpty())
            }
    }

    /**
     * Bande-annonce YouTube (identifiant de vidéo) d'une série ou d'un film : [tmdbId] s'il est connu, sinon
     * recherche par [titles]. Français d'abord, puis anglais, japonais ; bande-annonce plutôt que teaser.
     */
    suspend fun trailer(titles: List<String>, movie: Boolean = false, tmdbId: Int? = null): String? {
        if (settings.tmdbKey.isBlank()) return null
        val kind = if (movie) "movie" else "tv"
        val id = tmdbId ?: findTmdbId(titles, kind) ?: return null
        val videos = runCatching {
            Http.json.decodeFromString<TmdbVideos>(tmdb("$kind/$id/videos") {
                setQueryParameter("language", "fr-FR")
                addQueryParameter("include_video_language", "fr,en,ja,null")
            }).results
        }.getOrNull().orEmpty()
        val languages = listOf("fr", "en", "ja")
        return videos
            .filter { it.site == "YouTube" && it.key.isNotBlank() }
            .sortedWith(
                compareBy(
                    { languages.indexOf(it.language).let { i -> if (i < 0) languages.size else i } },
                    { it.type != "Trailer" },
                    { it.type != "Teaser" },
                    { !it.official },
                )
            )
            .firstOrNull()?.key
    }

    /** Série (ou film) TMDB la plus proche de [titles], de préférence une animation japonaise. */
    private suspend fun findTmdbId(titles: List<String>, kind: String): Int? {
        val clean = titles.map { it.trim() }.filter { it.length >= 2 }.distinct()
        val queries = clean.flatMap { listOf(it, TitleMatcher.baseTitle(it)) }.filter { it.length >= 2 }.distinct().take(4)
        for (query in queries) {
            val hits = runCatching {
                Http.json.decodeFromString<TmdbHits>(tmdb("search/$kind") { addQueryParameter("query", query) }).results
            }.getOrNull() ?: continue
            fun similarity(hit: TmdbHit) = hit.names.maxOfOrNull { n -> (clean + queries).maxOf { TitleMatcher.similarity(it, n) } } ?: 0.0
            val best = hits.take(8).maxByOrNull { hit ->
                similarity(hit) + (if (16 in hit.genreIds) 0.2 else 0.0) + (if ("JP" in hit.originCountry) 0.1 else 0.0)
            } ?: continue
            if (similarity(best) >= 0.5) return best.id
        }
        return null
    }

    /** Identifiant TMDB d'une série à partir d'un identifiant TVDB ([source] « tvdb_id ») ou IMDb (« imdb_id »). */
    suspend fun tmdbIdFrom(source: String, externalId: String): Int? {
        if (settings.tmdbKey.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<TmdbFind>(tmdb("find/$externalId") { addQueryParameter("external_source", source) })
                .tvResults.firstOrNull()?.id
        }.getOrNull()
    }

    suspend fun clear() {
        _found.value = emptyMap()
        _catalog.value = emptyMap()
        writeLock.withLock {
            file.delete()
            catalogFile.delete()
        }
    }

    private companion object {
        const val TTL = 7L * 24 * 3600 * 1000
    }
}
