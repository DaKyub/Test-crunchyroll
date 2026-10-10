package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class WatchedSeries(val fetchedAt: Long = 0, val ids: Set<String> = emptySet())

/**
 * Séries dont au moins un épisode figure dans l'historique Crunchyroll (toutes les pages, pas
 * seulement les plus récentes), avec un cache disque de 12 h.
 */
class HistoryRepository(context: Context, private val api: CrApi) {
    private val file = File(context.filesDir, "watched_series.json")
    private val mutex = Mutex()
    private var cache: WatchedSeries? = null

    suspend fun watchedSeriesIds(): Set<String> = mutex.withLock {
        val current = cache ?: runCatching {
            if (file.exists()) Http.json.decodeFromString<WatchedSeries>(file.readText()) else null
        }.getOrNull()
        if (current != null && System.currentTimeMillis() - current.fetchedAt < TTL) {
            cache = current
            return current.ids
        }
        val ids = mutableSetOf<String>()
        for (page in 1..MAX_PAGES) {
            val items = runCatching { api.watchHistory(pageSize = PAGE_SIZE, page = page) }.getOrNull() ?: break
            items.forEach { item ->
                (item.panel.episodeMetadata?.seriesId?.ifBlank { null } ?: item.parentId.ifBlank { null })?.let { ids += it }
            }
            if (items.size < PAGE_SIZE) break
        }
        val fresh = WatchedSeries(System.currentTimeMillis(), ids)
        cache = fresh
        runCatching { file.writeText(Http.json.encodeToString(fresh)) }
        ids
    }

    private companion object {
        const val TTL = 12L * 3600 * 1000
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 30
    }
}
