package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.mal.MalRecord
import com.dakyub.crunchymal.data.plexShowKey
import com.dakyub.crunchymal.data.progress.WatchStatus
import com.dakyub.crunchymal.ui.components.CompanionFilter
import com.dakyub.crunchymal.ui.components.MediaCard
import com.dakyub.crunchymal.ui.components.malCardItem
import com.dakyub.crunchymal.ui.components.malGenreLabel
import com.dakyub.crunchymal.ui.components.nextCompanionFilter
import com.dakyub.crunchymal.ui.components.prepareMalAnime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Série candidate : en cours (Crunchyroll, ADN, Plex) ou dans la liste MAL « À voir » ([anime]). */
private data class TonightCandidate(val card: CardItem, val inProgress: Boolean, val anime: MalAnime? = null)

private data class TonightInfo(val malId: Int?, val genres: List<String>, val score: Double?)

private fun TonightCandidate.info(records: Map<String, MalRecord>): TonightInfo =
    anime?.let { TonightInfo(it.malId, it.genres, it.score) }
        ?: records[card.series.malKey].let { TonightInfo(it?.malId, it?.genres.orEmpty(), it?.score) }

/** Séries en cours (pas finies, pas « à jour ») et liste MAL « À voir ». */
private suspend fun Graph.tonightCandidates(): List<TonightCandidate> = coroutineScope {
    val selected = providers.selected.value
    val startedPart = async {
        runCatching { started.get(selected, crunchyrollUsable = auth.loggedIn.value) }.getOrDefault(emptyList())
    }
    val plexPart = async { if (plex.connected.value) runCatching { plexResumeCards() }.getOrDefault(emptyList()) else emptyList() }
    val toWatchPart = async {
        if (!malAuth.loggedIn.value) emptyList()
        else runCatching { mal.myListCached(maxAgeMs = 30 * 60_000) }.getOrDefault(emptyList())
            .filter { it.status.status == "plan_to_watch" }.map { it.anime }
    }
    val summaries = progress.summaries.value
    val inProgress = startedPart.await()
        .filter { e -> summaries[e.series.progressKey]?.status.let { it != WatchStatus.COMPLETED && it != WatchStatus.UP_TO_DATE } }
        .map { e ->
            mal.request(e.series.malKey, e.series.malTitles)
            val summary = summaries[e.series.progressKey]
            TonightCandidate(
                CardItem(
                    series = e.series,
                    episodeId = summary?.nextEpisodeId ?: e.nextEpisodeId,
                    subtitle = summary?.nextLabel?.let { "Suite : $it" } ?: "En cours",
                ),
                inProgress = true,
            )
        }
    val plexItems = plexPart.await().map { (card, _) -> TonightCandidate(card.copy(wide = false), inProgress = true) }
    val toWatch = toWatchPart.await()
    // Plateformes des « À voir » : vérifiées en arrière-plan, prises en compte aux tirages suivants.
    prepareMalAnime(toWatch)
    (inProgress + plexItems).distinctBy { it.card.series.malKey } +
        toWatch.map { TonightCandidate(CardItem(series = SeriesRef(id = "mal-${it.malId}", title = it.title)), inProgress = false, anime = it) }
}

/**
 * « Que regarder ce soir ? » : 3 séries tirées au hasard parmi celles en cours et la liste « À voir »,
 * selon avec qui on regarde, le genre et la note MAL. « Relancer » pour d'autres propositions.
 */
