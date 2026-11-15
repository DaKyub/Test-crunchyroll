package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph

/**
 * Résumé d'une fiche Crunchyroll / ADN / Plex : celui de TMDB en français en priorité (le catalogue de
 * Crunchyroll a des résumés qui parlent parfois d'une autre série), sinon celui du service ([primary]),
 * sinon celui de MAL en anglais. La source est indiquée ; avec TMDB, le résumé du service ([service]) reste
 * lisible en dépliant le texte. [titles] : titres pour trouver la série sur TMDB, [malId] : fiche MAL.
 */
@Composable
fun SynopsisWithFallback(
    primary: String?,
    titles: List<String>,
    malId: Int?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    movie: Boolean = false,
    service: String = "du service",
) {
    val graph = LocalGraph.current
    val searchTitles = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val hasTmdb = graph.settings.tmdbKey.isNotBlank()
    // "" : TMDB n'a rien (ou pas de clé) ; null : recherche en cours (rien d'affiché, pour éviter un changement de texte).
    val french by produceState<String?>(if (hasTmdb) null else "", searchTitles, movie) {
        if (hasTmdb) value = runCatching { graph.watchPlatforms.frenchOverview(searchTitles, movie) }.getOrNull().orEmpty()
    }
    val tmdb = french ?: return
    val own = primary?.takeIf { it.isNotBlank() }
    val loadMal: (suspend () -> String?)? = malId?.let { id -> { graph.mal.synopsis(id) } }
    when {
        tmdb.isNotBlank() -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Déplié (OK), le résumé du service suit celui de TMDB.
            val text = if (own != null && own.trim() != tmdb.trim()) "$tmdb\n\n— Résumé $service —\n$own" else tmdb
            ExpandableSynopsis(text, onFocused = onFocused)
            Text(
                "Résumé TMDB" + if (own != null) " · OK pour lire aussi celui $service" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        own != null -> ExpandableSynopsis(own, onFocused = onFocused, modifier = modifier, loadFull = loadMal)
        else -> {
            val mal by produceState<String?>(null, malId) { value = malId?.let { id -> runCatching { graph.mal.synopsis(id) }.getOrNull() } }
            val text = mal ?: return
            Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ExpandableSynopsis(text, onFocused = onFocused)
                Text("Résumé MyAnimeList (en anglais)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Résumé d'une fiche MAL (ouverte depuis la liste MAL, Netflix…) : celui de TMDB en français s'il existe,
 * sinon celui de MAL ([english]). Rien n'est affiché le temps de la recherche TMDB, pour éviter que le
 * texte anglais apparaisse puis change.
 */
@Composable
fun FrenchFirstSynopsis(english: String?, titles: List<String>, movie: Boolean, onFocused: () -> Unit, collapsedLines: Int = 4) {
    val graph = LocalGraph.current
    val searchTitles = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val french by produceState<String?>(if (graph.settings.tmdbKey.isBlank()) "" else null, searchTitles, movie) {
        if (graph.settings.tmdbKey.isNotBlank()) {
            value = runCatching { graph.watchPlatforms.frenchOverview(searchTitles, movie) }.getOrNull().orEmpty()
        }
    }
    val text = french ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            text.isNotBlank() -> {
                ExpandableSynopsis(text, onFocused = onFocused, collapsedLines = collapsedLines)
                Text("Résumé TMDB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !english.isNullOrBlank() -> ExpandableSynopsis(english, onFocused = onFocused, collapsedLines = collapsedLines)
        }
    }
}
