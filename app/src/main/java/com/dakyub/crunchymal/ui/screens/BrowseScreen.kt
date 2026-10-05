package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.adn.AdnGenres
import com.dakyub.crunchymal.data.crunchyroll.CrCategory
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import com.dakyub.crunchymal.ui.components.MediaCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowseSelection(val provider: Provider, val id: String, val title: String)

enum class BrowseSort(val label: String) { POPULAR("Popularité"), ALPHA("A → Z"), MAL("Note MAL") }

data class BrowseUi(
    val crCategories: List<CrCategory> = emptyList(),
    val selection: BrowseSelection? = null,
    val sort: BrowseSort = BrowseSort.POPULAR,
    val status: StatusFilter = StatusFilter.ALL,
    val minScore: Double? = null,
    val loading: Boolean = false,
    val results: List<CardItem> = emptyList(),
    val error: String? = null,
)

/** Liste affichée après filtres (statut, note MAL) et tri. */
data class BrowseVisible(
    val items: List<CardItem> = emptyList(),
    val progressKnown: Int = 0,
    val progressNeeded: Int = 0,
    val malKnown: Int = 0,
)

class BrowseViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(BrowseUi())
    private var job: Job? = null
    private var progressJob: Job? = null

    val visible = combine(ui, graph.progress.summaries, graph.mal.records) { u, summaries, mal ->
        val rows = u.results.map { item ->
            val status = if (item.series.provider == Provider.CRUNCHYROLL) summaries[item.series.id]?.status else null
            Triple(item, status, mal[item.series.malKey]?.score)
        }
        val filtered = rows
            .filter { (_, status, _) -> u.status.status == null || status == u.status.status }
            .filter { (_, _, score) -> u.minScore == null || (score != null && score >= u.minScore) }
        val sorted = if (u.sort == BrowseSort.MAL) filtered.sortedByDescending { it.third ?: -1.0 } else filtered
        val crItems = u.results.filter { it.series.provider == Provider.CRUNCHYROLL }
        BrowseVisible(
            items = sorted.map { (item, _, _) ->
                val summary = if (item.series.provider == Provider.CRUNCHYROLL) summaries[item.series.id] else null
                if (summary != null && u.status != StatusFilter.ALL) item.copy(subtitle = "${summary.watched}/${summary.total} ép.") else item
            },
            progressKnown = crItems.count { summaries.containsKey(it.series.id) },
            progressNeeded = if (u.status == StatusFilter.ALL) 0 else crItems.size,
            malKnown = u.results.count { mal.containsKey(it.series.malKey) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseVisible())
    private var loadedFor: Set<Provider>? = null

    fun ensure(providers: Set<Provider>) {
        if (providers == loadedFor) return
        loadedFor = providers
        viewModelScope.launch {
            val categories = if (Provider.CRUNCHYROLL in providers) {
                runCatching { graph.api.categories() }.getOrDefault(emptyList())
            } else emptyList()
            val current = ui.value.selection?.takeIf { it.provider in providers }
            val first = current
                ?: categories.firstOrNull()?.let { BrowseSelection(Provider.CRUNCHYROLL, it.slug.ifBlank { it.id }, it.title) }
                ?: AdnGenres.first().takeIf { Provider.ADN in providers }?.let { (id, title) -> BrowseSelection(Provider.ADN, id, title) }
            ui.value = ui.value.copy(crCategories = categories)
            first?.let { select(it) }
        }
    }

    fun select(selection: BrowseSelection) {
        ui.value = ui.value.copy(selection = selection)
        reload()
    }

    fun nextSort() {
        val next = BrowseSort.entries[(ui.value.sort.ordinal + 1) % BrowseSort.entries.size]
        val serverSortChanged = (next == BrowseSort.ALPHA) != (ui.value.sort == BrowseSort.ALPHA)
        ui.value = ui.value.copy(sort = next)
        if (serverSortChanged) reload()
    }

    fun nextStatus() {
        ui.value = ui.value.copy(status = ui.value.status.next())
        computeProgressIfNeeded()
    }

    fun nextMinScore() {
        ui.value = ui.value.copy(minScore = MinScores[(MinScores.indexOf(ui.value.minScore) + 1) % MinScores.size])
    }

    /** La progression (coûteuse) n'est calculée que si un filtre de statut est actif. */
    private fun computeProgressIfNeeded() {
        progressJob?.cancel()
        if (ui.value.status == StatusFilter.ALL) return
        val ids = ui.value.results.filter { it.series.provider == Provider.CRUNCHYROLL }.map { it.series.id }
        progressJob = viewModelScope.launch {
            ids.forEach { id -> launch { runCatching { graph.progress.ensureSummary(id) } } }
        }
    }

    private fun reload() {
        val selection = ui.value.selection ?: return
        val alpha = ui.value.sort == BrowseSort.ALPHA
        job?.cancel()
        job = viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            ui.value = runCatching {
                when (selection.provider) {
                    Provider.CRUNCHYROLL -> graph.api
                        .browseCategory(selection.id, sortBy = if (alpha) "alphabetical" else "popularity")
                        .map { it.toSeriesCard() }
                    Provider.ADN -> graph.adn
                        .catalog(order = if (alpha) "alpha" else "popular", genre = selection.id, limit = 100)
                        .map { it.toCard() }
                }
            }.fold(
                { ui.value.copy(loading = false, results = it) },
                { ui.value.copy(loading = false, results = emptyList(), error = it.message) },
            )
            ui.value.results.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
            computeProgressIfNeeded()
        }
    }
}

