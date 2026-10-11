package com.dakyub.crunchymal.ui.screens

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

data class SearchUi(val loading: Boolean = false, val results: List<CardItem> = emptyList(), val error: String? = null)

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
        ui.value = runCatching {
            coroutineScope {
                val cr = async {
                    if (Provider.CRUNCHYROLL in providers) graph.api.search(query).map { it.toSeriesCard() } else emptyList()
                }
                val adn = async {
                    if (Provider.ADN in providers) graph.adn.catalog(order = "popular", search = query, limit = 40).map { it.toCard() }
                    else emptyList()
                }
                mergeProviders(cr.await(), adn.await())
            }
        }.fold({ SearchUi(results = it) }, { SearchUi(error = it.message) })
    }

    fun submit() {
        viewModelScope.launch { doSearch(query.value) }
    }
}

@Composable
fun SearchScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { SearchViewModel(graph) }
    val query by vm.query.collectAsState()
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    // Recherche vocale : reconnaissance de la parole d'Android TV (micro de la télécommande).
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
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRANCE.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dis le titre de la série")
                runCatching { speech.launch(intent) }
                    .onFailure { Toast.makeText(context, "Reconnaissance vocale indisponible sur cet appareil", Toast.LENGTH_LONG).show() }
            }) { Text("🎤 Dicter") }
        }
        when {
            ui.loading -> CenteredMessage("Recherche…")
            ui.error != null -> CenteredMessage("Erreur : ${ui.error}")
            ui.results.isEmpty() && query.length >= 2 -> CenteredMessage("Aucun résultat")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterWidth + 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.results) { item ->
                    MediaCard(item = item, onClick = { onOpenSeries(item.series) })
                }
            }
        }
    }
}
