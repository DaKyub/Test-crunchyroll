package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.CompanionTags
import com.dakyub.crunchymal.data.mal.MalListEntry
import com.dakyub.crunchymal.ui.components.CenteredMessage
import com.dakyub.crunchymal.ui.components.malGenreLabel
import java.util.Locale

/** Ligne d'un tableau à barres : libellé, valeur (longueur de la barre) et précision éventuelle. */
private data class StatBar(val label: String, val value: Int, val note: String? = null)

private data class MalStats(
    val completed: Int,
    val watching: Int,
    val planned: Int,
    val episodes: Int,
    val minutes: Long,
    val meanScore: Double?,
    val malMean: Double?,
    val byYear: List<StatBar>,
    val genres: List<StatBar>,
    val people: List<StatBar>,
    val scores: List<StatBar>,
    val types: List<StatBar>,
)

private val SeenStatuses = setOf("completed", "watching", "on_hold", "dropped")

private fun Double.fr(): String = String.format(Locale.FRANCE, "%.1f", this)
private fun Int.fr(): String = String.format(Locale.FRANCE, "%,d", this)

private fun computeStats(entries: List<MalListEntry>, peopleIn: (List<String>) -> Set<String>): MalStats {
    val seen = entries.filter { it.status.status in SeenStatuses }
    val scored = entries.filter { it.status.score > 0 }
    // Année de fin indiquée sur MAL, sinon celle de la dernière mise à jour de l'entrée.
    val byYear = entries.filter { it.status.status == "completed" }
        .mapNotNull { e -> (e.status.finishDate ?: e.status.updatedAt)?.take(4)?.toIntOrNull() }
        .groupingBy { it }.eachCount().entries
        .sortedByDescending { it.key }.take(12)
        .map { StatBar(it.key.toString(), it.value) }
    val genres = seen.flatMap { e -> e.anime.genres.map { it to e } }.groupBy({ it.first }, { it.second })
        .entries.sortedByDescending { it.value.size }.take(10)
        .map { (genre, list) ->
            val mean = list.map { it.status.score }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
            StatBar(malGenreLabel(genre), list.size, mean?.let { "ta note moyenne ${it.fr()}" })
        }
    val marks = seen.map { peopleIn(it.status.tags) }
    val people = marks.flatten().groupingBy { it }.eachCount().entries
        .sortedWith(compareBy({ it.key != CompanionTags.ALONE }, { -it.value }))
        .map { StatBar(it.key, it.value) } +
        listOfNotNull(marks.count { it.isEmpty() }.takeIf { it > 0 && marks.any { m -> m.isNotEmpty() } }?.let { StatBar("Non marquées", it) })
    val scores = (10 downTo 1).map { score -> StatBar(score.toString(), scored.count { it.status.score == score }) }
    val typeLabels = mapOf("tv" to "Séries TV", "movie" to "Films", "ova" to "OVA", "ona" to "ONA", "special" to "Spéciaux", "tv_special" to "Spéciaux TV", "music" to "Clips")
    val types = seen.groupingBy { it.anime.type?.lowercase().orEmpty() }.eachCount().entries
        .sortedByDescending { it.value }
        .map { StatBar(typeLabels[it.key] ?: it.key.ifBlank { "Autre" }.uppercase(), it.value) }
    return MalStats(
        completed = entries.count { it.status.status == "completed" },
        watching = entries.count { it.status.status == "watching" },
        planned = entries.count { it.status.status == "plan_to_watch" },
        episodes = seen.sumOf { it.status.episodesWatched },
        minutes = seen.sumOf { it.status.episodesWatched.toLong() * (it.anime.episodeMinutes ?: 24) },
        meanScore = scored.takeIf { it.isNotEmpty() }?.map { it.status.score }?.average(),
        malMean = scored.mapNotNull { it.anime.score }.takeIf { it.isNotEmpty() }?.average(),
        byYear = byYear,
        genres = genres,
        people = people,
        scores = scores,
        types = types,
    )
}

/** Couleur des barres : bleu de la palette de référence (pas de survol sur TV : chaque ligne porte sa valeur). */
private val StatBlue = Color(0xFF3987E5)
private val MaxBarWidth = 420.dp

/** Bilan de la liste MAL : chiffres clés, séries terminées par année, genres, personnes, notes, formats. */
@Composable
fun StatsScreen() {
    val graph = LocalGraph.current
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    if (!loggedIn) {
        CenteredMessage("Connecte ton compte MyAnimeList dans Réglages pour voir tes statistiques.")
        return
    }
    val result by produceState<Result<MalStats>?>(null) {
        value = runCatching { computeStats(graph.mal.myListCached(maxAgeMs = 10 * 60_000), graph.companions::peopleIn) }
    }
    val stats = result?.getOrNull()
    when {
        result == null -> CenteredMessage("Calcul de tes statistiques…")
        stats == null -> CenteredMessage("Erreur : ${result?.exceptionOrNull()?.message}")
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Statistiques", style = MaterialTheme.typography.headlineMedium)
                    Text("D'après ta liste MyAnimeList", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                StatSection(title = "En bref") {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        StatTile("Séries terminées", stats.completed.fr())
                        StatTile("En cours", stats.watching.fr())
                        StatTile("À voir", stats.planned.fr())
                        StatTile("Épisodes vus", stats.episodes.fr())
                        val hours = stats.minutes / 60
                        StatTile("Temps de visionnage", "${hours.toInt().fr()} h", "soit environ ${(hours / 24).toInt().fr()} jours")
                        stats.meanScore?.let { mean ->
                            StatTile("Ta note moyenne", mean.fr(), stats.malMean?.let { "moyenne MAL de ces séries ${it.fr()}" })
                        }
                    }
                }
            }
            if (stats.byYear.isNotEmpty()) {
                item {
                    StatSection("Séries terminées par année", "Année de fin indiquée sur MAL, sinon dernière mise à jour de l'entrée") {
                        BarTable(stats.byYear)
                    }
                }
            }
            if (stats.genres.isNotEmpty()) {
                item { StatSection("Genres les plus vus", "Séries vues ou commencées, par genre MAL") { BarTable(stats.genres) } }
            }
            if (stats.people.isNotEmpty()) {
                item { StatSection("Vu avec", "Séries vues ou commencées, d'après tes marques « vu seul » / « vu avec »") { BarTable(stats.people) } }
            }
            if (stats.scores.any { it.value > 0 }) {
                item { StatSection("Tes notes", "Nombre de séries par note que tu as donnée") { BarTable(stats.scores) } }
            }
            if (stats.types.isNotEmpty()) {
                item { StatSection("Formats", "Séries vues ou commencées") { BarTable(stats.types) } }
            }
        }
    }
}

/** Section focalisable (sinon la télécommande ne peut pas faire défiler une page sans boutons). */
@Composable
private fun StatSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .focusable(interactionSource = interaction)
            .padding(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

/** Chiffre clé : libellé, valeur, précision facultative. */
@Composable
private fun StatTile(label: String, value: String, detail: String? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Tableau à barres horizontales : libellé, barre fine (une seule couleur, bout arrondi, base droite) et
 * valeur au bout de la barre. Sur TV, pas de survol : chaque ligne porte donc sa valeur.
 */
@Composable
private fun BarTable(rows: List<StatBar>) {
    val max = rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(220.dp),
                )
                if (row.value > 0) {
                    Box(
                        Modifier
                            .width(MaxBarWidth * (row.value.toFloat() / max))
                            .height(14.dp)
                            .background(StatBlue, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(row.value.fr(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                row.note?.let {
                    Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