@Composable
fun BrowseScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { BrowseViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val visible by vm.visible.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    LaunchedEffect(providers) { vm.ensure(providers) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        if (Provider.CRUNCHYROLL in providers && ui.crCategories.isNotEmpty()) {
            CategoryRow("Crunchyroll", ui.crCategories.map { BrowseSelection(Provider.CRUNCHYROLL, it.slug.ifBlank { it.id }, it.title) }, ui.selection, vm::select)
        }
        if (Provider.ADN in providers) {
            CategoryRow("ADN", AdnGenres.map { (id, title) -> BrowseSelection(Provider.ADN, id, title) }, ui.selection, vm::select)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(
                ui.selection?.let { "${it.title} · ${it.provider.label}" } ?: "",
                style = MaterialTheme.typography.titleMedium,
            )
            CycleButton("Tri", ui.sort.label, vm::nextSort)
            CycleButton("Statut", ui.status.label, vm::nextStatus)
            CycleButton("MAL ≥", ui.minScore?.toString() ?: "toutes", vm::nextMinScore)
        }
        Text(
            buildString {
                append("${visible.items.size}/${ui.results.size} séries · MAL ${visible.malKnown}/${ui.results.size}")
                if (visible.progressNeeded > 0) append(" · progression ${visible.progressKnown}/${visible.progressNeeded}")
                if (ui.status != StatusFilter.ALL && Provider.ADN in providers) append(" · statut ADN pas encore disponible")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            ui.loading && ui.results.isEmpty() -> CenteredMessage("Chargement…")
            ui.error != null -> CenteredMessage("Erreur : ${ui.error}")
            ui.selection == null -> CenteredMessage("Aucune catégorie disponible.")
            ui.results.isEmpty() -> CenteredMessage("Aucune série dans cette catégorie.")
            visible.items.isEmpty() -> CenteredMessage(
                if (visible.progressNeeded > visible.progressKnown) "Calcul de la progression…" else "Aucune série ne correspond à ces filtres."
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(GridPosterWidth + 12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible.items) { item ->
                    MediaCard(item = item, width = GridPosterWidth, onClick = { onOpenSeries(item.series) })
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    items: List<BrowseSelection>,
    selected: BrowseSelection?,
    onSelect: (BrowseSelection) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { item ->
                FilterChip(selected = item == selected, onClick = { onSelect(item) }) { Text(item.title) }
            }
        }
    }
}
