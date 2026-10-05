package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Formats de liens candidats pour ouvrir un contenu dans l'app Crunchyroll officielle.
 * L'app TV ne documente pas ses liens : l'utilisateur choisit celui qui fonctionne dans les paramètres.
 */
enum class LinkFormat(val label: String, private val template: String) {
    CR_SCHEME("crunchyroll://{kind}/{id}", "crunchyroll://{kind}/{id}"),
    CR_SCHEME_HOST("crunchyroll://www.crunchyroll.com/{kind}/{id}", "crunchyroll://www.crunchyroll.com/{kind}/{id}"),
    HTTPS("https://www.crunchyroll.com/{kind}/{id}", "https://www.crunchyroll.com/{kind}/{id}"),
    HTTPS_FR("https://www.crunchyroll.com/fr/{kind}/{id}", "https://www.crunchyroll.com/fr/{kind}/{id}");

    fun uri(kind: String, id: String): Uri = Uri.parse(template.replace("{kind}", kind).replace("{id}", id))
}

/** Délègue la lecture à l'application Crunchyroll officielle. */
object OfficialApp {
    const val PACKAGE = "com.crunchyroll.crunchyroid"

    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Activité de l'app officielle qui accepterait ce format, ou null. */
    fun resolve(context: Context, format: LinkFormat): String? {
        val intent = viewIntent(format.uri("watch", "TEST"))
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
        val ordered = listOfNotNull(settings.linkFormat) + LinkFormat.entries.filter { it != settings.linkFormat }
        for (format in ordered) {
            if (resolve(context, format) == null) continue
            try {
                context.startActivity(viewIntent(format.uri(kind, id)))
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
