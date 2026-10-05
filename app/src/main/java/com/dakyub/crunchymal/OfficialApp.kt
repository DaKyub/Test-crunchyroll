package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Liens profonds de l'app Crunchyroll TV. L'analyse de l'app a montré que MainActivity accepte
 * crunchyroll://<route>, avec la route "showdetails/{showid}?type={type}" ; aucune route de lecteur.
 */
enum class LinkFormat(val label: String) {
    /** Ouvre la fiche de la série (fiable) : l'épisode suivant s'y lance avec « Lecture ». */
    SERIES_PAGE("Fiche de la série (showdetails, type=series)"),

    /** Essai : la même route avec l'identifiant de l'épisode. */
    EPISODE("Épisode direct (showdetails, type=episode)");

    fun uri(seriesId: String?, episodeId: String?): Uri? = when (this) {
        SERIES_PAGE -> seriesId?.takeIf { it.isNotBlank() }?.let { Uri.parse("crunchyroll://showdetails/$it?type=series") }
        EPISODE -> episodeId?.takeIf { it.isNotBlank() }?.let { Uri.parse("crunchyroll://showdetails/$it?type=episode") }
    }
}

/** Délègue la lecture à l'application Crunchyroll officielle. */
object OfficialApp {
    const val PACKAGE = "com.crunchyroll.crunchyroid"

    private fun intent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Activité de l'app officielle qui accepte les liens crunchyroll://, ou null. */
    fun resolve(context: Context): String? {
        val probe = intent(Uri.parse("crunchyroll://showdetails/TEST?type=series"))
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(probe, 0)
        }
        return info?.activityInfo?.name
    }

    /** Liste des activités exportées de l'app officielle (pour le diagnostic). */
    fun exportedActivities(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES)
        }
        pkg.activities.orEmpty().filter { it.exported }.map { it.name.removePrefix("com.crunchyroll.") }
    }.getOrDefault(emptyList())

    /** Ouvre un épisode : selon le réglage, fiche de la série ou essai direct sur l'épisode. */
    fun openEpisode(context: Context, episodeId: String, seriesId: String?) {
        val settings = (context.applicationContext as CrunchyMalApp).graph.settings
        val preferred = settings.linkFormat ?: LinkFormat.SERIES_PAGE
        val uri = preferred.uri(seriesId, episodeId)
            ?: LinkFormat.SERIES_PAGE.uri(seriesId, episodeId)
            ?: LinkFormat.EPISODE.uri(seriesId, episodeId)
        open(context, uri)
    }

    fun openSeries(context: Context, seriesId: String) = open(context, LinkFormat.SERIES_PAGE.uri(seriesId, null))

    private fun open(context: Context, uri: Uri?) {
        if (uri != null && resolve(context) != null) {
            try {
                context.startActivity(intent(uri))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
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
