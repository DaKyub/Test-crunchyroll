package com.dakyub.crunchymal.data.mal

import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Fiche MAL simplifiée utilisée dans l'app. */
data class MalAnime(
    val malId: Int,
    val title: String,
    val titleEnglish: String?,
    val titleJapanese: String?,
    val synonyms: List<String>,
    val score: Double?,
    val scoredBy: Int?,
    val type: String?,
    val episodes: Int?,
    val startYear: Int?,
    val genres: List<String> = emptyList(),
) {
    val url: String get() = "https://myanimelist.net/anime/$malId"

    val allTitles: List<String>
        get() = (listOf(title, titleEnglish, titleJapanese) + synonyms)
            .filterNotNull().filter { it.isNotBlank() }.distinct()
}

@Serializable
private data class AltTitles(
    val synonyms: List<String> = emptyList(),
    val en: String? = null,
    val ja: String? = null,
)

@Serializable
private data class Genre(val name: String = "")

@Serializable
private data class Node(
    val id: Int = 0,
    val title: String = "",
    @SerialName("alternative_titles") val alternativeTitles: AltTitles? = null,
    val mean: Double? = null,
    @SerialName("num_scoring_users") val numScoringUsers: Int? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    val genres: List<Genre> = emptyList(),
) {
    fun toAnime() = MalAnime(
        malId = id,
        title = title,
        titleEnglish = alternativeTitles?.en?.takeIf { it.isNotBlank() },
        titleJapanese = alternativeTitles?.ja?.takeIf { it.isNotBlank() },
        synonyms = alternativeTitles?.synonyms.orEmpty(),
        score = mean,
        scoredBy = numScoringUsers,
        type = mediaType,
        episodes = numEpisodes?.takeIf { it > 0 },
        startYear = startDate?.take(4)?.toIntOrNull(),
        genres = genres.map { it.name }.filter { it.isNotBlank() },
    )
}

@Serializable
private data class Wrapper(val node: Node = Node())

@Serializable
private data class SearchResponse(val data: List<Wrapper> = emptyList())

class MissingMalClientIdException : Exception("Client ID MyAnimeList manquant (Paramètres → MyAnimeList)")

/**
 * API officielle MyAnimeList v2 (https://myanimelist.net/apiconfig).
 * Lecture publique : seul l'en-tête X-MAL-CLIENT-ID est requis.
 */
class MalApi(private val clientId: () -> String) {
    private val gate = Mutex()
    private var lastCall = 0L

    private suspend fun get(url: String): String {
        val id = clientId().trim()
        if (id.isBlank()) throw MissingMalClientIdException()
        var attempt = 0
        while (true) {
            val result = gate.withLock {
                val wait = lastCall + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
                runCatching {
                    Http.client.fetch(Request.Builder().url(url).header("X-MAL-CLIENT-ID", id).build())
                }
            }
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            val retryable = error !is HttpException || error.code == 429 || error.code >= 500
            if (!retryable || ++attempt >= 4) throw error
            delay(2000L * attempt)
        }
    }

    suspend fun search(query: String, limit: Int = 10): List<MalAnime> {
        // L'API refuse les requêtes trop longues ou trop courtes.
        val q = query.replace(Regex("""[^\p{L}\p{N} ]+"""), " ").replace(Regex("""\s+"""), " ").trim().take(60)
        if (q.length < 3) return emptyList()
        val url = "$BASE/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("nsfw", "true")
            .addQueryParameter("fields", FIELDS)
            .build().toString()
        return Http.json.decodeFromString<SearchResponse>(get(url)).data.map { it.node.toAnime() }
    }

    suspend fun anime(malId: Int): MalAnime {
        val url = "$BASE/anime/$malId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FIELDS)
            .build().toString()
        return Http.json.decodeFromString<Node>(get(url)).toAnime()
    }

    private companion object {
        const val BASE = "https://api.myanimelist.net/v2"
        const val FIELDS = "id,title,alternative_titles,mean,num_scoring_users,media_type,num_episodes,start_date,genres"
        const val MIN_INTERVAL_MS = 400L
    }
}
