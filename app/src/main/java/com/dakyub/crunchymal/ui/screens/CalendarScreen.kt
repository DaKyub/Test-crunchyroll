package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.data.crunchyroll.originalEpisodesOnly
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Un épisode du calendrier : sorti, ou estimé (même heure que la semaine précédente). */
data class CalendarEntry(
    val card: CardItem,
    val time: Date,
    val estimated: Boolean,
)

data class CalendarUi(
    val loading: Boolean = true,
    val error: String? = null,
    val entries: List<CalendarEntry> = emptyList(),
    /** Décalage du jour affiché par rapport à aujourd'hui (-7…+7). */
    val dayOffset: Int = 0,
    val watchlistOnly: Boolean = false,
    val watchlistIds: Set<String> = emptySet(),
)

private fun dayKey(date: Date): Int {
    val c = Calendar.getInstance().apply { time = date }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun dayFromToday(offset: Int): Date = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time

class CalendarViewModel(private val graph: Graph) : ViewModel() {
    val ui = MutableStateFlow(CalendarUi())
    private var loadedFor: Set<Provider>? = null

    /** Épisodes du jour sélectionné, triés par heure. */
    val dayEntries = combine(ui, graph.providers.selected) { u, _ ->
        val key = dayKey(dayFromToday(u.dayOffset))
        u.entries
            .filter { dayKey(it.time) == key }
            .filter { !u.watchlistOnly || it.card.series.id in u.watchlistIds }
            .sortedBy { it.time }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun ensure(providers: Set<Provider>) {
        if (providers == loadedFor) return
        loadedFor = providers
        load(providers)
    }

    private fun load(providers: Set<Provider>) {
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            val cr = async { if (Provider.CRUNCHYROLL in providers) runCatching { crunchyrollEntries() } else Result.success(emptyList<CalendarEntry>()) }
            val adn = async { if (Provider.ADN in providers) runCatching { adnEntries() } else Result.success(emptyList<CalendarEntry>()) }
            val watchlist = async {
                if (Provider.CRUNCHYROLL in providers) runCatching { graph.watchlist.get().map { it.series.id }.toSet() }.getOrDefault(emptySet())
                else emptySet()
            }
            val results = listOf(cr.await(), adn.await())
            val entries = results.flatMap { it.getOrDefault(emptyList()) }
            ui.value = ui.value.copy(
                loading = false,
                entries = entries,
                watchlistIds = watchlist.await(),
                error = if (entries.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } else null,
            )
        }
    }

    /** Épisodes Crunchyroll des 7 derniers jours + estimation des 7 prochains (rythme hebdomadaire). */
    private suspend fun crunchyrollEntries(): List<CalendarEntry> {
        val pages = listOf(0, 100).map { start ->
            viewModelScope.async {
                runCatching {
                    graph.api.browseWith(mapOf("type" to "episode", "sort_by" to "newly_added", "n" to "100", "start" to start.toString()))
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        val now = System.currentTimeMillis()
        val weekAgo = now - 7 * 86_400_000L
        val released = pages.originalEpisodesOnly().mapNotNull { panel ->
            val meta = panel.episodeMetadata ?: return@mapNotNull null
            val time = DateUtils.parse(meta.releaseDate) ?: return@mapNotNull null
            if (time.time < weekAgo || time.time > now) return@mapNotNull null
            CalendarEntry(panel.toEpisodeCard(), time, estimated = false)
        }
        // Prochain épisode estimé : 7 jours après le dernier sorti de chaque série.
        val estimated = released.groupBy { it.card.series.id }.mapNotNull { (_, list) ->
            val last = list.maxByOrNull { it.time } ?: return@mapNotNull null
            val next = Date(last.time.time + 7 * 86_400_000L)
            if (next.time <= now) return@mapNotNull null
            CalendarEntry(
                last.card.copy(subtitle = "Prévu · ${last.card.series.title}", episodeId = null, progress = null),
                next,
                estimated = true,
            )
        }
        return released + estimated
    }

    /** Calendrier ADN de J-7 à J+7. */
    private suspend fun adnEntries(): List<CalendarEntry> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return (-7..7).map { offset ->
            viewModelScope.async {
                runCatching { graph.adn.calendar(format.format(dayFromToday(offset))) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.id }.mapNotNull { video ->
            val time = DateUtils.parse(video.releaseDate) ?: return@mapNotNull null
            val show = video.show ?: return@mapNotNull null
            CalendarEntry(
                CardItem(
                    series = show.toRef().copy(wideUrl = video.image2x ?: video.image),
                    subtitle = video.label.ifBlank { null },
                    wide = true,
                ),
                time,
                estimated = time.time > System.currentTimeMillis(),
            )
        }
    }

    fun selectDay(offset: Int) {
        ui.value = ui.value.copy(dayOffset = offset)
    }

    fun toggleWatchlistOnly() {
        ui.value = ui.value.copy(watchlistOnly = !ui.value.watchlistOnly)
    }
}

@Composable
fun CalendarScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { CalendarViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val entries by vm.dayEntries.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(providers) { vm.ensure(providers) }

    val dayLabel = SimpleDateFormat("EEE d", Locale.FRANCE)
    val timeLabel = SimpleDateFormat("HH:mm", Locale.FRANCE)

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            itemsIndexed((-7..7).toList()) { _, offset ->
                FilterChip(selected = offset == ui.dayOffset, onClick = { vm.selectDay(offset) }) {
                    Text(
                        when (offset) {
                            0 -> "Aujourd'hui"
                            -1 -> "Hier"
                            1 -> "Demain"
                            else -> dayLabel.format(dayFromToday(offset))
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (Provider.CRUNCHYROLL in providers) {
                FilterChip(selected = ui.watchlistOnly, onClick = vm::toggleWatchlistOnly) {
                    Text(if (ui.watchlistOnly) "✓ Ma watchlist uniquement" else "Ma watchlist uniquement", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "${entries.size} épisode(s) · « Prévu » = estimé d'après la semaine précédente",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            ui.loading && ui.entries.isEmpty() -> CenteredMessage("Chargement du calendrier…")
            ui.error != null -> CenteredMessage("Erreur : ${ui.error}")
            entries.isEmpty() -> CenteredMessage("Aucun épisode ce jour-là.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(272.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries) { entry ->
                    val card = entry.card
                    MediaCard(
                        item = card.copy(
                            subtitle = listOfNotNull(
                                timeLabel.format(entry.time) + if (entry.estimated) " (prévu)" else "",
                                card.subtitle?.removePrefix("Prévu · ")?.takeIf { !entry.estimated || it != card.series.title },
                            ).joinToString(" · "),
                        ),
                        onClick = { onOpenSeries(card.series) },
                        onLongClick = card.episodeId?.let { id -> { OfficialApp.openEpisode(context, id, card.series.id) } },
                    )
                }
            }
        }
    }
}
