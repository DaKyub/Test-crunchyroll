package com.dakyub.crunchymal.data.adn

import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import kotlinx.serialization.Serializable

@Serializable
data class AdnShow(
    val id: Int = 0,
    val title: String = "",
    val type: String? = null,
    val originalTitle: String? = null,
    val shortTitle: String? = null,
    val reference: String? = null,
    val summary: String? = null,
    val image: String? = null,
    val image2x: String? = null,
    val imageHorizontal: String? = null,
    val imageHorizontal2x: String? = null,
    val urlPath: String? = null,
    val episodeCount: Int = 0,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingsCount: Int? = null,
    val simulcast: Boolean = false,
    val nextVideoReleaseDate: String? = null,
) {
    fun toRef() = SeriesRef(
        id = id.toString(),
        title = title,
        posterUrl = image2x ?: image,
        wideUrl = imageHorizontal2x ?: imageHorizontal,
        provider = Provider.ADN,
        altTitles = listOfNotNull(originalTitle, shortTitle),
    )

    fun toCard() = CardItem(
        series = toRef(),
        subtitle = listOfNotNull(
            episodeCount.takeIf { it > 0 }?.let { "$it ép." },
            "simulcast".takeIf { simulcast },
        ).joinToString(" · ").ifBlank { null },
    )
}

@Serializable
data class AdnVideo(
    val id: Int = 0,
    val name: String? = null,
    val number: String? = null,
    val shortNumber: String? = null,
    val season: String? = null,
    /** Position dans la série. */
    val order: Int? = null,
    val image: String? = null,
    val image2x: String? = null,
    val summary: String? = null,
    val releaseDate: String? = null,
    val duration: Int = 0,
    val rating: Double? = null,
    val available: Boolean = true,
    /** Progression de l'utilisateur (renvoyée par la liste et l'historique quand on est connecté). */
    val user: AdnUserProgress? = null,
    val show: AdnShow? = null,
) {
    /** Libellé de saison : "1", "Saga 1 : East Blue", "Arc 1 : Bullet"… */
    val seasonKey: String get() = season?.takeIf { it.isNotBlank() } ?: "1"
    val seasonNumber: Int get() = Regex("\\d+").find(seasonKey)?.value?.toIntOrNull() ?: 1

    /**
     * Vu : marqué « fini » par ADN, ou regardé à 85 % au moins (ADN ne marque pas l'épisode quand on
     * passe au suivant pendant le générique de fin).
     */
    val watched: Boolean
        get() = user?.let { it.isFullyWatched || (duration > 0 && it.stoptime >= duration * WATCHED_RATIO) } == true
    val episodeNumber: Int? get() = shortNumber?.toIntOrNull()
    val label: String get() = listOfNotNull(number?.takeIf { it.isNotBlank() }, name?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** Part d'un épisode à partir de laquelle il compte comme vu. */
const val WATCHED_RATIO = 0.85

@Serializable
data class AdnUserProgress(
    val stoptime: Int = 0,
    val watchDate: String? = null,
    val isFullyWatched: Boolean = false,
)

@Serializable
internal data class AdnShowsResponse(val shows: List<AdnShow> = emptyList())

@Serializable
internal data class AdnShowResponse(val show: AdnShow = AdnShow())

@Serializable
internal data class AdnVideosResponse(val videos: List<AdnVideo> = emptyList())

@Serializable
internal data class AdnVideoResponse(val video: AdnVideo? = null)

@Serializable
internal data class AdnStatusResponse(val status: Boolean = false)

@Serializable
internal data class AdnLoginResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val message: String? = null,
    val code: String? = null,
)

/** Genres du catalogue ADN (identifiant API → libellé). */
val AdnGenres = listOf(
    "aventure--action" to "Action / Aventure",
    "arts_martiaux" to "Arts martiaux",
    "comedie" to "Comédie",
    "drame" to "Drame",
    "ecchi--fan_service" to "Ecchi",
    "fantastique--science-fiction" to "Fantastique / SF",
    "heroic_fantasy" to "Heroic fantasy",
    "historique" to "Historique",
    "jeunesse" to "Jeunesse",
    "josei" to "Josei",
    "mecha" to "Mecha",
    "musical" to "Musical",
    "nostalgie" to "Nostalgie",
    "policier--thriller" to "Policier / Thriller",
    "psychologie" to "Psychologique",
    "romance" to "Romance",
    "scolaire" to "Scolaire",
    "seinen" to "Seinen",
    "shojo" to "Shôjo",
    "shonen" to "Shônen",
    "sport" to "Sport",
)
