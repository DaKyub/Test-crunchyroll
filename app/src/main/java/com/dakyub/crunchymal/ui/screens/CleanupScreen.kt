package com.dakyub.crunchymal.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.mal.MalListEntry
import com.dakyub.crunchymal.ui.components.CenteredMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Seuils proposés (mois sans activité sur MAL). */
private val CleanupMonths = listOf(3, 6, 12)

/** Une série « gardée » n'est plus proposée pendant 6 mois. */
private const val KEEP_MS = 180L * 86_400_000

private const val KEEP = "keep"

/** Actions : statut MAL (ou [KEEP]) → libellé. */
private val CleanupActions = listOf("on_hold" to "En pause", "dropped" to "Abandonné", "completed" to "Terminé", KEEP to "Garder")

private data class StaleEntry(val entry: MalListEntry, val updatedAt: Long)

/**
 * Ménage de la liste MAL : séries « En cours » sans activité depuis plusieurs mois, à passer en pause,
 * abandonner, marquer terminées ou garder. Les lignes traitées restent affichées (le focus ne saute pas).
 */
@Composable
fun CleanupScreen() {
    val graph = LocalGraph.current
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    if (!loggedIn) {
        CenteredMessage("Connecte ton compte MyAnimeList dans Réglages pour faire le ménage de ta liste.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var months by rememberSaveable { mutableIntStateOf(CleanupMonths.first()) }
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<MalListEntry>>?>(null, reload) {
        value = runCatching { graph.mal.myListCached(maxAgeMs = if (reload == 0) 2 * 60_000L else 0L) }
    }
    // Choix faits pendant la visite : fiche MAL → action.
    var choices by remember { mutableStateOf(emptyMap<Int, String>()) }
    var busy by remember { mutableStateOf(emptySet<Int>()) }

    val stale = remember(result, months) {
        val now = System.currentTimeMillis()
        val cutoff = now - months * 30L * 86_400_000
        val kept = graph.settings.cleanupKept
        // Statut à jour (une fiche changée depuis la dernière lecture de la liste n'est plus proposée).
        val statuses = graph.mal.myListStatuses.value
        result?.getOrNull().orEmpty()
            .filter { (statuses[it.anime.malId] ?: it.status.status) == "watching" }
            .mapNotNull { e -> DateUtils.parse(e.status.updatedAt)?.time?.takeIf { it < cutoff }?.let { StaleEntry(e, it) } }
            .filter { now - (kept[it.entry.anime.malId] ?: 0) > KEEP_MS }
            .sortedBy { it.updatedAt }
    }

    fun apply(item: StaleEntry, action: String) {
        val anime = item.entry.anime
        if (action == KEEP) {
            graph.settings.cleanupKept = graph.settings.cleanupKept + (anime.malId to System.currentTimeMillis())
            choices = choices + (anime.malId to action)
            return
        }
        busy = busy + anime.malId
        scope.launch {
            val episodes = if (action == "completed") anime.episodes?.takeIf { it > 0 } else null
            runCatching { graph.mal.updateMyStatus(anime.malId, action, item.entry.status.score, episodes) }
                .onSuccess { choices = choices + (anime.malId to action) }
                .onFailure { Toast.makeText(context, "Erreur MAL : ${it.message}", Toast.LENGTH_LONG).show() }
            busy = busy - anime.malId
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ménage de la liste MAL", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Séries « En cours » sans activité sur MAL depuis plus de $months mois. " +
                        "« Garder » les cache pendant 6 mois.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CycleButton("Sans activité depuis", "$months mois") {
                        months = CleanupMonths[(CleanupMonths.indexOf(months) + 1) % CleanupMonths.size]
                    }
                    OutlinedButton(onClick = { reload++ }) { Text("Actualiser", style = MaterialTheme.typography.labelLarge) }
                    if (result?.isSuccess == true) {
                        val left = stale.count { it.entry.anime.malId !in choices }
                        Text("$left à trier", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val error = result?.exceptionOrNull()
        when {
            result == null -> item { Text("Chargement de ta liste MAL…") }
            error != null -> item { Text("Erreur : ${error.message}", color = MaterialTheme.colorScheme.error) }
            stale.isEmpty() -> item { Text("Rien à trier : toutes tes séries « En cours » ont bougé depuis moins de $months mois.") }
            else -> items(stale, key = { it.entry.anime.malId }) { item ->
                CleanupRow(item, choices[item.entry.anime.malId], busy = item.entry.anime.malId in busy) { apply(item, it) }
            }
        }
    }
}

@Composable
private fun CleanupRow(item: StaleEntry, choice: String?, busy: Boolean, onAction: (String) -> Unit) {
    val anime = item.entry.anime
    val date = remember(item.updatedAt) { SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(java.util.Date(item.updatedAt)) }
    val months = ((System.currentTimeMillis() - item.updatedAt) / (30L * 86_400_000)).toInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (choice != null) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        AsyncImage(
            model = anime.pictureUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).height(80.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.titleEnglish ?: anime.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Ép. ${item.entry.status.episodesWatched}/${anime.episodes?.takeIf { it > 0 } ?: "?"} · " +
                    "dernière activité il y a $months mois ($date)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val done = CleanupActions.firstOrNull { it.first == choice }?.second
            if (done != null) {
                Text(
                    if (choice == KEEP) "Gardée en cours ✓" else "Passée « $done » sur MAL ✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Boutons toujours présents (seul leur texte change) : le focus reste sur la ligne.
        CleanupActions.forEach { (action, label) ->
            OutlinedButton(onClick = { if (!busy && choice != action) onAction(action) }) {
                Text(if (choice == action) "✓ $label" else label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
