package com.dakyub.crunchymal.data.plex

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.dakyub.crunchymal.data.mal.MalSync
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
    val tvdbId: String? = null,
    val imdbId: String? = null,
    /** Saisons de la série d'après TMDB (null = inconnu). */
    val totalSeasons: Int? = null,
    val totalCheckedAt: Long = 0,
    /** Titres d'après TMDB : Plex affiche souvent les titres en français, introuvables sur MAL. */
    val englishTitle: String? = null,
    val tmdbOriginalTitle: String? = null,
    /** Fiche MAL déjà recherchée de nouveau avec le titre anglais. */
    val malRetried: Boolean = false,
    /** Épisodes vus sur Plex, et nombre déjà reporté sur MAL (synchro auto). */
    val watched: Int = 0,
    val syncedWatched: Int = 0,
) {
    /** Titres pour MAL et pour reconnaître la série ailleurs : anglais d'abord, titre Plex en dernier. */
    val malTitles: List<String>
        get() = listOfNotNull(englishTitle, originalTitle, tmdbOriginalTitle, title).filter { it.isNotBlank() }.distinct()
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

private val nonAlphanumeric = Regex("""[^\p{L}\p{N}]+""")

/** Clés de comparaison d'un titre : en minuscules, sans ponctuation, avec et sans numéro de saison. */
fun plexTitleKeys(title: String): List<String> =
    listOf(title, TitleMatcher.withoutSeason(title))
        .map { it.lowercase().replace(nonAlphanumeric, " ").trim() }
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
    private val malSync: MalSync,
) {
    private val file = File(context.filesDir, "plex_library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val saved = runCatching { Http.json.decodeFromString<PlexLibraryFile>(file.readText()) }.getOrElse { PlexLibraryFile() }
    @Volatile private var fetchedAt = saved.fetchedAt

    private val _shows = MutableStateFlow(saved.shows)
    val shows: StateFlow<List<PlexShowEntry>> = _shows

    // Index par titre : ne dépend que des séries (recalculé seulement quand la bibliothèque change).
    private val titleIndex: StateFlow<Map<String, PlexMatch>> = _shows.map(::buildTitles)
        .stateIn(scope, SharingStarted.Eagerly, buildTitles(saved.shows))

    /** Par fiche MAL (suit les notes MAL qui arrivent) et par titre ; publié seulement s'il change. */
    val index: StateFlow<PlexIndex> = combine(_shows, mal.records, titleIndex) { shows, records, titles ->
        PlexIndex(buildMalIds(shows, records), titles)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PlexIndex(buildMalIds(saved.shows, mal.records.value), buildTitles(saved.shows)))

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

    private fun buildMalIds(shows: List<PlexShowEntry>, records: Map<String, MalRecord>): Map<Int, PlexMatch> {
        val byMalId = HashMap<Int, PlexMatch>()
        shows.forEach { show ->
            records["plex:${show.ratingKey}"]?.malId?.let { byMalId.putIfAbsent(it, PlexMatch(show.ratingKey, show.seasons, show.totalSeasons)) }
        }
        return byMalId
    }

    private fun buildTitles(shows: List<PlexShowEntry>): Map<String, PlexMatch> {
        val byTitle = HashMap<String, PlexMatch>()
        shows.forEach { show ->
            val match = PlexMatch(show.ratingKey, show.seasons, show.totalSeasons)
            show.malTitles.flatMap(::plexTitleKeys).forEach { byTitle.putIfAbsent(it, match) }
        }
        return byTitle
    }

    /** Résumé de l'index pour le diagnostic Plex : saisons présentes sur le serveur et saisons d'après TMDB. */
    fun summary(): List<String> {
        val shows = _shows.value
        val partial = shows.filter { PlexMatch(it.ratingKey, it.seasons, it.totalSeasons).partial }
        fun line(it: PlexShowEntry) = "- ${it.englishTitle ?: it.title} : ${it.seasons} saison(s) sur le serveur / " +
            "${it.totalSeasons ?: "?"} d'après TMDB" + if (it.tmdbId == null) " (pas d'identifiant TMDB)" else ""
        return listOf(
            "",
            "Index Plex (badges) : ${shows.size} séries · id TMDB ${shows.count { it.tmdbId != null }} · " +
                "titre anglais ${shows.count { it.englishTitle != null }} · saisons TMDB connues ${shows.count { it.totalSeasons != null }} · " +
                "saisons serveur > 0 ${shows.count { it.seasons > 0 }} · incomplètes ${partial.size}",
        ) + partial.take(10).map(::line) + (shows - partial.toSet()).take(10).map(::line)
    }

    /** Titres TMDB (anglais, original) d'une série Plex, à essayer avant ses titres Plex. */
    fun extraTitles(ratingKey: String): List<String> =
        _shows.value.firstOrNull { it.ratingKey == ratingKey }?.let { listOfNotNull(it.englishTitle, it.tmdbOriginalTitle) }.orEmpty()

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
                fun guid(prefix: String) = show.guids.firstNotNullOfOrNull { g -> g.id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) }
                val previousEntry = previous[show.ratingKey]
                // Sans identifiant TMDB chez Plex, on garde celui retrouvé via TVDB / IMDb.
                val tmdbId = guid("tmdb://")?.toIntOrNull() ?: previousEntry?.tmdbId
                val old = previousEntry?.takeIf { it.tmdbId == tmdbId }
                PlexShowEntry(
                    ratingKey = show.ratingKey,
                    title = show.title,
                    originalTitle = show.originalTitle,
                    seasons = seasonsByShow[show.ratingKey] ?: show.childCount,
                    tmdbId = tmdbId,
                    tvdbId = guid("tvdb://"),
                    imdbId = guid("imdb://"),
                    totalSeasons = old?.totalSeasons,
                    totalCheckedAt = old?.totalCheckedAt ?: 0,
                    englishTitle = old?.englishTitle,
                    tmdbOriginalTitle = old?.tmdbOriginalTitle,
                    malRetried = old?.malRetried ?: false,
                    watched = show.viewedLeafCount,
                    syncedWatched = previousEntry?.syncedWatched ?: 0,
                )
            }
        }.distinctBy { it.ratingKey }
        _shows.value = entries
        fetchedAt = System.currentTimeMillis()
        save()
    }

    /**
     * D'après TMDB : identifiant (retrouvé via TVDB / IMDb si Plex n'en donne pas), titres anglais et original,
     * nombre de saisons. Puis fiche MAL de chaque série, cherchée avec ces titres ; une série restée sans fiche
     * MAL est recherchée une fois de plus quand son titre anglais devient connu.
     */
    private suspend fun completeDetails() {
        var updated = 0
        for (show in _shows.value) {
            val now = System.currentTimeMillis()
            var entry = show
            if (entry.tmdbId == null && (entry.tvdbId != null || entry.imdbId != null) && now - entry.totalCheckedAt > TOTAL_TTL) {
                val id = entry.tvdbId?.let { platforms.tmdbIdFrom("tvdb_id", it) } ?: entry.imdbId?.let { platforms.tmdbIdFrom("imdb_id", it) }
                entry = entry.copy(tmdbId = id, totalCheckedAt = if (id == null) now else 0)
            }
            val tmdbId = entry.tmdbId
            if (tmdbId != null && (entry.totalSeasons == null || entry.englishTitle == null || now - entry.totalCheckedAt > TOTAL_TTL)) {
                platforms.tvInfo(tmdbId)?.let { info ->
                    entry = entry.copy(
                        totalSeasons = info.seasons.takeIf { it > 0 },
                        englishTitle = info.name.ifBlank { null },
                        tmdbOriginalTitle = info.originalName.ifBlank { null },
                        totalCheckedAt = now,
                    )
                }
                delay(100)
            }
            if (entry != show) {
                _shows.value = _shows.value.map { if (it.ratingKey == entry.ratingKey) entry else it }
                if (++updated % 20 == 0) save()
            }
        }
        if (updated > 0) save()

        val retried = mutableSetOf<String>()
        _shows.value.forEach { show ->
            val retry = show.englishTitle != null && !show.malRetried
            mal.request("plex:${show.ratingKey}", show.malTitles, retryMissing = retry)
            if (retry) retried += show.ratingKey
        }
        if (retried.isNotEmpty()) {
            _shows.value = _shows.value.map { if (it.ratingKey in retried) it.copy(malRetried = true) else it }
            save()
        }
        syncLocked(_shows.value.associate { it.ratingKey to it.watched })
    }

    /**
     * Synchro MAL depuis Plex : le nombre d'épisodes vus sur Plex ([watched], par série) est reporté sur la
     * fiche MAL (réglage « Synchro auto de ma liste MAL »), sans jamais reculer. Les séries dont la fiche MAL
     * n'est pas encore connue le seront au passage suivant.
     */
    suspend fun syncWatched(watched: Map<String, Int>) = mutex.withLock { syncLocked(watched) }

    private suspend fun syncLocked(watched: Map<String, Int>) {
        if (!malSync.enabled) return
        val records = mal.records.value
        var changed = false
        for ((ratingKey, count) in watched) {
            if (count <= 0) continue
            val entry = _shows.value.firstOrNull { it.ratingKey == ratingKey }
            if (entry != null && entry.syncedWatched >= count) continue
            val malId = records["plex:$ratingKey"]?.malId ?: continue
            if (runCatching { malSync.sync(malId, count) }.isFailure) continue
            _shows.value = _shows.value.map {
                if (it.ratingKey == ratingKey) it.copy(syncedWatched = count, watched = maxOf(it.watched, count)) else it
            }
            changed = true
            delay(400)
        }
        if (changed) save()
    }

    private fun save() {
        runCatching { file.writeText(Http.json.encodeToString(PlexLibraryFile(fetchedAt, _shows.value))) }
    }

    private companion object {
        const val TTL = 6L * 3600 * 1000
        const val TOTAL_TTL = 7L * 24 * 3600 * 1000
    }
}
