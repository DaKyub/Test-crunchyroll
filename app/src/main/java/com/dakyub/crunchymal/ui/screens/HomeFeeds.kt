package com.dakyub.crunchymal.ui.screens

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

/**
 * « Reprendre » ADN : dernière vidéo de chaque série de l'historique, ou la suivante si elle a été finie.
 * [background] : portée où la recherche de l'épisode suivant peut se terminer (et rester en cache)
 * quand la rangée ne l'attend plus.
 */
suspend fun Graph.adnContinueCards(background: CoroutineScope): List<CardItem> {
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
                if (user != null && !video.watched) {
                    adnEpisodeCard(show, video, "Continuer", if (video.duration > 0) user.stoptime.toFloat() / video.duration else null)
                } else {
                    // Épisode suivant : il faut la liste des épisodes, parfois très longue (One Piece) ;
                    // la rangée ne l'attend que 8 s.
                    val pending = background.async {
                        runCatching { adnProgress.tree(show.id.toString()).episodes }.getOrNull()
                    }
                    val episodes = withTimeoutOrNull(8_000) { pending.await() }.orEmpty()
                    val index = episodes.indexOfFirst { it.video.id == video.id }
                    val next = if (index >= 0) episodes.drop(index + 1).firstOrNull { it.available } else null
                    next?.let { adnEpisodeCard(show, it.video, "Suite", null) }
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
