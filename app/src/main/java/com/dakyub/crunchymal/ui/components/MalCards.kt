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
import androidx.compose.foundation.lazy.itemsIndexed
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
 * Frise « Toute la licence » d'une fiche : préquelles, saisons, OVA, films et suites de la même histoire
 * d'après MAL, par date de sortie, avec la position de chacun ; ce qui est sur la fiche ([malIds] : fiches MAL
 * de la série et de ses saisons) est marqué « sur cette fiche », le reste indique où le regarder.
 * [currentPageId] : un anime que la recherche rattache à cette même fiche n'est pas proposé comme
 * disponible (Crunchyroll : les saisons de la fiche sont déjà dans [malIds]) ou, avec [hideIfOnThisPage]
 * (ADN, saisons non reliées à MAL), est marqué « sur cette fiche » car c'en est une autre saison.
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
    // Toute la licence (préquelles, saisons, OVA, films, suites…) par date de sortie, affichée au fur et à mesure.
    val franchise by produceState<List<MalAnime>?>(null, malIds) {
        value = runCatching { graph.sequels.chain(malIds) { partial -> value = partial } }.getOrDefault(value.orEmpty())
    }
    val others = franchise?.filter { it.malId !in malIds }
    val found by graph.availability.found.collectAsState()
    val platforms by graph.watchPlatforms.found.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    val crLoggedIn by graph.auth.loggedIn.collectAsState()
    val seen by graph.mal.myListSeenIds.collectAsState()
    LaunchedEffect(others?.map { it.malId }) {
        // Où les regarder : seulement pour ce qui est déjà sorti.
        others?.filter { it.airing != "not_yet_aired" }?.let { graph.prepareMalAnime(it) }
        others?.filter { it.airing == "not_yet_aired" }?.forEach { graph.mal.seed("mal:${it.malId}", it) }
    }
    val list = franchise.orEmpty()
    // Rien d'autre que la fiche elle-même dans la licence : pas de frise.
    if (others.isNullOrEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Toute la licence, par ordre de sortie", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(list, key = { _, anime -> anime.malId }) { index, anime ->
                val position = "${if (index == 0) "1re" else "${index + 1}e"}/${list.size} · "
                val raw = found[anime.malId]
                // Sur cette fiche : une de ses saisons, ou (ADN, saisons non reliées à MAL) une recherche qui y renvoie.
                val onThisPage = anime.malId in malIds || (hideIfOnThisPage && currentPageId != null &&
                    raw?.let { it.adn?.id == currentPageId || it.crunchyroll?.id == currentPageId } == true)
                // Disponibilité qui renvoie vers cette fiche alors que l'anime n'y est pas : ignorée.
                val availability = raw?.let { a ->
                    a.copy(crunchyroll = a.crunchyroll?.takeIf { it.id != currentPageId }, adn = a.adn?.takeIf { it.id != currentPageId })
                }
                val subtitle = position + if (onThisPage) {
                    sequelKind(anime) + (anime.startYear?.let { "$it · " }.orEmpty()) + "sur cette fiche"
                } else {
                    sequelStatus(anime, availability, platforms[anime.malId], raw != null, anime.malId in seen)
                }
                val item = malCardItem(anime, availability, platforms[anime.malId], providers, crLoggedIn, subtitle = subtitle)
                MediaCard(
                    item = item.card,
                    showProviderBadge = false,
                    onClick = { if (!onThisPage) item.target?.let(onOpenSeries) ?: onOpenMal(anime.malId) else onOpenMal(anime.malId) },
                )
            }
        }
    }
}

/** Type d'un anime de la licence (« Saison TV · », « OVA · »…). */
private fun sequelKind(anime: MalAnime): String = when (anime.type?.lowercase()) {
    "movie" -> "Film · "
    "ova" -> "OVA · "
    "ona" -> "ONA · "
    "special", "tv_special" -> "Spécial · "
    "music" -> "Clip · "
    else -> "Saison TV · "
}

/** Statut d'une suite : à venir, dans l'app, sur une autre plateforme, introuvable ou en cours de recherche. */
private fun sequelStatus(
    anime: MalAnime,
    availability: Availability?,
    platforms: WatchPlatforms?,
    availabilityChecked: Boolean,
    inMyList: Boolean,
): String {
    val kind = sequelKind(anime)
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
