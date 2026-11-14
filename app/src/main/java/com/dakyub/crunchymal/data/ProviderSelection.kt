package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.data.mal.TitleMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Services actuellement affichés (Crunchyroll, ADN ou les deux), observable par l'interface. */
class ProviderSelection(private val settings: Settings) {
    private val _selected = MutableStateFlow(settings.providers)
    val selected: StateFlow<Set<Provider>> = _selected

    /** Cycle Crunchyroll → ADN → Les deux. */
    fun next() {
        val order = listOf(setOf(Provider.CRUNCHYROLL), setOf(Provider.ADN), setOf(Provider.CRUNCHYROLL, Provider.ADN))
        set(order[(order.indexOf(_selected.value) + 1) % order.size])
    }

    fun set(value: Set<Provider>) {
        if (value.isEmpty()) return
        settings.providers = value
        _selected.value = value
    }

    companion object {
        fun label(selected: Set<Provider>): String = when {
            selected.size > 1 -> "CR + ADN"
            Provider.ADN in selected -> "ADN"
            else -> "Crunchyroll"
        }
    }
}

/**
 * Fusionne des cartes Crunchyroll et ADN : une série présente sur les deux n'apparaît qu'une fois
 * (version Crunchyroll, marquée "aussi sur ADN").
 */
fun mergeProviders(cr: List<CardItem>, adn: List<CardItem>): List<CardItem> {
    val crItems = cr.toMutableList()
    val extra = mutableListOf<CardItem>()
    // Titres préparés une seule fois, et index des titres identiques : la plupart des doublons se trouvent
    // sans comparaison approchée, et celle-ci saute les titres de longueurs trop différentes pour atteindre 0,85.
    val crProfiles = cr.map { c -> c.series.malTitles.map(TitleMatcher::profile) }
    val exact = HashMap<String, Int>()
    crProfiles.forEachIndexed { index, profiles -> profiles.forEach { p -> if (p.key.isNotEmpty()) exact.putIfAbsent(p.key, index) } }
    fun close(x: TitleMatcher.Profile, y: TitleMatcher.Profile): Boolean {
        val (short, long) = if (x.key.length <= y.key.length) x.key.length to y.key.length else y.key.length to x.key.length
        return short >= long * 0.74 && TitleMatcher.similarity(x, y) >= 0.85
    }
    for (a in adn) {
        val adnProfiles = a.series.malTitles.map(TitleMatcher::profile)
        val index = adnProfiles.firstNotNullOfOrNull { exact[it.key] }
            ?: crProfiles.indexOfFirst { profiles -> adnProfiles.any { ap -> profiles.any { cp -> close(ap, cp) } } }
        if (index >= 0) {
            val c = crItems[index]
            crItems[index] = c.copy(series = c.series.copy(alsoOn = c.series.alsoOn + Provider.ADN))
        } else {
            extra += a
        }
    }
    return crItems + extra
}
