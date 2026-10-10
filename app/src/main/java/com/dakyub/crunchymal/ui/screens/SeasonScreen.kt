package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import com.dakyub.crunchymal.ui.components.MalCardItem
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterGrid
import com.dakyub.crunchymal.ui.components.airingLabel
import com.dakyub.crunchymal.ui.components.malCardItem
import com.dakyub.crunchymal.ui.components.prepareMalAnime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

enum class AnimeSeason(val api: String, val label: String) {
    WINTER("winter", "Hiver"), SPRING("spring", "Printemps"), SUMMER("summer", "Été"), FALL("fall", "Automne")
}

data class SeasonRef(val year: Int, val season: AnimeSeason) {
    val label: String get() = "${season.label} $year"

    fun shift(by: Int): SeasonRef {
        val index = year * 4 + season.ordinal + by
        return SeasonRef(index.floorDiv(4), AnimeSeason.entries[index.mod(4)])
    }

    companion object {
        /** Saison d'anime en cours : hiver = janvier à mars, printemps = avril à juin, etc. */
        fun current(): SeasonRef {
            val now = Calendar.getInstance()
            return SeasonRef(now.get(Calendar.YEAR), AnimeSeason.entries[now.get(Calendar.MONTH) / 3])
        }
    }
}

data class SeasonUi(
    val season: SeasonRef = SeasonRef.current(),
    val loading: Boolean = true,
    val error: String? = null,
    val anime: List<MalAnime> = emptyList(),
    val tvOnly: Boolean = true,
    val availableOnly: Boolean = false,
)

data class SeasonVisible(val items: List<MalCardItem> = emptyList(), val total: Int = 0)

class SeasonViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(SeasonUi())

    val visible = combine(
        ui, graph.availability.found, graph.watchPlatforms.found, graph.providers.selected, graph.mal.myListSeenIds,
    ) { u, found, platforms, providers, seen ->
        val crUsable = graph.auth.loggedIn.value
        val items = u.anime
            .filter { !u.tvOnly || it.type?.lowercase() in setOf("tv", "ona") }
            .sortedByDescending { it.score ?: -1.0 }
            .map { anime ->
                malCardItem(
                    anime,
                    found[anime.malId],
                    platforms[anime.malId],
                    providers,
                    crUsable,
                    subtitle = listOfNotNull(
                        "✓ dans ta liste".takeIf { anime.malId in seen },
                        anime.type?.uppercase(),
                        anime.episodes?.let { "$it ép." },
                        airingLabel(anime.airing),
                    ).joinToString(" · ").ifBlank { null },
                )
            }
            .filter { !u.availableOnly || it.target != null }
        SeasonVisible(items, u.anime.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeasonVisible())

    init {
        load()
    }

    fun load() {
        val season = ui.value.season
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            runCatching { graph.mal.seasonal(season.year, season.season.api) }
                .onSuccess { list ->
                    if (ui.value.season != season) return@onSuccess
                    ui.value = ui.value.copy(loading = false, anime = list)
                    graph.prepareMalAnime(list)
                    graph.mal.refreshMyListSeenIds()
                }
                .onFailure { ui.value = ui.value.copy(loading = false, error = it.message ?: "Erreur", anime = emptyList()) }
        }
    }

    fun select(season: SeasonRef) {
        if (season == ui.value.season) return
        ui.value = ui.value.copy(season = season, anime = emptyList())
        load()
    }

    fun toggleTvOnly() { ui.value = ui.value.copy(tvOnly = !ui.value.tvOnly) }
    fun toggleAvailableOnly() { ui.value = ui.value.copy(availableOnly = !ui.value.availableOnly) }
}

/** Animes d'une saison d'après MAL, classés par note, avec les plateformes où les regarder. */
@Composable
fun SeasonScreen(onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { SeasonViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val visible by vm.visible.collectAsState()
    val current = SeasonRef.current()

    val message = when {
        ui.loading && ui.anime.isEmpty() -> "Chargement de la saison…"
        ui.error != null -> "Erreur : ${ui.error}"
        visible.items.isEmpty() -> "Aucun anime ne correspond à ces filtres."
        else -> null
    }
    PosterGrid(
        items = visible.items,
        message = message,
        key = { it.malId },
        actionLabel = "Réessayer".takeIf { ui.error != null },
        onAction = vm::load,
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                (-2..1).map { current.shift(it) }.forEach { season ->
                    FilterChip(selected = season == ui.season, onClick = { vm.select(season) }) {
                        Text(season.label + if (season == current) " (en cours)" else "", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                FilterChip(selected = ui.tvOnly, onClick = vm::toggleTvOnly) {
                    Text(if (ui.tvOnly) "✓ Séries seulement" else "Séries seulement", style = MaterialTheme.typography.labelMedium)
                }
                FilterChip(selected = ui.availableOnly, onClick = vm::toggleAvailableOnly) {
                    Text(if (ui.availableOnly) "✓ Sur Crunchyroll / ADN" else "Sur Crunchyroll / ADN", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "${visible.items.size}/${visible.total} animes · classés par note MAL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { item ->
        MediaCard(
            item = item.card,
            width = GridPosterWidth,
            showProviderBadge = false,
            onClick = { item.target?.let(onOpenSeries) ?: onOpenMal(item.malId) },
        )
    }
}
