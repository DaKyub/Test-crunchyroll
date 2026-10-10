package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.crunchyroll.CrSeries
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.progress.SeriesTree
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MalBadge
import com.dakyub.crunchymal.ui.components.MalRecommendationsRow
import com.dakyub.crunchymal.ui.components.airingLabel
import com.dakyub.crunchymal.ui.theme.CrunchyOrange
import com.dakyub.crunchymal.ui.components.seasonChipColors
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.TvTextField
import com.dakyub.crunchymal.ui.components.rememberMalRecord
import com.dakyub.crunchymal.ui.components.GenresLine
import com.dakyub.crunchymal.ui.components.MalEntryChoice
import com.dakyub.crunchymal.ui.components.MalListDialog
import com.dakyub.crunchymal.ui.components.rememberSeasonRatings
import com.dakyub.crunchymal.data.ratings.firstEpisodeNumber
import kotlinx.coroutines.flow.MutableStateFlow
import com.dakyub.crunchymal.data.mal.MalRecord
import com.dakyub.crunchymal.data.mal.TitleMatcher
import com.dakyub.crunchymal.data.crunchyroll.CrSeason
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dakyub.crunchymal.ui.components.ExpandableSynopsis
import kotlinx.coroutines.delay
import java.util.Locale

data class SeriesUi(
    val loading: Boolean = true,
    val error: String? = null,
    val series: CrSeries? = null,
    val tree: SeriesTree? = null,
    val treeError: String? = null,
    val inWatchlist: Boolean? = null,
    val selectedSeason: Int = 0,
    /** Catégories Crunchyroll (repli quand MAL n'a pas de genres). */
    val categories: List<String> = emptyList(),
)

class SeriesViewModel(private val graph: Graph, private val seriesId: String) : ViewModel() {
    val state = MutableStateFlow(SeriesUi())
    private var firstResume = true

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            val series = try {
                graph.api.series(seriesId)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = e.message ?: "Erreur")
                return@launch
            }
            state.value = state.value.copy(loading = false, series = series)
            launch {
                val categories = runCatching { graph.api.categoriesOf(seriesId).map { it.title } }.getOrDefault(emptyList())
                state.value = state.value.copy(categories = categories)
            }
            launch {
                val inList = runCatching { graph.api.isInWatchlist(seriesId) }.getOrNull()
                state.value = state.value.copy(inWatchlist = inList)
            }
            refreshProgress(force = false)
        }
    }

    fun refreshProgress(force: Boolean) {
        viewModelScope.launch {
            runCatching { graph.progress.tree(seriesId, force) }
                .onSuccess { tree ->
                    val current = state.value
                    // Au premier chargement, on se place sur la saison de l'épisode suivant.
                    val selected = if (current.tree == null) {
                        tree.seasons.indexOfFirst { s -> s.episodes.any { it.episode.id == tree.summary.nextEpisodeId } }
                            .coerceAtLeast(0)
                    } else current.selectedSeason.coerceAtMost((tree.seasons.size - 1).coerceAtLeast(0))
                    state.value = current.copy(tree = tree, treeError = null, selectedSeason = selected)
                }
                .onFailure { state.value = state.value.copy(treeError = it.message) }
        }
    }

    /** Au retour de l'app officielle, on recalcule la progression. */
    fun onResume() {
        if (firstResume) {
            firstResume = false
            return
        }
        graph.progress.invalidate(seriesId)
        refreshProgress(force = true)
    }

    fun selectSeason(index: Int) {
        state.value = state.value.copy(selectedSeason = index)
    }

    fun toggleWatchlist() {
        val current = state.value.inWatchlist ?: return
        viewModelScope.launch {
            runCatching {
                if (current) graph.api.removeFromWatchlist(seriesId) else graph.api.addToWatchlist(seriesId)
            }.onSuccess {
                graph.watchlist.invalidate()
                state.value = state.value.copy(inWatchlist = !current)
            }
        }
    }
}

/** Clé + titres de recherche pour la note MAL d'une saison. */
private val genericSeasonTitle = Regex("""(?i)^\s*((season|saison|part|cour)\s*)?\d*\s*$""")

/**
 * Titres de recherche MAL d'une saison. Un titre générique (« Season 2 ») ne suffit pas : il trouverait
 * n'importe quel anime, on le complète avec le nom de la série.
 */
private fun seasonMalTitles(seriesTitle: String, seriesSlug: String, seasonTitle: String, seasonSlug: String, seasonNumber: Int): List<String> {
    val series = seriesSlug.replace('-', ' ').ifBlank { seriesTitle }
    fun specific(title: String) = title.takeUnless { it.isBlank() || genericSeasonTitle.matches(it) }
    fun withSeries(title: String) =
        if (TitleMatcher.normalize(title).contains(TitleMatcher.normalize(series))) title else "$series $title"
    return listOfNotNull(
        specific(seasonTitle)?.let { withSeries(it) },
        specific(seasonSlug.replace('-', ' '))?.let { withSeries(it) },
        "$series season $seasonNumber".takeIf { seasonNumber > 1 },
        "$series $seasonNumber".takeIf { seasonNumber > 1 },
    ).distinct()
}

