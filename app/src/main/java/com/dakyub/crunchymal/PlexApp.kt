package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Lancement d'un épisode dans l'app Plex de la Shield. Le format de lien n'est pas documenté par
 * Plex : plusieurs sont proposés (Réglages → Plex), le premier qui marche sur ta Shield est à garder.
 */
object PlexApp {
    const val PACKAGE = "com.plexapp.android"

    val formats = listOf(
        "plex://play/?metadataKey=%2Flibrary%2Fmetadata%2F{key}&server={server}",
        "plex://server://{server}/com.plexapp.plugins.library/library/metadata/{key}",
        "https://app.plex.tv/desktop#!/server/{server}/details?key=%2Flibrary%2Fmetadata%2F{key}",
    )

    fun open(context: Context, machineId: String, ratingKey: String, format: Int) {
        val uri = formats[format.coerceIn(formats.indices)].replace("{server}", machineId).replace("{key}", ratingKey)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Lien refusé : on ouvre au moins l'app Plex.
            val pm = context.packageManager
            val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "Lien refusé par Plex : essaie un autre format dans Réglages → Plex", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "L'application Plex n'est pas installée", Toast.LENGTH_LONG).show()
            }
        }
    }
}
