package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.progress.WatchStatus
import androidx.compose.runtime.LaunchedEffect
import com.dakyub.crunchymal.data.crunchyroll.CrPanel
import com.dakyub.crunchymal.data.crunchyroll.best
import com.dakyub.crunchymal.data.crunchyroll.originalEpisodesOnly
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.MediaCard
import android.net.Uri
import com.dakyub.crunchymal.data.crunchyroll.CrFeedItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.dakyub.crunchymal.data.DateUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

/** [minMal] : rangée filtrée dynamiquement sur la note MAL (ex. « Pépites non vues »). */
data class HomeRow(val key: String, val title: String, val items: List<CardItem>, val minMal: Double? = null)

data class HomeState(val loading: Boolean = true, val rows: List<HomeRow> = emptyList(), val error: String? = null)

fun CrPanel.toSeriesCard(): CardItem = CardItem(
    series = SeriesRef(
        id = id,
        title = title,
        slug = slugTitle,
        posterUrl = images.posterTall.best(240),
        wideUrl = images.posterWide.best(400),
    ),
    subtitle = seriesMetadata?.let { m ->
        listOfNotNull(m.launchYear?.toString(), m.episodeCount.takeIf { it > 0 }?.let { "$it ép." }).joinToString(" · ")
    }?.ifBlank { null },
)

/** Carte "épisode" (historique, derniers épisodes) : vignette large + série associée. */
fun CrPanel.toEpisodeCard(playhead: Long = 0, fullyWatched: Boolean = false, fallbackSeriesId: String = ""): CardItem {
    val meta = episodeMetadata
    val duration = meta?.durationMs ?: 0
    return CardItem(
        series = SeriesRef(
            id = meta?.seriesId?.takeIf { it.isNotBlank() } ?: fallbackSeriesId,
            title = meta?.seriesTitle?.takeIf { it.isNotBlank() } ?: title,
            slug = meta?.seriesSlugTitle.orEmpty(),
            wideUrl = images.thumbnail.best(400),
        ),
        subtitle = "S${meta?.seasonNumber ?: "?"} E${meta?.episode?.ifBlank { null } ?: meta?.episodeNumber ?: "?"} · $title",
        episodeId = id,
        progress = when {
            fullyWatched -> 1f
            duration > 0 && playhead > 0 -> playhead * 1000f / duration
            else -> null
        },
        wide = true,
    )
}

fun CrPanel.toCard(): CardItem = if (type == "episode") toEpisodeCard() else toSeriesCard()

enum class HomeSort(val label: String) { DEFAULT("Par défaut"), MAL("Note MAL") }

data class HomeFilters(
    val sort: HomeSort = HomeSort.DEFAULT,
    val statuses: Set<WatchStatus> = emptySet(),
    val minScore: Double? = null,
) {
    val needsMal: Boolean get() = sort == HomeSort.MAL || minScore != null
}

data class HomeVisible(val rows: List<HomeRow> = emptyList(), val progressKnown: Int = 0, val progressNeeded: Int = 0)

class HomeViewModel(private val graph: Graph) : ViewModel() {
    val state = MutableStateFlow(HomeState())
    val filters = MutableStateFlow(HomeFilters())
    private var progressJob: Job? = null

