package com.dakyub.crunchymal.data

/** Service de streaming d'où vient un contenu. */
enum class Provider(val label: String, val badge: String) {
    CRUNCHYROLL("Crunchyroll", "CR"),
    ADN("ADN", "ADN"),
}

/** Référence minimale d'une série pour l'affichage (carte, badge MAL, navigation). */
data class SeriesRef(
    val id: String,
    val title: String,
    val slug: String = "",
    val posterUrl: String? = null,
    val wideUrl: String? = null,
    val provider: Provider = Provider.CRUNCHYROLL,
    /** Titres supplémentaires pour MAL (titre original ADN, etc.). */
    val altTitles: List<String> = emptyList(),
    /** Autres services proposant la même série (fusion "Les deux"). */
    val alsoOn: Set<Provider> = emptySet(),
    /** Clé MAL imposée (fiche déjà connue, ex. liste MAL de l'utilisateur). */
    val malKeyOverride: String? = null,
) {
    /** Titres utilisés pour chercher sur MAL : le slug est en anglais quelle que soit la langue choisie. */
    val malTitles: List<String>
        get() = (listOf(slug.replace('-', ' ')) + altTitles + title).filter { it.isNotBlank() }.distinct()

    val malKey: String
        get() = malKeyOverride ?: when (provider) {
            Provider.CRUNCHYROLL -> "series:$id"
            Provider.ADN -> "adn:$id"
        }

    /** Clé de progression / watchlist, unique tous services confondus. */
    val progressKey: String
        get() = when (provider) {
            Provider.CRUNCHYROLL -> id
            Provider.ADN -> "adn:$id"
        }

    /** Route de navigation vers la fiche. */
    val route: String get() = "series/${provider.name}/$id"
}

/** Carte affichée dans les rangées / grilles. */
data class CardItem(
    val series: SeriesRef,
    val subtitle: String? = null,
    val episodeId: String? = null,
    val progress: Float? = null,
    val wide: Boolean = false,
    /** Plateformes affichées en badges sur l'affiche ("Crunchyroll", "ADN", "Netflix"…). */
    val badges: List<String> = emptyList(),
) {
    val imageUrl: String? get() = if (wide) series.wideUrl ?: series.posterUrl else series.posterUrl ?: series.wideUrl
}
