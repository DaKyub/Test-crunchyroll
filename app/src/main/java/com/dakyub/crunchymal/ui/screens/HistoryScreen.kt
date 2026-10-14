package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.ui.components.CompanionFilter
import com.dakyub.crunchymal.ui.components.companionsLabel
import com.dakyub.crunchymal.ui.components.nextCompanionFilter
import com.dakyub.crunchymal.ui.components.platformBadge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Épisode regardé : date, service, carte (série et épisode) et série Plex à ouvrir. */
private data class HistoryEntry(val at: Long, val source: String, val card: CardItem, val plexShow: String? = null)

/** Historiques Crunchyroll, ADN et Plex réunis, du plus récent au plus ancien. */
private suspend fun Graph.loadHistory(): List<HistoryEntry> = coroutineScope {
    val selected = providers.selected.value
    val cr = async {
        if (Provider.CRUNCHYROLL !in selected || !auth.loggedIn.value) emptyList()
        else runCatching { api.watchHistory(pageSize = 100) }.getOrDefault(emptyList()).mapNotNull { item ->
            val at = DateUtils.parse(item.datePlayed)?.time ?: return@mapNotNull null
            HistoryEntry(at, "Crunchyroll", item.panel.toEpisodeCard(item.playhead, item.fullyWatched, fallbackSeriesId = item.parentId))
        }
    }
    val adnPart = async {
        if (Provider.ADN !in selected || !adn.loggedIn.value) emptyList()
        else runCatching { adn.viewingHistory() }.getOrDefault(emptyList()).mapNotNull { video ->
            val show = video.show ?: return@mapNotNull null
            val at = DateUtils.parse(video.user?.watchDate)?.time ?: return@mapNotNull null
            val progress = video.user?.let { u -> if (video.watched) 1f else if (video.duration > 0) u.stoptime.toFloat() / video.duration else null }
            HistoryEntry(at, "ADN", adnEpisodeCard(show, video, "", progress).copy(subtitle = video.label.ifBlank { show.title }))
        }
    }
    val plexPart = async {
        if (!plex.connected.value) emptyList()
        else runCatching { plex.history() }.getOrDefault(emptyList()).mapNotNull { episode ->
            val show = episode.grandparentRatingKey ?: episode.grandparentKey?.substringAfterLast('/') ?: return@mapNotNull null
            val card = CardItem(
                series = SeriesRef(
                    id = "plex-$show",
                    title = episode.grandparentTitle ?: episode.title,
                    wideUrl = plex.image(episode.thumb ?: episode.grandparentThumb, 400, 225),
                    posterUrl = plex.image(episode.grandparentThumb, 240, 360),
                    malKeyOverride = "plex:$show",
                ),
                subtitle = "${episode.episodeLabel} · ${episode.title}",
                wide = true,
            )
            HistoryEntry(episode.viewedAt * 1000, "Plex", card, plexShow = show)
        }
    }
    (cr.await() + adnPart.await() + plexPart.await()).sortedByDescending { it.at }
}

private fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

/** « Aujourd'hui », « Hier », sinon « Lundi 12 octobre » (avec l'année si ce n'est pas l'année en cours). */
private fun dayLabel(at: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(day, today) -> "Aujourd'hui"
        same(day, yesterday) -> "Hier"
        else -> {
            val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEEE d MMMM" else "EEEE d MMMM yyyy"
            SimpleDateFormat(pattern, Locale.FRANCE).format(Date(at)).replaceFirstChar { it.uppercaseChar() }
        }
    }
}

/** Historique de visionnage : Crunchyroll, ADN et Plex, jour par jour, avec les marques « vu avec ». */
@Composable
fun HistoryScreen(onOpenSeries: (SeriesRef) -> Unit, onOpenPlexShow: (String) -> Unit) {
    val graph = LocalGraph.current
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<HistoryEntry>>?>(null, reload) {
        value = null
        value = runCatching { graph.loadHistory() }
    }
    val records by graph.mal.records.collectAsState()
    val marks by graph.companions.byMalId.collectAsState()
    val malLoggedIn by graph.malAuth.loggedIn.collectAsState()
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var companion by remember { mutableStateOf<CompanionFilter?>(null) }
    val entries = result?.getOrNull().orEmpty()

    // Fiche MAL de chaque série : nécessaire pour afficher (et filtrer) avec qui elle a été vue.
    LaunchedEffect(entries) {
        if (malLoggedIn) entries.map { it.card.series }.distinctBy { it.malKey }.forEach { graph.mal.request(it.malKey, it.malTitles) }
    }

    fun who(entry: HistoryEntry): Set<String> = records[entry.card.series.malKey]?.malId?.let { marks[it] }.orEmpty()

    val sources = entries.map { it.source }.distinct()
    val shown = entries
        .filter { source == null || it.source == source }
        .filter { companion?.accepts(who(it)) ?: true }
    val days = shown.groupBy { dayKey(it.at) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Historique", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Épisodes regardés sur Crunchyroll, ADN et Plex, du plus récent au plus ancien.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sources.size > 1) {
                        CycleButton("Service", source ?: "tous") {
                            val options = listOf<String?>(null) + sources
                            source = options[(options.indexOf(source) + 1) % options.size]
                        }
                    }
                    if (malLoggedIn) {
                        CycleButton("Vu avec", companion?.label ?: "toutes") {
                            companion = nextCompanionFilter(companion, graph.companions.people.value)
                        }
                    }
                    OutlinedButton(onClick = { reload++ }) { Text("Actualiser", style = MaterialTheme.typography.labelLarge) }
                    if (result?.isSuccess == true) {
                        Text("${shown.size} épisodes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val error = result?.exceptionOrNull()
        when {
            result == null -> item { Text("Chargement de l'historique…") }
            error != null -> item { Text("Erreur : ${error.message}", color = MaterialTheme.colorScheme.error) }
            shown.isEmpty() -> item { Text("Aucun épisode regardé ne correspond.") }
            else -> days.forEach { (day, list) ->
                item(key = "day-$day") {
                    Text(
                        dayLabel(list.first().at) + " · ${list.size} épisode" + if (list.size > 1) "s" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                itemsIndexed(list, key = { index, _ -> "$day-$index" }) { _, entry ->
                    HistoryRow(entry, who(entry)) {
                        entry.plexShow?.let(onOpenPlexShow) ?: onOpenSeries(entry.card.series)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, people: Set<String>, onClick: () -> Unit) {
    val time = remember(entry.at) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(entry.at)) }
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(10.dp),
        ) {
            AsyncImage(
                model = entry.card.series.wideUrl ?: entry.card.series.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.card.series.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.card.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (people.isNotEmpty()) {
                    Text("👥 " + companionsLabel(people), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            val (label, color) = platformBadge(entry.source)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
