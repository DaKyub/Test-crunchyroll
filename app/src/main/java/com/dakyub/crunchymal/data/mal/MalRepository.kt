package com.dakyub.crunchymal.data.mal

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
)

class MalRepository(context: Context, clientId: () -> String) {
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

    private suspend fun put(key: String, record: MalRecord) {
        _records.update { it + (key to record) }
        writeLock.withLock {
            runCatching { file.writeText(Http.json.encodeToString(_records.value)) }
        }
    }

    private fun isStale(r: MalRecord): Boolean {
        val age = System.currentTimeMillis() - r.fetchedAt
        return when {
            r.manual && r.malId == null -> false
            r.malId == null -> age > NO_MATCH_TTL
            r.genres == null -> true
            else -> age > SCORE_TTL
        }
    }

    /**
     * Demande (en arrière-plan) la note MAL associée à [key]. Le résultat arrive dans [records].
     * [titles] : titres Crunchyroll servant à la recherche (titre anglais déduit du slug en premier).
     */
    fun request(key: String, titles: List<String>) {
        val existing = _records.value[key]
        if (existing != null && !isStale(existing)) return
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

    suspend fun candidates(query: String): List<MalAnime> = api.search(query, limit = 15)

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
    )

    private companion object {
        const val SCORE_TTL = 7L * 24 * 3600 * 1000
        const val NO_MATCH_TTL = 3L * 24 * 3600 * 1000
    }
}
