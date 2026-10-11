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
        Provider.ADN -> AdnApp.open(context, series.id, episodeId)
    }
}

/** Intent qui lance l'épisode dans l'app officielle (chaîne de l'écran d'accueil, notifications). */
fun episodeIntent(context: Context, series: SeriesRef, episodeId: String): Intent? {
    val intent = when (series.provider) {
        Provider.CRUNCHYROLL -> Intent(Intent.ACTION_VIEW, Uri.parse("crunchyroll://episode/$episodeId")).setPackage(OfficialApp.PACKAGE)
        Provider.ADN -> AdnApp.packageName(context)?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse("anidn://video/$episodeId")).setPackage(it)
        }
    }
    // CLEAR_TASK : l'app officielle déjà ouverte traite le lien au lieu de revenir à son dernier écran.
    return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
