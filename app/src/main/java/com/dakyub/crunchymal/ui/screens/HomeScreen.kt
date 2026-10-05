package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import androidx.compose.runtime.LaunchedEffect
import com.dakyub.crunchymal.data.crunchyroll.CrPanel
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import android.net.Uri
import com.dakyub.crunchymal.data.crunchyroll.CrFeedItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class HomeRow(val key: String, val title: String, val items: List<CardItem>)

data class HomeState(val loading: Boolean = true, val rows: List<HomeRow> = emptyList(), val error: String? = null)

fun CrPanel.toSeriesCard(): CardItem = CardItem(
    series = SeriesRef(
        id = id,
        title = title,
        slug = slugTitle,
        posterUrl = images.posterTall.best(240),
        wideUrl = images.posterWide.best(400),
    ),
    subtitle = seriesMetadata?.let { m ->
        listOfNotNull(m.launchYear?.toString(), m.episodeCount.takeIf { it > 0 }?.let { "$it ép." }).joinToString(" · ")
    }?.ifBlank { null },
)

/** Carte "épisode" (historique, derniers épisodes) : vignette large + série associée. */
fun CrPanel.toEpisodeCard(playhead: Long = 0, fullyWatched: Boolean = false, fallbackSeriesId: String = ""): CardItem {
    val meta = episodeMetadata
    val duration = meta?.durationMs ?: 0
    return CardItem(
        series = SeriesRef(
            id = meta?.seriesId?.takeIf { it.isNotBlank() } ?: fallbackSeriesId,
            title = meta?.seriesTitle?.takeIf { it.isNotBlank() } ?: title,
            slug = meta?.seriesSlugTitle.orEmpty(),
            wideUrl = images.thumbnail.best(400),
        ),
        subtitle = "S${meta?.seasonNumber ?: "?"} E${meta?.episode?.ifBlank { null } ?: meta?.episodeNumber ?: "?"} · $title",
        episodeId = id,
        progress = when {
            fullyWatched -> 1f
            duration > 0 && playhead > 0 -> playhead * 1000f / duration
            else -> null
        },
        wide = true,
    )
}

fun CrPanel.toCard(): CardItem = if (type == "episode") toEpisodeCard() else toSeriesCard()

class HomeViewModel(private val graph: Graph) : ViewModel() {
    val state = MutableStateFlow(HomeState())
    private var job: Job? = null

    private var loadedFor: Set<Provider>? = null

    /** Charge (ou recharge si les services affichés ont changé). */
    fun ensure(providers: Set<Provider>) {
        if (providers != loadedFor) load()
    }

    fun load() {
        val providers = graph.providers.selected.value
        loadedFor = providers
        job?.cancel()
        job = viewModelScope.launch {
            state.value = HomeState(loading = true)
            val loaders = mutableListOf<Pair<String, suspend () -> List<CardItem>>>()
            if (Provider.CRUNCHYROLL in providers) {
                val feed = runCatching { graph.api.homeFeed() }.getOrNull()
                loaders += feed?.let { feedLoaders(it) }?.takeIf { it.isNotEmpty() } ?: fallbackLoaders()
            }
            if (Provider.ADN in providers) loaders += adnLoaders()

            // Les rangées s'affichent au fur et à mesure, dans l'ordre du fil officiel.
            val results = arrayOfNulls<List<CardItem>>(loaders.size)
            val done = BooleanArray(loaders.size)
            var firstError: String? = null
            fun publish() {
                val rows = loaders.indices.mapNotNull { i ->
                    results[i]?.takeIf { it.isNotEmpty() }?.let { HomeRow("$i", loaders[i].first, it) }
                }
                val finished = done.all { it }
                state.value = HomeState(
                    loading = !finished,
                    rows = rows,
                    error = if (finished && rows.isEmpty()) firstError ?: "Rien à afficher" else null,
                )
            }
            loaders.mapIndexed { i, (_, loader) ->
                launch {
                    runCatching { loader() }
                        .onSuccess { results[i] = it }
                        .onFailure { if (firstError == null) firstError = it.message }
                    done[i] = true
                    publish()
                }
            }.joinAll()
            publish()
        }
    }

