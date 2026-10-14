package com.dakyub.crunchymal.ui.screens

import com.dakyub.crunchymal.ui.components.rememberVoiceInput
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.PosterWidth
import com.dakyub.crunchymal.ui.components.TvTextField
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.mergeProviders
import com.dakyub.crunchymal.data.ProviderSelection
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.plex.PlexMatch
import com.dakyub.crunchymal.data.plex.PlexShowEntry
import com.dakyub.crunchymal.data.plex.plexTitleKeys
import com.dakyub.crunchymal.ui.components.malCardItem
import com.dakyub.crunchymal.ui.components.prepareMalAnime
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope

/**
 * Résultats par source : Crunchyroll / ADN ([results]), séries du serveur Plex ([plex]) et fiches MAL
 * ([mal], affichées seulement si elles ne sont ni sur Crunchyroll ni sur ADN).
 */
data class SearchUi(
    val loading: Boolean = false,
    val results: List<CardItem> = emptyList(),
    val plex: List<CardItem> = emptyList(),
    val mal: List<MalAnime> = emptyList(),
    val error: String? = null,
) {
    val empty: Boolean get() = results.isEmpty() && plex.isEmpty() && mal.isEmpty()
}

/** Séries Plex dont un titre (Plex, anglais, original) contient la recherche. */
private fun Graph.searchPlex(query: String): List<CardItem> {
    val key = plexTitleKeys(query).firstOrNull() ?: return emptyList()
    return plexLibrary.shows.value
        .filter { show -> show.malTitles.any { title -> plexTitleKeys(title).any { it.contains(key) } } }
        .take(30)
        .map { it.searchCard(this) }
}

private fun PlexShowEntry.searchCard(graph: Graph) = CardItem(
    series = SeriesRef(
        id = "plex-$ratingKey",
        title = title,
        posterUrl = graph.plex.image("/library/metadata/$ratingKey/thumb", 240, 360),
        altTitles = malTitles - title,
        malKeyOverride = "plex:$ratingKey",
    ),
    subtitle = "$seasons saison" + (if (seasons > 1) "s" else "") + (if (watched > 0) " · $watched ép. vus" else ""),
    badges = listOf(PlexMatch(ratingKey, seasons, totalSeasons).label),
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val graph: Graph) : ViewModel() {
    val query = MutableStateFlow("")
    val ui = MutableStateFlow(SearchUi())

    init {
        viewModelScope.launch {
            query.debounce(600).distinctUntilChanged().collectLatest { q -> doSearch(q) }
        }
    }

    private suspend fun doSearch(q: String) {
        if (q.trim().length < 2) {
            ui.value = SearchUi()
            return
        }
        ui.value = ui.value.copy(loading = true, error = null)
        val providers = graph.providers.selected.value
        val query = q.trim()
        ui.value = coroutineScope {
            val cr = async {
                runCatching { if (Provider.CRUNCHYROLL in providers) graph.api.search(query).map { it.toSeriesCard() } else emptyList() }
            }
            val adn = async {
                runCatching {
                    if (Provider.ADN in providers) graph.adn.catalog(order = "popular", search = query, limit = 40).map { it.toCard() }
                    else emptyList()
                }
            }
            val mal = async {
                if (!graph.malAuth.loggedIn.value) emptyList()
                else runCatching { graph.mal.candidates(query) }.getOrDefault(emptyList())
            }
            val plex = if (graph.plex.connected.value) graph.searchPlex(query) else emptyList()
            val parts = listOf(cr.await(), adn.await())
            val results = mergeProviders(parts[0].getOrDefault(emptyList()), parts[1].getOrDefault(emptyList()))
            val malFound = mal.await()
            graph.prepareMalAnime(malFound)
            SearchUi(
                results = results,
                plex = plex,
                mal = malFound,
                // Erreur affichée seulement si aucune source n'a rien donné.
                error = parts.firstNotNullOfOrNull { it.exceptionOrNull() }?.message
                    ?.takeIf { results.isEmpty() && plex.isEmpty() && malFound.isEmpty() },
            )
        }
    }

    fun submit() {
        viewModelScope.launch { doSearch(query.value) }
    }
}

@Composable
fun SearchScreen(onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit, onOpenPlexShow: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { SearchViewModel(graph) }
    val query by vm.query.collectAsState()
    val ui by vm.ui.collectAsState()
    val found by graph.availability.found.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val context = LocalContext.current
    // Recherche vocale écoutée dans l'app (micro de la télécommande), la fenêtre vocale du système
    // n'ouvrant pas toujours le micro pour une autre app.
    val voice = rememberVoiceInput { text ->
        vm.query.value = text
        vm.submit()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start()
        else Toast.makeText(context, "Autorise le micro pour dicter, ou utilise le micro du clavier à l'écran.", Toast.LENGTH_LONG).show()
    }
    // Repli si aucun service de reconnaissance n'est utilisable dans l'app : fenêtre vocale du système.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.query.value = text
            vm.submit()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(query, { vm.query.value = it }, "Rechercher une série…", modifier = Modifier.weight(1f), onSubmit = vm::submit)
            OutlinedButton(onClick = {
                when {
                    voice.listening -> voice.stop()
                    voice.available -> {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) voice.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    else -> {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRANCE.toLanguageTag())
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dis le titre de la série")
                        runCatching { speech.launch(intent) }
                            .onFailure { Toast.makeText(context, "Reconnaissance vocale indisponible sur cet appareil", Toast.LENGTH_LONG).show() }
                    }
                }
            }) { Text(if (voice.listening) "■ Terminer" else "🎤 Dicter") }
        }
        if (voice.listening || voice.error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (voice.listening) {
                    // Jauge du volume capté : elle bouge si le micro entend.
                    Box(
                        Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(voice.level)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        )
                    }
                    Text(
                        voice.partial?.let { "« $it »" } ?: "Écoute… dis le titre de la série",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(voice.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        when {
            ui.loading -> CenteredMessage("Recherche…")
            ui.error != null -> CenteredMessage("Erreur : ${ui.error}")
            ui.empty && query.length >= 2 -> CenteredMessage("Aucun résultat")
            else -> {
                // Fiches MAL absentes de Crunchyroll / ADN (les autres sont déjà dans la première section).
                val malItems = ui.mal
                    .map { malCardItem(it, found[it.malId], platforms[it.malId], providers, crLoggedIn, subtitle = it.startYear?.toString()) }
                    .filter { it.target == null }
                    .distinctBy { it.malId }
                val sections = listOf(ui.results.isNotEmpty(), ui.plex.isNotEmpty(), malItems.isNotEmpty()).count { it }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterWidth + 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (ui.results.isNotEmpty()) {
                        if (sections > 1) header(ProviderSelection.label(providers))
                        items(ui.results) { item ->
                            MediaCard(item = item, onClick = { onOpenSeries(item.series) })
                        }
                    }
                    if (ui.plex.isNotEmpty()) {
                        header("Sur ton serveur Plex")
                        items(ui.plex, key = { it.series.id }) { item ->
                            MediaCard(item = item, showProviderBadge = false, onClick = { onOpenPlexShow(item.series.id.removePrefix("plex-")) })
                        }
                    }
                    if (malItems.isNotEmpty()) {
                        header("Sur MyAnimeList (ni sur Crunchyroll ni sur ADN)")
                        items(malItems, key = { "mal-${it.malId}" }) { item ->
                            MediaCard(item = item.card, showProviderBadge = false, onClick = { onOpenMal(item.malId) })
                        }
                    }
                }
            }
        }
    }
}

/** Titre de section sur toute la largeur de la grille. */
private fun LazyGridScope.header(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
