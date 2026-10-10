package com.dakyub.crunchymal.ui.screens

import android.widget.Toast
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
import com.dakyub.crunchymal.data.Availability
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.mal.MalListEntry
import com.dakyub.crunchymal.data.mal.MalStatuses
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import com.dakyub.crunchymal.ui.components.MediaCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MalListSort(val label: String) { MAL("Note MAL"), TITLE("Titre"), UPDATED("Modifiées récemment") }

data class MalListUi(
    val loading: Boolean = true,
    val error: String? = null,
    val entries: List<MalListEntry> = emptyList(),
    val statuses: Set<String> = setOf("watching", "plan_to_watch"),
    val availableOnly: Boolean = false,
    val sort: MalListSort = MalListSort.MAL,
)

/** Carte de la liste MAL + série correspondante sur Crunchyroll / ADN (si trouvée). */
data class MalListRow(val card: CardItem, val target: SeriesRef?)

data class MalListVisible(val rows: List<MalListRow> = emptyList(), val checked: Int = 0, val total: Int = 0)

class MalListViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(MalListUi())

    val visible = combine(ui, graph.availability.found, graph.providers.selected, graph.watchPlatforms.found) { u, found, providers, platforms ->
        val entries = u.entries.filter { entry -> entry.status.status?.let { it in u.statuses } == true }
        val rows = entries.map { entry ->
            val availability: Availability? = found[entry.anime.malId]
            val elsewhere = platforms[entry.anime.malId]
            val target = availability?.refFor(providers)
            // Crunchyroll / ADN seulement s'ils sont trouvés par l'app (lien garanti), puis les autres
            // plateformes TMDB (France) ou MAL (monde).
            val badges = listOfNotNull(
                "Crunchyroll".takeIf { availability?.crunchyroll != null },
                "ADN".takeIf { availability?.adn != null },
            ) + elsewhere?.names.orEmpty().filter { it != "Crunchyroll" && it != "ADN" }
            val where = when {
                availability == null && elsewhere == null -> "Recherche…"
                badges.isEmpty() -> "Indisponible"
                elsewhere?.source == "MAL" -> "plateformes MAL (monde)"
                else -> null
            }
            val statusLabel = MalStatuses.firstOrNull { it.first == entry.status.status }?.second.orEmpty()
            entry to MalListRow(
                card = CardItem(
                    series = SeriesRef(
                        id = "mal-${entry.anime.malId}",
                        title = entry.anime.titleEnglish ?: entry.anime.title,
                        posterUrl = entry.anime.pictureUrl,
                        malKeyOverride = "mal:${entry.anime.malId}",
                    ),
                    subtitle = listOfNotNull(statusLabel.ifBlank { null }, where).joinToString(" · "),
                    badges = badges,
                ),
                target = target,
            )
        }
            .filter { (_, row) -> !u.availableOnly || row.target != null }
            .let { list ->
                when (u.sort) {
                    MalListSort.MAL -> list.sortedByDescending { it.first.anime.score ?: -1.0 }
                    MalListSort.TITLE -> list.sortedBy { it.second.card.series.title.lowercase() }
                    MalListSort.UPDATED -> list.sortedByDescending { it.first.status.updatedAt.orEmpty() }
                }
            }
        MalListVisible(
            rows = rows.map { it.second },
            checked = entries.count { found.containsKey(it.anime.malId) },
            total = entries.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MalListVisible())

    fun load() {
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            runCatching { graph.mal.myList() }
                .onSuccess { entries ->
                    ui.value = ui.value.copy(loading = false, entries = entries)
                    val crEnabled = Provider.CRUNCHYROLL in graph.providers.selected.value && graph.auth.loggedIn.value
                    entries.forEach { entry ->
                        graph.mal.seed("mal:${entry.anime.malId}", entry.anime)
                        graph.availability.request(entry.anime, crEnabled)
                        graph.watchPlatforms.request(entry.anime)
                    }
                }
                .onFailure { ui.value = ui.value.copy(loading = false, error = it.message ?: "Erreur") }
        }
    }

    fun toggleStatus(status: String) {
        val current = ui.value.statuses
        ui.value = ui.value.copy(statuses = if (status in current) current - status else current + status)
    }

    fun toggleAvailableOnly() {
        ui.value = ui.value.copy(availableOnly = !ui.value.availableOnly)
    }

    fun nextSort() {
        ui.value = ui.value.copy(sort = MalListSort.entries[(ui.value.sort.ordinal + 1) % MalListSort.entries.size])
    }
}

@Composable
fun MalListScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    if (!loggedIn) {
        CenteredMessage("Connecte ton compte MyAnimeList dans Réglages → Compte MyAnimeList.")
        return
    }
    val vm = viewModel { MalListViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val visible by vm.visible.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (ui.entries.isEmpty()) vm.load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text("Statut", style = MaterialTheme.typography.labelLarge)
            MalStatuses.forEach { (value, label) ->
                val checked = value in ui.statuses
                FilterChip(selected = checked, onClick = { vm.toggleStatus(value) }) {
                    Text(if (checked) "✓ $label" else label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            CycleButton("Tri", ui.sort.label, vm::nextSort)
            FilterChip(selected = ui.availableOnly, onClick = vm::toggleAvailableOnly) {
                Text(if (ui.availableOnly) "✓ Disponibles seulement" else "Disponibles seulement", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = vm::load) { Text("Actualiser", style = MaterialTheme.typography.labelLarge) }
            Text(
                "${visible.rows.size} séries · disponibilité vérifiée ${visible.checked}/${visible.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            ui.loading && ui.entries.isEmpty() -> CenteredMessage("Chargement de ta liste MAL…")
            ui.error != null -> CenteredMessage("Erreur : ${ui.error}", "Réessayer") { vm.load() }
            visible.rows.isEmpty() -> CenteredMessage("Aucune série ne correspond à ces filtres.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(GridPosterWidth + 12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible.rows, key = { it.card.series.id }) { row ->
                    MediaCard(
                        item = row.card,
                        width = GridPosterWidth,
                        showProviderBadge = false,
                        onClick = {
                            val target = row.target
                            if (target != null) onOpenSeries(target)
                            else Toast.makeText(
                                context,
                                row.card.badges.takeIf { it.isNotEmpty() }?.let { "Disponible sur : ${it.joinToString(", ")}" }
                                    ?: "Pas disponible sur les services affichés",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }
        }
    }
}
