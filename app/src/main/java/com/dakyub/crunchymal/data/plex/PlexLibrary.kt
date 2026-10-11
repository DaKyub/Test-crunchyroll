package com.dakyub.crunchymal.data.plex

import android.content.Context
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.WatchPlatformsRepository
import com.dakyub.crunchymal.data.mal.MalRecord
import com.dakyub.crunchymal.data.mal.MalRepository
import com.dakyub.crunchymal.data.mal.TitleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Série du serveur Plex, avec le nombre de saisons présentes et (d'après TMDB) existantes. */
@Serializable
data class PlexShowEntry(
    val ratingKey: String,
    val title: String,
    val originalTitle: String? = null,
    /** Saisons présentes sur le serveur (hors spéciaux). */
    val seasons: Int = 0,
    val tmdbId: Int? = null,
    /** Saisons de la série d'après TMDB (null = inconnu). */
    val totalSeasons: Int? = null,
    val totalCheckedAt: Long = 0,
) {
    val malTitles: List<String> get() = listOfNotNull(originalTitle, title).filter { it.isNotBlank() }.distinct()
}

@Serializable
private data class PlexLibraryFile(val fetchedAt: Long = 0, val shows: List<PlexShowEntry> = emptyList())

/** Série trouvée sur le serveur Plex : badge « Plex », ou « Plex 1/3 » s'il manque des saisons. */
data class PlexMatch(val ratingKey: String, val have: Int, val total: Int?) {
    val partial: Boolean get() = total != null && have in 1 until total
    val label: String get() = if (partial) "Plex $have/$total" else "Plex"
}

/** Recherche d'une série sur le serveur Plex : par fiche MAL, sinon par titre exact (saison retirée). */
data class PlexIndex(val byMalId: Map<Int, PlexMatch> = emptyMap(), val byTitle: Map<String, PlexMatch> = emptyMap()) {
    fun find(malId: Int?, titles: List<String>): PlexMatch? {
        if (byMalId.isEmpty() && byTitle.isEmpty()) return null
        malId?.let { byMalId[it] }?.let { return it }
        return titles.firstNotNullOfOrNull { title -> plexTitleKeys(title).firstNotNullOfOrNull { byTitle[it] } }
    }
}

/** Clés de comparaison d'un titre : en minuscules, sans ponctuation, avec et sans numéro de saison. */
fun plexTitleKeys(title: String): List<String> =
    listOf(title, TitleMatcher.withoutSeason(title))
        .map { it.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() }
        .filter { it.length >= 3 }
        .distinct()

/**
 * Index des séries du serveur Plex, pour afficher le badge « Plex » partout où la série apparaît.
 * Rafraîchi au plus toutes les 6 h ; la fiche MAL de chaque série est cherchée en arrière-plan, et son
 * nombre de saisons d'après TMDB (identifiant fourni par Plex) gardé une semaine.
 */
class PlexLibrary(
    context: Context,
    private val plex: PlexApi,
    private val mal: MalRepository,
    private val platforms: WatchPlatformsRepository,
) {
    private val file = File(context.filesDir, "plex_library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val saved = runCatching { Http.json.decodeFromString<PlexLibraryFile>(file.readText()) }.getOrElse { PlexLibraryFile() }
    @Volatile private var fetchedAt = saved.fetchedAt

    private val _shows = MutableStateFlow(saved.shows)
    val shows: StateFlow<List<PlexShowEntry>> = _shows

    val index: StateFlow<PlexIndex> = combine(_shows, mal.records) { shows, records -> build(shows, records) }
        .stateIn(scope, SharingStarted.Eagerly, build(saved.shows, mal.records.value))

    init {
        // Déconnexion de Plex : plus de badge.
        scope.launch {
            plex.connected.drop(1).collect { connected ->
                if (!connected) mutex.withLock {
                    _shows.value = emptyList()
                    fetchedAt = 0
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun build(shows: List<PlexShowEntry>, records: Map<String, MalRecord>): PlexIndex {
        val byMalId = HashMap<Int, PlexMatch>()
        val byTitle = HashMap<String, PlexMatch>()
        shows.forEach { show ->
            val match = PlexMatch(show.ratingKey, show.seasons, show.totalSeasons)
            records["plex:${show.ratingKey}"]?.malId?.let { byMalId.putIfAbsent(it, match) }
            show.malTitles.flatMap(::plexTitleKeys).forEach { byTitle.putIfAbsent(it, match) }
        }
        return PlexIndex(byMalId, byTitle)
    }

    /** Relit la bibliothèque si elle date de plus de 6 h (ou [force]), puis complète fiches MAL et saisons. */
    fun refresh(force: Boolean = false) {
        if (!plex.connected.value) return
        scope.launch {
            mutex.withLock {
                if (force || System.currentTimeMillis() - fetchedAt > TTL) runCatching { fetch() }
                completeDetails()
            }
        }
    }

    private suspend fun fetch() {
        val previous = _shows.value.associateBy { it.ratingKey }
        val entries = plex.libraries().flatMap { library ->
            // Saisons comptées une par une (hors spéciaux) ; à défaut, le nombre donné par la série.
            val seasonsByShow = runCatching { plex.seasons(library.key) }.getOrDefault(emptyList())
                .filter { (it.index ?: 0) > 0 && it.parentRatingKey != null }
                .groupingBy { it.parentRatingKey!! }.eachCount()
            plex.showsWithGuids(library.key).map { show ->
                val tmdbId = show.guids.firstNotNullOfOrNull { guid ->
                    guid.id.takeIf { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull()
                }
                val old = previous[show.ratingKey]?.takeIf { it.tmdbId == tmdbId }
                PlexShowEntry(
                    ratingKey = show.ratingKey,
                    title = show.title,
                    originalTitle = show.originalTitle,
                    seasons = seasonsByShow[show.ratingKey] ?: show.childCount,
                    tmdbId = tmdbId,
                    totalSeasons = old?.totalSeasons,
                    totalCheckedAt = old?.totalCheckedAt ?: 0,
                )
            }
        }.distinctBy { it.ratingKey }
        _shows.value = entries
        fetchedAt = System.currentTimeMillis()
        save()
    }

    /** Fiche MAL de chaque série (badge sur les cartes MAL) et nombre de saisons d'après TMDB. */
    private suspend fun completeDetails() {
        _shows.value.forEach { mal.request("plex:${it.ratingKey}", it.malTitles) }
        val now = System.currentTimeMillis()
        val stale = _shows.value.filter { it.tmdbId != null && (it.totalSeasons == null || now - it.totalCheckedAt > TOTAL_TTL) }
        stale.forEachIndexed { i, show ->
            val total = platforms.tvSeasonCount(show.tmdbId!!) ?: return@forEachIndexed
            _shows.value = _shows.value.map {
                if (it.ratingKey == show.ratingKey) it.copy(totalSeasons = total, totalCheckedAt = System.currentTimeMillis()) else it
            }
            if (i % 20 == 19) save()
            delay(100)
        }
        if (stale.isNotEmpty()) save()
    }

    private fun save() {
        runCatching { file.writeText(Http.json.encodeToString(PlexLibraryFile(fetchedAt, _shows.value))) }
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
        const val TOTAL_TTL = 7L * 24 * 3600 * 1000
    }
}
