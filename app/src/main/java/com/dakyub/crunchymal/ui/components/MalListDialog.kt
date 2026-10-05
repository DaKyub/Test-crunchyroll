package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.mal.MalStatuses
import kotlinx.coroutines.launch

/** Fiche MAL proposée à la notation : libellé affiché + clé du cache MAL (série ou saison). */
data class MalEntryChoice(val label: String, val malKey: String)

/**
 * Modifie la liste MAL de l'utilisateur : statut, note (1-10) et épisodes vus (remplis
 * automatiquement pour "Terminé").
 */
@Composable
fun MalListDialog(choices: List<MalEntryChoice>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val records by graph.mal.records.collectAsState()
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    val scope = rememberCoroutineScope()
    val available = choices.filter { records[it.malKey]?.malId != null }.distinctBy { records[it.malKey]?.malId }

    var choiceIndex by remember { mutableIntStateOf(0) }
    val choice = available.getOrNull(choiceIndex)
    val malId = choice?.let { records[it.malKey]?.malId }
    var status by remember(malId) { mutableStateOf<String?>(null) }
    var score by remember(malId) { mutableIntStateOf(0) }
    var totalEpisodes by remember(malId) { mutableIntStateOf(0) }
    var watched by remember(malId) { mutableIntStateOf(0) }
    var message by remember(malId) { mutableStateOf<String?>(null) }

    LaunchedEffect(malId, loggedIn) {
        if (malId == null || !loggedIn) return@LaunchedEffect
        message = "Chargement de ta liste…"
        runCatching { graph.mal.myStatus(malId) }
            .onSuccess { (current, total) ->
                status = current?.status
                score = current?.score ?: 0
                watched = current?.episodesWatched ?: 0
                totalEpisodes = total
                message = null
            }
            .onFailure { message = "Erreur : ${it.message}" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(720.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Ma liste MyAnimeList", style = MaterialTheme.typography.titleLarge)
            when {
                !loggedIn -> Text("Connecte d'abord ton compte MAL dans Paramètres → MyAnimeList.")
                available.isEmpty() -> Text("Aucune fiche MAL associée (corrige la correspondance d'abord).")
                else -> {
                    if (available.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            available.forEachIndexed { index, c ->
                                FilterChip(selected = index == choiceIndex, onClick = { choiceIndex = index }) {
                                    Text(c.label)
                                }
                            }
                        }
                    }
                    Text(
                        records[choice!!.malKey]?.title.orEmpty() +
                            (if (totalEpisodes > 0) " · $watched/$totalEpisodes ép. vus" else ""),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Statut", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MalStatuses.forEach { (value, label) ->
                            FilterChip(selected = status == value, onClick = { status = value }) { Text(label) }
                        }
                    }
                    Text("Note", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((0..10).toList()) { value ->
                            FilterChip(selected = score == value, onClick = { score = value }) {
                                Text(if (value == 0) "—" else value.toString())
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            val s = status ?: "completed"
                            val episodes = if (s == "completed" && totalEpisodes > 0) totalEpisodes else null
                            message = "Enregistrement…"
                            scope.launch {
                                runCatching { graph.mal.updateMyStatus(malId!!, s, score, episodes) }
                                    .onSuccess {
                                        status = s
                                        episodes?.let { watched = it }
                                        message = "Enregistré sur MAL ✓"
                                    }
                                    .onFailure { message = "Erreur : ${it.message}" }
                            }
                        }) { Text("Enregistrer sur MAL") }
                        OutlinedButton(onClick = onDismiss) { Text("Fermer") }
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
