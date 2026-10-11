package com.dakyub.crunchymal.ui.screens

import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.DateUtils
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.data.adn.AdnVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Listes « Reprendre » et « Nouveaux épisodes », partagées par l'accueil, la chaîne de l'écran
 * d'accueil Android TV et les notifications (tâche en arrière-plan).
 */

/** « Reprendre » Crunchyroll : même source que la rangée de l'app officielle ; repli sur l'historique. */
suspend fun Graph.crContinueCards(): List<CardItem> {
    val resume = runCatching { api.continueWatching() }.getOrDefault(emptyList())
        .filter { it.panel.type == "episode" && !it.fullyWatched }
    return if (resume.isNotEmpty()) {
        resume.distinctBy { it.panel.episodeMetadata?.seriesId ?: it.panel.id }.take(20).map { item ->
            val card = item.panel.toEpisodeCard(item.playhead, item.fullyWatched)
            val prefix = if (item.playhead > 0) "Continuer" else "Suite"
            card.copy(subtitle = "$prefix · ${card.subtitle.orEmpty()}")
        }
    } else {
        api.watchHistory()
            .filter { it.panel.type == "episode" && !it.fullyWatched }
            .distinctBy { it.panel.episodeMetadata?.seriesId ?: it.parentId }
            .take(20)
            .map { it.panel.toEpisodeCard(it.playhead, it.fullyWatched, it.parentId) }
    }
}

/** Carte « Reprendre » et date de son dernier visionnage (ms, 0 = inconnue). */
private typealias Dated = Pair<CardItem, Long>

/** Date inconnue : celle de l'élément précédent du même service (chaque liste va déjà du plus récent au plus ancien). */
private fun List<Dated>.filled(): List<Dated> {
    var last = Long.MAX_VALUE
    return map { (card, date) ->
        val value = if (date > 0) date else last
        last = value
        card to value
    }
}

/**
 * « Reprendre » unique : Crunchyroll, ADN et Plex mêlés, du plus récemment regardé au plus ancien.
 * Lève l'erreur d'un service seulement si aucun n'a rien renvoyé.
 */
suspend fun Graph.resumeCards(background: CoroutineScope, providers: Set<Provider>): List<CardItem> = coroutineScope {
    val crPart = async {
        if (Provider.CRUNCHYROLL !in providers || !auth.loggedIn.value) return@async Result.success(emptyList<Dated>())
        runCatching {
            val cards = crContinueCards()
            // Dates de l'historique Crunchyroll (en cache ; on ne les attend pas plus de 4 s).
            val played = withTimeoutOrNull(4_000) { runCatching { history.lastPlayed() }.getOrNull() }.orEmpty()
            cards.map { it to (DateUtils.parse(played[it.series.id])?.time ?: 0L) }
        }
    }
    val adnPart = async {
        if (Provider.ADN !in providers || !adn.loggedIn.value) Result.success(emptyList()) else runCatching { adnContinueDated(background) }
    }
    val plexPart = async { if (!plex.connected.value) Result.success(emptyList()) else runCatching { plexResumeCards() } }
    val parts = listOf(crPart.await(), adnPart.await(), plexPart.await())
    val merged = parts.flatMap { it.getOrDefault(emptyList()).filled() }.sortedByDescending { it.second }.map { it.first }
    if (merged.isEmpty()) parts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    merged
}

/** « Reprendre » Plex (On Deck) : épisodes en cours ou suivants, ouverts dans l'app Plex. */
suspend fun Graph.plexResumeCards(): List<Pair<CardItem, Long>> = plex.onDeck().map { episode ->
    val showKey = episode.grandparentRatingKey.orEmpty()
    CardItem(
        series = SeriesRef(
            id = "plex-$showKey",
            title = episode.grandparentTitle ?: episode.title,
            wideUrl = plex.image(episode.thumb, 400, 225),
            posterUrl = plex.image(episode.grandparentThumb, 240, 360),
            malKeyOverride = "plex:$showKey",
        ),
        subtitle = (if (episode.viewOffset > 0) "Continuer" else "Suite") + " · ${episode.episodeLabel} · ${episode.title}",
        episodeId = CardItem.PLEX_EPISODE + episode.ratingKey,
        progress = episode.duration.takeIf { it > 0 }?.let { (episode.viewOffset.toFloat() / it).coerceIn(0f, 1f) }?.takeIf { it > 0f },
        wide = true,
        badges = listOf("Plex"),
    ) to episode.lastViewedAt * 1000
}

