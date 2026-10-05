package com.dakyub.crunchymal.data.mal

import kotlinx.serialization.decodeFromString
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

@Serializable
data class JikanTitle(val type: String = "", val title: String = "")

@Serializable
data class JikanAired(val from: String? = null)

@Serializable
data class JikanAnime(
    @SerialName("mal_id") val malId: Int = 0,
    val url: String = "",
    val title: String = "",
    @SerialName("title_english") val titleEnglish: String? = null,
    @SerialName("title_japanese") val titleJapanese: String? = null,
    @SerialName("title_synonyms") val synonyms: List<String> = emptyList(),
    val titles: List<JikanTitle> = emptyList(),
    val score: Double? = null,
    @SerialName("scored_by") val scoredBy: Int? = null,
    val type: String? = null,
    val episodes: Int? = null,
    val year: Int? = null,
    val aired: JikanAired? = null,
) {
    val allTitles: List<String>
        get() = (listOf(title, titleEnglish, titleJapanese) + synonyms + titles.map { it.title })
            .filterNotNull().filter { it.isNotBlank() }.distinct()

    val startYear: Int? get() = year ?: aired?.from?.take(4)?.toIntOrNull()
}

@Serializable
private data class JikanList(val data: List<JikanAnime> = emptyList())

@Serializable
private data class JikanSingle(val data: JikanAnime = JikanAnime())

/** Client Jikan (API non officielle de MyAnimeList, sans clé). Limite : ~3 req/s et 60 req/min. */
class JikanApi {
    private val gate = Mutex()
    private var lastCall = 0L

    private suspend fun get(url: String): String {
        var attempt = 0
        while (true) {
            val result = gate.withLock {
                val wait = lastCall + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
                runCatching { Http.client.fetch(Request.Builder().url(url).build()) }
            }
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            val retryable = error !is HttpException || error.code == 429 || error.code >= 500
            if (!retryable || ++attempt >= 4) throw error
            delay(1500L * attempt)
        }
    }

    suspend fun search(query: String, limit: Int = 10): List<JikanAnime> {
        val url = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(100))
            .addQueryParameter("limit", limit.toString())
            .build().toString()
        return Http.json.decodeFromString<JikanList>(get(url)).data
    }

    suspend fun anime(malId: Int): JikanAnime =
        Http.json.decodeFromString<JikanSingle>(get("https://api.jikan.moe/v4/anime/$malId")).data

    private companion object {
        const val MIN_INTERVAL_MS = 1100L
    }
}
