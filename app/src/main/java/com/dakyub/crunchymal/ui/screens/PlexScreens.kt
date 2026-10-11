package com.dakyub.crunchymal.ui.screens

import com.dakyub.crunchymal.ui.components.CompanionsButton
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.PlexApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.plex.PlexApi
import com.dakyub.crunchymal.data.plex.PlexDirectory
import com.dakyub.crunchymal.data.plex.PlexItem
import com.dakyub.crunchymal.data.progress.WatchStatus
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.ExpandableSynopsis
import com.dakyub.crunchymal.ui.components.GenresLine
import com.dakyub.crunchymal.ui.components.GridPosterWidth
import com.dakyub.crunchymal.ui.components.MalBadge
import com.dakyub.crunchymal.ui.components.MalEntryChoice
import com.dakyub.crunchymal.ui.components.MalListDialog
import com.dakyub.crunchymal.ui.components.MalRecommendationsRow
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterGrid
import com.dakyub.crunchymal.ui.components.airingLabel
import com.dakyub.crunchymal.ui.components.rememberMalRecord
import com.dakyub.crunchymal.ui.components.seasonChipColors
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

private val PlexOrange = Color(0xFFE5A00D)

/** Série Plex sous forme de référence (clé MAL « plex:<id> » pour la note et les corrections). */
private fun PlexItem.showRef(plex: PlexApi) = SeriesRef(
    id = "plex-$ratingKey",
    title = title,
    posterUrl = plex.image(thumb, 240, 360),
    altTitles = listOfNotNull(originalTitle),
    malKeyOverride = "plex:$ratingKey",
)

private fun PlexItem.status(): WatchStatus = when {
    viewedLeafCount <= 0 -> WatchStatus.NOT_STARTED
    viewedLeafCount >= leafCount -> WatchStatus.COMPLETED
    else -> WatchStatus.IN_PROGRESS
}

data class PlexUi(
    val loading: Boolean = true,
    val error: String? = null,
    val libraries: List<PlexDirectory> = emptyList(),
    val library: PlexDirectory? = null,
    val shows: List<PlexItem> = emptyList(),
    val onDeck: List<PlexItem> = emptyList(),
    val statuses: Set<WatchStatus> = emptySet(),
    val minScore: Double? = null,
    val sort: SortMode = SortMode.TITLE,
)

class PlexViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(PlexUi())

    val visible = combine(ui, graph.mal.records) { u, mal ->
        u.shows
            .filter { u.statuses.accepts(it.status()) }
            .filter { show -> u.minScore == null || (mal["plex:${show.ratingKey}"]?.score ?: -1.0) >= u.minScore }
            .let { list ->
                when (u.sort) {
                    SortMode.MAL -> list.sortedByDescending { mal["plex:${it.ratingKey}"]?.score ?: -1.0 }
                    SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                    SortMode.RECENT -> list
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
    }

    fun load(library: PlexDirectory? = ui.value.library) {
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            runCatching {
                val libraries = ui.value.libraries.ifEmpty { graph.plex.libraries() }
                val selected = library ?: libraries.firstOrNull()
                val onDeck = async { runCatching { graph.plex.onDeck() }.getOrDefault(emptyList()) }
                val shows = selected?.let { graph.plex.shows(it.key) }.orEmpty()
                ui.value = ui.value.copy(loading = false, libraries = libraries, library = selected, shows = shows, onDeck = onDeck.await())
                shows.forEach { graph.mal.request("plex:${it.ratingKey}", it.showRef(graph.plex).malTitles) }
            }.onFailure { ui.value = ui.value.copy(loading = false, error = it.message ?: "Serveur Plex injoignable") }
        }
    }

    fun toggleStatus(status: WatchStatus) { ui.value = ui.value.copy(statuses = ui.value.statuses.toggle(status)) }
    fun nextMinScore() { ui.value = ui.value.copy(minScore = MinScores[(MinScores.indexOf(ui.value.minScore) + 1) % MinScores.size]) }
    fun nextSort() { ui.value = ui.value.copy(sort = SortMode.entries[(ui.value.sort.ordinal + 1) % SortMode.entries.size]) }
}