    /** Rangées après filtres (statut, note MAL) et tri ; les rangées vides disparaissent. */
    val visible = combine(state, filters, graph.progress.summaries, graph.mal.records, graph.mal.myListSeenIds) { st, f, summaries, mal, malSeen ->
        fun statusOf(item: CardItem) =
            if (item.series.provider == Provider.CRUNCHYROLL) summaries[item.series.id]?.status else null
        val rows = st.rows.mapNotNull { row ->
            val items = row.items
                .filter { f.statuses.accepts(statusOf(it)) }
                .filter { item -> f.minScore == null || (mal[item.series.malKey]?.score ?: -1.0) >= f.minScore }
                .filter { item -> row.minMal == null || (mal[item.series.malKey]?.score ?: -1.0) >= row.minMal }
                // Pépites : on retire aussi ce qui est déjà commencé (progression) ou vu d'après la liste MAL.
                .filter { item ->
                    row.minMal == null || (
                        summaries[item.series.id]?.started != true &&
                            mal[item.series.malKey]?.malId?.let { it in malSeen } != true
                        )
                }
                .let { list ->
                    if (f.sort == HomeSort.MAL || row.minMal != null) list.sortedByDescending { mal[it.series.malKey]?.score ?: -1.0 }
                    else list
                }
            items.takeIf { it.isNotEmpty() }?.let { row.copy(items = it) }
        }
        val crIds = crSeriesIds(st)
        HomeVisible(
            rows = rows,
            progressKnown = crIds.count { summaries.containsKey(it) },
            progressNeeded = if (f.statuses.isEmpty()) 0 else crIds.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeVisible())

    private fun crSeriesIds(st: HomeState = state.value) = st.rows.flatMap { it.items }
        .filter { it.series.provider == Provider.CRUNCHYROLL && it.series.id.isNotBlank() }
        .map { it.series.id }.distinct()

    fun nextSort() {
        filters.value = filters.value.copy(sort = HomeSort.entries[(filters.value.sort.ordinal + 1) % HomeSort.entries.size])
        applyFilterNeeds()
    }

    fun toggleStatus(status: WatchStatus) {
        filters.value = filters.value.copy(statuses = filters.value.statuses.toggle(status))
        applyFilterNeeds()
    }

    fun nextMinScore() {
        filters.value = filters.value.copy(minScore = MinScores[(MinScores.indexOf(filters.value.minScore) + 1) % MinScores.size])
        applyFilterNeeds()
    }

    /** Demande les notes MAL et/ou la progression de toutes les séries quand un filtre en a besoin. */
    private fun applyFilterNeeds() {
        val f = filters.value
        if (f.needsMal) {
            state.value.rows.flatMap { it.items }.distinctBy { it.series.malKey }
                .forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
        }
        progressJob?.cancel()
        if (f.statuses.isNotEmpty()) {
            val ids = crSeriesIds()
            progressJob = viewModelScope.launch {
                ids.forEach { id -> launch { runCatching { graph.progress.ensureSummary(id) } } }
            }
        }
    }
    private var job: Job? = null

    private var loadedFor: Set<Provider>? = null

    /** Charge (ou recharge si les services affichés ont changé). */
    fun ensure(providers: Set<Provider>) {
        if (providers != loadedFor) load()
    }

    // Rangées courantes : conservées pour pouvoir rafraîchir « Reprendre » seule au retour de Crunchyroll.
    private var loaders: List<Pair<String, suspend () -> List<CardItem>>> = emptyList()
    private var results: Array<List<CardItem>?> = emptyArray()
    private var done = BooleanArray(0)
    private var firstError: String? = null
    private var lastResumeRefresh = 0L
    private val continueLoader = continueWatching()
    private val newEpisodesLoader = newEpisodesRow()
    private val gemsLoader = gemsRow()

    fun load() {
        val providers = graph.providers.selected.value
        loadedFor = providers
        job?.cancel()
        job = viewModelScope.launch {
            state.value = HomeState(loading = true)
            val list = mutableListOf<Pair<String, suspend () -> List<CardItem>>>()
            if (Provider.CRUNCHYROLL in providers) {
                val feed = runCatching { graph.api.homeFeed() }.getOrNull()
                val official = feed?.let { feedLoaders(it) }?.takeIf { it.isNotEmpty() } ?: fallbackLoaders()
                // « Nouveaux épisodes pour toi » en tête, « Pépites non vues » après les premières rangées.
                list += "Nouveaux épisodes pour toi" to newEpisodesLoader
                list += official.take(2)
                list += "Pépites non vues (MAL 8+)" to gemsLoader
                list += official.drop(2)
            }
            if (Provider.ADN in providers) list += adnLoaders()

            // Les rangées s'affichent au fur et à mesure, dans l'ordre du fil officiel.
            loaders = list
            results = arrayOfNulls<List<CardItem>>(list.size)
            done = BooleanArray(list.size)
            firstError = null
            lastResumeRefresh = System.currentTimeMillis()
            list.mapIndexed { i, (_, loader) ->
                launch {
                    runCatching { loader() }
                        .onSuccess { items ->
                            results[i] = items
                            // Les pépites sont filtrées sur la note MAL : on la demande pour toutes.
                            if (loader === gemsLoader) items.forEach { graph.mal.request(it.series.malKey, it.series.malTitles) }
                        }
                        .onFailure { if (firstError == null) firstError = it.message }
                    done[i] = true
                    publish()
                }
            }.joinAll()
            publish()
            applyFilterNeeds()
        }
    }

    private fun publish() {
        val rows = loaders.indices.mapNotNull { i ->
            results.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                HomeRow("$i", loaders[i].first, it, minMal = if (loaders[i].second === gemsLoader) 8.0 else null)
            }
        }
        val finished = done.all { it }
        state.value = HomeState(
            loading = !finished,
            rows = rows,
            error = if (finished && rows.isEmpty()) firstError ?: "Rien à afficher" else null,
        )
    }

