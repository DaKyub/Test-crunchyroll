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
import com.dakyub.crunchymal.data.progress.WatchStatus
import com.dakyub.crunchymal.data.adn.AdnGenreFallback
import com.dakyub.crunchymal.data.adn.adnGenreLabel
import com.dakyub.crunchymal.data.crunchyroll.CrCategory
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowseSelection(val provider: Provider, val id: String, val title: String) {
    val isAll: Boolean get() = id == ALL

    companion object {
        /** Identifiant de la puce « Tout » (aucun filtre de genre). */
        const val ALL = "__all__"

        fun all(provider: Provider) = BrowseSelection(provider, ALL, "Tout")
    }
}

enum class BrowseSort(val label: String) { POPULAR("Popularité"), ALPHA("A → Z"), MAL("Note MAL") }

data class BrowseUi(
    val crCategories: List<CrCategory> = emptyList(),
    /** Genres du catalogue ADN (identifiants de l'API). */
    val adnGenres: List<String> = emptyList(),
    val selection: BrowseSelection? = null,
    val sort: BrowseSort = BrowseSort.POPULAR,
    val statuses: Set<WatchStatus> = emptySet(),
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
    /** Séries de l'historique Crunchyroll : les autres sont « non commencées » sans rien calculer. */
    private val crWatched = MutableStateFlow<Set<String>?>(null)

    val visible = combine(ui, graph.progress.summaries, graph.mal.records, graph.adn.loggedIn, crWatched) { u, summaries, mal, _, watched ->
        fun statusOf(item: CardItem): WatchStatus? = summaries[item.series.progressKey]?.status
            ?: if (item.series.provider == Provider.CRUNCHYROLL && watched != null && item.series.id !in watched) WatchStatus.NOT_STARTED else null
        val rows = u.results.map { item -> Triple(item, statusOf(item), mal[item.series.malKey]?.score) }
        val filtered = rows
            .filter { (_, status, _) -> u.statuses.accepts(status) }
            .filter { (_, _, score) -> u.minScore == null || (score != null && score >= u.minScore) }
        val sorted = if (u.sort == BrowseSort.MAL) filtered.sortedByDescending { it.third ?: -1.0 } else filtered
        val trackable = u.results.filter { graph.progressAvailable(it.series) }
        BrowseVisible(
            items = sorted.map { (item, _, _) ->
                val summary = summaries[item.series.progressKey]
                if (summary != null && u.statuses.isNotEmpty()) item.copy(subtitle = "${summary.watched}/${summary.total} ép.") else item
            },
            progressKnown = trackable.count { statusOf(it) != null },
            progressNeeded = if (u.statuses.isEmpty()) 0 else trackable.size,
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
            val adnGenres = if (Provider.ADN in providers) {
                runCatching { graph.adn.genres() }.getOrDefault(AdnGenreFallback)
            } else emptyList()
            // Une sélection ADN qui n'existe plus (genres renommés par ADN) est abandonnée.
            val current = ui.value.selection?.takeIf { it.provider in providers }
                ?.takeIf { it.provider != Provider.ADN || it.isAll || it.id in adnGenres }
            // Par défaut : « Tout » (catalogue sans filtre de genre).
            val first = current ?: BrowseSelection.all(if (Provider.CRUNCHYROLL in providers) Provider.CRUNCHYROLL else Provider.ADN)
            ui.value = ui.value.copy(crCategories = categories, adnGenres = adnGenres)
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

    fun toggleStatus(status: WatchStatus) {
        ui.value = ui.value.copy(statuses = ui.value.statuses.toggle(status))
        computeProgressIfNeeded()
    }

    fun nextMinScore() {
        ui.value = ui.value.copy(minScore = MinScores[(MinScores.indexOf(ui.value.minScore) + 1) % MinScores.size])
    }

    /** La progression (coûteuse) n'est calculée que si un filtre de statut est actif. */
    private fun computeProgressIfNeeded() {
        progressJob?.cancel()
        if (ui.value.statuses.isEmpty()) return
        val refs = ui.value.results.map { it.series }.filter { graph.progressAvailable(it) }
        progressJob = viewModelScope.launch {
            // Crunchyroll : seules les séries de l'historique ont une progression à calculer.
            val watched = if (refs.any { it.provider == Provider.CRUNCHYROLL }) {
                runCatching { graph.history.watchedSeriesIds() }.getOrNull()?.also { crWatched.value = it }
            } else null
            refs.filter { it.provider != Provider.CRUNCHYROLL || watched == null || it.id in watched }
                .forEach { ref -> launch { runCatching { graph.ensureProgress(ref) } } }
        }
    }

    private fun reload() {
        val selection = ui.value.selection ?: return
        val alpha = ui.value.sort == BrowseSort.ALPHA
        job?.cancel()
        job = viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            ui.value = runCatching {
                val sortBy = if (alpha) "alphabetical" else "popularity"
                val order = if (alpha) "alpha" else "popular"
                when {
                    // « Tout » : deux pages de 100 séries, tous genres confondus.
                    selection.provider == Provider.CRUNCHYROLL && selection.isAll -> listOf(0, 100).flatMap { start ->
                        graph.api.browseWith(mapOf("type" to "series", "sort_by" to sortBy, "n" to "100", "start" to start.toString()))
                    }.distinctBy { it.id }.map { it.toSeriesCard() }
                    selection.provider == Provider.CRUNCHYROLL -> graph.api
                        .browseCategory(selection.id, sortBy = sortBy)
                        .map { it.toSeriesCard() }
                    selection.isAll -> listOf(0, 100).flatMap { offset ->
                        graph.adn.catalog(order = order, limit = 100, offset = offset)
                    }.distinctBy { it.id }.map { it.toCard() }
                    else -> graph.adn
                        .catalog(order = order, genre = selection.id, limit = 100)
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
    val adnLoggedIn by graph.adn.loggedIn.collectAsState()
    LaunchedEffect(providers) { vm.ensure(providers) }

    val message = when {
        ui.loading && ui.results.isEmpty() -> "Chargement…"
        ui.error != null -> "Erreur : ${ui.error}"
        ui.selection == null -> "Aucune catégorie disponible."
        ui.results.isEmpty() -> "Aucune série dans cette catégorie."
        visible.items.isEmpty() ->
            if (visible.progressNeeded > visible.progressKnown) "Calcul de la progression…" else "Aucune série ne correspond à ces filtres."
        else -> null
    }
    PosterGrid(
        items = visible.items,
        message = message,
        header = {
            if (Provider.CRUNCHYROLL in providers) {
                CategoryRow(
                    "Crunchyroll",
                    listOf(BrowseSelection.all(Provider.CRUNCHYROLL)) +
                        ui.crCategories.map { BrowseSelection(Provider.CRUNCHYROLL, it.slug.ifBlank { it.id }, it.title) },
                    ui.selection,
                    vm::select,
                )
            }
            if (Provider.ADN in providers) {
                CategoryRow(
                    "ADN",
                    listOf(BrowseSelection.all(Provider.ADN)) + ui.adnGenres.map { BrowseSelection(Provider.ADN, it, adnGenreLabel(it)) },
                    ui.selection,
                    vm::select,
                )
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
                CycleButton("MAL ≥", ui.minScore?.toString() ?: "toutes", vm::nextMinScore)
            }
            StatusChips(ui.statuses, vm::toggleStatus)
            Text(
                buildString {
                    append("${visible.items.size}/${ui.results.size} séries · MAL ${visible.malKnown}/${ui.results.size}")
                    if (visible.progressNeeded > 0) append(" · progression ${visible.progressKnown}/${visible.progressNeeded}")
                    if (ui.statuses.isNotEmpty() && Provider.ADN in providers && !adnLoggedIn) append(" · statut ADN : connecte ADN dans Réglages")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { item ->
        MediaCard(item = item, width = GridPosterWidth, onClick = { onOpenSeries(item.series) })
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
