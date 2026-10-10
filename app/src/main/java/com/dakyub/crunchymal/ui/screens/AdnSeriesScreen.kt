package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.OutlinedButton
import com.dakyub.crunchymal.ui.components.MalEntryChoice
import com.dakyub.crunchymal.ui.components.MalListDialog
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dakyub.crunchymal.data.progress.AdnEpisodeNode
import com.dakyub.crunchymal.data.progress.AdnSeriesTree
import com.dakyub.crunchymal.data.progress.ProgressSummary
import com.dakyub.crunchymal.data.progress.WatchStatus
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.AdnApp
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GenresLine
import com.dakyub.crunchymal.ui.components.MalBadge
import com.dakyub.crunchymal.ui.components.MinimalFocusScroll
import com.dakyub.crunchymal.ui.components.seasonChipColors
import com.dakyub.crunchymal.ui.theme.AdnBlue
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.rememberMalRecord
import com.dakyub.crunchymal.ui.components.rememberSeasonRatings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class AdnSeriesUi(
    val loading: Boolean = true,
    val error: String? = null,
    val show: AdnShow? = null,
    val episodes: List<AdnEpisodeNode> = emptyList(),
    /** Progression (null si non connecté à ADN ou historique injoignable). */
    val summary: ProgressSummary? = null,
    val selectedSeason: Int = 0,
    val inWatchlist: Boolean? = null,
    val watchlistBusy: Boolean = false,
) {
    /** Épisodes groupés par saison ("1", "Saga 1 : East Blue"…), dans l'ordre de la série. */
    val seasons: List<Pair<String, List<AdnEpisodeNode>>>
        get() = episodes.groupBy { it.video.seasonKey }.map { (k, v) -> k to v }
}

class AdnSeriesViewModel(private val graph: Graph, private val showId: String) : ViewModel() {
    val state = MutableStateFlow(AdnSeriesUi())
    private var firstResume = true

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            runCatching {
                val show = async { graph.adn.show(showId) }
                val tree = async { runCatching { graph.adnProgress.tree(showId, force) }.getOrNull() }
                show.await() to tree.await()
            }.onSuccess { (show, tree) ->
                applyTree(tree, show)
            }.onFailure {
                state.value = state.value.copy(loading = false, error = it.message ?: "Erreur")
            }
            if (graph.adn.loggedIn.value) {
                val inList = runCatching { graph.adn.inWatchlist(showId) }.getOrNull()
                state.value = state.value.copy(inWatchlist = inList)
            }
        }
    }

    private fun applyTree(tree: AdnSeriesTree?, show: AdnShow? = state.value.show) {
        val current = state.value
        val episodes = tree?.episodes ?: current.episodes
        val seasons = episodes.groupBy { it.video.seasonKey }.values.toList()
        // Au premier chargement, on se place sur la saison de l'épisode suivant.
        val selected = if (current.show == null) {
            seasons.indexOfFirst { s -> s.any { it.video.id.toString() == tree?.summary?.nextEpisodeId } }.coerceAtLeast(0)
        } else current.selectedSeason.coerceAtMost((seasons.size - 1).coerceAtLeast(0))
        state.value = current.copy(
            loading = false,
            show = show,
            episodes = episodes,
            summary = tree?.summary ?: current.summary,
            selectedSeason = selected,
        )
    }

    fun refreshProgress() {
        viewModelScope.launch {
            runCatching { graph.adnProgress.tree(showId, force = true) }.onSuccess { applyTree(it) }
        }
    }

    /** Au retour de l'app ADN, on recalcule la progression. */
    fun onResume() {
        if (firstResume) {
            firstResume = false
            return
        }
        if (graph.adn.loggedIn.value) refreshProgress()
    }

    fun toggleWatchlist(onResult: (String) -> Unit) {
        val current = state.value.inWatchlist ?: return
        viewModelScope.launch {
            state.value = state.value.copy(watchlistBusy = true)
            val change = runCatching { graph.adn.setInWatchlist(showId, add = !current) }
                .getOrElse { AdnApi.WatchlistChange(false, listOf("${it.javaClass.simpleName}: ${it.message}")) }
            val now = runCatching { graph.adn.inWatchlist(showId) }.getOrNull()
            graph.adnWatchlist.invalidate()
            state.value = state.value.copy(watchlistBusy = false, inWatchlist = now ?: current)
            onResult(
                when {
                    change.ok -> if (current) "Retirée de ta watchlist ADN" else "Ajoutée à ta watchlist ADN"
                    graph.diagnostics.configured -> {
                        // Détail des essais envoyé sur GitHub pour trouver la bonne méthode.
                        graph.diagnostics.launch("Watchlist ADN (${if (current) "retrait" else "ajout"})") { change.log }
                        "ADN a refusé la modification : détails envoyés sur GitHub"
                    }
                    else -> "ADN a refusé la modification (${change.log.drop(1).joinToString(", ") { it.substringAfter("→ ").substringBefore(" ·") }})"
                }
            )
        }
    }

    fun selectSeason(index: Int) {
        state.value = state.value.copy(selectedSeason = index)
    }
}

