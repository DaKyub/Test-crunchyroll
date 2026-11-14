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
 * Résumé d'une fiche : celui du service ([primary]) ; s'il est vide, celui de TMDB en français, sinon celui
 * de MAL en anglais, avec la source indiquée. [titles] : titres pour trouver la série sur TMDB (anglais
 * ou d'origine d'abord), [malId] : fiche MAL de la série.
 */
@Composable
fun SynopsisWithFallback(
    primary: String?,
    titles: List<String>,
    malId: Int?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    movie: Boolean = false,
) {
    val graph = LocalGraph.current
    val loadMal: (suspend () -> String?)? = malId?.let { id -> { graph.mal.synopsis(id) } }
    if (!primary.isNullOrBlank()) {
        ExpandableSynopsis(primary, onFocused = onFocused, modifier = modifier, loadFull = loadMal)
        return
    }
    val searchTitles = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val fallback by produceState<Pair<String, String>?>(null, searchTitles, malId) {
        value = runCatching { graph.watchPlatforms.frenchOverview(searchTitles, movie) }.getOrNull()?.let { it to "Résumé TMDB" }
            ?: malId?.let { id -> runCatching { graph.mal.synopsis(id) }.getOrNull() }?.let { it to "Résumé MyAnimeList (en anglais)" }
    }
    val (text, source) = fallback ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExpandableSynopsis(text, onFocused = onFocused)
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
