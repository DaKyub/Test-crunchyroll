package com.dakyub.crunchymal.data

/** Référence minimale d'une série pour l'affichage (carte, badge MAL, navigation). */
data class SeriesRef(
    val id: String,
    val title: String,
    val slug: String = "",
    val posterUrl: String? = null,
    val wideUrl: String? = null,
) {
    /** Titres utilisés pour chercher sur MAL : le slug est en anglais quelle que soit la langue choisie. */
    val malTitles: List<String>
        get() = listOf(slug.replace('-', ' '), title).filter { it.isNotBlank() }.distinct()

    val malKey: String get() = "series:$id"
}

/** Carte affichée dans les rangées / grilles. */
data class CardItem(
    val series: SeriesRef,
    val subtitle: String? = null,
    val episodeId: String? = null,
    val progress: Float? = null,
    val wide: Boolean = false,
) {
    val imageUrl: String? get() = if (wide) series.wideUrl ?: series.posterUrl else series.posterUrl ?: series.wideUrl
}