    /** Au retour dans l'app (après un épisode), seule la rangée « Reprendre » est rechargée. */
    fun onResume() {
        val index = loaders.indexOfFirst { it.second === continueLoader }
        if (index < 0 || System.currentTimeMillis() - lastResumeRefresh < 20_000) return
        lastResumeRefresh = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { continueLoader() }.onSuccess {
                if (index < results.size && loaders.getOrNull(index)?.second === continueLoader) {
                    results[index] = it
                    publish()
                }
            }
        }
    }

    private fun row(block: suspend () -> List<CardItem>): suspend () -> List<CardItem> = block

    private fun continueWatching(): suspend () -> List<CardItem> = {
        // Même source que la rangée « Reprendre » de l'app officielle ; repli sur l'historique.
        val resume = runCatching { graph.api.continueWatching() }.getOrDefault(emptyList())
            .filter { it.panel.type == "episode" && !it.fullyWatched }
        if (resume.isNotEmpty()) {
            resume.distinctBy { it.panel.episodeMetadata?.seriesId ?: it.panel.id }.take(20).map { item ->
                val card = item.panel.toEpisodeCard(item.playhead, item.fullyWatched)
                val prefix = if (item.playhead > 0) "Continuer" else "Suite"
                card.copy(subtitle = "$prefix · ${card.subtitle.orEmpty()}")
            }
        } else {
            graph.api.watchHistory()
                .filter { it.panel.type == "episode" && !it.fullyWatched }
                .distinctBy { it.panel.episodeMetadata?.seriesId ?: it.parentId }
                .take(20)
                .map { it.panel.toEpisodeCard(it.playhead, it.fullyWatched, it.parentId) }
        }
    }

    /**
     * Séries de la watchlist où l'utilisateur était à jour et qui ont reçu 1 à 3 épisodes
     * sortis depuis moins de 3 semaines.
     */
    private fun newEpisodesRow(): suspend () -> List<CardItem> = {
        val entries = graph.watchlist.get()
        coroutineScope {
            entries.map { e -> async { runCatching { graph.progress.ensureSummary(e.series.id) } } }.awaitAll()
        }
        val summaries = graph.progress.summaries.value
        val cutoff = DateUtils.isoDaysAgo(21)
        entries.mapNotNull { e ->
            val s = summaries[e.series.id] ?: return@mapNotNull null
            val remaining = s.remainingAfterLast ?: return@mapNotNull null
            val released = s.nextReleased?.take(19) ?: return@mapNotNull null
            if (s.watched > 0 && remaining in 1..3 && released >= cutoff && s.nextEpisodeId != null) Triple(e, s, released) else null
        }.sortedByDescending { it.third }.map { (e, s, _) ->
            val more = (s.remainingAfterLast ?: 1) - 1
            CardItem(
                series = e.series,
                episodeId = s.nextEpisodeId,
                subtitle = "Nouveau : ${s.nextLabel.orEmpty()}" + (if (more > 0) " (+$more)" else ""),
            )
        }
    }

    /** Séries populaires jamais commencées ; la rangée ne garde que celles notées 8+ sur MAL. */
    private fun gemsRow(): suspend () -> List<CardItem> = {
        coroutineScope {
            val popular = async { graph.api.browse("popularity", n = 100) }
            // Historique complet (toutes les pages, en cache 12 h) + watchlist + liste MAL de l'utilisateur.
            val history = async { runCatching { graph.history.watchedSeriesIds() }.getOrDefault(emptySet()) }
            val watchlist = async { runCatching { graph.watchlist.get() }.getOrDefault(emptyList()) }
            launch { graph.mal.refreshMyListSeenIds() }
            val started = history.await() + watchlist.await().filter { !it.neverWatched }.map { it.series.id }
            popular.await().filter { it.id !in started }.map { it.toSeriesCard() }
        }
    }

    private fun watchlistRow(): suspend () -> List<CardItem> = {
        graph.watchlist.get().take(30).map { e -> CardItem(series = e.series, episodeId = e.nextEpisodeId) }
    }

    private fun feedLoaders(feed: List<CrFeedItem>): List<Pair<String, suspend () -> List<CardItem>>> {
        val api = graph.api
        return feed.mapNotNull { item ->
            val title = item.title.ifBlank { item.sourceMediaTitle }
            when (item.resourceType) {
                "dynamic_collection" -> when (item.responseType) {
                    "history" -> title.ifBlank { "Continuer à regarder" } to continueLoader
                    "watchlist" -> title.ifBlank { "Ma watchlist" } to watchlistRow()
                    "recommendations" -> title.ifBlank { "Recommandé pour vous" } to row {
                        api.recommendations().map { it.toCard() }
                    }
                    "because_you_watched" -> item.sourceMediaId.takeIf { it.isNotBlank() }?.let { id ->
                        title.ifBlank { "Parce que vous avez regardé" } to row { api.similarTo(id).map { it.toCard() } }
                    }
                    "browse", "recent_episodes" -> title to row {
                        // Les doublages étant retirés, on demande plus d'épisodes pour garder une rangée bien remplie.
                        val params = feedParams(item).let { p ->
                            if (item.responseType == "recent_episodes" || p["type"] == "episode") p + ("n" to "60") else p
                        }
                        api.browseWith(params).originalEpisodesOnly().take(30).map { it.toCard() }
                    }
                    else -> null
                }
                "curated_collection" -> if (item.responseType == "series" && item.ids.isNotEmpty()) {
                    title to row { api.objects(item.ids).map { it.toCard() } }
                } else null
                else -> null
            }
        }
    }

    /** Paramètres de browse : query_params si présents, sinon ceux du lien (sans les variables de locale). */
    private fun feedParams(item: CrFeedItem): Map<String, String> {
        val fromJson = item.queryParams?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        }.orEmpty()
        val fromLink = item.link.substringAfter('?', "").split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val v = Uri.decode(part.substringAfter('=', ""))
            if (k.isBlank() || v.isBlank()) null else k to v
        }.toMap()
        return (fromLink + fromJson).filter { (k, v) ->
            k !in setOf("locale", "preferred_audio_language") && !v.contains('{')
        }
    }

    private fun adnLoaders(): List<Pair<String, suspend () -> List<CardItem>>> = listOf(
        "ADN · Simulcasts" to row { graph.adn.catalog(order = "popular", simulcastOnly = true, limit = 40).map { it.toCard() } },
        "ADN · Populaires" to row { graph.adn.catalog(order = "popular", limit = 40).map { it.toCard() } },
    )

    private fun fallbackLoaders(): List<Pair<String, suspend () -> List<CardItem>>> = listOf(
        "Continuer à regarder" to continueLoader,
        "Ma watchlist" to watchlistRow(),
        "Populaires" to row { graph.api.browse("popularity").map { it.toSeriesCard() } },
        "Nouveautés" to row { graph.api.browse("newly_added").map { it.toSeriesCard() } },
    )
}

