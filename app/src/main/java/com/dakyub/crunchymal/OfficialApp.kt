package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Façons d'ouvrir un contenu dans l'app Crunchyroll officielle.
 * L'app TV ne documente pas ses liens : l'utilisateur choisit celle qui fonctionne dans les paramètres.
 */
enum class LinkFormat(val label: String, private val template: String, val activity: String? = null) {
    CR_SCHEME("crunchyroll://{kind}/{id}", "crunchyroll://{kind}/{id}"),
    CR_SCHEME_HOST("crunchyroll://www.crunchyroll.com/{kind}/{id}", "crunchyroll://www.crunchyroll.com/{kind}/{id}"),
    HTTPS("https://www.crunchyroll.com/{kind}/{id}", "https://www.crunchyroll.com/{kind}/{id}"),
    HTTPS_FR("https://www.crunchyroll.com/fr/{kind}/{id}", "https://www.crunchyroll.com/fr/{kind}/{id}"),
    PLAYER("Lecteur direct (PlayerActivity)", "crunchyroll://{kind}/{id}", "com.crunchyroll.crunchyroid.player.ui.PlayerActivity"),
    SHOW_DETAILS("Fiche série directe (ShowDetailsActivity)", "crunchyroll://{kind}/{id}", "com.crunchyroll.crunchyroid.showdetails.ui.ShowDetailsActivity");

    fun uri(kind: String, id: String): Uri = Uri.parse(template.replace("{kind}", kind).replace("{id}", id))

    fun intent(kind: String, id: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, uri(kind, id))
            .setPackage(OfficialApp.PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activity != null) {
            intent.setClassName(OfficialApp.PACKAGE, activity)
            // Noms d'extras courants : l'activité lira peut-être l'un d'eux.
            listOf(
                "id", "guid", "mediaId", "media_id", "contentId", "content_id",
                "episodeId", "episode_id", "seriesId", "series_id", "showId", "show_id",
            ).forEach { intent.putExtra(it, id) }
        }
        return intent
    }
}

/** Délègue la lecture à l'application Crunchyroll officielle. */
object OfficialApp {
    const val PACKAGE = "com.crunchyroll.crunchyroid"

    /** Activité de l'app officielle qui accepterait ce format, ou null. */
    fun resolve(context: Context, format: LinkFormat): String? {
        val intent = format.intent("watch", "TEST")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)
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

    fun openEpisode(context: Context, episodeId: String) = open(context, "watch", episodeId)

    fun openSeries(context: Context, seriesId: String) = open(context, "series", seriesId)

    private fun open(context: Context, kind: String, id: String) {
        val settings = (context.applicationContext as CrunchyMalApp).graph.settings
        // Format choisi dans les paramètres en premier, puis les autres formats acceptés.
        // Les lancements explicites ne sont tentés que s'ils sont choisis manuellement.
        val ordered = listOfNotNull(settings.linkFormat) +
            LinkFormat.entries.filter { it != settings.linkFormat && it.activity == null }
        for (format in ordered) {
            if (resolve(context, format) == null) continue
            try {
                context.startActivity(format.intent(kind, id))
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
