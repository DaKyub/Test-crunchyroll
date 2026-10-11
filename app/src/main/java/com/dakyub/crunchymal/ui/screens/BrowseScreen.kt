package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.dakyub.crunchymal.ui.components.nextCompanionFilter
import com.dakyub.crunchymal.ui.components.CompanionFilter
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.dakyub.crunchymal.data.TmdbPlatforms
import kotlinx.coroutines.launch
import com.dakyub.crunchymal.data.mergeProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

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
    /** Catégorie choisie sur chaque plateforme affichée (« Tout » par défaut). */
    val selections: Map<Provider, BrowseSelection> = emptyMap(),
    val sort: BrowseSort = BrowseSort.POPULAR,
    val statuses: Set<WatchStatus> = emptySet(),
    val minScore: Double? = null,
    val loading: Boolean = false,
    val results: List<CardItem> = emptyList(),
    val error: String? = null,
    /** Plateforme TMDB affichée (Netflix, Prime Video, Disney+) à la place de Crunchyroll / ADN. */
    val platform: Pair<Int, String>? = null,
    /** Seulement les séries disponibles sur une seule plateforme en France. */
    val exclusiveOnly: Boolean = false,
    val companion: CompanionFilter? = null,
)

/** Carte d'un catalogue de plateforme (TMDB) : pas de fiche Crunchyroll / ADN, seulement MAL. */
private val CardItem.isPlatformItem: Boolean get() = series.id.startsWith("tmdb-")

/** Clé de la série dans le cache des plateformes des catalogues. */
private val CardItem.catalogKey: String
    get() = if (isPlatformItem) "tmdb:" + series.id.removePrefix("tmdb-") else "${series.provider.name}:${series.id}"

/** Liste affichée après filtres (statut, note MAL) et tri. */
data class BrowseVisible(
    val items: List<CardItem> = emptyList(),
    val progressKnown: Int = 0,
    val progressNeeded: Int = 0,
    val malKnown: Int = 0,
    /** Séries dont les plateformes en France sont connues (filtre « Exclusivités »). */
    val platformsKnown: Int = 0,
)

class BrowseViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(BrowseUi())
    private var job: Job? = null
    private var progressJob: Job? = null
    /** Séries de l'historique Crunchyroll : les autres sont « non commencées » sans rien calculer. */
    private val crWatched = MutableStateFlow<Set<String>?>(null)

    // La connexion ADN change ce qui est calculable : elle relance le calcul avec l'historique Crunchyroll.
    // Avec les marques « vu avec » (tags MAL) pour le filtre du même nom.
    private val watchedFlow = combine(graph.adn.loggedIn, crWatched, graph.companions.byMalId) { _, watched, marks -> watched to marks }

    val visible = combine(ui, graph.progress.summaries, graph.mal.records, watchedFlow, graph.watchPlatforms.catalog) { u, summaries, mal, watchedAndMarks, catalog ->
        val (watched, marks) = watchedAndMarks
        val malStatuses = graph.mal.myListStatuses.value
        /** Plateformes en France (TMDB + ce que l'app sait déjà), ou null tant que ce n'est pas vérifié. */
        fun platformsOf(item: CardItem): Set<String>? {
            val found = catalog[item.catalogKey]?.takeIf { it.source.isNotEmpty() } ?: return null
            val own = if (item.isPlatformItem) item.badges else (listOf(item.series.provider) + item.series.alsoOn).map { it.label }
            return (found.names + own).toSet()
        }
        fun statusOf(item: CardItem): WatchStatus? {
            // Plateformes : statut d'après la liste MAL (pas de progression disponible chez elles).
            if (item.isPlatformItem) {
                return when (mal[item.series.malKey]?.malId?.let { malStatuses[it] }) {
                    "completed" -> WatchStatus.COMPLETED
                    "watching", "on_hold", "dropped" -> WatchStatus.IN_PROGRESS
                    else -> WatchStatus.NOT_STARTED
                }
            }
            return summaries[item.series.progressKey]?.status
                ?: if (item.series.provider == Provider.CRUNCHYROLL && watched != null && item.series.id !in watched) WatchStatus.NOT_STARTED else null
        }
        val rows = u.results.map { item -> Triple(item, statusOf(item), mal[item.series.malKey]?.score) }
        val filtered = rows
            .filter { (_, status, _) -> u.statuses.accepts(status) }
            .filter { (_, _, score) -> u.minScore == null || (score != null && score >= u.minScore) }
            .filter { (item, _, _) -> !u.exclusiveOnly || platformsOf(item)?.size == 1 }
            .filter { (item, _, _) -> u.companion?.accepts(mal[item.series.malKey]?.malId?.let { marks[it] }.orEmpty()) ?: true }
        val sorted = if (u.sort == BrowseSort.MAL) filtered.sortedByDescending { it.third ?: -1.0 } else filtered
        val trackable = u.results.filter { !it.isPlatformItem && graph.progressAvailable(it.series) }
        BrowseVisible(
            items = sorted.map { (item, _, _) ->
                val summary = summaries[item.series.progressKey]
                if (summary != null && u.statuses.isNotEmpty()) item.copy(subtitle = "${summary.watched}/${summary.total} ép.") else item
            },
            progressKnown = trackable.count { statusOf(it) != null },
            progressNeeded = if (u.statuses.isEmpty()) 0 else trackable.size,
            malKnown = u.results.count { mal.containsKey(it.series.malKey) },
            platformsKnown = u.results.count { catalog[it.catalogKey] != null },
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
            // Une catégorie par plateforme affichée : celle déjà choisie si elle existe encore (ADN renomme
            // parfois ses genres), sinon « Tout ».
            val selections = providers.associateWith { provider ->
                ui.value.selections[provider]
                    ?.takeIf { provider != Provider.ADN || it.isAll || it.id in adnGenres }
                    ?: BrowseSelection.all(provider)
            }
            ui.value = ui.value.copy(crCategories = categories, adnGenres = adnGenres, selections = selections)
            reload()
        }
    }

    fun select(selection: BrowseSelection) {
        ui.value = ui.value.copy(selections = ui.value.selections + (selection.provider to selection), platform = null)
        reload()
    }

    /** Catalogue anime d'une plateforme (TMDB), à la place des catégories Crunchyroll / ADN. */
    fun selectPlatform(platform: Pair<Int, String>) {
        ui.value = ui.value.copy(platform = platform)
        reload()
    }

    fun nextCompanion() {
        ui.value = ui.value.copy(companion = nextCompanionFilter(ui.value.companion, graph.companions.people.value))
    }

    fun toggleExclusive() {
        ui.value = ui.value.copy(exclusiveOnly = !ui.value.exclusiveOnly)
        requestPlatformsIfNeeded()
    }

    /** Plateformes de chaque série (TMDB), vérifiées seulement quand le filtre « Exclusivités » est actif. */
    private fun requestPlatformsIfNeeded() {
        if (!ui.value.exclusiveOnly) return
        ui.value.results.forEach { item ->
            if (item.isPlatformItem) {
                item.series.id.removePrefix("tmdb-").toIntOrNull()?.let { graph.watchPlatforms.requestTmdbShow(it) }
            } else {
                graph.watchPlatforms.requestByTitle(item.catalogKey, item.series.malTitles + item.series.title)
            }
        }
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
        val refs = ui.value.results.filter { !it.isPlatformItem }.map { it.series }.filter { graph.progressAvailable(it) }
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
        ui.value.platform?.let { loadPlatform(it); return }
        val selections = ui.value.selections
        if (selections.isEmpty()) return
        val alpha = ui.value.sort == BrowseSort.ALPHA
        job?.cancel()
        job = viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            val results = selections.values.map { selection -> async { selection.provider to runCatching { load(selection, alpha) } } }.awaitAll()
            val cr = results.firstOrNull { it.first == Provider.CRUNCHYROLL }?.second?.getOrNull().orEmpty()
            val adn = results.firstOrNull { it.first == Provider.ADN }?.second?.getOrNull().orEmpty()
            // Les deux plateformes dans une seule grille, sans doublon (une série sur les deux porte les deux badges).
            val merged = withContext(Dispatchers.Default) { mergeRanked(cr, adn, alpha) }
            val error = results.mapNotNull { (provider, result) -> result.exceptionOrNull()?.let { "${provider.label} : ${it.message}" } }
                .joinToString(" · ").ifBlank { null }
            ui.value = ui.value.copy(loading = false, results = merged, error = error.takeIf { merged.isEmpty() })
            merged.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
            computeProgressIfNeeded()
            requestPlatformsIfNeeded()
        }
    }

    private fun loadPlatform(platform: Pair<Int, String>) {
        val alpha = ui.value.sort == BrowseSort.ALPHA
        job?.cancel()
        job = viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null, results = emptyList())
            runCatching { graph.watchPlatforms.animeOn(platform.first) }
                .onSuccess { shows ->
                    val cards = shows.map { show ->
                        CardItem(
                            series = SeriesRef(
                                id = "tmdb-${show.id}",
                                title = show.name,
                                posterUrl = show.posterUrl,
                                altTitles = listOf(show.originalName).filter { it.isNotBlank() },
                                malKeyOverride = "tmdb:${show.id}",
                            ),
                            subtitle = show.firstAirDate?.take(4),
                            badges = listOf(platform.second),
                        )
                    }.let { list -> if (alpha) list.sortedBy { it.series.title.lowercase() } else list }
                    ui.value = ui.value.copy(loading = false, results = cards)
                    cards.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
                    requestPlatformsIfNeeded()
                    graph.mal.refreshMyListSeenIds()
                }
                .onFailure { ui.value = ui.value.copy(loading = false, error = it.message ?: "Erreur TMDB") }
        }
    }

    private suspend fun load(selection: BrowseSelection, alpha: Boolean): List<CardItem> {
        val sortBy = if (alpha) "alphabetical" else "popularity"
        val order = if (alpha) "alpha" else "popular"
        return when {
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
    }

    /**
     * Fusion Crunchyroll + ADN : doublons regroupés ([mergeProviders]), puis entrelacement par rang de
     * popularité (1er CR, 1er ADN, 2e CR…) ou tri par titre.
     */
    private fun mergeRanked(cr: List<CardItem>, adn: List<CardItem>, alpha: Boolean): List<CardItem> {
        if (cr.isEmpty() || adn.isEmpty()) return cr + adn
        val merged = mergeProviders(cr, adn)
        if (alpha) return merged.sortedBy { it.series.title.lowercase() }
        val crRank = cr.withIndex().associate { (i, item) -> item.series.progressKey to i }
        val adnRank = adn.withIndex().associate { (i, item) -> item.series.progressKey to i }
        return merged.sortedWith(compareBy({ crRank[it.series.progressKey] ?: adnRank[it.series.progressKey] ?: Int.MAX_VALUE }, { it.series.provider.ordinal }))
    }
}

