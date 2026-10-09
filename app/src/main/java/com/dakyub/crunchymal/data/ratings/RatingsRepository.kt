package com.dakyub.crunchymal.data.ratings

import android.util.Log
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.data.fetch
import com.dakyub.crunchymal.data.mal.TitleMatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class EpisodeRating(val value: Double, val source: String) {
    val label: String get() = "$source ${String.format(Locale.US, "%.1f", value)}"
}

/** Notes d'une saison : par numéro d'épisode, et par position (si la numérotation diffère). */
data class SeasonRatings(
    val byNumber: Map<Int, EpisodeRating>,
    val byPosition: List<EpisodeRating?>,
    /** Ligne de diagnostic affichée sous les épisodes (sources trouvées, erreurs…). */
    val info: String = "",
) {
    fun find(number: Int?, position: Int): EpisodeRating? =
        number?.let { byNumber[it] } ?: byPosition.getOrNull(position)

    companion object {
        val EMPTY = SeasonRatings(emptyMap(), emptyList())
    }
}

@Serializable
private data class OmdbSeries(@SerialName("imdbID") val imdbId: String? = null, @SerialName("Response") val response: String = "")

@Serializable
private data class OmdbEpisode(@SerialName("Episode") val episode: String = "", @SerialName("imdbRating") val rating: String = "")

@Serializable
private data class OmdbSeason(@SerialName("Episodes") val episodes: List<OmdbEpisode> = emptyList())

@Serializable
private data class TmdbShow(
    val id: Int = 0,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
)

@Serializable
private data class TmdbSearch(val results: List<TmdbShow> = emptyList())

@Serializable
private data class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class TmdbEpisode(
    @SerialName("episode_number") val number: Int = 0,
    @SerialName("vote_average") val average: Double = 0.0,
    @SerialName("vote_count") val count: Int = 0,
)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

/**
 * Notes des épisodes : IMDb (via OMDb) en priorité, TMDB en secours. Il faut au moins une des deux
 * clés dans les paramètres ; avec la clé TMDB, l'identifiant IMDb est retrouvé de façon fiable.
 */
class RatingsRepository(private val settings: Settings) {
    private data class Ids(val imdb: String?, val tmdb: Int?)

    private val ids = ConcurrentHashMap<String, Ids>()
    private val seasons = ConcurrentHashMap<String, SeasonRatings>()

    val configured: Boolean get() = settings.omdbKey.isNotBlank() || settings.tmdbKey.isNotBlank()

    suspend fun season(titles: List<String>, seasonNumber: Int): SeasonRatings {
        if (!configured) return SeasonRatings(emptyMap(), emptyList(), "Notes IMDb/TMDB : aucune clé (Paramètres → Notes des épisodes)")
        if (titles.isEmpty()) return SeasonRatings.EMPTY
        val key = titles.joinToString("|").lowercase() + "#" + seasonNumber
        seasons[key]?.let { return it }
        val errors = mutableListOf<String>()
        val seriesIds = resolve(titles, errors)
        if (seriesIds.imdb == null && seriesIds.tmdb == null) {
            return SeasonRatings(emptyMap(), emptyList(), (listOf("Notes : série introuvable sur IMDb/TMDB") + errors).joinToString(" · "))
                .also { if (errors.isEmpty()) seasons[key] = it }
        }

        suspend fun load(season: Int): Pair<List<Pair<Int, EpisodeRating>>, List<Pair<Int, EpisodeRating>>> {
            val imdb = seriesIds.imdb?.let { id ->
                runCatching { omdbSeason(id, season) }.onFailure { errors += "OMDb : ${it.message?.take(80)}" }.getOrNull()
            }.orEmpty()
            val tmdb = seriesIds.tmdb?.let { id ->
                runCatching { tmdbSeason(id, season) }.onFailure { if (it !is HttpException || it.code != 404) errors += "TMDB : ${it.message?.take(80)}" }.getOrNull()
            }.orEmpty()
            return imdb to tmdb
        }

        var (imdb, tmdb) = load(seasonNumber)
        // IMDb range souvent tous les épisodes d'un anime en saison 1 (numérotation absolue).
        var fallback = false
        if (imdb.isEmpty() && tmdb.isEmpty() && seasonNumber > 1) {
            load(1).let { imdb = it.first; tmdb = it.second }
            fallback = imdb.isNotEmpty() || tmdb.isNotEmpty()
        }
        val numbers = (imdb.map { it.first } + tmdb.map { it.first }).distinct().sorted()
        val byNumber = numbers.associateWith { n ->
            imdb.firstOrNull { it.first == n }?.second ?: tmdb.first { it.first == n }.second
        }
        val info = buildList {
            add("Notes : IMDb ${seriesIds.imdb ?: "—"} · TMDB ${seriesIds.tmdb ?: "—"} · ${byNumber.size} ép. notés" +
                (if (fallback) " (saison 1 IMDb/TMDB)" else " (saison $seasonNumber)"))
            addAll(errors.distinct())
        }.joinToString(" · ")
        val result = SeasonRatings(byNumber, if (fallback) emptyList() else numbers.map { byNumber[it] }, info)
        if (errors.isEmpty()) seasons[key] = result
        return result
    }

