package com.dakyub.crunchymal.data.mal

import com.dakyub.crunchymal.data.CompanionTags
import com.dakyub.crunchymal.data.CompanionsRepository
import com.dakyub.crunchymal.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Série que la synchro vient de passer « Terminé » sur MAL, sans note : l'app propose de la noter. */
data class RatingRequest(val malId: Int, val title: String)

/**
 * Synchronisation automatique de la liste MAL : le nombre d'épisodes vus sur Crunchyroll / ADN est
 * reporté sur la fiche MAL correspondante (« En cours », puis « Terminé » au dernier épisode).
 * Ne fait jamais reculer MAL, et respecte les séries « Terminé » (revisionnage) et « Abandonné ».
 */
class MalSync(private val mal: MalRepository, private val settings: Settings, private val companions: CompanionsRepository) {
    /** Synchro active : réglage activé et compte MAL connecté. */
    val enabled: Boolean get() = settings.malAutoSync && mal.auth.loggedIn.value

    /** Dernier nombre d'épisodes traité par fiche MAL pendant la session (évite les appels répétés). */
    private val handled = ConcurrentHashMap<Int, Int>()

    private val _toRate = MutableStateFlow<List<RatingRequest>>(emptyList())

    /** Séries terminées à noter (fenêtre « Série terminée » au premier plan de l'app). */
    val toRate: StateFlow<List<RatingRequest>> = _toRate

    /** Notée, ou « Plus tard » : la fenêtre ne la propose plus. */
    fun dismissRating(malId: Int) {
        _toRate.update { list -> list.filterNot { it.malId == malId } }
    }

    /** Renvoie un message à afficher si la fiche MAL a été mise à jour, sinon null. */
    suspend fun sync(malId: Int, watched: Int): String? {
        if (!settings.malAutoSync || !mal.auth.loggedIn.value || watched <= 0) return null
        if ((handled[malId] ?: -1) >= watched) return null
        val (status, total) = mal.myStatus(malId)
        handled[malId] = watched
        val current = status?.status
        var message: String? = null
        if (current != "completed" && current != "dropped") {
            val episodes = if (total > 0) minOf(watched, total) else watched
            if (episodes > (status?.episodesWatched ?: 0)) {
                val newStatus = if (total > 0 && episodes >= total) "completed" else "watching"
                mal.updateMyStatus(malId, newStatus, status?.score ?: 0, episodes)
                val record = mal.records.value.values.firstOrNull { it.malId == malId }
                val title = record?.title ?: "MAL"
                if (newStatus == "completed" && (status?.score ?: 0) == 0) {
                    val request = RatingRequest(malId, record?.englishTitle ?: record?.title ?: "Série #$malId")
                    _toRate.update { list -> if (list.any { it.malId == malId }) list else list + request }
                }
                message = "$title : " + if (newStatus == "completed") "terminé sur MAL ✓" else "$episodes/${total.takeIf { it > 0 } ?: "?"} ép. sur MAL"
            }
        }
        // Marques « Qui regarde ? » seulement quand un nouvel épisode vu vient d'être reporté (pas à la simple
        // ouverture de la fiche).
        val tagged = if (message != null) tagViewers(malId, status?.tags.orEmpty()) else null
        return listOfNotNull(message, tagged).joinToString(" · ").ifBlank { null }
    }

    /**
     * « Qui regarde ? » : ajoute aux marques de la fiche les personnes devant l'écran (sans retirer les
     * autres). Renvoie un message si des marques ont été ajoutées.
     */
    private suspend fun tagViewers(malId: Int, tags: List<String>): String? {
        val viewers = companions.currentViewers().takeIf { it.isNotEmpty() } ?: return null
        val marks = companions.peopleIn(tags)
        if (viewers.all { viewer -> marks.any { it.equals(viewer, ignoreCase = true) } }) return null
        if (runCatching { companions.set(malId, marks + viewers, tags) }.isFailure) return null
        return if (viewers == setOf(CompanionTags.ALONE)) "marquée « vu seul »" else "marquée « vu avec ${viewers.filter { it != CompanionTags.ALONE }.joinToString(", ")} »"
    }
}
