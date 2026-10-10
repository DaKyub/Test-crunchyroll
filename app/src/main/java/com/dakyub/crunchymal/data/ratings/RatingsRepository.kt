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

/**
 * Notes d'une saison, par numéro d'épisode relatif (1 = premier épisode de la saison), quelle que soit
 * la numérotation des sources (par saison ou continue).
 */
data class SeasonRatings(
    val byRelative: Map<Int, EpisodeRating>,
    /** Ligne de diagnostic affichée sous les épisodes (sources trouvées, erreurs…). */
    val info: String = "",
) {
    /**
     * [number] : numéro de l'épisode dans l'app (peut être continu : 23, 24… pour une saison 2) ;
     * [firstNumber] : celui du premier épisode de la saison ; [position] sert si l'épisode n'a pas de numéro.
     */
    fun find(number: Int?, position: Int, firstNumber: Int = 1): EpisodeRating? =
        if (number != null) byRelative[number - firstNumber + 1] else byRelative[position + 1]

    companion object {
        val EMPTY = SeasonRatings(emptyMap())
    }
}

/** Premier numéro d'épisode (≥ 1) d'une saison, pour [SeasonRatings.find]. */
fun firstEpisodeNumber(numbers: List<Int?>): Int = numbers.filterNotNull().filter { it >= 1 }.minOrNull() ?: 1

/** Épisodes d'une saison chez une source : numéros listés (notés ou non) et notes. */
private data class SourceSeason(val listed: List<Int>, val ratings: List<Pair<Int, EpisodeRating>>)

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

    /**
     * [episodeCount] : épisodes de la saison dans l'app ; [episodesBefore] : épisodes des saisons
     * précédentes. Ils servent quand IMDb/TMDB rangent toute la série en « saison 1 » (numérotation continue).
     */
    suspend fun season(titles: List<String>, seasonNumber: Int, episodeCount: Int = 0, episodesBefore: Int = 0): SeasonRatings {
        if (!configured) return SeasonRatings(emptyMap(), "Notes IMDb/TMDB : aucune clé (Paramètres → Notes des épisodes)")
        if (titles.isEmpty()) return SeasonRatings.EMPTY
        val key = titles.joinToString("|").lowercase() + "#$seasonNumber#$episodeCount#$episodesBefore"
        seasons[key]?.let { return it }
        val errors = mutableListOf<String>()
        val seriesIds = resolve(titles, errors)
        if (seriesIds.imdb == null && seriesIds.tmdb == null) {
            return SeasonRatings(emptyMap(), (listOf("Notes : série introuvable sur IMDb/TMDB") + errors).joinToString(" · "))
                .also { if (errors.isEmpty()) seasons[key] = it }
        }

        val imdb = seriesIds.imdb?.let { id ->
            relative("IMDb", seasonNumber, episodeCount, episodesBefore, errors) { omdbSeason(id, it) }
        }
        val tmdb = seriesIds.tmdb?.let { id ->
            relative("TMDB", seasonNumber, episodeCount, episodesBefore, errors) { tmdbSeason(id, it) }
        }
        // IMDb en priorité, TMDB pour les épisodes qu'IMDb ne note pas.
        val byRelative = tmdb?.first.orEmpty() + imdb?.first.orEmpty()
        val info = buildList {
            add("Notes : ${byRelative.size}${if (episodeCount > 0) "/$episodeCount" else ""} ép. notés (saison $seasonNumber)")
            add("IMDb ${seriesIds.imdb ?: "—"}${imdb?.second?.let { " $it" }.orEmpty()}")
            add("TMDB ${seriesIds.tmdb ?: "—"}${tmdb?.second?.let { " $it" }.orEmpty()}")
            addAll(errors.distinct())
        }.joinToString(" · ")
        val result = SeasonRatings(byRelative, info)
        if (errors.isEmpty()) seasons[key] = result
        return result
    }

    /**
     * Notes d'une source en numérotation relative, avec le détail pour le diagnostic. Prend la saison
     * demandée ; si elle manque ou est incomplète, essaie la saison 1 en numérotation continue
     * (épisodes [before] + 1 à [before] + [count]) et garde celle qui a le plus de notes.
     */
    private suspend fun relative(
        source: String,
        seasonNumber: Int,
        count: Int,
        before: Int,
        errors: MutableList<String>,
        fetch: suspend (Int) -> SourceSeason,
    ): Pair<Map<Int, EpisodeRating>, String> {
        suspend fun load(season: Int): SourceSeason? = runCatching { fetch(season) }
            .onFailure { if (it !is HttpException || it.code != 404) errors += "$source : ${it.message?.take(80)}" }
            .getOrNull()

        val direct = load(seasonNumber)
        // Saison numérotée à partir de 13, 23… chez la source : ramenée à 1.
        val directFirst = direct?.listed?.filter { it >= 1 }?.minOrNull() ?: 1
        val directRel = direct?.ratings.orEmpty().associate { (n, r) -> (n - directFirst + 1) to r }
        var best = directRel
        var detail = "${directRel.size} notes / ${direct?.listed?.size ?: 0} ép."

        val incomplete = direct == null || direct.listed.size < maxOf(1, count * 6 / 10)
        if (seasonNumber > 1 && incomplete) {
            val first = load(1)
            if (first != null) {
                val absolute = if (before > 0 && count > 0) {
                    first.ratings.filter { it.first in (before + 1)..(before + count) }.associate { (n, r) -> (n - before) to r }
                } else if (direct == null) {
                    first.ratings.toMap()
                } else emptyMap()
                if (absolute.size > best.size) {
                    best = absolute
                    detail = "${absolute.size} notes (saison 1 en numérotation continue)"
                }
            }
        }
        return best to detail
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

    private suspend fun omdbSeason(imdbId: String, season: Int): SourceSeason {
        if (settings.omdbKey.isBlank()) return SourceSeason(emptyList(), emptyList())
        val json = omdb { addQueryParameter("i", imdbId); addQueryParameter("Season", season.toString()) }
        val episodes = Http.json.decodeFromString<OmdbSeason>(json).episodes
        return SourceSeason(
            listed = episodes.mapNotNull { it.episode.toIntOrNull() },
            ratings = episodes.mapNotNull { ep ->
                val n = ep.episode.toIntOrNull() ?: return@mapNotNull null
                val r = ep.rating.toDoubleOrNull() ?: return@mapNotNull null
                n to EpisodeRating(r, "IMDb")
            },
        )
    }

    private suspend fun tmdbSeason(tmdbId: Int, season: Int): SourceSeason {
        val json = tmdb("tv/$tmdbId/season/$season") {}
        val episodes = Http.json.decodeFromString<TmdbSeason>(json).episodes
        return SourceSeason(
            listed = episodes.map { it.number },
            ratings = episodes.filter { it.count > 0 && it.average > 0 }.map { it.number to EpisodeRating(it.average, "TMDB") },
        )
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
