package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** Délègue la lecture à l'application Crunchyroll officielle. */
object OfficialApp {
    const val PACKAGE = "com.crunchyroll.crunchyroid"

    const val DEFAULT_TEMPLATE = "crunchyroll://showdetails/{series}?type=series"

    /**
     * Formats de liens à essayer depuis les paramètres. {series} / {episode} sont remplacés par les
     * identifiants ; un préfixe "Activité|" force l'ouverture de cette activité précise.
     */
    val CANDIDATES = listOf(
        "crunchyroll://showdetails/{series}?type=series",
        "crunchyroll://showdetails/{series}?type=SERIES",
        "crunchyroll://showdetails/{series}",
        "crunchyroll://showdetails/{episode}?type=episode",
        "crunchyroll://series/{series}",
        "crunchyroll://watch/{episode}",
        "crunchyroll://episode/{episode}",
        "crunchyroll://media/{episode}",
        "crunchyroll://play/{episode}",
        "crunchyroll://player/{episode}",
        "crunchyroll://www.crunchyroll.com/series/{series}",
        "crunchyroll://www.crunchyroll.com/watch/{episode}",
        "crunchyroll://deeplink?url=https%3A%2F%2Fwww.crunchyroll.com%2Fseries%2F{series}",
        "ShowDetailsActivity|crunchyroll://showdetails/{series}?type=series",
        "PlayerActivity|crunchyroll://watch/{episode}",
    )

    private val activities = mapOf(
        "ShowDetailsActivity" to "com.crunchyroll.crunchyroid.showdetails.ui.ShowDetailsActivity",
        "PlayerActivity" to "com.crunchyroll.crunchyroid.player.ui.PlayerActivity",
    )

    /** Intent pour un format donné, ou null s'il manque l'identifiant requis. */
    fun buildIntent(template: String, seriesId: String?, episodeId: String?): Intent? {
        if ("{series}" in template && seriesId.isNullOrBlank()) return null
        if ("{episode}" in template && episodeId.isNullOrBlank()) return null
        val activity = template.substringBefore('|', "").takeIf { it.isNotBlank() }
        val uri = template.substringAfter('|')
            .replace("{series}", seriesId.orEmpty())
            .replace("{episode}", episodeId.orEmpty())
        // CLEAR_TASK : si Crunchyroll tourne déjà, il redémarre et traite le lien au lieu de juste revenir au premier plan.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity?.let { name -> activities[name]?.let { intent.setClassName(PACKAGE, it) } }
        return intent
    }

    /** Activité de l'app officielle qui accepte les liens crunchyroll://, ou null. */
    fun resolve(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("crunchyroll://showdetails/TEST?type=series")).setPackage(PACKAGE)
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

    private fun template(context: Context) =
        (context.applicationContext as CrunchyMalApp).graph.settings.linkTemplate ?: DEFAULT_TEMPLATE

    fun openEpisode(context: Context, episodeId: String, seriesId: String?) {
        val intent = buildIntent(template(context), seriesId, episodeId)
            ?: buildIntent(DEFAULT_TEMPLATE, seriesId, episodeId)
        start(context, intent)
    }

    fun openSeries(context: Context, seriesId: String) {
        val chosen = template(context).takeIf { "{episode}" !in it } ?: DEFAULT_TEMPLATE
        start(context, buildIntent(chosen, seriesId, null))
    }

    /** Lance l'intent ; renvoie false (et ouvre l'accueil de Crunchyroll) si impossible. */
    fun start(context: Context, intent: Intent?): Boolean {
        if (intent != null) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, "Lien refusé : Crunchyroll ouvert à l'accueil", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "L'application Crunchyroll n'est pas installée", Toast.LENGTH_LONG).show()
        }
        return false
    }
}
