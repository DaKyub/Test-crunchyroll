package com.dakyub.crunchymal.data.mal

import com.dakyub.crunchymal.data.CacheWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import android.content.Context
import android.util.Log
import com.dakyub.crunchymal.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** malId == null signifie "aucune correspondance trouvée" (ou choisie manuellement). */
@Serializable
data class MalRecord(
    val malId: Int? = null,
    val title: String? = null,
    val score: Double? = null,
    val scoredBy: Int? = null,
    val url: String? = null,
    val fetchedAt: Long = 0,
    val manual: Boolean = false,
    /** null = fiche mise en cache avant l'ajout des genres (à rafraîchir). */
    val genres: List<String>? = null,
    val englishTitle: String? = null,
    /** Diffusion (finished_airing, currently_airing, not_yet_aired) ; null = ancienne fiche à rafraîchir. */
    val airing: String? = null,
)

class MalRepository(context: Context, clientId: () -> String, val auth: MalAuth) {
    private val file = File(context.filesDir, "mal_cache.json")
    private val api = MalApi(clientId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val writeLock = Mutex()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, MalRecord>> = _records

    /** Dernière erreur rencontrée (affichée dans l'interface pour le diagnostic). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Incrémenté quand la configuration change : les badges redemandent alors leur note. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun configChanged() {
        _lastError.value = null
        _version.value++
    }

    private fun load(): Map<String, MalRecord> = runCatching {
        if (file.exists()) Http.json.decodeFromString<Map<String, MalRecord>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    // Écritures groupées : une liste ou un classement enregistre des centaines de fiches d'affilée.
    private val recordsWriter = CacheWriter(file) { Http.json.encodeToString(_records.value) }

    private fun put(key: String, record: MalRecord) {
        _records.update { it + (key to record) }
        recordsWriter.schedule()
    }

    private fun isStale(r: MalRecord): Boolean {
        val age = System.currentTimeMillis() - r.fetchedAt
        return when {
            r.manual && r.malId == null -> false
            r.malId == null -> age > NO_MATCH_TTL
            r.genres == null || r.airing == null -> true
            else -> age > SCORE_TTL
        }
    }

    /**
     * Demande (en arrière-plan) la note MAL associée à [key]. Le résultat arrive dans [records].
     * [titles] : titres Crunchyroll servant à la recherche (titre anglais déduit du slug en premier).
     */
    fun request(key: String, titles: List<String>, retryMissing: Boolean = false) {
        val existing = _records.value[key]
        // [retryMissing] : nouvelle recherche d'une fiche restée sans correspondance (titres plus fiables).
        val retry = retryMissing && existing != null && existing.malId == null && !existing.manual
        if (existing != null && !isStale(existing) && !retry) return
        val queries = titles.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (queries.isEmpty() && existing?.malId == null) return
        if (!pending.add(key)) return
        scope.launch {
            try {
                put(key, resolve(existing, queries))
                _lastError.value = null
            } catch (e: Exception) {
                Log.w("MalRepository", "Échec MAL pour $key", e)
                _lastError.value = e.message ?: e.javaClass.simpleName
            } finally {
                pending.remove(key)
            }
        }
    }

    private suspend fun resolve(existing: MalRecord?, queries: List<String>): MalRecord {
        val now = System.currentTimeMillis()
        existing?.malId?.let { id ->
            return api.anime(id).toRecord(now, existing.manual)
        }
        for (q in queries) {
            val best = TitleMatcher.pick(api.search(q), queries)
            if (best != null) return best.toRecord(now, manual = false)
        }
        return MalRecord(malId = null, fetchedAt = now)
    }

    /** Statut dans la liste MAL de l'utilisateur connecté, et nombre total d'épisodes. */
    suspend fun myStatus(malId: Int): Pair<MalListStatus?, Int> = api.myStatus(malId, auth.accessToken())

    suspend fun updateMyStatus(malId: Int, status: String, score: Int, episodesWatched: Int?) {
        api.updateStatus(malId, auth.accessToken(), status, score, episodesWatched)
        _myListStatuses.update { it + (malId to status) }
        if (status != "plan_to_watch") _myListSeenIds.update { it + malId }
        _myListCompleted.update { if (status == "completed") it + (malId to (episodesWatched ?: it[malId] ?: 0)) else it - malId }
    }

    /** Fiches « Terminé » de la liste MAL → nombre d'épisodes de l'anime (0 = inconnu). */
    private val _myListCompleted = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val myListCompleted: StateFlow<Map<Int, Int>> = _myListCompleted

    suspend fun myList(): List<MalListEntry> = api.userList(auth.accessToken()).also { list ->
        _myListSeenIds.value = list.filter { it.status.status != null && it.status.status != "plan_to_watch" }
            .map { it.anime.malId }.toSet()
        _myListStatuses.value = list.mapNotNull { e -> e.status.status?.let { e.anime.malId to it } }.toMap()
        _myListCompleted.value = list.filter { it.status.status == "completed" }
            .associate { it.anime.malId to (it.anime.episodes ?: it.status.episodesWatched) }
        myListFetchedAt = System.currentTimeMillis()
        lastList = list
        _myListTags.value = list.filter { it.status.tags.isNotEmpty() }.associate { it.anime.malId to it.status.tags }
        tagsWriter.schedule()
    }

    private var lastList: List<MalListEntry>? = null

    /** Liste MAL récupérée il y a moins de [maxAgeMs], sinon relue. */
    suspend fun myListCached(maxAgeMs: Long): List<MalListEntry> =
        lastList?.takeIf { System.currentTimeMillis() - myListFetchedAt < maxAgeMs } ?: myList()

    /** Tags de chaque fiche de la liste MAL (gardés sur disque pour un affichage immédiat au démarrage). */
    private val tagsFile = File(context.filesDir, "mal_tags.json")
    private val _myListTags = MutableStateFlow(
        runCatching { Http.json.decodeFromString<Map<Int, List<String>>>(tagsFile.readText()) }.getOrElse { emptyMap() }
    )
    val myListTags: StateFlow<Map<Int, List<String>>> = _myListTags
    private val tagsWriter = CacheWriter(tagsFile) { Http.json.encodeToString(_myListTags.value) }

    /**
     * Remplace les tags d'une fiche. [statusIfNew] : statut donné si l'anime n'est pas encore dans la liste
     * (il y est alors ajouté) ; null = l'anime y est déjà, statut et note inchangés.
     */
    suspend fun setTags(malId: Int, tags: List<String>, statusIfNew: String?) {
        api.updateTags(malId, auth.accessToken(), tags, statusIfNew)
        _myListTags.update { if (tags.isEmpty()) it - malId else it + (malId to tags) }
        statusIfNew?.let { status ->
            _myListStatuses.update { it + (malId to status) }
            if (status != "plan_to_watch") _myListSeenIds.update { it + malId }
        }
        lastList = lastList?.map { e -> if (e.anime.malId == malId) e.copy(status = e.status.copy(tags = tags)) else e }
        tagsWriter.schedule()
    }

    /** Fiches MAL déjà vues ou commencées par l'utilisateur (tout statut sauf « À voir »). */
    private val _myListSeenIds = MutableStateFlow<Set<Int>>(emptySet())
    val myListSeenIds: StateFlow<Set<Int>> = _myListSeenIds

    /** Statut MAL (watching, completed…) de chaque fiche de la liste de l'utilisateur. */
    private val _myListStatuses = MutableStateFlow<Map<Int, String>>(emptyMap())
    val myListStatuses: StateFlow<Map<Int, String>> = _myListStatuses
    private var myListFetchedAt = 0L

    /** Rafraîchit [myListSeenIds] (au plus toutes les 30 min) si le compte MAL est connecté. */
    suspend fun refreshMyListSeenIds() {
        if (!auth.loggedIn.value || System.currentTimeMillis() - myListFetchedAt < 30 * 60 * 1000) return
        runCatching { myList() }
    }

    /** Enregistre une fiche déjà connue (ex. liste MAL) pour que les badges l'affichent sans recherche. */
    suspend fun seed(key: String, anime: MalAnime) {
        val existing = _records.value[key]
        if (existing != null && !isStale(existing)) return
        put(key, anime.toRecord(System.currentTimeMillis(), manual = true))
    }

    suspend fun candidates(query: String): List<MalAnime> = api.search(query, limit = 15)

    private val detailsCache = java.util.concurrent.ConcurrentHashMap<Int, MalAnime>()

    /** Fiche complète (affiche, résumé), gardée le temps de la session. */
    suspend fun details(malId: Int): MalAnime = detailsCache[malId] ?: api.details(malId).also { detailsCache[malId] = it }

    /** Résumé MAL (anglais) sans la mention finale « [Written by MAL Rewrite] ». */
    suspend fun synopsis(malId: Int): String? =
        details(malId).synopsis?.replace(Regex("""\s*\[Written by [^\]]*\]\s*$"""), "")?.trim()?.ifBlank { null }

    suspend fun seasonal(year: Int, season: String): List<MalAnime> = api.seasonal(year, season)

    private val rankingCache = ConcurrentHashMap<String, List<MalAnime>>()

    /** Les 1 000 premiers animes d'un classement MAL, gardés le temps de la session. */
    suspend fun ranking(type: String): List<MalAnime> = rankingCache[type] ?: run {
        val first = api.ranking(type, limit = 500, offset = 0)
        val second = if (first.size >= 500) runCatching { api.ranking(type, limit = 500, offset = 500) }.getOrDefault(emptyList()) else emptyList()
        (first + second).distinctBy { it.malId }.also { rankingCache[type] = it }
    }

    /**
     * Ajoute l'anime à « À voir » sur la liste MAL s'il n'y est pas encore. Renvoie null s'il a été ajouté,
     * sinon son statut actuel (rien n'est modifié).
     */
    suspend fun addToPlanToWatch(malId: Int): String? {
        val current = myStatus(malId).first?.status
        if (current != null) {
            _myListStatuses.update { it + (malId to current) }
            return current
        }
        api.updateStatus(malId, auth.accessToken(), "plan_to_watch", 0, null)
        _myListStatuses.update { it + (malId to "plan_to_watch") }
        return null
    }

    private val recommendationCache = java.util.concurrent.ConcurrentHashMap<Int, List<MalAnime>>()

    /** Recommandations MAL d'un anime (fiches complètes, 12 au plus), gardées le temps de la session. */
    suspend fun recommendations(malId: Int): List<MalAnime> = recommendationCache[malId] ?: run {
        api.recommendationIds(malId).take(12)
            .mapNotNull { id -> runCatching { api.details(id) }.getOrNull() }
            .also { recommendationCache[malId] = it }
    }

    /** Correction manuelle ; [anime] null = "pas sur MAL". */
    suspend fun setManual(key: String, anime: MalAnime?) {
        val now = System.currentTimeMillis()
        put(key, anime?.toRecord(now, manual = true) ?: MalRecord(malId = null, fetchedAt = now, manual = true))
    }

    suspend fun clear() {
        _records.value = emptyMap()
        writeLock.withLock { file.delete() }
    }

    private fun MalAnime.toRecord(now: Long, manual: Boolean) = MalRecord(
        malId = malId,
        title = title,
        score = score,
        scoredBy = scoredBy,
        url = url,
        fetchedAt = now,
        manual = manual,
        genres = genres,
        englishTitle = titleEnglish,
        airing = airing,
    )

    private companion object {
        const val SCORE_TTL = 7L * 24 * 3600 * 1000
        const val NO_MATCH_TTL = 3L * 24 * 3600 * 1000
    }
}