/** Onglet Plex : séries de la bibliothèque choisie (note MAL, progression Plex) et « Reprendre ». */
@Composable
fun PlexScreen(onOpenPlexShow: (String) -> Unit) {
    val graph = LocalGraph.current
    val connected by graph.plex.connected.collectAsState()
    if (!connected) {
        CenteredMessage("Connecte ton serveur Plex dans Réglages → Plex.")
        return
    }
    val vm = viewModel { PlexViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val shows by vm.visible.collectAsState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!ui.loading) vm.load() }

    val message = when {
        ui.loading && ui.shows.isEmpty() -> "Chargement de ${graph.plex.serverName}…"
        ui.error != null -> "Erreur : ${ui.error}"
        ui.libraries.isEmpty() -> "Aucune bibliothèque de séries sur ${graph.plex.serverName}."
        shows.isEmpty() -> "Aucune série ne correspond à ces filtres."
        else -> null
    }
    PosterGrid(
        items = shows,
        message = message,
        key = { it.ratingKey },
        actionLabel = "Réessayer".takeIf { ui.error != null },
        onAction = { vm.load() },
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(graph.plex.serverName, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.libraries) { library ->
                        FilterChip(selected = library == ui.library, onClick = { vm.load(library) }) { Text(library.title) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                CycleButton("Tri", ui.sort.label, vm::nextSort)
                CycleButton("MAL ≥", ui.minScore?.toString() ?: "toutes", vm::nextMinScore)
                Text(
                    "${shows.size}/${ui.shows.size} séries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChips(ui.statuses, vm::toggleStatus)
            if (ui.onDeck.isNotEmpty()) {
                Text("Reprendre", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(ui.onDeck, key = { it.ratingKey }) { episode ->
                        val showKey = episode.grandparentRatingKey.orEmpty()
                        MediaCard(
                            item = CardItem(
                                series = SeriesRef(
                                    id = "plex-$showKey",
                                    title = episode.grandparentTitle ?: episode.title,
                                    wideUrl = graph.plex.image(episode.thumb, 400, 225),
                                    malKeyOverride = "plex:$showKey",
                                ),
                                subtitle = "${episode.episodeLabel} · ${episode.title}",
                                progress = episode.duration.takeIf { it > 0 }?.let { (episode.viewOffset.toFloat() / it).coerceIn(0f, 1f) }?.takeIf { it > 0f },
                                wide = true,
                                badges = listOf("Plex"),
                            ),
                            onClick = { PlexApp.open(context, graph.plex.machineId, episode.ratingKey, graph.settings.plexLinkFormat) },
                            onLongClick = { if (showKey.isNotBlank()) onOpenPlexShow(showKey) },
                        )
                    }
                }
            }
        },
    ) { show ->
        MediaCard(
            item = CardItem(
                series = show.showRef(graph.plex),
                subtitle = if (show.leafCount > 0) "${show.viewedLeafCount}/${show.leafCount} ép." else show.year?.toString(),
                progress = show.leafCount.takeIf { it > 0 }?.let { show.viewedLeafCount.toFloat() / it }?.takeIf { it > 0f },
                badges = listOf("Plex"),
            ),
            width = GridPosterWidth,
            onClick = { onOpenPlexShow(show.ratingKey) },
        )
    }
}

data class PlexShowUi(
    val loading: Boolean = true,
    val error: String? = null,
    val show: PlexItem? = null,
    val episodes: List<PlexItem> = emptyList(),
    val selectedSeason: Int = 0,
) {
    val seasons: List<Pair<Int, List<PlexItem>>> get() = episodes.groupBy { it.parentIndex ?: 1 }.toSortedMap().map { (k, v) -> k to v }

    /** Épisode à lancer : celui commencé, sinon le premier pas vu après le dernier vu. */
    val next: PlexItem?
        get() = episodes.firstOrNull { !it.watched && it.viewOffset > 0 }
            ?: episodes.indexOfLast { it.watched }.let { last -> episodes.getOrNull(last + 1) }
}

class PlexShowViewModel(private val graph: Graph, private val ratingKey: String) : ViewModel() {
    val state = MutableStateFlow(PlexShowUi())
    private var firstResume = true

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            runCatching {
                val show = async { graph.plex.item(ratingKey) }
                val episodes = async { graph.plex.episodes(ratingKey) }
                show.await() to episodes.await()
            }.onSuccess { (show, episodes) ->
                val current = state.value
                val seasons = episodes.groupBy { it.parentIndex ?: 1 }.toSortedMap().keys.toList()
                val nextSeason = PlexShowUi(episodes = episodes).next?.parentIndex
                state.value = current.copy(
                    loading = false,
                    show = show,
                    episodes = episodes,
                    selectedSeason = if (current.show == null) seasons.indexOf(nextSeason).coerceAtLeast(0) else current.selectedSeason,
                )
            }.onFailure { state.value = state.value.copy(loading = false, error = it.message ?: "Erreur Plex") }
        }
    }

    /** Au retour de l'app Plex, on relit la progression. */
    fun onResume() {
        if (firstResume) firstResume = false else load()
    }

    fun selectSeason(index: Int) { state.value = state.value.copy(selectedSeason = index) }
}

