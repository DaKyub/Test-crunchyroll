package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/**
 * Lancement d'un épisode dans l'app Plex de la Shield par un lien plex://. Formats tirés de l'analyse de
 * l'app Plex 2026 (Réglages → Plex → Analyser) : « plex://watch/video?uri= » suivi de l'adresse de l'élément
 * (server://<serveur>/com.plexapp.plugins.library/library/metadata/<id>). Les autres restent en secours.
 */
object PlexApp {
    const val PACKAGE = "com.plexapp.android"

    val formats = listOf(
        "plex://watch/video?uri={uri}",
        "plex://watch/video?uri={rawUri}",
        "plex://watch?uri={uri}",
        "plex://server://{server}/com.plexapp.plugins.library/library/metadata/{key}",
        "plex://play/?metadataKey=%2Flibrary%2Fmetadata%2F{key}&server={server}",
    )

    fun open(context: Context, machineId: String, ratingKey: String, format: Int) {
        val itemUri = "server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey"
        val uri = formats[format.coerceIn(formats.indices)]
            .replace("{uri}", URLEncoder.encode(itemUri, "UTF-8"))
            .replace("{rawUri}", itemUri)
            .replace("{server}", machineId)
            .replace("{key}", ratingKey)
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
