package com.dakyub.crunchymal.ui.screens

import com.dakyub.crunchymal.ui.components.malGenreLabel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.dakyub.crunchymal.ui.components.PlatformFilterOptions
import com.dakyub.crunchymal.ui.components.PosterGrid
import com.dakyub.crunchymal.ui.components.addToPlanToWatch
import com.dakyub.crunchymal.ui.components.airingLabel
import com.dakyub.crunchymal.ui.components.malCardItem
import com.dakyub.crunchymal.ui.components.prepareMalAnime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Classements MAL proposés (valeur de l'API → libellé). */
enum class DiscoverRanking(val api: String, val label: String) {
    TOP("all", "Mieux notés"),
    POPULAR("bypopularity", "Populaires"),
    AIRING("airing", "En diffusion"),
    UPCOMING("upcoming", "À venir"),
    TV("tv", "Séries TV"),
    MOVIE("movie", "Films"),
}

enum class DiscoverType(val label: String) { ALL("tous"), SERIES("séries"), MOVIES("films") }

enum class DiscoverSort(val label: String) { RANK("classement"), SCORE("note MAL"), YEAR("plus récents") }

private val SinceYears = listOf<Int?>(null, 2020, 2015, 2010, 2000)

/** Filtre de disponibilité : null = toutes, [STREAMING] = au moins une plateforme, sinon une plateforme. */
private const val STREAMING = "en streaming"
private val AvailabilityOptions = listOf<String?>(null, STREAMING) + PlatformFilterOptions.filterNotNull()

data class DiscoverUi(
    val ranking: DiscoverRanking = DiscoverRanking.TOP,
    val loading: Boolean = true,
    val error: String? = null,
    val anime: List<MalAnime> = emptyList(),
    /** Genres MAL cochés : l'anime doit tous les avoir. */
    val genres: Set<String> = emptySet(),
    val type: DiscoverType = DiscoverType.ALL,
    val minScore: Double? = null,
    val sinceYear: Int? = null,
    val availability: String? = null,
    val sort: DiscoverSort = DiscoverSort.RANK,
)

data class DiscoverVisible(
    val items: List<MalCardItem> = emptyList(),
    /** Animes hors de la liste MAL qui passent les filtres autres que la disponibilité. */
    val candidates: Int = 0,
    /** Parmi eux, ceux dont les plateformes sont déjà vérifiées. */
    val checked: Int = 0,
    /** Genres des animes du classement (hors liste MAL), les plus fréquents d'abord. */
    val genres: List<String> = emptyList(),
)

class DiscoverViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(DiscoverUi())

    /** Animes du classement hors de la liste MAL, filtrés (sauf disponibilité) et triés. */
    private fun candidates(u: DiscoverUi, inList: Set<Int>): List<MalAnime> = u.anime
        .filter { it.malId !in inList }
        .filter { anime -> u.genres.all { it in anime.genres } }
        .filter { anime ->
            when (u.type) {
                DiscoverType.ALL -> true
                DiscoverType.SERIES -> anime.type?.lowercase() in setOf("tv", "ona")
                DiscoverType.MOVIES -> anime.type?.lowercase() == "movie"
            }
        }
        .filter { u.minScore == null || (it.score ?: 0.0) >= u.minScore }
        .filter { u.sinceYear == null || (it.startYear ?: 0) >= u.sinceYear }
        .let { list ->
            when (u.sort) {
                DiscoverSort.RANK -> list
                DiscoverSort.SCORE -> list.sortedByDescending { it.score ?: -1.0 }
                DiscoverSort.YEAR -> list.sortedByDescending { it.startYear ?: 0 }
            }
        }

    val visible = combine(
        ui, graph.availability.found, graph.watchPlatforms.found, graph.providers.selected, graph.mal.myListStatuses,
    ) { u, found, platforms, providers, statuses ->
        val inList = statuses.keys
        val list = candidates(u, inList)
        val crUsable = graph.auth.loggedIn.value
        val items = list.map { anime ->
            malCardItem(
                anime,
                found[anime.malId],
                platforms[anime.malId],
                providers,
                crUsable,
                subtitle = listOfNotNull(
                    anime.type?.uppercase(),
                    anime.startYear?.toString(),
                    anime.episodes?.let { "$it ép." },
                    airingLabel(anime.airing).takeIf { anime.airing != "finished_airing" },
                ).joinToString(" · ").ifBlank { null },
            )
        }.filter { item ->
            when (u.availability) {
                null -> true
                STREAMING -> item.card.badges.isNotEmpty()
                else -> u.availability in item.card.badges
            }
        }
        val genres = u.anime.filter { it.malId !in inList }.flatMap { it.genres }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        DiscoverVisible(
            items = items,
            candidates = list.size,
            checked = list.count { found.containsKey(it.malId) && platforms.containsKey(it.malId) },
            genres = genres,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverVisible())

    init {
        load()
        // Plateformes vérifiées pour les animes affichables : les 150 premiers, 400 si un filtre de
        // disponibilité est actif (les recherches sont gardées en cache).
        viewModelScope.launch {
            combine(ui, graph.mal.myListStatuses) { u, statuses ->
                candidates(u, statuses.keys).take(if (u.availability != null) 400 else 150)
            }.map { list -> list.map { it.malId } to list }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, list) -> graph.prepareMalAnime(list) }
        }
    }

    fun load() {
        val ranking = ui.value.ranking
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            graph.mal.refreshMyListSeenIds()
            runCatching { graph.mal.ranking(ranking.api) }
                .onSuccess { list ->
                    if (ui.value.ranking != ranking) return@onSuccess
                    ui.value = ui.value.copy(loading = false, anime = list)
                }
                .onFailure { ui.value = ui.value.copy(loading = false, error = it.message ?: "Erreur", anime = emptyList()) }
        }
    }

    fun select(ranking: DiscoverRanking) {
        if (ranking == ui.value.ranking) return
        ui.value = ui.value.copy(ranking = ranking, anime = emptyList())
        load()
    }

    fun toggleGenre(genre: String) {
        val genres = ui.value.genres
        ui.value = ui.value.copy(genres = if (genre in genres) genres - genre else genres + genre)
    }

    fun clearGenres() { ui.value = ui.value.copy(genres = emptySet()) }
    fun nextType() { ui.value = ui.value.copy(type = DiscoverType.entries[(ui.value.type.ordinal + 1) % DiscoverType.entries.size]) }
    fun nextMinScore() { ui.value = ui.value.copy(minScore = MinScores[(MinScores.indexOf(ui.value.minScore) + 1) % MinScores.size]) }
    fun nextSinceYear() { ui.value = ui.value.copy(sinceYear = SinceYears[(SinceYears.indexOf(ui.value.sinceYear) + 1) % SinceYears.size]) }
    fun nextAvailability() {
        ui.value = ui.value.copy(availability = AvailabilityOptions[(AvailabilityOptions.indexOf(ui.value.availability) + 1) % AvailabilityOptions.size])
    }
    fun nextSort() { ui.value = ui.value.copy(sort = DiscoverSort.entries[(ui.value.sort.ordinal + 1) % DiscoverSort.entries.size]) }
}


