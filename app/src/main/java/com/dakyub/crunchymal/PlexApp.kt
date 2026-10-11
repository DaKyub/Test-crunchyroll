package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.dakyub.crunchymal.data.plex.PlexApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lancement d'un épisode dans l'app Plex de la Shield : par le contrôle à distance de Plex (comme un cast
 * depuis le téléphone), sinon par un lien. Le format de lien n'est pas documenté par Plex : plusieurs sont
 * proposés (Réglages → Plex).
 */
object PlexApp {
    const val PACKAGE = "com.plexapp.android"
    private val scope = MainScope()

    /**
     * Met l'app Plex au premier plan, attend que son contrôle à distance réponde, puis lui demande de lire
     * l'épisode à partir de [offsetMs]. Sans contrôle à distance : lien (format choisi dans Réglages).
     */
    fun play(context: Context, plex: PlexApi, ratingKey: String, offsetMs: Long, format: Int) {
        val app = context.applicationContext
        scope.launch {
            if (!launchApp(app)) {
                Toast.makeText(app, "L'application Plex n'est pas installée", Toast.LENGTH_LONG).show()
                return@launch
            }
            // Plex vient peut-être de démarrer : son contrôle à distance met quelques secondes à répondre.
            var player = plex.localPlayer()
            var waited = 0
            while (player == null && waited < 15) {
                delay(1000)
                waited++
                // De temps en temps, recherche complète (découverte réseau, appareils du compte Plex).
                player = if (waited == 2 || waited == 8) plex.discoverPlayer() else plex.localPlayer()
            }
            val target = player ?: run {
                Toast.makeText(
                    app,
                    "Plex ne répond pas au contrôle à distance : dans l'app Plex, active « Autoriser le contrôle à distance » " +
                        "(Advertise as player). Lien de secours utilisé.",
                    Toast.LENGTH_LONG,
                ).show()
                open(app, plex.machineId, ratingKey, format)
                return@launch
            }
            runCatching { plex.playOn(target, ratingKey, offsetMs) }.onFailure {
                Toast.makeText(app, "Plex a refusé la commande de lecture : ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Ouvre l'app Plex (ou la remet au premier plan si elle tourne déjà). */
    private fun launchApp(context: Context): Boolean {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(PACKAGE) ?: pm.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

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