@Composable
fun AdnSeriesScreen(showId: String) {
    val graph = LocalGraph.current
    val vm = viewModel(key = "adn-$showId") { AdnSeriesViewModel(graph, showId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val adnLoggedIn by graph.adn.loggedIn.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val show = state.show
    var malListOpen by remember { mutableStateOf(false) }
    if (malListOpen && show != null) {
        MalListDialog(choices = listOf(MalEntryChoice("Série", show.toRef().malKey)), onDismiss = { malListOpen = false })
    }

    when {
        state.loading && show == null -> CenteredMessage("Chargement…")
        state.error != null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        show != null -> {
            val ref = show.toRef()
            val record = rememberMalRecord(ref.malKey, ref.malTitles)
            val seasons = state.seasons
            val season = seasons.getOrNull(state.selectedSeason)
            val ratingTitles = listOfNotNull(record?.englishTitle, show.originalTitle, show.title)
                .filter { it.isNotBlank() }.distinct()
            val ratings = rememberSeasonRatings(ratingTitles, season?.second?.firstOrNull()?.video?.seasonNumber ?: 1)
            val summary = state.summary
            val next = summary?.nextEpisodeId?.let { id -> state.episodes.firstOrNull { it.video.id.toString() == id } }

            MinimalFocusScroll {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            AsyncImage(
                                model = show.image2x ?: show.image,
                                contentDescription = show.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(150.dp)
                                    .aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(show.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MalBadge(ref.malKey, ref.malTitles, large = true)
                                    Text(
                                        listOfNotNull(
                                            record?.scoredBy?.let { String.format(Locale.FRANCE, "%,d votes", it) },
                                            record?.title?.takeIf { it != show.title }?.let { "MAL : $it" },
                                            "${state.episodes.size} épisodes".takeIf { state.episodes.isNotEmpty() },
                                            summary?.let { s ->
                                                when (s.status) {
                                                    WatchStatus.NOT_STARTED -> "non commencée"
                                                    WatchStatus.COMPLETED -> "✓ tout vu"
                                                    WatchStatus.UP_TO_DATE -> "à jour (${s.watched}/${s.total})"
                                                    WatchStatus.IN_PROGRESS -> "${s.watched}/${s.total} vus"
                                                }
                                            },
                                            "ADN",
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                GenresLine(record?.genres, show.genres)
                                show.summary?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { if (next != null) AdnApp.open(context, show, next.video) else AdnApp.openShow(context, show) }) {
                                        val episode = next?.video?.let { v -> v.number?.takeIf { it.isNotBlank() } ?: v.label }
                                        Text(
                                            when {
                                                next == null -> "▶ Ouvrir dans ADN"
                                                summary?.started == true -> "▶ Reprendre ${episode.orEmpty()}"
                                                else -> "▶ Regarder ${episode.orEmpty()}"
                                            }
                                        )
                                    }
                                    if (next != null) {
                                        OutlinedButton(onClick = { AdnApp.openShow(context, show) }) { Text("Fiche ADN") }
                                    }
                                    state.inWatchlist?.let { inList ->
                                        OutlinedButton(
                                            onClick = {
                                                vm.toggleWatchlist { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                                            },
                                            enabled = !state.watchlistBusy,
                                        ) { Text(if (inList) "✓ Watchlist ADN" else "+ Watchlist ADN") }
                                    }
                                    OutlinedButton(onClick = { malListOpen = true }) { Text("Ma liste MAL") }
                                    if (adnLoggedIn) {
                                        OutlinedButton(onClick = { vm.refreshProgress() }) { Text("↻") }
                                    }
                                }
                            }
                        }
                    }
                    if (seasons.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(seasons) { index, (name, videos) ->
                                    FilterChip(selected = index == state.selectedSeason, onClick = { vm.selectSeason(index) }, colors = seasonChipColors(AdnBlue)) {
                                        Text("${if (name.all { it.isDigit() }) "Saison $name" else name} · ${videos.size} ép.")
                                    }
                                }
                            }
                        }
                    }
                    season?.let { (_, nodes) ->
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                itemsIndexed(nodes, key = { _, n -> n.video.id }) { index, node ->
                                    val video = node.video
                                    val rating = ratings.find(video.episodeNumber, index)
                                    MediaCard(
                                        item = CardItem(
                                            series = SeriesRef(
                                                id = video.id.toString(),
                                                title = video.label.ifBlank { "Épisode ${index + 1}" },
                                                wideUrl = video.image2x ?: video.image,
                                                provider = Provider.ADN,
                                            ),
                                            subtitle = listOfNotNull(
                                                "✓ vu".takeIf { node.watched },
                                                rating?.label,
                                                if (video.duration > 0) "${video.duration / 60} min" else null,
                                                "à venir".takeIf { !node.available },
                                            ).joinToString(" · ").ifBlank { null },
                                            progress = node.progress.takeIf { it > 0f },
                                            wide = true,
                                        ),
                                        onClick = { AdnApp.open(context, show, video) },
                                        showMal = false,
                                    )
                                }
                            }
                        }
                        if (ratings.info.isNotBlank()) {
                            item {
                                Text(ratings.info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
