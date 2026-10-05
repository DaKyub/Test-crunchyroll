package com.dakyub.crunchymal.ui.screens

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
        ui.value = runCatching { graph.api.search(q.trim()).map { it.toSeriesCard() } }
            .fold({ SearchUi(results = it) }, { SearchUi(error = it.message) })
    }

    fun submit() {
        viewModelScope.launch { doSearch(query.value) }
    }
}

@Composable
fun SearchScreen(onOpenSeries: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { SearchViewModel(graph) }
    val query by vm.query.collectAsState()
    val ui by vm.ui.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        TvTextField(query, { vm.query.value = it }, "Rechercher une série…", onSubmit = vm::submit)
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
                    MediaCard(item = item, onClick = { onOpenSeries(item.series.id) })
                }
            }
        }
    }
}