    private suspend fun resolve(titles: List<String>, errors: MutableList<String>): Ids {
        val key = titles.joinToString("|").lowercase()
        ids[key]?.let { return it }
        var tmdbId: Int? = null
        var imdbId: String? = null
        if (settings.tmdbKey.isNotBlank()) {
            for (title in titles) {
                val results = try {
                    tmdb("search/tv") { addQueryParameter("query", title) }
                        .let { Http.json.decodeFromString<TmdbSearch>(it).results }
                } catch (e: Exception) {
                    errors += "TMDB : ${e.message?.take(80)}"
                    break
                }
                val best = results
                    .take(8)
                    .maxByOrNull { show ->
                        val sim = listOf(show.name, show.originalName).maxOf { n -> titles.maxOf { TitleMatcher.similarity(it, n) } }
                        sim + (if (16 in show.genreIds) 0.2 else 0.0) + (if ("JP" in show.originCountry) 0.1 else 0.0)
                    }
                val bestSim = best?.let { b -> listOf(b.name, b.originalName).maxOf { n -> titles.maxOf { TitleMatcher.similarity(it, n) } } } ?: 0.0
                if (best != null && bestSim >= 0.5) {
                    tmdbId = best.id
                    break
                }
            }
            tmdbId?.let { id ->
                imdbId = runCatching {
                    Http.json.decodeFromString<TmdbExternalIds>(tmdb("tv/$id/external_ids") {}).imdbId
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
        if (imdbId == null && settings.omdbKey.isNotBlank()) {
            for (title in titles) {
                val found = try {
                    Http.json.decodeFromString<OmdbSeries>(omdb { addQueryParameter("t", title); addQueryParameter("type", "series") })
                } catch (e: Exception) {
                    errors += "OMDb : ${e.message?.take(80)}"
                    break
                }
                if (found.response == "True" && !found.imdbId.isNullOrBlank()) {
                    imdbId = found.imdbId
                    break
                }
            }
        }
        return Ids(imdbId, tmdbId).also { if (errors.isEmpty()) ids[key] = it }
    }

    private suspend fun omdbSeason(imdbId: String, season: Int): List<Pair<Int, EpisodeRating>> {
        if (settings.omdbKey.isBlank()) return emptyList()
        val json = omdb { addQueryParameter("i", imdbId); addQueryParameter("Season", season.toString()) }
        return Http.json.decodeFromString<OmdbSeason>(json).episodes.mapNotNull { ep ->
            val n = ep.episode.toIntOrNull() ?: return@mapNotNull null
            val r = ep.rating.toDoubleOrNull() ?: return@mapNotNull null
            n to EpisodeRating(r, "IMDb")
        }
    }

    private suspend fun tmdbSeason(tmdbId: Int, season: Int): List<Pair<Int, EpisodeRating>> {
        val json = tmdb("tv/$tmdbId/season/$season") {}
        return Http.json.decodeFromString<TmdbSeason>(json).episodes
            .filter { it.count > 0 && it.average > 0 }
            .map { it.number to EpisodeRating(it.average, "TMDB") }
    }

    private suspend fun omdb(params: HttpUrl.Builder.() -> Unit): String {
        val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", settings.omdbKey)
            .apply(params)
            .build()
        return Http.client.fetch(Request.Builder().url(url).build())
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

    fun clear() {
        ids.clear()
        seasons.clear()
    }
}
