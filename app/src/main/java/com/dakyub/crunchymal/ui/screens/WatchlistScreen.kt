package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.WatchlistEntry
import com.dakyub.crunchymal.data.progress.WatchStatus
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterWidth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StatusFilter(val label: String, val status: WatchStatus?) {
    ALL("Toutes", null),
    NOT_STARTED("Non commencées", WatchStatus.NOT_STARTED),
    IN_PROGRESS("En cours", WatchStatus.IN_PROGRESS),
    COMPLETED("Terminées / à jour", WatchStatus.COMPLETED),
}

enum class SortMode(val label: String) { RECENT("Récentes"), MAL("Note MAL"), TITLE("Titre A→Z") }

val MinScores = listOf<Double?>(null, 7.0, 7.5, 8.0, 8.5)

data class WatchlistFilters(
    val status: StatusFilter = StatusFilter.ALL,
    val sort: SortMode = SortMode.RECENT,
    val minScore: Double? = null,
)

data class WatchlistUi(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<CardItem> = emptyList(),
    val total: Int = 0,
    val progressKnown: Int = 0,
    val malKnown: Int = 0,
    val filters: WatchlistFilters = WatchlistFilters(),
)

private data class Loaded(val loading: Boolean = true, val error: String? = null, val entries: List<WatchlistEntry> = emptyList())

class WatchlistViewModel(private val graph: Graph) : ViewModel() {
    private val loaded = MutableStateFlow(Loaded())
    private val filters = MutableStateFlow(WatchlistFilters())

    val ui = combine(loaded, filters, graph.progress.summaries, graph.mal.records) { l, f, summaries, mal ->
        val rows = l.entries.map { e ->
            val summary = summaries[e.series.id]
            val status = summary?.status ?: if (e.neverWatched) WatchStatus.NOT_STARTED else null
            Triple(e, status, mal[e.series.malKey]?.score)
        }
        val filtered = rows
            .filter { (_, status, _) -> f.status.status == null || status == f.status.status }
            .filter { (_, _, score) -> f.minScore == null || (score != null && score >= f.minScore) }
        val sorted = when (f.sort) {
            SortMode.RECENT -> filtered.sortedBy { it.first.order }
            SortMode.MAL -> filtered.sortedByDescending { it.third ?: -1.0 }
            SortMode.TITLE -> filtered.sortedBy { it.first.series.title.lowercase() }
        }
        WatchlistUi(
            loading = l.loading,
            error = l.error,
            total = l.entries.size,
            progressKnown = l.entries.count { summaries.containsKey(it.series.id) },
            malKnown = l.entries.count { mal.containsKey(it.series.malKey) },
            filters = f,
            items = sorted.map { (e, status, _) ->
                val summary = summaries[e.series.id]
                CardItem(
                    series = e.series,
                    episodeId = summary?.nextEpisodeId ?: e.nextEpisodeId,
                    subtitle = when {
                        summary != null && status == WatchStatus.COMPLETED -> "✓ ${summary.watched}/${summary.total} ép."
                        summary != null -> "${summary.watched}/${summary.total} ép." + (summary.nextLabel?.let { " · suite : $it" } ?: "")
                        status == WatchStatus.NOT_STARTED -> "Non commencée"
                        else -> "Calcul de la progression…"
                    },
                    progress = summary?.takeIf { it.total > 0 }?.let { it.watched.toFloat() / it.total },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchlistUi())

    init {
        load(force = false)
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loaded.value = loaded.value.copy(loading = true, error = null)
            val entries = try {
                graph.watchlist.get(force)
            } catch (e: Exception) {
                loaded.value = Loaded(loading = false, error = e.message ?: "Erreur de chargement")
                return@launch
            }
            loaded.value = Loaded(loading = false, entries = entries)
            entries.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
            entries.forEach { e ->
                launch { runCatching { graph.progress.ensureSummary(e.series.id, force) } }
            }
        }
    }

    fun setStatus(s: StatusFilter) { filters.value = filters.value.copy(status = s) }
    fun setSort(s: SortMode) { filters.value = filters.value.copy(sort = s) }
    fun setMinScore(m: Double?) { filters.value = filters.value.copy(minScore = m) }
}

@Composable
fun WatchlistScreen(onOpenSeries: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { WatchlistViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 8.dp)) {
        ChipRow("Statut") {
            StatusFilter.entries.forEach { s ->
                FilterChip(selected = ui.filters.status == s, onClick = { vm.setStatus(s) }) { Text(s.label) }
            }
        }
        ChipRow("Trier") {
            SortMode.entries.forEach { s ->
                FilterChip(selected = ui.filters.sort == s, onClick = { vm.setSort(s) }) { Text(s.label) }
            }
        }
        ChipRow("MAL ≥") {
            MinScores.forEach { m ->
                FilterChip(selected = ui.filters.minScore == m, onClick = { vm.setMinScore(m) }) {
                    Text(m?.toString() ?: "Toutes")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                "${ui.items.size} / ${ui.total} séries · progression ${ui.progressKnown}/${ui.total} · notes MAL ${ui.malKnown}/${ui.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { vm.load(force = true) }) { Text("Actualiser") }
        }

        when {
            ui.loading && ui.total == 0 -> CenteredMessage("Chargement de la watchlist…")
            ui.error != null -> CenteredMessage(ui.error!!, "Réessayer") { vm.load(force = true) }
            ui.items.isEmpty() -> CenteredMessage("Aucune série ne correspond à ces filtres.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterWidth + 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.items, key = { it.series.id }) { item ->
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

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        content()
    }
}
