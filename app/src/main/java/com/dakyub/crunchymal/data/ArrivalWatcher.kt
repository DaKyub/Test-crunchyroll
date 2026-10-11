package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.crunchyroll.CrApi
import com.dakyub.crunchymal.data.mal.MalAnime
import com.dakyub.crunchymal.data.mal.MalRepository
import com.dakyub.crunchymal.data.mal.TitleMatcher

/** Série arrivée sur un service et présente dans la liste MAL « À voir ». */
data class Arrival(val provider: Provider, val seriesId: String, val title: String)

/**
 * Alertes de disponibilité : compare les séries récemment ajoutées sur Crunchyroll et le catalogue ADN
 * à ceux du passage précédent, et signale les nouvelles qui correspondent à un anime « À voir » de la
 * liste MAL. Au premier passage, le catalogue est seulement mémorisé (aucune alerte).
 */
class ArrivalWatcher(context: Context, private val api: CrApi, private val adn: AdnApi, private val mal: MalRepository) {
    private val prefs = context.getSharedPreferences("arrivals", Context.MODE_PRIVATE)

    suspend fun check(providers: Set<Provider>): List<Arrival> {
        if (!mal.auth.loggedIn.value) return emptyList()
        val planned = mal.myList().filter { it.status.status == "plan_to_watch" }.map { it.anime }
        val arrivals = mutableListOf<Arrival>()
        if (Provider.CRUNCHYROLL in providers) {
            val series = api.browseWith(mapOf("type" to "series", "sort_by" to "newly_added", "n" to "100"))
                .map { Triple(it.id, it.title, listOf(it.title, it.slugTitle.replace('-', ' '))) }
            arrivals += newMatches(Provider.CRUNCHYROLL, series, planned)
        }
        if (Provider.ADN in providers) {
            val shows = mutableListOf<Triple<String, String, List<String>>>()
            for (page in 0 until MAX_ADN_PAGES) {
                val batch = adn.catalog(order = "alpha", limit = 100, offset = page * 100)
                shows += batch.map { Triple(it.id.toString(), it.title, listOfNotNull(it.title, it.originalTitle, it.shortTitle)) }
                if (batch.size < 100) break
            }
            arrivals += newMatches(Provider.ADN, shows, planned)
        }
        return arrivals
    }

    /** Séries jamais vues lors des passages précédents qui correspondent à un anime « À voir ». */
    private fun newMatches(provider: Provider, series: List<Triple<String, String, List<String>>>, planned: List<MalAnime>): List<Arrival> {
        if (series.isEmpty()) return emptyList()
        val key = "known_${provider.name}"
        val known = prefs.getStringSet(key, null)
        prefs.edit().putStringSet(key, (known.orEmpty() + series.map { it.first }).toSet()).apply()
        if (known == null) return emptyList()
        return series.filter { it.first !in known }.mapNotNull { (id, title, titles) ->
            val match = planned.firstOrNull { anime ->
                val malTitles = anime.allTitles.flatMap { listOf(it, TitleMatcher.withoutSeason(it)) }
                titles.any { t -> malTitles.any { TitleMatcher.similarity(it, t) >= MATCH } }
            }
            match?.let { Arrival(provider, id, title) }
        }
    }

    private companion object {
        const val MATCH = 0.85
        const val MAX_ADN_PAGES = 20
    }
}
