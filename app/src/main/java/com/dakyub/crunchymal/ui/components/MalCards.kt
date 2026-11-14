package com.dakyub.crunchymal.ui.components

import com.dakyub.crunchymal.ui.screens.CycleButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.Availability
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.data.WatchPlatforms
import com.dakyub.crunchymal.data.mal.MalAnime

/** Filtre « Plateforme » des listes d'animes MAL (null = toutes). */
val PlatformFilterOptions = listOf<String?>(null, "Crunchyroll", "ADN", "Netflix", "Prime Video", "Disney+")

fun nextPlatformFilter(current: String?): String? =
    PlatformFilterOptions[(PlatformFilterOptions.indexOf(current) + 1) % PlatformFilterOptions.size]

/** Carte d'un anime MAL, avec la fiche Crunchyroll / ADN à ouvrir si l'app l'a trouvée. */
data class MalCardItem(val malId: Int, val card: CardItem, val target: SeriesRef?)

/** Badges : Crunchyroll / ADN trouvés par l'app, puis les autres plateformes (TMDB France ou MAL). */
fun malCardItem(
    anime: MalAnime,
    availability: Availability?,
    platforms: WatchPlatforms?,
    providers: Set<Provider>,
    crunchyrollUsable: Boolean,
    subtitle: String?,
): MalCardItem {
    val badges = listOfNotNull(
        "Crunchyroll".takeIf { availability?.crunchyroll != null },
        "ADN".takeIf { availability?.adn != null },
    ) + platforms?.names.orEmpty().filter { it != "Crunchyroll" && it != "ADN" }
    return MalCardItem(
        malId = anime.malId,
        card = CardItem(
            series = SeriesRef(
                id = "mal-${anime.malId}",
                title = anime.titleEnglish ?: anime.title,
                posterUrl = anime.pictureUrl,
                malKeyOverride = "mal:${anime.malId}",
            ),
            subtitle = subtitle,
            badges = badges,
        ),
        target = availability?.refFor(providers, crunchyrollUsable),
    )
}

/** Lance la recherche des plateformes d'animes MAL et enregistre leur note (badge MAL immédiat). */
suspend fun Graph.prepareMalAnime(list: List<MalAnime>) {
    val crEnabled = Provider.CRUNCHYROLL in providers.selected.value && auth.loggedIn.value
    list.forEach { anime ->
        mal.seed("mal:${anime.malId}", anime)
        availability.request(anime, crEnabled)
        watchPlatforms.request(anime)
    }
}

/**
 * Rangée « Si tu as aimé » : recommandations des membres MAL pour [malId], celles que tu n'as pas encore
 * vues (d'après ta liste MAL) en premier, avec où les regarder.
 */
