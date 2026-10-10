package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.crunchyroll.CrHistoryItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class WatchedSeries(
    val fetchedAt: Long = 0,
    val ids: Set<String> = emptySet(),
    /** Série → date (ISO) du dernier épisode regardé. Vide = cache d'une ancienne version. */
    val lastPlayed: Map<String, String> = emptyMap(),
)

/**
 * Séries dont au moins un épisode figure dans l'historique Crunchyroll (toutes les pages, pas
 * seulement les plus récentes), avec la date du dernier visionnage. Cache disque de 12 h ; entre deux
 * lectures complètes, la première page suffit à ajouter les visionnages récents.
 */
class HistoryRepository(context: Context, private val api: CrApi) {
    private val file = File(context.filesDir, "watched_series.json")
    private val mutex = Mutex()
    private var cache: WatchedSeries? = null

    suspend fun watchedSeriesIds(): Set<String> = load(force = false, refreshRecent = false).ids

    /** Série → date du dernier visionnage, la première page de l'historique étant relue à chaque fois. */
    suspend fun lastPlayed(force: Boolean = false): Map<String, String> = load(force, refreshRecent = true).lastPlayed

    private suspend fun load(force: Boolean, refreshRecent: Boolean): WatchedSeries = mutex.withLock {
        val current = cache ?: runCatching {
            if (file.exists()) Http.json.decodeFromString<WatchedSeries>(file.readText()) else null
        }.getOrNull()
        val usable = current != null && System.currentTimeMillis() - current.fetchedAt < TTL &&
            (current.ids.isEmpty() || current.lastPlayed.isNotEmpty())
        if (!force && usable && current != null) {
            if (!refreshRecent) {
                cache = current
                return current
            }
            val recent = runCatching { api.watchHistory(pageSize = PAGE_SIZE, page = 1) }.getOrNull()
            val merged = if (recent == null) current else {
                val played = current.lastPlayed.toMutableMap()
                recent.forEach { add(it, played) }
                current.copy(ids = current.ids + played.keys, lastPlayed = played)
            }
            cache = merged
            if (merged !== current) runCatching { file.writeText(Http.json.encodeToString(merged)) }
            return merged
        }
        val played = mutableMapOf<String, String>()
        for (page in 1..MAX_PAGES) {
            val items = runCatching { api.watchHistory(pageSize = PAGE_SIZE, page = page) }.getOrNull() ?: break
            items.forEach { add(it, played) }
            if (items.size < PAGE_SIZE) break
        }
        val fresh = WatchedSeries(System.currentTimeMillis(), played.keys, played)
        cache = fresh
        runCatching { file.writeText(Http.json.encodeToString(fresh)) }
        fresh
    }

    /** Ajoute la série d'un élément de l'historique en gardant la date la plus récente. */
    private fun add(item: CrHistoryItem, played: MutableMap<String, String>) {
        val id = item.panel.episodeMetadata?.seriesId?.ifBlank { null } ?: item.parentId.ifBlank { null } ?: return
        val date = item.datePlayed
        val known = played[id]
        if (known == null || date > known) played[id] = date
    }

    private companion object {
        const val TTL = 12L * 3600 * 1000
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 30
    }
}
