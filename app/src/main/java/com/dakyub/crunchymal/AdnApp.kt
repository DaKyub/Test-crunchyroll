package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.data.adn.AdnVideo

/**
 * Ouverture dans l'app ADN officielle. Ses liens ne sont pas documentés : des formats sont proposés
 * dans les paramètres (banc d'essai), avec un analyseur de l'app pour en découvrir d'autres.
 */
object AdnApp {
    private val KNOWN_PACKAGES = listOf("fr.anidn", "fr.anidn.tv", "com.adn.tv")

    /**
     * L'analyse de l'app ADN TV montre le schéma anidn:// avec seulement « home » et « show/ » :
     * la fiche d'une série s'ouvre donc par défaut avec anidn://show/{show}.
     */
    const val DEFAULT_TEMPLATE = "anidn://show/{show}"

    /** {show}/{video} = identifiants ADN ; {ref} = référence de la série ; {spath}/{vpath} = chemins web. */
    val CANDIDATES = listOf(
        "anidn://show/{show}",
        "anidn://show/{ref}",
        "anidn://show/{show}/{video}",
        "anidn://show/{show}?video={video}",
        "anidn://show/{show}?videoId={video}",
        "anidn://video/{video}",
        "https://animationdigitalnetwork.com{vpath}",
        "adn://video/{show}/{video}",
    )

    /** Paquet de l'app ADN installée (connu ou trouvé par son nom dans le lanceur), ou null. */
    fun packageName(context: Context): String? {
        val pm = context.packageManager
        KNOWN_PACKAGES.firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }?.let { return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(launcher, 0).firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains("ADN", ignoreCase = false) || info.activityInfo.packageName.contains("anidn")
        }?.activityInfo?.packageName
    }

    fun buildIntent(context: Context, template: String, show: AdnShow?, video: AdnVideo?): Intent? {
        val pkg = packageName(context) ?: return null
        val values = mapOf(
            "{show}" to (show?.id ?: video?.show?.id)?.toString(),
            "{video}" to video?.id?.toString(),
            "{ref}" to (show?.reference ?: video?.show?.reference),
            "{spath}" to (show?.urlPath ?: video?.show?.urlPath),
            "{vpath}" to (show?.urlPath ?: video?.show?.urlPath)?.let { base -> video?.id?.let { "$base/$it" } },
        )
        var uri = template
        for ((placeholder, value) in values) {
            if (placeholder in uri) {
                if (value.isNullOrBlank()) return null
                uri = uri.replace(placeholder, value)
            }
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /** Ouvre un épisode (ou la série) avec le format choisi, sinon lance simplement l'app ADN. */
    fun open(context: Context, show: AdnShow?, video: AdnVideo?) {
        val template = (context.applicationContext as CrunchyMalApp).graph.settings.adnLinkTemplate?.takeIf { it in CANDIDATES } ?: DEFAULT_TEMPLATE
        val intent = buildIntent(context, template, show, video) ?: buildIntent(context, DEFAULT_TEMPLATE, show, video)
        start(context, intent)
    }

    fun start(context: Context, intent: Intent?): Boolean {
        if (intent != null) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        val pkg = packageName(context)
        val pm = context.packageManager
        val launch = pkg?.let { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) }
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (intent != null) Toast.makeText(context, "Lien refusé : ADN ouvert à l'accueil", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "L'application ADN n'est pas installée", Toast.LENGTH_LONG).show()
        }
        return false
    }
}