/** Onglet « Découvrir » : classements MAL sans les séries déjà dans ta liste, avec filtres et ajout à « À voir ». */
@Composable
fun DiscoverScreen(onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { DiscoverViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val visible by vm.visible.collectAsState()
    val malLoggedIn by graph.malAuth.loggedIn.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val message = when {
        ui.loading && ui.anime.isEmpty() -> "Chargement du classement MAL…"
        ui.error != null -> "Erreur : ${ui.error}"
        visible.items.isEmpty() && ui.availability != null && visible.checked < visible.candidates -> "Vérification des plateformes…"
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                items(DiscoverRanking.entries) { ranking ->
                    FilterChip(selected = ranking == ui.ranking, onClick = { vm.select(ranking) }) {
                        Text(ranking.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 2.dp).horizontalScroll(rememberScrollState()),
            ) {
                CycleButton("Type", ui.type.label, vm::nextType)
                CycleButton("MAL ≥", ui.minScore?.toString() ?: "toutes", vm::nextMinScore)
                CycleButton("Depuis", ui.sinceYear?.toString() ?: "toujours", vm::nextSinceYear)
                CycleButton("Dispo", ui.availability ?: "toutes", vm::nextAvailability)
                CycleButton("Tri", ui.sort.label, vm::nextSort)
                Text(
                    "${visible.items.size}/${ui.anime.size} animes" +
                        (if (malLoggedIn) " hors de ta liste MAL" else "") +
                        " · plateformes vérifiées ${visible.checked}/${visible.candidates}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (visible.genres.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    item {
                        FilterChip(selected = ui.genres.isEmpty(), onClick = vm::clearGenres) {
                            Text("Tous les genres", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    items((ui.genres.toList() + visible.genres).distinct()) { genre ->
                        val checked = genre in ui.genres
                        FilterChip(selected = checked, onClick = { vm.toggleGenre(genre) }) {
                            Text(if (checked) "✓ ${malGenreLabel(genre)}" else malGenreLabel(genre), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text(
                if (malLoggedIn) "Appui long sur une affiche : ajouter à « À voir » sur ta liste MAL."
                else "Connecte ton compte MAL (Réglages) pour masquer ta liste et ajouter des séries à voir.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { item ->
        MediaCard(
            item = item.card,
            width = GridPosterWidth,
            showProviderBadge = false,
            onClick = { item.target?.let(onOpenSeries) ?: onOpenMal(item.malId) },
            onLongClick = { scope.launch { graph.addToPlanToWatch(context, item.malId, item.card.series.title) } },
        )
    }
}
