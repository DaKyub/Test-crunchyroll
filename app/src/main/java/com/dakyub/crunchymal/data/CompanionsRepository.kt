package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.data.mal.MalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Marques « vu seul » / « vu avec Prénom », rangées comme tags de la liste MAL. */
object CompanionTags {
    /** Personne « seul » (colonne, puce et filtre). */
    const val ALONE = "Seul"
    private const val TAG_ALONE = "vu-seul"
    private const val TAG_WITH = "vu-avec-"

    fun isOurs(tag: String): Boolean = tag.trim().let { it.equals(TAG_ALONE, true) || it.startsWith(TAG_WITH, true) }

    /** Personnes d'après les tags d'une entrée ([ALONE] pour « vu-seul »), avec la casse du tag. */
    fun people(tags: List<String>): List<String> = tags.mapNotNull { raw ->
        val tag = raw.trim()
        when {
            tag.equals(TAG_ALONE, true) -> ALONE
            tag.startsWith(TAG_WITH, true) -> tag.substring(TAG_WITH.length).trim().ifBlank { null }
            else -> null
        }
    }

    /** Prénom utilisable dans un tag MAL (la virgule sépare les tags). */
    fun clean(name: String): String = name.replace(",", " ").replace(Regex("""\s+"""), " ").trim().take(30)

    private fun tag(person: String): String = if (person.equals(ALONE, true)) TAG_ALONE else TAG_WITH + clean(person)

    /** Tags à enregistrer : les autres tags de l'entrée sont gardés, les marques remplacées par [people]. */
    fun merge(existing: List<String>, people: Collection<String>): List<String> =
        existing.filterNot { isOurs(it) } + people.filter { clean(it).isNotEmpty() }.map { tag(it) }.distinctBy { it.lowercase() }
}

/** Personnes avec qui les séries sont regardées, et marques de chaque fiche MAL. */
class CompanionsRepository(private val settings: Settings, private val mal: MalRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saved = MutableStateFlow(settings.companions)

    /** Prénoms (sans « Seul ») : ceux des réglages, puis ceux trouvés dans les tags MAL. */
    val people: StateFlow<List<String>> = combine(saved, mal.myListTags) { names, tags -> merged(names, tags) }
        .stateIn(scope, SharingStarted.Eagerly, merged(saved.value, mal.myListTags.value))

    /** Personnes avec qui chaque fiche a été vue ([CompanionTags.ALONE] compris), prénoms écrits comme dans les réglages. */
    val byMalId: StateFlow<Map<Int, Set<String>>> = combine(saved, mal.myListTags) { names, tags -> marks(names, tags) }
        .stateIn(scope, SharingStarted.Eagerly, marks(saved.value, mal.myListTags.value))

    private fun display(raw: String, names: List<String>): String =
        if (raw.equals(CompanionTags.ALONE, true)) CompanionTags.ALONE
        else names.firstOrNull { it.equals(raw, true) } ?: raw.replaceFirstChar { it.uppercaseChar() }

    private fun merged(names: List<String>, tags: Map<Int, List<String>>): List<String> {
        val found = tags.values.flatMap { CompanionTags.people(it) }.filter { it != CompanionTags.ALONE }.map { display(it, names) }
        return (names + found).distinctBy { it.lowercase() }
    }

    private fun marks(names: List<String>, tags: Map<Int, List<String>>): Map<Int, Set<String>> =
        tags.mapValues { (_, list) -> CompanionTags.people(list).map { display(it, names) }.toSet() }.filterValues { it.isNotEmpty() }

    /** Personnes d'après les tags d'une entrée, prénoms écrits comme dans les réglages. */
    fun peopleIn(tags: List<String>): Set<String> = CompanionTags.people(tags).map { display(it, saved.value) }.toSet()

    fun setPeople(names: List<String>) {
        val list = names.map { CompanionTags.clean(it) }.filter { it.isNotEmpty() && !it.equals(CompanionTags.ALONE, true) }
            .distinctBy { it.lowercase() }
        settings.companions = list
        saved.value = list
    }

    fun addPerson(name: String) {
        val clean = CompanionTags.clean(name)
        if (clean.isEmpty() || saved.value.any { it.equals(clean, true) }) return
        setPeople(saved.value + clean)
    }

    /**
     * Enregistre sur MAL les personnes avec qui la fiche a été vue. [currentTags] : tags actuels de l'entrée
     * (les autres tags sont conservés). [statusIfNew] : statut si l'anime n'est pas encore dans la liste MAL.
     */
    suspend fun set(malId: Int, people: Collection<String>, currentTags: List<String>, statusIfNew: String? = null) {
        people.filter { !it.equals(CompanionTags.ALONE, true) }.forEach { addPerson(it) }
        mal.setTags(malId, CompanionTags.merge(currentTags, people), statusIfNew)
    }
}
