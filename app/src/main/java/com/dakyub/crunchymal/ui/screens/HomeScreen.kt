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
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.crunchyroll.CrPanel
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class HomeRow(val title: String, val items: List<CardItem>)

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

class HomeViewModel(private val graph: Graph) : ViewModel() {
    val state = MutableStateFlow(HomeState())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            val api = graph.api
            val continueRow = async {
                runCatching {
                    api.watchHistory()
                        .filter { it.panel.type == "episode" }
                        .distinctBy { it.panel.episodeMetadata?.seriesId ?: it.parentId }
                        .take(20)
                        .map { h ->
                            val meta = h.panel.episodeMetadata
                            val duration = meta?.durationMs ?: 0
                            CardItem(
                                series = SeriesRef(
                                    id = meta?.seriesId?.takeIf { it.isNotBlank() } ?: h.parentId,
                                    title = meta?.seriesTitle ?: h.panel.title,
                                    slug = meta?.seriesSlugTitle.orEmpty(),
                                    wideUrl = h.panel.images.thumbnail.best(400),
                                ),
                                subtitle = "S${meta?.seasonNumber ?: "?"} E${meta?.episode?.ifBlank { null } ?: meta?.episodeNumber ?: "?"} · ${h.panel.title}",
                                episodeId = h.panel.id,
                                progress = if (h.fullyWatched) 1f else if (duration > 0) h.playhead * 1000f / duration else null,
                                wide = true,
                            )
                        }
                }
            }
            val watchlistRow = async {
                runCatching {
                    graph.watchlist.get().take(30).map { e ->
                        CardItem(series = e.series, episodeId = e.nextEpisodeId)
                    }
                }
            }
            val popular = async { runCatching { api.browse("popularity").map { it.toSeriesCard() } } }
            val newest = async { runCatching { api.browse("newly_added").map { it.toSeriesCard() } } }

            val results = listOf(
                "Continuer à regarder" to continueRow.await(),
                "Ma watchlist" to watchlistRow.await(),
                "Populaires" to popular.await(),
                "Nouveautés" to newest.await(),
            )
            val rows = results.mapNotNull { (title, r) -> r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { HomeRow(title, it) } }
            val error = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.message
            state.value = HomeState(loading = false, rows = rows, error = if (rows.isEmpty()) error ?: "Rien à afficher" else null)
        }
    }
}

@Composable
fun HomeScreen(onOpenSeries: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { HomeViewModel(graph) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    when {
        state.loading && state.rows.isEmpty() -> CenteredMessage("Chargement…")
        state.error != null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(state.rows, key = { it.title }) { row ->
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
                                onClick = { onOpenSeries(item.series.id) },
                                onLongClick = item.episodeId?.let { id -> { OfficialApp.openEpisode(context, id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
