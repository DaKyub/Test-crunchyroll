package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Délègue la lecture à l'application Crunchyroll officielle. */
object OfficialApp {
    private const val PACKAGE = "com.crunchyroll.crunchyroid"

    fun openEpisode(context: Context, episodeId: String) =
        open(context, Uri.parse("https://www.crunchyroll.com/watch/$episodeId"))

    fun openSeries(context: Context, seriesId: String) =
        open(context, Uri.parse("https://www.crunchyroll.com/series/$seriesId"))

    private fun open(context: Context, uri: Uri) {
        val deepLink = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(deepLink)
            return
        } catch (_: ActivityNotFoundException) {
        }

        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, "Lien direct non pris en charge : Crunchyroll ouvert à l'accueil", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "L'application Crunchyroll n'est pas installée", Toast.LENGTH_LONG).show()
        }
    }
}