@Composable
fun HomeScreen(onOpenSeries: (SeriesRef) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel { HomeViewModel(graph) }
    val state by vm.state.collectAsState()
    val visible by vm.visible.collectAsState()
    val filters by vm.filters.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    LaunchedEffect(providers) { vm.ensure(providers) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val context = LocalContext.current

    when {
        state.loading && state.rows.isEmpty() -> CenteredMessage("Chargement…")
        state.error != null -> CenteredMessage(state.error!!, "Réessayer") { vm.load() }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "filters") {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CycleButton("Tri", filters.sort.label, vm::nextSort)
                        CycleButton("MAL ≥", filters.minScore?.toString() ?: "toutes", vm::nextMinScore)
                        if (visible.progressNeeded > 0) {
                            Text(
                                "progression ${visible.progressKnown}/${visible.progressNeeded}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusChips(filters.statuses, vm::toggleStatus)
                }
            }
            if (visible.rows.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Text(
                        if (visible.progressNeeded > visible.progressKnown) "Calcul de la progression…" else "Aucune série ne correspond à ces filtres.",
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
            items(visible.rows, key = { it.key }) { row ->
                Column {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(row.items) { item ->
                            MediaCard(
                                item = item,
                                onClick = {
                                    when {
                                        item.series.id.isNotBlank() -> onOpenSeries(item.series)
                                        item.episodeId != null -> OfficialApp.openEpisode(context, item.episodeId, null)
                                    }
                                },
                                onLongClick = item.episodeId?.let { id -> { OfficialApp.openEpisode(context, id, item.series.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