/**
 * Clé MAL d'une saison : la première saison est la fiche MAL de la série. Les autres ont leur propre clé
 * (« seasonmal: » : les anciennes recherches « season: » trouvaient parfois un autre anime) ; une
 * correction manuelle faite avec l'ancienne clé est conservée.
 */
private fun seasonMalKey(seriesKey: String, index: Int, season: CrSeason, records: Map<String, MalRecord>): String = when {
    records["season:${season.id}"]?.manual == true -> "season:${season.id}"
    index == 0 && season.seasonNumber <= 1 -> seriesKey
    else -> "seasonmal:${season.id}"
}

@Composable
fun SeriesScreen(seriesId: String, onOpenSeries: (SeriesRef) -> Unit = {}, onOpenMal: (Int) -> Unit = {}) {
    val graph = LocalGraph.current
    val vm = viewModel(key = seriesId) { SeriesViewModel(graph, seriesId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var correcting by remember { mutableStateOf<Pair<String, String>?>(null) } // clé MAL, requête
    var malListOpen by remember { mutableStateOf(false) }
    val malRecords by graph.mal.records.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val series = state.series
    when {
        state.loading && series == null -> CenteredMessage("Chargement…")
        state.error != null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        series != null -> {
            val ref = SeriesRef(series.id, series.title, series.slugTitle)
            val tree = state.tree
            val summary = tree?.summary
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = series.images.posterWide.best(1280),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to MaterialTheme.colorScheme.background,
                                0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                1f to Color.Transparent,
                            )
                        )
                )
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(series.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MalBadge(ref.malKey, ref.malTitles, large = true)
                            val record = rememberMalRecord(ref.malKey, ref.malTitles)
                            val info = listOfNotNull(
                                record?.scoredBy?.let { String.format(Locale.FRANCE, "%,d votes", it) },
                                record?.title?.takeIf { it != series.title }?.let { "MAL : $it" },
                                series.launchYear?.toString(),
                                summary?.let { "${it.watched}/${it.total} épisodes vus" },
                            ).joinToString(" · ")
                            Text(info, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        val record = rememberMalRecord(ref.malKey, ref.malTitles)
                        GenresLine(record?.genres, state.categories)
                    }
                    if (series.description.isNotBlank()) {
                        item {
                            // Focus sur le résumé : la fiche remonte tout en haut (titre, note MAL).
                            ExpandableSynopsis(
                                series.description,
                                onFocused = { scope.launch { delay(150); listState.animateScrollToItem(0) } },
                                modifier = Modifier.fillMaxWidth(0.6f),
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val nextId = summary?.nextEpisodeId
                            Button(onClick = {
                                if (nextId != null) OfficialApp.openEpisode(context, nextId, series.id) else OfficialApp.openSeries(context, series.id)
                            }) {
                                Text(
                                    when {
                                        summary == null -> "▶ Ouvrir dans Crunchyroll"
                                        nextId == null -> "▶ Revoir"
                                        summary.started -> "▶ Reprendre ${summary.nextLabel ?: ""}"
                                        else -> "▶ Commencer"
                                    }
                                )
                            }
                            OutlinedButton(onClick = { OfficialApp.openSeries(context, series.id) }) { Text("Fiche Crunchyroll") }
                            state.inWatchlist?.let { inList ->
                                OutlinedButton(onClick = { vm.toggleWatchlist() }) {
                                    Text(if (inList) "✓ Dans la watchlist" else "+ Watchlist")
                                }
                            }
                            OutlinedButton(onClick = { malListOpen = true }) { Text("Ma liste MAL") }
                            OutlinedButton(onClick = { correcting = ref.malKey to (ref.malTitles.firstOrNull() ?: series.title) }) {
                                Text("Corriger MAL")
                            }
                            OutlinedButton(onClick = { vm.refreshProgress(force = true) }) { Text("↻") }
                        }
                    }

                    if (tree == null) {
                        item {
                            Text(
                                state.treeError?.let { "Progression indisponible : $it" } ?: "Chargement des épisodes…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (tree.seasons.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(tree.seasons) { index, node ->
                                    val s = node.season
                                    val key = seasonMalKey(ref.malKey, index, s, malRecords)
                                    val titles = if (key == ref.malKey) ref.malTitles
                                    else seasonMalTitles(series.title, series.slugTitle, s.title, s.slugTitle, s.seasonNumber)
                                    val watched = node.episodes.count { it.watched }
                                    FilterChip(
                                        selected = index == state.selectedSeason,
                                        onClick = { vm.selectSeason(index) },
                                        onLongClick = { correcting = key to (titles.firstOrNull() ?: s.title) },
                                        colors = seasonChipColors(CrunchyOrange),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("S${s.seasonNumber} · $watched/${node.episodes.size}")
                                            MalBadge(key, titles)
                                            // Diffusion de la saison d'après MAL (en cours, terminée, pas encore sortie).
                                            airingLabel(malRecords[key]?.airing)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            val season = tree.seasons.getOrNull(state.selectedSeason)
                            val seriesRecord = rememberMalRecord(ref.malKey, ref.malTitles)
                            val ratingTitles = listOfNotNull(seriesRecord?.englishTitle, series.slugTitle.replace('-', ' '), series.title)
                                .filter { it.isNotBlank() }.distinct()
                            val ratings = rememberSeasonRatings(
                                ratingTitles,
                                season?.season?.seasonNumber ?: 1,
                                episodeCount = season?.episodes?.size ?: 0,
                                episodesBefore = tree.seasons.take(state.selectedSeason).sumOf { it.episodes.size },
                            )
                            val firstNumber = firstEpisodeNumber(season?.episodes.orEmpty().map { it.episode.episodeNumber })
                            if (season != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(season.season.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Appui long sur une saison pour corriger sa correspondance MAL.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        itemsIndexed(season.episodes, key = { _, n -> n.episode.id }) { index, node ->
                                            val ep = node.episode
                                            val rating = ratings.find(ep.episodeNumber, index, firstNumber)
                                            MediaCard(
                                                item = CardItem(
                                                    series = SeriesRef(ep.id, "${if (node.watched) "✓ " else ""}${ep.label} · ${ep.title}", wideUrl = ep.images.thumbnail.best(400)),
                                                    subtitle = listOfNotNull(
                                                        "à venir".takeIf { !node.available },
                                                        rating?.label,
                                                        if (ep.durationMs > 0) "${ep.durationMs / 60000} min" else null,
                                                    ).joinToString(" · ").ifBlank { null },
                                                    progress = node.progress.takeIf { it > 0f },
                                                    wide = true,
                                                ),
                                                onClick = { OfficialApp.openEpisode(context, ep.id, series.id) },
                                                showMal = false,
                                            )
                                        }
                                    }
                                    if (ratings.info.isNotBlank()) {
                                        Text(ratings.info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    // Recommandations : membres MAL, puis séries similaires d'après Crunchyroll.
                    item { MalRecommendationsRow(malRecords[ref.malKey]?.malId, onOpenSeries, onOpenMal) }
                    item { CrSimilarRow(series.id, onOpenSeries) }
                }
            }
        }
    }

    if (malListOpen && series != null) {
        val selectedSeason = state.tree?.seasons?.getOrNull(state.selectedSeason)?.season
        MalListDialog(
            choices = listOfNotNull(
                MalEntryChoice("Série", "series:${series.id}"),
                selectedSeason?.let {
                    MalEntryChoice("Saison ${it.seasonNumber}", seasonMalKey("series:${series.id}", state.selectedSeason, it, malRecords))
                },
            ),
            onDismiss = { malListOpen = false },
        )
    }

    correcting?.let { (key, query) ->
        MalCorrectionDialog(malKey = key, initialQuery = query, onDismiss = { correcting = null })
    }
}

@Composable
private fun MalCorrectionDialog(malKey: String, initialQuery: String, onDismiss: () -> Unit) {
    val mal = LocalGraph.current.mal
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<MalAnime>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        results = null
        error = null
        scope.launch {
            runCatching { mal.candidates(query) }
                .onSuccess { results = it }
                .onFailure { error = it.message }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { search() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(640.dp)
                .height(480.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choisir la fiche MyAnimeList", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(query, { query = it }, "Titre à rechercher", Modifier.weight(1f), onSubmit = ::search)
                Button(onClick = ::search) { Text("Chercher") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                item {
                    ListItem(
                        selected = false,
                        onClick = { scope.launch { mal.setManual(malKey, null); onDismiss() } },
                        headlineContent = { Text("Pas de fiche MAL pour cette série") },
                    )
                }
                when {
                    error != null -> item { Text("Erreur : $error", color = MaterialTheme.colorScheme.error) }
                    results == null -> item { Text("Recherche…") }
                    results!!.isEmpty() -> item { Text("Aucun résultat") }
                    else -> items(results!!, key = { it.malId }) { anime ->
                        ListItem(
                            selected = false,
                            onClick = { scope.launch { mal.setManual(malKey, anime); onDismiss() } },
                            headlineContent = { Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(anime.titleEnglish, anime.type, anime.startYear?.toString(), anime.episodes?.let { "$it ép." })
                                        .joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Text(anime.score?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A", fontWeight = FontWeight.Bold)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Séries que Crunchyroll juge similaires (« similar_to »). */
@Composable
private fun CrSimilarRow(seriesId: String, onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val similar by produceState<List<CardItem>?>(null, seriesId) {
        value = runCatching { graph.api.similarTo(seriesId).map { it.toSeriesCard() } }.getOrDefault(emptyList())
    }
    val list = similar?.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Similaires sur Crunchyroll", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list) { item -> MediaCard(item = item, onClick = { onOpenSeries(item.series) }) }
        }
    }
}