@Composable
fun BrowseScreen(onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit = {}) {
    val graph = LocalGraph.current
    val vm = viewModel { BrowseViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val visible by vm.visible.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val adnLoggedIn by graph.adn.loggedIn.collectAsState()
    val malRecords by graph.mal.records.collectAsState()
    val malLoggedIn by graph.malAuth.loggedIn.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(providers) { vm.ensure(providers) }

    val message = when {
        ui.loading && ui.results.isEmpty() -> "Chargement…"
        ui.error != null -> "Erreur : ${ui.error}"
        ui.selections.isEmpty() -> "Aucune catégorie disponible."
        ui.results.isEmpty() -> "Aucune série dans cette catégorie."
        visible.items.isEmpty() -> when {
            visible.progressNeeded > visible.progressKnown -> "Calcul de la progression…"
            ui.exclusiveOnly && visible.platformsKnown < ui.results.size -> "Vérification des plateformes…"
            else -> "Aucune série ne correspond à ces filtres."
        }
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
                    ui.selections[Provider.CRUNCHYROLL].takeIf { ui.platform == null },
                    vm::select,
                )
            }
            if (Provider.ADN in providers) {
                CategoryRow(
                    "ADN",
                    listOf(BrowseSelection.all(Provider.ADN)) + ui.adnGenres.map { BrowseSelection(Provider.ADN, it, adnGenreLabel(it)) },
                    ui.selections[Provider.ADN].takeIf { ui.platform == null },
                    vm::select,
                )
            }
            // Catalogues anime des autres plateformes (TMDB, France) : il faut la clé TMDB.
            if (graph.settings.tmdbKey.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Autres", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TmdbPlatforms) { platform ->
                            FilterChip(selected = ui.platform == platform, onClick = { vm.selectPlatform(platform) }) { Text(platform.second) }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    ui.platform?.let { "Animes sur ${it.second} (France, d'après TMDB)" }
                        ?: ui.selections.values.joinToString(" + ") { "${it.title} · ${it.provider.label}" },
                    style = MaterialTheme.typography.titleMedium,
                )
                CycleButton("Tri", ui.sort.label, vm::nextSort)
                CycleButton("MAL ≥", ui.minScore?.toString() ?: "toutes", vm::nextMinScore)
                if (malLoggedIn) CycleButton("Vu avec", ui.companion?.label ?: "toutes", vm::nextCompanion)
                if (graph.settings.tmdbKey.isNotBlank()) {
                    FilterChip(selected = ui.exclusiveOnly, onClick = vm::toggleExclusive) {
                        Text(if (ui.exclusiveOnly) "✓ Exclusivités" else "Exclusivités")
                    }
                }
            }
            StatusChips(ui.statuses, vm::toggleStatus)
            Text(
                buildString {
                    append("${visible.items.size}/${ui.results.size} séries · MAL ${visible.malKnown}/${ui.results.size}")
                    if (visible.progressNeeded > 0) append(" · progression ${visible.progressKnown}/${visible.progressNeeded}")
                    if (ui.exclusiveOnly) {
                        append(" · exclusivités (une seule plateforme en France, d'après TMDB) : vérifiées ${visible.platformsKnown}/${ui.results.size}")
                    }
                    if (ui.statuses.isNotEmpty() && Provider.ADN in providers && !adnLoggedIn) append(" · statut ADN : connecte ADN dans Réglages")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { item ->
        MediaCard(
            item = item,
            width = GridPosterWidth,
            onClick = {
                if (!item.isPlatformItem) onOpenSeries(item.series)
                else malRecords[item.series.malKey]?.malId?.let(onOpenMal)
                    ?: Toast.makeText(context, "Fiche MAL pas encore trouvée pour ${item.series.title}", Toast.LENGTH_SHORT).show()
            },
        )
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
