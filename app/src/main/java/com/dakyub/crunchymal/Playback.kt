package com.dakyub.crunchymal

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef

/** Lance un épisode dans l'app officielle du service de la série (Crunchyroll ou ADN). */
fun playEpisode(context: Context, series: SeriesRef, episodeId: String) {
    when (series.provider) {
        Provider.CRUNCHYROLL -> OfficialApp.openEpisode(context, episodeId, series.id.ifBlank { null })
        Provider.ADN -> AdnApp.open(context, series.id, episodeId, series.title)
    }
}

/** Intent qui ouvre la fiche d'une série dans l'app officielle (alertes de disponibilité). */
fun seriesIntent(context: Context, provider: Provider, seriesId: String): Intent? {
    val intent = when (provider) {
        Provider.CRUNCHYROLL -> Intent(Intent.ACTION_VIEW, Uri.parse("crunchyroll://series/$seriesId")).setPackage(OfficialApp.PACKAGE)
        // ADN TV 7 n'accepte plus de lien vers un contenu : son accueil.
        Provider.ADN -> return AdnApp.launchIntent(context)
    }
    return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/** Intent qui lance l'épisode dans l'app officielle (chaîne de l'écran d'accueil, notifications). */
fun episodeIntent(context: Context, series: SeriesRef, episodeId: String): Intent? {
    val intent = when (series.provider) {
        Provider.CRUNCHYROLL -> Intent(Intent.ACTION_VIEW, Uri.parse("crunchyroll://episode/$episodeId")).setPackage(OfficialApp.PACKAGE)
        // ADN TV 7 n'accepte plus de lien vers un contenu : son accueil.
        Provider.ADN -> return AdnApp.launchIntent(context)
    }
    // CLEAR_TASK : l'app officielle déjà ouverte traite le lien au lieu de revenir à son dernier écran.
    return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
