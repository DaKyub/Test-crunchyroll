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
)

class MalRepository(context: Context) {
    private val file = File(context.filesDir, "mal_cache.json")
    private val jikan = JikanApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val writeLock = Mutex()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, MalRecord>> = _records

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
            } catch (e: Exception) {
                Log.w("MalRepository", "Échec MAL pour $key", e)
            } finally {
                pending.remove(key)
            }
        }
    }

    private suspend fun resolve(existing: MalRecord?, queries: List<String>): MalRecord {
        val now = System.currentTimeMillis()
        existing?.malId?.let { id ->
            return jikan.anime(id).toRecord(now, existing.manual)
        }
        for (q in queries) {
            val best = TitleMatcher.pick(jikan.search(q), queries)
            if (best != null) return best.toRecord(now, manual = false)
        }
        return MalRecord(malId = null, fetchedAt = now)
    }

    suspend fun candidates(query: String): List<JikanAnime> = jikan.search(query, limit = 15)

    /** Correction manuelle ; [anime] null = "pas sur MAL". */
    suspend fun setManual(key: String, anime: JikanAnime?) {
        val now = System.currentTimeMillis()
        put(key, anime?.toRecord(now, manual = true) ?: MalRecord(malId = null, fetchedAt = now, manual = true))
    }

    suspend fun clear() {
        _records.value = emptyMap()
        writeLock.withLock { file.delete() }
    }

    private fun JikanAnime.toRecord(now: Long, manual: Boolean) = MalRecord(
        malId = malId,
        title = title,
        score = score,
        scoredBy = scoredBy,
        url = url,
        fetchedAt = now,
        manual = manual,
    )

    private companion object {
        const val SCORE_TTL = 7L * 24 * 3600 * 1000
        const val NO_MATCH_TTL = 3L * 24 * 3600 * 1000
    }
}
