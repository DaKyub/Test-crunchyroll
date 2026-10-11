package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CompanionTags
import kotlinx.coroutines.launch

/** Personnes d'une fiche, « Seul » d'abord : « Seul · Julie ». */
fun companionsLabel(people: Set<String>): String =
    people.sortedWith(compareBy({ it != CompanionTags.ALONE }, { it.lowercase() })).joinToString(" · ")

/**
 * Filtre « Vu avec » des listes : séries vues ([with] = true) ou pas vues avec [person] ;
 * [person] vide = séries sans aucune marque.
 */
data class CompanionFilter(val person: String, val with: Boolean) {
    val label: String
        get() = when {
            person.isEmpty() -> "non marquées"
            person == CompanionTags.ALONE -> if (with) "seul" else "pas seul"
            with -> person
            else -> "pas avec $person"
        }

    fun accepts(marks: Set<String>): Boolean =
        if (person.isEmpty()) marks.isEmpty() else marks.any { it.equals(person, ignoreCase = true) } == with
}

/** Valeur suivante du filtre : toutes → seul → pas seul → Julie → pas avec Julie → … → non marquées. */
fun nextCompanionFilter(current: CompanionFilter?, people: List<String>): CompanionFilter? {
    val options = listOf<CompanionFilter?>(null) +
        (listOf(CompanionTags.ALONE) + people).flatMap { listOf(CompanionFilter(it, true), CompanionFilter(it, false)) } +
        CompanionFilter("", false)
    return options[(options.indexOf(current) + 1) % options.size]
}

/** Pastille « 👥 Seul · Julie » posée sur les affiches. */
@Composable
fun CompanionsBadge(people: Set<String>, modifier: Modifier = Modifier) {
    Text(
        "👥 " + companionsLabel(people),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Bouton des fiches : affiche avec qui la série a été vue et ouvre la fenêtre pour le modifier. */
@Composable
fun CompanionsButton(choices: List<MalEntryChoice>) {
    val graph = LocalGraph.current
    val records by graph.mal.records.collectAsState()
    val marks by graph.companions.byMalId.collectAsState()
    var open by remember { mutableStateOf(false) }
    val current = choices.firstNotNullOfOrNull { records[it.malKey]?.malId }?.let { marks[it] }.orEmpty()
    OutlinedButton(onClick = { open = true }) {
        Text(if (current.isEmpty()) "Vu avec…" else "👥 " + companionsLabel(current))
    }
    if (open) CompanionsDialog(choices, onDismiss = { open = false })
}

/**
 * Coche les personnes avec qui la série a été vue (« Seul » compris) et l'enregistre en tags MAL.
 * Une série absente de la liste MAL y est ajoutée (Terminé ou En cours).
 */
@Composable
fun CompanionsDialog(choices: List<MalEntryChoice>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val records by graph.mal.records.collectAsState()
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    val people by graph.companions.people.collectAsState()
    val scope = rememberCoroutineScope()
    val available = choices.filter { records[it.malKey]?.malId != null }.distinctBy { records[it.malKey]?.malId }

    var choiceIndex by remember { mutableIntStateOf(0) }
    var allEntries by remember { mutableStateOf(false) }
    val choice = available.getOrNull(choiceIndex)
    val malId = choice?.let { records[it.malKey]?.malId }
    // null tant que la liste MAL n'a pas été lue.
    var inList by remember(malId) { mutableStateOf<Boolean?>(null) }
    var tags by remember(malId) { mutableStateOf(emptyList<String>()) }
    var selected by remember(malId) { mutableStateOf(emptySet<String>()) }
    var message by remember(malId) { mutableStateOf<String?>(null) }
    var addStatus by remember { mutableStateOf("completed") }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(malId, loggedIn) {
        if (malId == null || !loggedIn) return@LaunchedEffect
        message = "Chargement de ta liste…"
        runCatching { graph.mal.myStatus(malId) }
            .onSuccess { (current, _) ->
                inList = current?.status != null
                tags = current?.tags.orEmpty()
                selected = graph.companions.peopleIn(tags)
                message = null
            }
            .onFailure { message = "Erreur : ${it.message}" }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(720.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Vu avec…", style = MaterialTheme.typography.titleLarge)
            when {
                !loggedIn -> Text("Connecte d'abord ton compte MAL dans Réglages → Compte MyAnimeList.")
                available.isEmpty() -> Text("Aucune fiche MAL associée (corrige la correspondance d'abord).")
                else -> {
                    if (available.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(available.indices.toList()) { index ->
                                FilterChip(selected = index == choiceIndex, onClick = { choiceIndex = index }) {
                                    Text(available[index].label, maxLines = 1)
                                }
                            }
                            item {
                                FilterChip(selected = allEntries, onClick = { allEntries = !allEntries }) {
                                    Text(if (allEntries) "✓ Appliquer aux ${available.size} fiches" else "Appliquer aux ${available.size} fiches", maxLines = 1)
                                }
                            }
                        }
                    }
                    Text(
                        records[choice!!.malKey]?.title.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Vue avec", style = MaterialTheme.typography.labelLarge)
                    val options = (listOf(CompanionTags.ALONE) + people + selected).distinctBy { it.lowercase() }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options) { person ->
                            val checked = selected.any { it.equals(person, true) }
                            FilterChip(selected = checked, onClick = {
                                selected = if (checked) selected.filterNot { it.equals(person, true) }.toSet() else selected + person
                            }) { Text(if (checked) "✓ $person" else person, maxLines = 1) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(
                            newName,
                            { newName = it },
                            "Autre personne (prénom)",
                            modifier = Modifier.width(320.dp),
                            onSubmit = {
                                val name = CompanionTags.clean(newName)
                                if (name.isNotEmpty()) { graph.companions.addPerson(name); selected = selected + name; newName = "" }
                            },
                        )
                        OutlinedButton(onClick = {
                            val name = CompanionTags.clean(newName)
                            if (name.isNotEmpty()) { graph.companions.addPerson(name); selected = selected + name; newName = "" }
                        }) { Text("Ajouter") }
                    }
                    if (inList == false) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Absente de ta liste MAL, ajoutée comme", style = MaterialTheme.typography.labelLarge)
                            listOf("completed" to "Terminé", "watching" to "En cours").forEach { (value, label) ->
                                FilterChip(selected = addStatus == value, onClick = { addStatus = value }) { Text(label) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = inList != null,
                            onClick = click@{
                                val id = malId ?: return@click
                                val chosen = selected
                                val targets = if (allEntries) available.mapNotNull { records[it.malKey]?.malId }.distinct() else listOf(id)
                                message = "Enregistrement…"
                                scope.launch {
                                    val errors = mutableListOf<String>()
                                    targets.forEach { target ->
                                        runCatching {
                                            // Statut et tags actuels de chaque fiche : ses autres tags sont conservés.
                                            val (listed, current) = if (target == id) (inList == true) to tags else {
                                                val status = graph.mal.myStatus(target).first
                                                (status?.status != null) to status?.tags.orEmpty()
                                            }
                                            graph.companions.set(target, chosen, current, statusIfNew = if (listed) null else addStatus)
                                            if (target == id) {
                                                tags = CompanionTags.merge(current, chosen)
                                                inList = true
                                            }
                                        }.onFailure { errors += it.message ?: it.javaClass.simpleName }
                                    }
                                    message = if (errors.isEmpty()) "Enregistré sur MAL ✓" else "Erreur : ${errors.first()}"
                                }
                            },
                        ) { Text("Enregistrer sur MAL") }
                        OutlinedButton(onClick = onDismiss) { Text("Fermer") }
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
