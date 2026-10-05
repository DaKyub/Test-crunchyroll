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
    for (a in adn) {
        val index = crItems.indexOfFirst { c ->
            a.series.malTitles.any { at -> c.series.malTitles.any { ct -> TitleMatcher.similarity(at, ct) >= 0.85 } }
        }
        if (index >= 0) {
            val c = crItems[index]
            crItems[index] = c.copy(series = c.series.copy(alsoOn = c.series.alsoOn + Provider.ADN))
        } else {
            extra += a
        }
    }
    return crItems + extra
}
