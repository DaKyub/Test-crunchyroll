package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.playEpisode
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.WatchlistEntry
import com.dakyub.crunchymal.data.progress.WatchStatus
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterGrid
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Statuts proposés en cases à cocher (aucune case cochée = toutes les séries). */
val StatusOptions = listOf(
    WatchStatus.NOT_STARTED to "Non commencées",
    WatchStatus.IN_PROGRESS to "En cours",
    WatchStatus.UP_TO_DATE to "À jour",
    WatchStatus.COMPLETED to "Tout vu",
)

fun Set<WatchStatus>.accepts(status: WatchStatus?): Boolean = isEmpty() || (status != null && status in this)

fun Set<WatchStatus>.toggle(status: WatchStatus): Set<WatchStatus> = if (status in this) this - status else this + status

enum class SortMode(val label: String) { RECENT("Récentes"), MAL("Note MAL"), TITLE("Titre A→Z") }

val MinScores = listOf<Double?>(null, 7.0, 7.5, 8.0, 8.5)

data class WatchlistFilters(
    val statuses: Set<WatchStatus> = emptySet(),
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

internal data class Loaded(val loading: Boolean = true, val error: String? = null, val entries: List<WatchlistEntry> = emptyList())

class WatchlistViewModel(private val graph: Graph) : ViewModel() {
    private val loaded = MutableStateFlow(Loaded())
    private val filters = MutableStateFlow(WatchlistFilters())
    private var loadedFor: Pair<Set<Provider>, Boolean>? = null

    val ui = combine(loaded, filters, graph.progress.summaries, graph.mal.records) { l, f, summaries, mal ->
        val rows = l.entries.map { e ->
            val summary = summaries[e.series.progressKey]
            val status = summary?.status ?: if (e.neverWatched) WatchStatus.NOT_STARTED else null
            Triple(e, status, mal[e.series.malKey]?.score)
        }
        val filtered = rows
            .filter { (_, status, _) -> f.statuses.accepts(status) }
            .filter { (_, _, score) -> f.minScore == null || (score != null && score >= f.minScore) }
        val sorted = when (f.sort) {
            // « Les deux » : les deux watchlists sont entrelacées, chacune dans son ordre.
            SortMode.RECENT -> filtered.sortedWith(compareBy({ it.first.order }, { it.first.series.provider.ordinal }))
            SortMode.MAL -> filtered.sortedByDescending { it.third ?: -1.0 }
            SortMode.TITLE -> filtered.sortedBy { it.first.series.title.lowercase() }
        }
        WatchlistUi(
            loading = l.loading,
            error = l.error,
            total = l.entries.size,
            progressKnown = l.entries.count { summaries.containsKey(it.series.progressKey) },
            malKnown = l.entries.count { mal.containsKey(it.series.malKey) },
            filters = f,
            items = sorted.map { (e, status, _) ->
                val summary = summaries[e.series.progressKey]
                CardItem(
                    series = e.series,
                    episodeId = summary?.nextEpisodeId ?: e.nextEpisodeId,
                    subtitle = when {
                        summary != null && status == WatchStatus.COMPLETED -> "✓ ${summary.watched}/${summary.total} ép."
                        summary != null && status == WatchStatus.UP_TO_DATE -> "À jour · ${summary.watched}/${summary.total} ép."
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
        // Client ID MAL ajouté ou modifié : on redemande les notes.
        viewModelScope.launch {
            graph.mal.version.drop(1).collect {
                loaded.value.entries.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
            }
        }
    }

    /** Charge (ou recharge si les services affichés ou la connexion ADN ont changé). */
    fun ensure(providers: Set<Provider>, adnLoggedIn: Boolean) {
        if (providers to adnLoggedIn != loadedFor) load(force = false)
    }

    fun load(force: Boolean) {
        val providers = graph.providers.selected.value
        loadedFor = providers to graph.adn.loggedIn.value
        viewModelScope.launch {
            loaded.value = loaded.value.copy(loading = true, error = null)
            val cr = async {
                if (Provider.CRUNCHYROLL in providers) runCatching { graph.watchlist.get(force) } else Result.success(emptyList<WatchlistEntry>())
            }
            val adn = async {
                if (Provider.ADN in providers) runCatching { graph.adnWatchlist.get(force) } else Result.success(emptyList<WatchlistEntry>())
            }
            val results = listOf(cr.await(), adn.await())
            val entries = results.flatMap { it.getOrDefault(emptyList()) }
            val errors = results.zip(listOf(Provider.CRUNCHYROLL, Provider.ADN)).mapNotNull { (result, provider) ->
                result.exceptionOrNull()?.let { "${provider.label} : ${it.message ?: "erreur de chargement"}" }
            }
            loaded.value = Loaded(loading = false, entries = entries, error = errors.joinToString(" · ").ifBlank { null })
            entries.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
            entries.forEach { e ->
                launch { runCatching { graph.ensureProgress(e.series, force) } }
            }
        }
    }

    fun toggleStatus(s: WatchStatus) { filters.value = filters.value.copy(statuses = filters.value.statuses.toggle(s)) }
    fun setSort(s: SortMode) { filters.value = filters.value.copy(sort = s) }
    fun setMinScore(m: Double?) { filters.value = filters.value.copy(minScore = m) }
}

@Composable
fun WatchlistScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val providers by graph.providers.selected.collectAsState()
    val adnLoggedIn by graph.adn.loggedIn.collectAsState()
    if (providers == setOf(Provider.ADN) && !adnLoggedIn) {
        CenteredMessage("Connecte ton compte ADN dans Réglages pour afficher ta watchlist ADN.")
        return
    }
    val vm = viewModel { WatchlistViewModel(graph) }
    LaunchedEffect(providers, adnLoggedIn) { vm.ensure(providers, adnLoggedIn) }
    val ui by vm.ui.collectAsState()
    val malError by graph.mal.lastError.collectAsState()
    val context = LocalContext.current

    val message = when {
        ui.loading && ui.total == 0 -> "Chargement de la watchlist…"
        ui.error != null && ui.total == 0 -> ui.error
        ui.items.isEmpty() -> "Aucune série ne correspond à ces filtres."
        else -> null
    }
    PosterGrid(
        items = ui.items,
        message = message,
        key = { it.series.progressKey },
        actionLabel = "Réessayer".takeIf { ui.error != null && ui.total == 0 },
        onAction = { vm.load(force = true) },
        header = {
            // Une seule ligne de filtres : chaque bouton passe à la valeur suivante.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                CycleButton("Tri", ui.filters.sort.label) { vm.setSort(ui.filters.sort.next()) }
                CycleButton("MAL ≥", ui.filters.minScore?.toString() ?: "toutes") {
                    vm.setMinScore(MinScores[(MinScores.indexOf(ui.filters.minScore) + 1) % MinScores.size])
                }
                OutlinedButton(onClick = { vm.load(force = true) }, scale = OutlinedButtonDefaults.scale(focusedScale = 1.05f)) {
                    Text("Actualiser", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "${ui.items.size}/${ui.total} séries · progression ${ui.progressKnown}/${ui.total} · MAL ${ui.malKnown}/${ui.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChips(ui.filters.statuses, vm::toggleStatus)
            malError?.let {
                Text(
                    "Notes MAL indisponibles : $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (Provider.ADN in providers && !adnLoggedIn) {
                Text(
                    "Watchlist ADN : connecte ton compte ADN dans Réglages.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.error != null && ui.total > 0) {
                Text(ui.error!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        },
    ) { item ->
        MediaCard(
            item = item,
            width = GridPosterWidth,
            onClick = { onOpenSeries(item.series) },
            onLongClick = item.episodeId?.let { id -> { playEpisode(context, item.series, id) } },
        )
    }
}


private fun SortMode.next() = SortMode.entries[(ordinal + 1) % SortMode.entries.size]

@Composable
internal fun CycleButton(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, scale = OutlinedButtonDefaults.scale(focusedScale = 1.05f)) {
        Text("$label : ", style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** Cases à cocher de statut : plusieurs peuvent être cochées à la fois. */
@Composable
internal fun StatusChips(selected: Set<WatchStatus>, onToggle: (WatchStatus) -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(vertical = 2.dp),
    ) {
        Text("Statut", style = MaterialTheme.typography.labelLarge)
        StatusOptions.forEach { (status, label) ->
            val checked = status in selected
            FilterChip(selected = checked, onClick = { onToggle(status) }) {
                Text(if (checked) "✓ $label" else label, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (selected.isEmpty()) {
            Text("(toutes)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
