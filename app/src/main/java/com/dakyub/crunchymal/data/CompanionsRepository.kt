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

/** Famille de marques rangées en tags MAL : « [tagAlone] » et « [tagWith]Prénom ». */
class MarkTags(private val tagAlone: String, private val tagWith: String) {
    fun isOurs(tag: String): Boolean = tag.trim().let { it.equals(tagAlone, true) || it.startsWith(tagWith, true) }

    /** Personnes d'après les tags d'une entrée ([CompanionTags.ALONE] pour « seul »), avec la casse du tag. */
    fun people(tags: List<String>): List<String> = tags.mapNotNull { raw ->
        val tag = raw.trim()
        when {
            tag.equals(tagAlone, true) -> CompanionTags.ALONE
            tag.startsWith(tagWith, true) -> tag.substring(tagWith.length).trim().ifBlank { null }
            else -> null
        }
    }

    private fun tag(person: String): String = if (person.equals(CompanionTags.ALONE, true)) tagAlone else tagWith + CompanionTags.clean(person)

    /** Tags à enregistrer : les autres tags de l'entrée sont gardés, ces marques remplacées par [people]. */
    fun merge(existing: List<String>, people: Collection<String>): List<String> =
        existing.filterNot { isOurs(it) } + people.filter { CompanionTags.clean(it).isNotEmpty() }.map { tag(it) }.distinctBy { it.lowercase() }
}

/** Marques « vu seul » / « vu avec Prénom », rangées comme tags de la liste MAL. */
object CompanionTags {
    /** Personne « seul » (colonne, puce et filtre). */
    const val ALONE = "Seul"
    private val seen = MarkTags("vu-seul", "vu-avec-")

    /** Séries prévues : « a-voir-seul » / « a-voir-avec-Prénom ». */
    val planned = MarkTags("a-voir-seul", "a-voir-avec-")

    fun isOurs(tag: String): Boolean = seen.isOurs(tag)

    /** Personnes d'après les tags d'une entrée ([ALONE] pour « vu-seul »), avec la casse du tag. */
    fun people(tags: List<String>): List<String> = seen.people(tags)

    /** Prénom utilisable dans un tag MAL (la virgule sépare les tags). */
    fun clean(name: String): String = name.replace(",", " ").replace(Regex("""\s+"""), " ").trim().take(30)

    /** Tags à enregistrer : les autres tags de l'entrée sont gardés, les marques remplacées par [people]. */
    fun merge(existing: List<String>, people: Collection<String>): List<String> = seen.merge(existing, people)
}

/** Personnes avec qui les séries sont regardées, et marques de chaque fiche MAL. */
class CompanionsRepository(private val settings: Settings, private val mal: MalRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saved = MutableStateFlow(settings.companions)

    /** Prénoms (sans « Seul ») : ceux des réglages, puis ceux trouvés dans les tags MAL. */
    val people: StateFlow<List<String>> = combine(saved, mal.myListTags) { names, tags -> merged(names, tags) }
        .stateIn(scope, SharingStarted.Eagerly, merged(saved.value, mal.myListTags.value))

    /** Personnes avec qui chaque fiche a été vue ([CompanionTags.ALONE] compris), prénoms écrits comme dans les réglages. */
    val byMalId: StateFlow<Map<Int, Set<String>>> = combine(saved, mal.myListTags) { names, tags -> marks(names, tags, CompanionTags::people) }
        .stateIn(scope, SharingStarted.Eagerly, marks(saved.value, mal.myListTags.value, CompanionTags::people))

    /** Personnes avec qui chaque fiche est prévue (« À voir avec… »). */
    val plannedByMalId: StateFlow<Map<Int, Set<String>>> =
        combine(saved, mal.myListTags) { names, tags -> marks(names, tags, CompanionTags.planned::people) }
            .stateIn(scope, SharingStarted.Eagerly, marks(saved.value, mal.myListTags.value, CompanionTags.planned::people))

    private fun display(raw: String, names: List<String>): String =
        if (raw.equals(CompanionTags.ALONE, true)) CompanionTags.ALONE
        else names.firstOrNull { it.equals(raw, true) } ?: raw.replaceFirstChar { it.uppercaseChar() }

    private fun merged(names: List<String>, tags: Map<Int, List<String>>): List<String> {
        val found = tags.values.flatMap { CompanionTags.people(it) + CompanionTags.planned.people(it) }
            .filter { it != CompanionTags.ALONE }.map { display(it, names) }
        return (names + found).distinctBy { it.lowercase() }
    }

    private fun marks(names: List<String>, tags: Map<Int, List<String>>, read: (List<String>) -> List<String>): Map<Int, Set<String>> =
        tags.mapValues { (_, list) -> read(list).map { display(it, names) }.toSet() }.filterValues { it.isNotEmpty() }

    private val _viewers = MutableStateFlow(settings.viewers.toSet())

    /** « Qui regarde ? » choisi (vide = personne) ; voir [currentViewers] pour la durée de validité. */
    val viewers: StateFlow<Set<String>> = _viewers

    /** Personnes devant l'écran, si elles ont été choisies il y a moins de 6 h (une soirée). */
    fun currentViewers(): Set<String> =
        if (System.currentTimeMillis() - settings.viewersSetAt > VIEWERS_TTL) emptySet() else _viewers.value

    fun setViewers(people: Set<String>) {
        settings.viewers = people.toList()
        settings.viewersSetAt = System.currentTimeMillis()
        _viewers.value = people
    }

    /** Personnes d'après les tags d'une entrée, prénoms écrits comme dans les réglages. */
    fun peopleIn(tags: List<String>): Set<String> = CompanionTags.people(tags).map { display(it, saved.value) }.toSet()

    /** Personnes avec qui l'entrée est prévue (« À voir avec… »). */
    fun plannedIn(tags: List<String>): Set<String> = CompanionTags.planned.people(tags).map { display(it, saved.value) }.toSet()

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
     * [planned] non nul : remplace aussi les marques « À voir avec… ».
     */
    suspend fun set(
        malId: Int,
        people: Collection<String>,
        currentTags: List<String>,
        statusIfNew: String? = null,
        planned: Collection<String>? = null,
    ) {
        (people + planned.orEmpty()).filter { !it.equals(CompanionTags.ALONE, true) }.forEach { addPerson(it) }
        mal.setTags(malId, tagsFor(currentTags, people, planned), statusIfNew)
    }

    /** Tags de l'entrée après enregistrement des marques. */
    fun tagsFor(currentTags: List<String>, people: Collection<String>, planned: Collection<String>?): List<String> =
        CompanionTags.merge(currentTags, people).let { if (planned == null) it else CompanionTags.planned.merge(it, planned) }

    private companion object {
        const val VIEWERS_TTL = 6L * 3600 * 1000
    }
}
