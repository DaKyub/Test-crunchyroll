package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Délègue la lecture à l'application Crunchyroll officielle via ses liens profonds
 * (formats validés sur Shield) : crunchyroll://episode/<id> et crunchyroll://series/<id>.
 */
object OfficialApp {
    const val PACKAGE = "com.crunchyroll.crunchyroid"

    fun openEpisode(context: Context, episodeId: String, seriesId: String?) =
        start(context, "crunchyroll://episode/$episodeId")

    fun openSeries(context: Context, seriesId: String) =
        start(context, "crunchyroll://series/$seriesId")

    private fun start(context: Context, uri: String) {
        // CLEAR_TASK : si Crunchyroll tourne déjà, il redémarre et traite le lien au lieu de revenir à l'écran affiché.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "L'application Crunchyroll n'est pas installée", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "Impossible d'ouvrir Crunchyroll", Toast.LENGTH_LONG).show()
        }
    }
}