@Composable
fun TonightDialog(
    onDismiss: () -> Unit,
    onOpenSeries: (SeriesRef) -> Unit,
    onOpenMal: (Int) -> Unit,
    onOpenPlexShow: (String) -> Unit,
) {
    val graph = LocalGraph.current
    val malLoggedIn by graph.malAuth.loggedIn.collectAsState()
    var candidates by remember { mutableStateOf<List<TonightCandidate>?>(null) }
    var includeInProgress by remember { mutableStateOf(true) }
    var includeToWatch by remember { mutableStateOf(true) }
    var availableOnly by remember { mutableStateOf(true) }
    var companion by remember { mutableStateOf<CompanionFilter?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var minScore by remember { mutableStateOf<Double?>(null) }
    var picks by remember { mutableStateOf<List<TonightCandidate>>(emptyList()) }
    var possible by remember { mutableStateOf(0) }

    /** Candidats qui passent les filtres, avec la carte « À voir » à jour (plateformes trouvées depuis). */
    fun eligible(): List<TonightCandidate> {
        val records = graph.mal.records.value
        val marks = graph.companions.byMalId.value
        val found = graph.availability.found.value
        val platforms = graph.watchPlatforms.found.value
        val providers = graph.providers.selected.value
        val crUsable = graph.auth.loggedIn.value
        return candidates.orEmpty().mapNotNull { candidate ->
            if (candidate.inProgress && !includeInProgress || !candidate.inProgress && !includeToWatch) return@mapNotNull null
            val resolved = candidate.anime?.let { anime ->
                val item = malCardItem(anime, found[anime.malId], platforms[anime.malId], providers, crUsable, subtitle = "À voir")
                if (availableOnly && item.card.badges.isEmpty()) return@mapNotNull null
                candidate.copy(card = item.card)
            } ?: candidate
            val info = resolved.info(records)
            val ok = (companion?.accepts(info.malId?.let { marks[it] }.orEmpty()) ?: true) &&
                (genre == null || genre in info.genres) &&
                (minScore == null || (info.score ?: 0.0) >= minScore!!)
            resolved.takeIf { ok }
        }
    }

    fun roll() {
        val list = eligible()
        possible = list.size
        picks = list.shuffled().take(3)
    }

    LaunchedEffect(Unit) {
        candidates = runCatching { graph.tonightCandidates() }.getOrDefault(emptyList())
        roll()
    }

    fun open(candidate: TonightCandidate) {
        onDismiss()
        val anime = candidate.anime
        val plexShow = candidate.card.series.plexShowKey
        when {
            plexShow != null -> onOpenPlexShow(plexShow)
            anime != null -> {
                val target = graph.availability.found.value[anime.malId]
                    ?.refFor(graph.providers.selected.value, graph.auth.loggedIn.value)
                if (target != null) onOpenSeries(target) else onOpenMal(anime.malId)
            }
            else -> onOpenSeries(candidate.card.series)
        }
    }

    // Genres proposés : les plus fréquents parmi les candidats.
    val genres = remember(candidates) {
        val records = graph.mal.records.value
        candidates.orEmpty().flatMap { it.info(records).genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(14).map { it.key }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(900.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Que regarder ce soir ?", style = MaterialTheme.typography.titleLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(selected = includeInProgress, onClick = { includeInProgress = !includeInProgress; roll() }) {
                    Text(if (includeInProgress) "✓ En cours" else "En cours")
                }
                FilterChip(selected = includeToWatch, onClick = { includeToWatch = !includeToWatch; roll() }) {
                    Text(if (includeToWatch) "✓ À voir" else "À voir")
                }
                if (includeToWatch) {
                    FilterChip(selected = availableOnly, onClick = { availableOnly = !availableOnly; roll() }) {
                        Text(if (availableOnly) "✓ « À voir » disponibles" else "« À voir » disponibles")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                if (malLoggedIn) {
                    CycleButton("Vu avec", companion?.label ?: "toutes") {
                        companion = nextCompanionFilter(companion, graph.companions.people.value)
                        roll()
                    }
                }
                CycleButton("Genre", genre?.let(::malGenreLabel) ?: "tous") {
                    val options = listOf<String?>(null) + genres
                    genre = options[(options.indexOf(genre) + 1) % options.size]
                    roll()
                }
                CycleButton("MAL ≥", minScore?.toString() ?: "toutes") {
                    minScore = MinScores[(MinScores.indexOf(minScore) + 1) % MinScores.size]
                    roll()
                }
            }
            when {
                candidates == null -> Text("Préparation des propositions…", style = MaterialTheme.typography.bodyMedium)
                picks.isEmpty() -> Text("Aucune série ne correspond : change les filtres.", style = MaterialTheme.typography.bodyMedium)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    picks.forEach { pick ->
                        MediaCard(item = pick.card, width = 150.dp, showProviderBadge = false, onClick = { open(pick) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { roll() }, enabled = candidates != null) { Text("🎲 Relancer") }
                OutlinedButton(onClick = onDismiss) { Text("Fermer") }
                if (candidates != null) {
                    Text("$possible séries possibles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
