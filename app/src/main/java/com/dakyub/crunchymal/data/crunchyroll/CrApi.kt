package com.dakyub.crunchymal.data.crunchyroll

import kotlinx.serialization.decodeFromString
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.data.fetch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class CrApi(private val auth: CrAuth, private val settings: Settings) {

    private val account get() = auth.accountId

    /** [path] peut contenir une query déjà encodée ; la locale est ajoutée automatiquement. */
    private suspend fun call(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): String {
        val url = "${CrConfig.BASE}$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("locale", settings.locale)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        suspend fun build(force: Boolean) = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader(force))
            .header("User-Agent", settings.userAgent)
            .method(method, body)
            .build()

        return try {
            Http.client.fetch(build(false))
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            Http.client.fetch(build(true))
        }
    }

    private suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T =
        Http.json.decodeFromString(call("GET", path, params))

    suspend fun watchlist(): List<CrWatchlistItem> =
        get<CrListResponse<CrWatchlistItem>>(
            "/content/v2/discover/$account/watchlist",
            mapOf("order" to "desc", "n" to "500", "preferred_audio_language" to settings.preferredAudio),
        ).all

    suspend fun watchHistory(pageSize: Int = 60): List<CrHistoryItem> =
        get<CrListResponse<CrHistoryItem>>(
            "/content/v2/$account/watch-history",
            mapOf("page_size" to pageSize.toString()),
        ).all

    suspend fun browse(sortBy: String, n: Int = 30): List<CrPanel> =
        get<CrListResponse<CrPanel>>(
            "/content/v2/discover/browse",
            mapOf("sort_by" to sortBy, "n" to n.toString(), "type" to "series"),
        ).all

    suspend fun search(query: String, n: Int = 40): List<CrPanel> =
        get<CrListResponse<CrSearchBucket>>(
            "/content/v2/discover/search",
            mapOf("q" to query, "n" to n.toString(), "type" to "series"),
        ).all.filter { it.type == "series" }.flatMap { it.items }

    /** Objets CMS par identifiants (sert à récupérer les affiches des séries). */
    suspend fun objects(ids: List<String>): List<CrPanel> =
        ids.filter { it.isNotBlank() }.distinct().chunked(40).flatMap { chunk ->
            get<CrListResponse<CrPanel>>("/content/v2/cms/objects/${chunk.joinToString(",")}").all
        }

    suspend fun series(id: String): CrSeries =
        get<CrListResponse<CrSeries>>("/content/v2/cms/series/$id").all.first()

    suspend fun seasons(seriesId: String): List<CrSeason> =
        get<CrListResponse<CrSeason>>(
            "/content/v2/cms/series/$seriesId/seasons",
            mapOf("preferred_audio_language" to settings.preferredAudio),
        ).all

    suspend fun episodes(seasonId: String): List<CrEpisode> =
        get<CrListResponse<CrEpisode>>("/content/v2/cms/seasons/$seasonId/episodes").all

    suspend fun playheads(contentIds: List<String>): List<CrPlayhead> =
        contentIds.filter { it.isNotBlank() }.distinct().chunked(50).flatMap { chunk ->
            get<CrListResponse<CrPlayhead>>("/content/v2/$account/playheads?content_ids=${chunk.joinToString(",")}").all
        }

    suspend fun isInWatchlist(contentId: String): Boolean =
        get<CrListResponse<kotlinx.serialization.json.JsonObject>>(
            "/content/v2/$account/watchlist?content_ids=$contentId",
        ).all.isNotEmpty()

    suspend fun addToWatchlist(contentId: String) {
        require(contentId.isNotBlank())
        val body = buildJsonObject { put("content_id", contentId) }.toString()
            .toRequestBody("application/json".toMediaType())
        call("POST", "/content/v2/$account/watchlist", body = body)
    }

    suspend fun removeFromWatchlist(contentId: String) {
        // Sans identifiant, cet endpoint viderait toute la watchlist !
        require(contentId.isNotBlank())
        call("DELETE", "/content/v2/$account/watchlist/$contentId")
    }
}
