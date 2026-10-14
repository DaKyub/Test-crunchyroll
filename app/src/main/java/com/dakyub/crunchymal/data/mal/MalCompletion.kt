package com.dakyub.crunchymal.data.mal

import com.dakyub.crunchymal.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Fiches « Terminé » de la liste MAL (→ nombre d'épisodes) et option qui les masque des listes. */
data class MalCompletionState(val completed: Map<Int, Int> = emptyMap(), val hide: Boolean = false) {
    /**
     * Série terminée d'après MAL. [totalEpisodes] : épisodes de la série sur Crunchyroll / ADN / Plex, quand
     * on les connaît ; s'il y en a nettement plus que sur la fiche MAL (souvent la saison 1 seulement), la
     * série n'est pas considérée comme terminée.
     */
    fun isCompleted(malId: Int?, totalEpisodes: Int? = null): Boolean {
        val episodes = completed[malId ?: return false] ?: return false
        return totalEpisodes == null || totalEpisodes <= 0 || episodes <= 0 || totalEpisodes <= episodes + 2
    }

    /** À retirer des listes (option des réglages activée). */
    fun hides(malId: Int?, totalEpisodes: Int? = null): Boolean = hide && isCompleted(malId, totalEpisodes)
}

class MalCompletion(settings: Settings, mal: MalRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<MalCompletionState> =
        combine(mal.myListCompleted, settings.hideMalCompletedFlow) { completed, hide -> MalCompletionState(completed, hide) }
            .stateIn(scope, SharingStarted.Eagerly, MalCompletionState(mal.myListCompleted.value, settings.hideMalCompleted))
}