/** Fiche d'une série Plex : note MAL, progression Plex, épisodes à lancer dans l'app Plex. */
@Composable
fun PlexShowScreen(ratingKey: String, onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel(key = "plex-$ratingKey") { PlexShowViewModel(graph, ratingKey) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val malKey = "plex:$ratingKey"
    var malListOpen by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf(false) }
    val show = state.show
    if (malListOpen) MalListDialog(choices = listOf(MalEntryChoice("Série", malKey)), onDismiss = { malListOpen = false })
    if (correcting && show != null) MalCorrectionDialog(malKey = malKey, initialQuery = show.title, onDismiss = { correcting = false })

    when {
        state.loading && show == null -> CenteredMessage("Chargement…")
        state.error != null && show == null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        show != null -> {
            val ref = show.showRef(graph.plex)
            val record = rememberMalRecord(malKey, ref.malTitles)
            val watched = state.episodes.count { it.watched }
            val next = state.next
            val seasons = state.seasons
            val season = seasons.getOrNull(state.selectedSeason)
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            // Synchro MAL : épisodes vus sur Plex reportés sur la fiche MAL (option des réglages).
            LaunchedEffect(record?.malId, watched) {
                val malId = record?.malId ?: return@LaunchedEffect
                runCatching { graph.malSync.sync(malId, watched) }.getOrNull()
                    ?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        AsyncImage(
                            model = graph.plex.image(show.thumb, 300, 450),
                            contentDescription = show.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(150.dp).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(show.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MalBadge(malKey, ref.malTitles, large = true)
                                Text(
                                    listOfNotNull(
                                        record?.scoredBy?.let { String.format(Locale.FRANCE, "%,d votes", it) },
                                        record?.title?.takeIf { it != show.title }?.let { "MAL : $it" },
                                        airingLabel(record?.airing),
                                        show.year?.toString(),
                                        "$watched/${state.episodes.size} vus".takeIf { state.episodes.isNotEmpty() },
                                        "Plex",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GenresLine(record?.genres, emptyList())
                            show.summary?.takeIf { it.isNotBlank() }?.let {
                                // Focus sur le résumé : la fiche remonte tout en haut (titre, note MAL).
                                ExpandableSynopsis(it, onFocused = { scope.launch { delay(150); listState.animateScrollToItem(0) } })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                Button(onClick = {
                                    val episode = next ?: state.episodes.firstOrNull()
                                    if (episode != null) PlexApp.open(context, graph.plex.machineId, episode.ratingKey, graph.settings.plexLinkFormat)
                                }) {
                                    Text(
                                        when {
                                            next == null && watched > 0 -> "▶ Revoir"
                                            next != null && (watched > 0 || next.viewOffset > 0) -> "▶ Reprendre ${next.episodeLabel}"
                                            else -> "▶ Regarder"
                                        }
                                    )
                                }
                                OutlinedButton(onClick = { malListOpen = true }) { Text("Ma liste MAL") }
                                CompanionsButton(listOf(MalEntryChoice("Série", malKey)))
                                OutlinedButton(onClick = { correcting = true }) { Text("Corriger MAL") }
                                OutlinedButton(onClick = { vm.load() }) { Text("↻") }
                            }
                        }
                    }
                }
                if (seasons.size > 1) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(seasons) { index, (number, episodes) ->
                                FilterChip(
                                    selected = index == state.selectedSeason,
                                    onClick = { vm.selectSeason(index) },
                                    colors = seasonChipColors(PlexOrange),
                                ) { Text("Saison $number · ${episodes.count { it.watched }}/${episodes.size}") }
                            }
                        }
                    }
                }
                season?.let { (_, episodes) ->
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(episodes, key = { it.ratingKey }) { episode ->
                                MediaCard(
                                    item = CardItem(
                                        series = SeriesRef(
                                            id = episode.ratingKey,
                                            title = "${if (episode.watched) "✓ " else ""}${episode.episodeLabel} · ${episode.title}",
                                            wideUrl = graph.plex.image(episode.thumb, 400, 225),
                                        ),
                                        subtitle = if (episode.duration > 0) "${episode.duration / 60000} min" else null,
                                        progress = when {
                                            episode.watched -> 1f
                                            episode.duration > 0 && episode.viewOffset > 0 -> (episode.viewOffset.toFloat() / episode.duration).coerceIn(0f, 1f)
                                            else -> null
                                        },
                                        wide = true,
                                    ),
                                    onClick = { PlexApp.open(context, graph.plex.machineId, episode.ratingKey, graph.settings.plexLinkFormat) },
                                    showMal = false,
                                )
                            }
                        }
                    }
                }
                // Recommandations des membres MAL, avec où les regarder.
                item { MalRecommendationsRow(record?.malId, onOpenSeries, onOpenMal) }
            }
        }
    }
}
