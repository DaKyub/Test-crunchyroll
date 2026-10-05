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
import androidx.compose.ui.Alignment
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
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.data.adn.AdnVideo
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GenresLine
import com.dakyub.crunchymal.ui.components.MalBadge
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
    val episodes: List<AdnVideo> = emptyList(),
    val selectedSeason: Int = 0,
) {
    /** Épisodes groupés par saison, dans l'ordre. */
    val seasons: List<Pair<Int, List<AdnVideo>>>
        get() = episodes.groupBy { it.seasonNumber }.toSortedMap().map { (k, v) -> k to v }
}

class AdnSeriesViewModel(private val graph: Graph, private val showId: String) : ViewModel() {
    val state = MutableStateFlow(AdnSeriesUi())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            runCatching {
                val show = async { graph.adn.show(showId) }
                val episodes = async { runCatching { graph.adn.episodes(showId) }.getOrDefault(emptyList()) }
                show.await() to episodes.await()
            }.onSuccess { (show, episodes) ->
                state.value = AdnSeriesUi(loading = false, show = show, episodes = episodes)
            }.onFailure {
                state.value = state.value.copy(loading = false, error = it.message ?: "Erreur")
            }
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
            val ratings = rememberSeasonRatings(ratingTitles, season?.first ?: 1)

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
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
                                .width(180.dp)
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
                                        "ADN",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GenresLine(record?.genres, show.genres)
                            show.summary?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { AdnApp.open(context, show, state.episodes.firstOrNull()) }) {
                                    Text("▶ Ouvrir dans ADN")
                                }
                                OutlinedButton(onClick = { malListOpen = true }) { Text("Ma liste MAL") }
                            }
                        }
                    }
                }
                if (seasons.size > 1) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(seasons) { index, (number, videos) ->
                                FilterChip(selected = index == state.selectedSeason, onClick = { vm.selectSeason(index) }) {
                                    Text("Saison $number · ${videos.size} ép.")
                                }
                            }
                        }
                    }
                }
                season?.let { (_, videos) ->
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
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
                                            rating?.label,
                                            if (video.duration > 0) "${video.duration / 60} min" else null,
                                            "à venir".takeIf { !video.available },
                                        ).joinToString(" · ").ifBlank { null },
                                        wide = true,
                                    ),
                                    onClick = { AdnApp.open(context, show, video) },
                                    showMal = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