@Composable
fun MalRecommendationsRow(malId: Int?, onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    if (malId == null) return
    val graph = LocalGraph.current
    val recommendations by produceState<List<MalAnime>?>(null, malId) {
        value = runCatching { graph.mal.recommendations(malId) }.getOrDefault(emptyList())
    }
    val found by graph.availability.found.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val seen by graph.mal.myListSeenIds.collectAsState()
    var platform by remember { mutableStateOf<String?>(null) }
    val completion by graph.malCompletion.state.collectAsState()
    LaunchedEffect(recommendations) {
        recommendations?.let { graph.prepareMalAnime(it) }
        graph.mal.refreshMyListSeenIds()
    }
    val list = recommendations
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Si tu as aimé cette série (recommandations MAL)", style = MaterialTheme.typography.titleMedium)
            if (!list.isNullOrEmpty()) CycleButton("Plateforme", platform ?: "toutes") { platform = nextPlatformFilter(platform) }
        }
        when {
            list == null -> Text("Chargement…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("Aucune recommandation sur MAL.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val items = list
                    .filter { !completion.hides(it.malId) }
                    .sortedBy { it.malId in seen }
                    .map { anime ->
                        malCardItem(
                            anime,
                            found[anime.malId],
                            platforms[anime.malId],
                            providers,
                            crLoggedIn,
                            subtitle = if (anime.malId in seen) "✓ dans ta liste MAL" else airingLabel(anime.airing),
                        )
                    }
                    .filter { platform == null || platform in it.card.badges }
                if (items.isEmpty()) {
                    Text("Aucune recommandation trouvée sur $platform.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(items, key = { it.malId }) { item ->
                        MediaCard(
                            item = item.card,
                            showProviderBadge = false,
                            onClick = { item.target?.let(onOpenSeries) ?: onOpenMal(item.malId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rangée « Suites » d'une fiche : saisons, films… qui suivent la série d'après MAL et ne sont pas sur la
 * fiche ([malIds] : fiches MAL de la série et de ses saisons), chacune avec où la regarder ou sa sortie.
 * [currentPageId] : une suite que la recherche rattache à cette même fiche n'est pas proposée comme
 * disponible (Crunchyroll : les saisons de la fiche sont déjà dans [malIds]) ou, avec [hideIfOnThisPage]
 * (ADN, saisons non reliées à MAL), est masquée car c'est une autre saison de cette fiche.
 */
@Composable
fun SequelsRow(
    malIds: Set<Int>,
    currentPageId: String?,
    onOpenSeries: (SeriesRef) -> Unit,
    onOpenMal: (Int) -> Unit,
    hideIfOnThisPage: Boolean = false,
) {
    if (malIds.isEmpty()) return
    val graph = LocalGraph.current
    // Toute la licence connue (fiche + suites), dans l'ordre chronologique : sert à situer chaque suite.
    val franchise by produceState<List<MalAnime>?>(null, malIds) {
        value = runCatching { graph.sequels.chain(malIds) }.getOrDefault(emptyList())
    }
    val sequels = franchise?.filter { it.malId !in malIds }
    val found by graph.availability.found.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val seen by graph.mal.myListSeenIds.collectAsState()
    LaunchedEffect(sequels) {
        // Où les regarder : seulement pour celles déjà sorties.
        sequels?.filter { it.airing != "not_yet_aired" }?.let { graph.prepareMalAnime(it) }
        sequels?.filter { it.airing == "not_yet_aired" }?.forEach { graph.mal.seed("mal:${it.malId}", it) }
    }
    val list = sequels.orEmpty().filter { anime ->
        !hideIfOnThisPage || currentPageId == null || found[anime.malId]?.let { it.adn?.id == currentPageId || it.crunchyroll?.id == currentPageId } != true
    }.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suites (ordre de sortie, position dans la licence)", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.malId }) { anime ->
                // Disponibilité qui renvoie vers cette fiche : la suite n'y est pas, on l'ignore.
                val availability = found[anime.malId]?.let { a ->
                    a.copy(
                        crunchyroll = a.crunchyroll?.takeIf { it.id != currentPageId },
                        adn = a.adn?.takeIf { it.id != currentPageId },
                    )
                }
                // Position dans la licence (1re, 2e…), fiche et suites comprises, par date de sortie.
                val position = franchise.orEmpty().indexOfFirst { it.malId == anime.malId } + 1
                val total = franchise.orEmpty().size
                val item = malCardItem(
                    anime, availability, platforms[anime.malId], providers, crLoggedIn,
                    subtitle = (if (position > 0) "${if (position == 1) "1re" else "${position}e"}/$total · " else "") +
                        sequelStatus(anime, availability, platforms[anime.malId], found.containsKey(anime.malId), anime.malId in seen),
                )
                MediaCard(
                    item = item.card,
                    showProviderBadge = false,
                    onClick = { item.target?.let(onOpenSeries) ?: onOpenMal(anime.malId) },
                )
            }
        }
    }
}

/** Statut d'une suite : à venir, dans l'app, sur une autre plateforme, introuvable ou en cours de recherche. */
private fun sequelStatus(
    anime: MalAnime,
    availability: Availability?,
    platforms: WatchPlatforms?,
    availabilityChecked: Boolean,
    inMyList: Boolean,
): String {
    val kind = when (anime.type?.lowercase()) {
        "movie" -> "Film · "
        "ova" -> "OVA · "
        "ona" -> "ONA · "
        "special", "tv_special" -> "Spécial · "
        else -> "Saison TV · "
    }
    val year = anime.startYear?.takeIf { anime.airing != "not_yet_aired" }?.let { "$it · " }.orEmpty()
    val status = year + when {
        anime.airing == "not_yet_aired" -> "Pas encore sortie" + (anime.startYear?.let { " · prévue $it" } ?: "")
        availability?.crunchyroll != null && availability.adn != null -> "Sur Crunchyroll et ADN"
        availability?.crunchyroll != null -> "Sur Crunchyroll"
        availability?.adn != null -> "Sur ADN"
        platforms != null && platforms.names.isNotEmpty() ->
            (if (platforms.source == "TMDB") "Sur " else "Hors de France : ") + platforms.names.joinToString(", ")
        availabilityChecked && platforms != null -> "Introuvable en France"
        else -> "Recherche des plateformes…"
    }
    return kind + status + if (inMyList) " · ✓ dans ta liste MAL" else ""
}

/** Genres MAL en français (les autres restent en anglais). */
private val MalGenreLabels = mapOf(
    "Adventure" to "Aventure", "Comedy" to "Comédie", "Drama" to "Drame", "Sci-Fi" to "Science-fiction",
    "Slice of Life" to "Tranche de vie", "Sports" to "Sport", "Supernatural" to "Surnaturel", "Mystery" to "Mystère",
    "Horror" to "Horreur", "Award Winning" to "Primé", "Gourmet" to "Gastronomie", "Avant Garde" to "Avant-garde",
    "Music" to "Musique", "School" to "École", "Military" to "Militaire", "Historical" to "Historique",
    "Psychological" to "Psychologique", "Martial Arts" to "Arts martiaux", "Super Power" to "Super-pouvoirs",
    "Space" to "Espace", "Detective" to "Détective", "Time Travel" to "Voyage dans le temps", "Mythology" to "Mythologie",
    "Parody" to "Parodie", "Workplace" to "Travail", "Adult Cast" to "Personnages adultes", "Video Game" to "Jeu vidéo",
    "Strategy Game" to "Jeu de stratégie", "Team Sports" to "Sport d'équipe", "Combat Sports" to "Sport de combat",
    "Racing" to "Course", "Reincarnation" to "Réincarnation", "Love Polygon" to "Triangle amoureux", "Survival" to "Survie",
    "Organized Crime" to "Crime organisé", "Delinquents" to "Délinquants", "Childcare" to "Garde d'enfants",
    "Performing Arts" to "Arts du spectacle", "Visual Arts" to "Arts visuels", "Educational" to "Éducatif",
    "Medical" to "Médical", "Pets" to "Animaux", "Gag Humor" to "Humour absurde", "High Stakes Game" to "Jeux à enjeux",
    "Anthropomorphic" to "Anthropomorphe", "Kids" to "Enfants", "Romantic Subtext" to "Sous-texte romantique",
)

fun malGenreLabel(genre: String): String = MalGenreLabels[genre] ?: genre
