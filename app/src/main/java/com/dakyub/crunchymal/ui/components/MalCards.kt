package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.Availability
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.WatchPlatforms
import com.dakyub.crunchymal.data.mal.MalAnime

/** Carte d'un anime MAL, avec la fiche Crunchyroll / ADN à ouvrir si l'app l'a trouvée. */
data class MalCardItem(val malId: Int, val card: CardItem, val target: SeriesRef?)

/** Badges : Crunchyroll / ADN trouvés par l'app, puis les autres plateformes (TMDB France ou MAL). */
fun malCardItem(
    anime: MalAnime,
    availability: Availability?,
    platforms: WatchPlatforms?,
    providers: Set<Provider>,
    crunchyrollUsable: Boolean,
    subtitle: String?,
): MalCardItem {
    val badges = listOfNotNull(
        "Crunchyroll".takeIf { availability?.crunchyroll != null },
        "ADN".takeIf { availability?.adn != null },
    ) + platforms?.names.orEmpty().filter { it != "Crunchyroll" && it != "ADN" }
    return MalCardItem(
        malId = anime.malId,
        card = CardItem(
            series = SeriesRef(
                id = "mal-${anime.malId}",
                title = anime.titleEnglish ?: anime.title,
                posterUrl = anime.pictureUrl,
                malKeyOverride = "mal:${anime.malId}",
            ),
            subtitle = subtitle,
            badges = badges,
        ),
        target = availability?.refFor(providers, crunchyrollUsable),
    )
}

/** Lance la recherche des plateformes d'animes MAL et enregistre leur note (badge MAL immédiat). */
suspend fun Graph.prepareMalAnime(list: List<MalAnime>) {
    val crEnabled = Provider.CRUNCHYROLL in providers.selected.value && auth.loggedIn.value
    list.forEach { anime ->
        mal.seed("mal:${anime.malId}", anime)
        availability.request(anime, crEnabled)
        watchPlatforms.request(anime)
    }
}

/**
 * Rangée « Si tu as aimé » : recommandations des membres MAL pour [malId], celles que tu n'as pas encore
 * vues (d'après ta liste MAL) en premier, avec où les regarder.
 */
@Composable
fun MalRecommendationsRow(malId: Int?, onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    if (malId == null) return
    val graph = LocalGraph.current
    val recommendations by produceState<List<MalAnime>?>(null, malId) {
        value = runCatching { graph.mal.recommendations(malId) }.getOrDefault(emptyList())
    }
    val found by graph.availability.found.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val seen by graph.mal.myListSeenIds.collectAsState()
    LaunchedEffect(recommendations) {
        recommendations?.let { graph.prepareMalAnime(it) }
        graph.mal.refreshMyListSeenIds()
    }
    val list = recommendations
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Si tu as aimé cette série (recommandations MAL)", style = MaterialTheme.typography.titleMedium)
        when {
            list == null -> Text("Chargement…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("Aucune recommandation sur MAL.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val items = list
                    .sortedBy { it.malId in seen }
                    .map { anime ->
                        malCardItem(
                            anime,
                            found[anime.malId],
                            platforms[anime.malId],
                            providers,
                            crLoggedIn,
                            subtitle = if (anime.malId in seen) "✓ dans ta liste MAL" else airingLabel(anime.airing),
                        )
                    }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(items, key = { it.malId }) { item ->
                        MediaCard(
                            item = item.card,
                            showProviderBadge = false,
                            onClick = { item.target?.let(onOpenSeries) ?: onOpenMal(item.malId) },
                        )
                    }
                }
            }
        }
    }
}
