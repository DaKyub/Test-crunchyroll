package com.dakyub.crunchymal.data.crunchyroll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CrListResponse<T>(
    val total: Int = 0,
    val data: List<T> = emptyList(),
    val items: List<T> = emptyList(),
) {
    val all: List<T> get() = data.ifEmpty { items }
}

@Serializable
data class CrImage(
    val source: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class CrImages(
    @SerialName("poster_tall") val posterTall: List<List<CrImage>> = emptyList(),
    @SerialName("poster_wide") val posterWide: List<List<CrImage>> = emptyList(),
    val thumbnail: List<List<CrImage>> = emptyList(),
)

/** Choisit la plus petite image dont la largeur dépasse [minWidth], sinon la plus grande. */
fun List<List<CrImage>>.best(minWidth: Int): String? {
    val all = flatten().filter { it.source.isNotBlank() }.sortedBy { it.width }
    return (all.firstOrNull { it.width >= minWidth } ?: all.lastOrNull())?.source
}

@Serializable
data class CrEpisodeMeta(
    @SerialName("series_id") val seriesId: String = "",
    @SerialName("series_title") val seriesTitle: String = "",
    @SerialName("series_slug_title") val seriesSlugTitle: String = "",
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val episode: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class CrSeriesMeta(
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("season_count") val seasonCount: Int = 0,
    @SerialName("series_launch_year") val launchYear: Int? = null,
)

/** Objet "panel" générique (browse, search, watchlist, historique, objects). */
@Serializable
data class CrPanel(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    @SerialName("slug_title") val slugTitle: String = "",
    val description: String = "",
    val images: CrImages = CrImages(),
    @SerialName("episode_metadata") val episodeMetadata: CrEpisodeMeta? = null,
    @SerialName("series_metadata") val seriesMetadata: CrSeriesMeta? = null,
)

@Serializable
data class CrWatchlistItem(
    val panel: CrPanel = CrPanel(),
    val playhead: Long = 0,
    @SerialName("fully_watched") val fullyWatched: Boolean = false,
    @SerialName("never_watched") val neverWatched: Boolean = false,
)

@Serializable
data class CrHistoryItem(
    val id: String = "",
    @SerialName("parent_id") val parentId: String = "",
    val panel: CrPanel = CrPanel(),
    val playhead: Long = 0,
    @SerialName("fully_watched") val fullyWatched: Boolean = false,
    @SerialName("date_played") val datePlayed: String = "",
)

@Serializable
data class CrSearchBucket(
    val type: String = "",
    val items: List<CrPanel> = emptyList(),
)

@Serializable
data class CrSeries(
    val id: String = "",
    val title: String = "",
    @SerialName("slug_title") val slugTitle: String = "",
    val description: String = "",
    val images: CrImages = CrImages(),
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("season_count") val seasonCount: Int = 0,
    @SerialName("series_launch_year") val launchYear: Int? = null,
)

@Serializable
data class CrVersion(
    @SerialName("audio_locale") val audioLocale: String = "",
    val guid: String = "",
    val original: Boolean = false,
)

@Serializable
data class CrSeason(
    val id: String = "",
    val title: String = "",
    @SerialName("slug_title") val slugTitle: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("season_sequence_number") val seasonSequenceNumber: Int = 0,
    @SerialName("audio_locale") val audioLocale: String = "",
    val versions: List<CrVersion> = emptyList(),
)

@Serializable
data class CrEpisode(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val episode: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("sequence_number") val sequenceNumber: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val images: CrImages = CrImages(),
    val versions: List<CrVersion> = emptyList(),
) {
    val label: String
        get() = "S$seasonNumber E${episode.ifBlank { episodeNumber?.toString() ?: "?" }}"
}

@Serializable
data class CrPlayhead(
    @SerialName("content_id") val contentId: String = "",
    val playhead: Long = 0,
    @SerialName("fully_watched") val fullyWatched: Boolean = false,
)

@Serializable
data class CrToken(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 300,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("account_id") val accountId: String = "",
)

@Serializable
data class CrDeviceCode(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Long = 300,
    val interval: Long = 5,
)

@Serializable
data class CrOAuthError(val error: String = "", @SerialName("error_description") val description: String = "")

@Serializable
data class CrAccount(@SerialName("account_id") val accountId: String = "")

/** Élément du fil d'accueil de l'app officielle (home_feed). */
@Serializable
data class CrFeedItem(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("resource_type") val resourceType: String = "",
    @SerialName("response_type") val responseType: String = "",
    val link: String = "",
    val ids: List<String> = emptyList(),
    @SerialName("source_media_id") val sourceMediaId: String = "",
    @SerialName("source_media_title") val sourceMediaTitle: String = "",
    @SerialName("query_params") val queryParams: kotlinx.serialization.json.JsonObject? = null,
)
