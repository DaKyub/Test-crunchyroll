package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.ratings.SeasonRatings

/** Genres affichés sous le titre d'une fiche (MAL en priorité, sinon ceux du service). */
@Composable
fun GenresLine(malGenres: List<String>?, fallback: List<String>, modifier: Modifier = Modifier) {
    val genres = malGenres?.takeIf { it.isNotEmpty() } ?: fallback
    if (genres.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        val source = if (!malGenres.isNullOrEmpty()) "MAL" else null
        genres.take(8).forEach { genre ->
            Text(
                genre,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        source?.let {
            Text("($it)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Notes IMDb / TMDB d'une saison (vide tant que rien n'est chargé ou si aucune clé n'est configurée). */
@Composable
fun rememberSeasonRatings(titles: List<String>, seasonNumber: Int, episodeCount: Int = 0, episodesBefore: Int = 0): SeasonRatings {
    val ratings = LocalGraph.current.ratings
    val value = produceState(SeasonRatings.EMPTY, titles, seasonNumber, episodeCount, episodesBefore) {
        value = ratings.season(titles, seasonNumber, episodeCount, episodesBefore)
    }
    return value.value
}