    private fun row(block: suspend () -> List<CardItem>): suspend () -> List<CardItem> = block

    private fun continueWatching(): suspend () -> List<CardItem> = {
        graph.api.watchHistory()
            .filter { it.panel.type == "episode" }
            .distinctBy { it.panel.episodeMetadata?.seriesId ?: it.parentId }
            .take(20)
            .map { it.panel.toEpisodeCard(it.playhead, it.fullyWatched, it.parentId) }
    }

    private fun watchlistRow(): suspend () -> List<CardItem> = {
        graph.watchlist.get().take(30).map { e -> CardItem(series = e.series, episodeId = e.nextEpisodeId) }
    }

    private fun feedLoaders(feed: List<CrFeedItem>): List<Pair<String, suspend () -> List<CardItem>>> {
        val api = graph.api
        return feed.mapNotNull { item ->
            val title = item.title.ifBlank { item.sourceMediaTitle }
            when (item.resourceType) {
                "dynamic_collection" -> when (item.responseType) {
                    "history" -> title.ifBlank { "Continuer à regarder" } to continueWatching()
                    "watchlist" -> title.ifBlank { "Ma watchlist" } to watchlistRow()
                    "recommendations" -> title.ifBlank { "Recommandé pour vous" } to row {
                        api.recommendations().map { it.toCard() }
                    }
                    "because_you_watched" -> item.sourceMediaId.takeIf { it.isNotBlank() }?.let { id ->
                        title.ifBlank { "Parce que vous avez regardé" } to row { api.similarTo(id).map { it.toCard() } }
                    }
                    "browse", "recent_episodes" -> title to row {
                        api.browseWith(feedParams(item)).map { it.toCard() }
                    }
                    else -> null
                }
                "curated_collection" -> if (item.responseType == "series" && item.ids.isNotEmpty()) {
                    title to row { api.objects(item.ids).map { it.toCard() } }
                } else null
                else -> null
            }
        }
    }

    /** Paramètres de browse : query_params si présents, sinon ceux du lien (sans les variables de locale). */
    private fun feedParams(item: CrFeedItem): Map<String, String> {
        val fromJson = item.queryParams?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        }.orEmpty()
        val fromLink = item.link.substringAfter('?', "").split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val v = Uri.decode(part.substringAfter('=', ""))
            if (k.isBlank() || v.isBlank()) null else k to v
        }.toMap()
        return (fromLink + fromJson).filter { (k, v) ->
            k !in setOf("locale", "preferred_audio_language") && !v.contains('{')
        }
    }

    private fun adnLoaders(): List<Pair<String, suspend () -> List<CardItem>>> = listOf(
        "ADN · Simulcasts" to row { graph.adn.catalog(order = "popular", simulcastOnly = true, limit = 40).map { it.toCard() } },
        "ADN · Populaires" to row { graph.adn.catalog(order = "popular", limit = 40).map { it.toCard() } },
    )

    private fun fallbackLoaders(): List<Pair<String, suspend () -> List<CardItem>>> = listOf(
        "Continuer à regarder" to continueWatching(),
        "Ma watchlist" to watchlistRow(),
        "Populaires" to row { graph.api.browse("popularity").map { it.toSeriesCard() } },
        "Nouveautés" to row { graph.api.browse("newly_added").map { it.toSeriesCard() } },
    )
}

@Composable
fun HomeScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { HomeViewModel(graph) }
    val state by vm.state.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    LaunchedEffect(providers) { vm.ensure(providers) }
    val context = LocalContext.current

    when {
        state.loading && state.rows.isEmpty() -> CenteredMessage("Chargement…")
        state.error != null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(state.rows, key = { it.key }) { row ->
                Column {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(row.items) { item ->
                            MediaCard(
                                item = item,
                                onClick = {
                                    when {
                                        item.series.id.isNotBlank() -> onOpenSeries(item.series)
                                        item.episodeId != null -> OfficialApp.openEpisode(context, item.episodeId, null)
                                    }
                                },
                                onLongClick = item.episodeId?.let { id -> { OfficialApp.openEpisode(context, id, item.series.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
