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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.PlatformApps
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.GenresLine
import com.dakyub.crunchymal.ui.components.MalBadge
import com.dakyub.crunchymal.ui.components.MalEntryChoice
import com.dakyub.crunchymal.ui.components.MalListDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class MalSeriesUi(val loading: Boolean = true, val error: String? = null, val anime: MalAnime? = null)

class MalSeriesViewModel(private val graph: Graph, private val malId: Int) : ViewModel() {
    val state = MutableStateFlow(MalSeriesUi())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            runCatching { graph.mal.details(malId) }
                .onSuccess { anime ->
                    // Clé "mal:<id>" : utilisée par le badge et par « Ma liste MAL ».
                    graph.mal.seed("mal:$malId", anime)
                    graph.watchPlatforms.request(anime)
                    state.value = MalSeriesUi(loading = false, anime = anime)
                }
                .onFailure { state.value = state.value.copy(loading = false, error = it.message ?: "Erreur") }
        }
    }
}

/** Fiche d'une série de la liste MAL absente de Crunchyroll / ADN : infos MAL et plateformes où la regarder. */
@Composable
fun MalSeriesScreen(malId: Int, onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel(key = "mal-$malId") { MalSeriesViewModel(graph, malId) }
    val state by vm.state.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val availability by graph.availability.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val context = LocalContext.current
    var malListOpen by remember { mutableStateOf(false) }
    if (malListOpen) {
        MalListDialog(choices = listOf(MalEntryChoice("Série", "mal:$malId")), onDismiss = { malListOpen = false })
    }

    val anime = state.anime
    when {
        state.loading && anime == null -> CenteredMessage("Chargement…")
        state.error != null && anime == null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        anime != null -> {
            val target = availability[malId]?.refFor(providers, crunchyrollUsable = crLoggedIn)
            val found = platforms[malId]
            val others = found?.names.orEmpty().filter { it != "Crunchyroll" && it != "ADN" }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        AsyncImage(
                            model = anime.pictureUrl,
                            contentDescription = anime.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(180.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(anime.titleEnglish ?: anime.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            if (anime.titleEnglish != null && anime.titleEnglish != anime.title) {
                                Text(anime.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MalBadge("mal:$malId", anime.allTitles, large = true)
                                Text(
                                    listOfNotNull(
                                        anime.scoredBy?.let { String.format(Locale.FRANCE, "%,d votes", it) },
                                        anime.type?.uppercase(),
                                        anime.episodes?.let { "$it ép." },
                                        anime.startYear?.toString(),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GenresLine(anime.genres, emptyList())
                            anime.synopsis?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                when {
                                    found == null -> "Recherche des plateformes…"
                                    others.isEmpty() && target == null -> "Aucune plateforme trouvée en France."
                                    found.source == "MAL" -> "Plateformes d'après MAL (liste mondiale, pas forcément en France) :"
                                    else -> "Disponible en France sur :"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            // Boutons dans une rangée défilante : la télécommande atteint aussi ceux hors écran.
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                target?.let { ref ->
                                    item { Button(onClick = { onOpenSeries(ref) }) { Text("Fiche ${ref.provider.label}") } }
                                }
                                items(others) { platform ->
                                    OutlinedButton(onClick = { PlatformApps.open(context, platform) }) { Text("▶ $platform") }
                                }
                                item { OutlinedButton(onClick = { malListOpen = true }) { Text("Ma liste MAL") } }
                            }
                        }
                    }
                }
            }
        }
    }
}