/**
 * « Reprendre » ADN : dernière vidéo de chaque série de l'historique, ou la suivante si elle a été finie.
 * [background] : portée où la recherche de l'épisode suivant peut se terminer (et rester en cache)
 * quand la rangée ne l'attend plus.
 */
suspend fun Graph.adnContinueCards(background: CoroutineScope): List<CardItem> = adnContinueDated(background).map { it.first }

/** Comme [adnContinueCards], avec la date du dernier visionnage de chaque série. */
private suspend fun Graph.adnContinueDated(background: CoroutineScope): List<Pair<CardItem, Long>> {
    val recent = adn.viewingHistory()
        .filter { it.show != null }
        .sortedByDescending { it.user?.watchDate.orEmpty() }
        .distinctBy { it.show!!.id }
        .take(20)
    return coroutineScope {
        recent.map { video ->
            async {
                val show = video.show!!
                val user = video.user
                val watchedAt = DateUtils.parse(user?.watchDate)?.time ?: 0L
                if (user != null && !video.watched) {
                    adnEpisodeCard(show, video, "Continuer", if (video.duration > 0) user.stoptime.toFloat() / video.duration else null) to watchedAt
                } else {
                    // Épisode suivant : il faut la liste des épisodes, parfois très longue (One Piece) ;
                    // la rangée ne l'attend que 8 s.
                    val pending = background.async {
                        runCatching { adnProgress.tree(show.id.toString()).episodes }.getOrNull()
                    }
                    val episodes = withTimeoutOrNull(8_000) { pending.await() }.orEmpty()
                    val index = episodes.indexOfFirst { it.video.id == video.id }
                    val next = if (index >= 0) episodes.drop(index + 1).firstOrNull { it.available } else null
                    next?.let { adnEpisodeCard(show, it.video, "Suite", null) to watchedAt }
                }
            }
        }.awaitAll().filterNotNull()
    }
}

internal fun adnEpisodeCard(show: AdnShow, video: AdnVideo, prefix: String, progress: Float?) = CardItem(
    series = show.toRef().copy(wideUrl = video.image2x ?: video.image ?: show.imageHorizontal2x),
    subtitle = "$prefix · ${video.label.ifBlank { show.title }}",
    episodeId = video.id.toString(),
    progress = progress?.coerceIn(0f, 1f)?.takeIf { it > 0f },
    wide = true,
)

/**
 * Séries des watchlists ([providers]) où l'utilisateur était à jour et qui ont reçu 1 à 3 épisodes
 * sortis depuis moins de 3 semaines, les plus récents d'abord.
 */
suspend fun Graph.newEpisodeCards(providers: Set<Provider>): List<CardItem> {
    val entries = coroutineScope {
        val cr = async { if (Provider.CRUNCHYROLL in providers) watchlist.get() else emptyList() }
        val adnEntries = async { if (Provider.ADN in providers) runCatching { adnWatchlist.get() }.getOrDefault(emptyList()) else emptyList() }
        cr.await() + adnEntries.await()
    }
    coroutineScope {
        entries.map { e -> async { runCatching { ensureProgress(e.series) } } }.awaitAll()
    }
    val summaries = progress.summaries.value
    val cutoff = DateUtils.isoDaysAgo(21)
    return entries.mapNotNull { e ->
        val s = summaries[e.series.progressKey] ?: return@mapNotNull null
        val remaining = s.remainingAfterLast ?: return@mapNotNull null
        val released = s.nextReleased?.take(19) ?: return@mapNotNull null
        if (s.watched > 0 && remaining in 1..3 && released >= cutoff && s.nextEpisodeId != null) Triple(e, s, released) else null
    }.sortedByDescending { it.third }.map { (e, s, _) ->
        val more = (s.remainingAfterLast ?: 1) - 1
        CardItem(
            series = e.series,
            episodeId = s.nextEpisodeId,
            subtitle = "Nouveau : ${s.nextLabel.orEmpty()}" + (if (more > 0) " (+$more)" else ""),
        )
    }
}
