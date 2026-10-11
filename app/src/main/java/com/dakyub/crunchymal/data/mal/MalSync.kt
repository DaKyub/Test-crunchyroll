package com.dakyub.crunchymal.data.mal

import com.dakyub.crunchymal.data.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * Synchronisation automatique de la liste MAL : le nombre d'épisodes vus sur Crunchyroll / ADN est
 * reporté sur la fiche MAL correspondante (« En cours », puis « Terminé » au dernier épisode).
 * Ne fait jamais reculer MAL, et respecte les séries « Terminé » (revisionnage) et « Abandonné ».
 */
class MalSync(private val mal: MalRepository, private val settings: Settings) {
    /** Dernier nombre d'épisodes traité par fiche MAL pendant la session (évite les appels répétés). */
    private val handled = ConcurrentHashMap<Int, Int>()

    /** Renvoie un message à afficher si la fiche MAL a été mise à jour, sinon null. */
    suspend fun sync(malId: Int, watched: Int): String? {
        if (!settings.malAutoSync || !mal.auth.loggedIn.value || watched <= 0) return null
        if ((handled[malId] ?: -1) >= watched) return null
        val (status, total) = mal.myStatus(malId)
        handled[malId] = watched
        val current = status?.status
        if (current == "completed" || current == "dropped") return null
        val episodes = if (total > 0) minOf(watched, total) else watched
        if (episodes <= (status?.episodesWatched ?: 0)) return null
        val newStatus = if (total > 0 && episodes >= total) "completed" else "watching"
        mal.updateMyStatus(malId, newStatus, status?.score ?: 0, episodes)
        val title = mal.records.value.values.firstOrNull { it.malId == malId }?.title ?: "MAL"
        return "$title : " + if (newStatus == "completed") "terminé sur MAL ✓" else "$episodes/${total.takeIf { it > 0 } ?: "?"} ép. sur MAL"
    }
}
