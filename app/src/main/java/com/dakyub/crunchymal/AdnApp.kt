package com.dakyub.crunchymal

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.pm.PackageManager
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
     * Formats de liens possibles, essayés dans l'ordre : on demande à Android lequel l'app ADN installée sait
     * ouvrir (ses intent-filter), au lieu d'en supposer un seul. anidn://video/<id> marchait jusqu'à une mise
     * à jour d'ADN qui l'a retiré (« Lien refusé »). Le format qui marche est retenu pour les fois suivantes.
     */
    private val EPISODE_TEMPLATES = listOf(
        "anidn://video/{video}",
        "anidn://videos/{video}",
        "anidn://watch/{video}",
        "anidn://player/{video}",
        "anidn://play/{video}",
        "https://animationdigitalnetwork.com/video/{spath}/{video}",
        "https://animationdigitalnetwork.fr/video/{spath}/{video}",
        "https://animationdigitalnetwork.com/video/{video}",
        "https://animationdigitalnetwork.fr/video/{video}",
    )
    private val SHOW_TEMPLATES = listOf(
        "anidn://show/{show}",
        "anidn://shows/{show}",
        "https://animationdigitalnetwork.com/video/{spath}",
        "https://animationdigitalnetwork.fr/video/{spath}",
        "https://animationdigitalnetwork.com/show/{show}",
    )

    /** Premier format (dans [templates]) que l'app ADN accepte pour ce contenu, avec le dernier qui a marché en tête. */
    private fun firstAccepted(context: Context, templates: List<String>, show: AdnShow?, video: AdnVideo?, remembered: String): Intent? {
        val prefs = context.getSharedPreferences("adn_links", Context.MODE_PRIVATE)
        val last = prefs.getString(remembered, null)
        val ordered = listOfNotNull(last) + templates.filter { it != last }
        val pm = context.packageManager
        for (template in ordered) {
            val intent = buildIntent(context, template, show, video) ?: continue
            @Suppress("DEPRECATION")
            if (pm.resolveActivity(intent, 0) != null) {
                prefs.edit().putString(remembered, template).apply()
                return intent
            }
        }
        return null
    }

    /**
     * Banc d'essai : l'app ADN TV 7 ne déclare plus aucun lien. On lance donc directement son écran principal
     * (composant explicite, qui passe outre l'absence de liens déclarés) en lui donnant l'épisode de diverses
     * façons ; l'utilisateur dit laquelle lance l'épisode, et elle est retenue ([saveVariant]).
     */
    class Variant(val label: String, val build: (Intent, AdnShow?, AdnVideo) -> Intent)

    private fun webUrl(host: String, show: AdnShow?, video: AdnVideo): String {
        val path = (show ?: video.show)?.urlPath?.takeIf { it.isNotBlank() }
        return if (path != null) "https://$host/video/$path/${video.id}" else "https://$host/video/${video.id}"
    }

    val VARIANTS = listOf(
        Variant("Écran principal + adresse androidtv.animationdigitalnetwork.com") { base, show, video ->
            base.setAction(Intent.ACTION_VIEW).setData(Uri.parse(webUrl("androidtv.animationdigitalnetwork.com", show, video)))
        },
        Variant("Écran principal + adresse animationdigitalnetwork.com") { base, show, video ->
            base.setAction(Intent.ACTION_VIEW).setData(Uri.parse(webUrl("animationdigitalnetwork.com", show, video)))
        },
        Variant("Écran principal + anidn://video/<id>") { base, _, video ->
            base.setAction(Intent.ACTION_VIEW).setData(Uri.parse("anidn://video/${video.id}"))
        },
        Variant("Écran principal + identifiants en extras (videoId, showId…)") { base, show, video ->
            val showId = (show ?: video.show)?.id
            base.putExtra("videoId", video.id).putExtra("video_id", video.id).putExtra("id", video.id)
                .apply { showId?.let { putExtra("showId", it).putExtra("show_id", it) } }
                .putExtra("videoIdString", video.id.toString())
        },
        Variant("Écran principal + adresse en extras (url, deeplink, link)") { base, show, video ->
            val url = webUrl("animationdigitalnetwork.com", show, video)
            base.putExtra("url", url).putExtra("deeplink", url).putExtra("deepLink", url).putExtra("link", url).putExtra("uri", url)
        },
        Variant("Écran principal (MAIN) + adresse androidtv… en données") { base, show, video ->
            base.setAction(Intent.ACTION_MAIN).setData(Uri.parse(webUrl("androidtv.animationdigitalnetwork.com", show, video)))
        },
    )

    /** Intention explicite vers l'écran principal de l'app ADN, prête à recevoir l'épisode. */
    private fun mainActivity(context: Context): Intent? {
        val pkg = packageName(context) ?: return null
        val pm = context.packageManager
        val component: ComponentName = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.component ?: return null
        return Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    fun variantIntent(context: Context, index: Int, show: AdnShow?, video: AdnVideo): Intent? =
        VARIANTS.getOrNull(index)?.let { variant -> mainActivity(context)?.let { variant.build(it, show, video) } }

    fun savedVariant(context: Context): Int = context.getSharedPreferences("adn_links", Context.MODE_PRIVATE).getInt("variant", -1)

    fun saveVariant(context: Context, index: Int) {
        context.getSharedPreferences("adn_links", Context.MODE_PRIVATE).edit().putInt("variant", index).apply()
    }

    /** Écrans de l'app ADN ouvrables par une autre app (pour le diagnostic). */
    fun exportedActivities(context: Context): List<String> {
        val pkg = packageName(context) ?: return listOf("App ADN introuvable")
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) }.getOrNull()
        return info?.activities.orEmpty().filter { it.exported }.map { it.name }.ifEmpty { listOf("aucun") }
    }

    /** Formats acceptés par l'app ADN installée, pour le diagnostic (« Analyser l'app ADN »). */
    fun acceptedFormats(context: Context): List<String> {
        val sample = AdnShow(id = 1, urlPath = "1-exemple")
        val video = AdnVideo(id = 2, show = sample)
        val pm = context.packageManager
        return (EPISODE_TEMPLATES + SHOW_TEMPLATES).map { template ->
            val intent = buildIntent(context, template, sample, video)
            @Suppress("DEPRECATION")
            val ok = intent != null && pm.resolveActivity(intent, 0) != null
            (if (ok) "✓ " else "✗ ") + template
        }
    }

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

    /** Ouvre l'épisode s'il est fourni (lecture directe), sinon la fiche de la série. */
    fun open(context: Context, show: AdnShow?, video: AdnVideo?) {
        // Méthode retenue au banc d'essai (ADN TV 7 sans liens déclarés).
        val variant = savedVariant(context)
        if (video != null && variant >= 0) {
            variantIntent(context, variant, show, video)?.let { if (start(context, it)) return }
        }
        val episode = video?.let { firstAccepted(context, EPISODE_TEMPLATES, show, it, "episode") }
        if (episode != null) {
            start(context, episode)
            return
        }
        val page = firstAccepted(context, SHOW_TEMPLATES, show ?: video?.show, null, "show")
        if (page != null && video != null) {
            Toast.makeText(context, "ADN n'accepte pas de lien vers l'épisode : ouverture de la fiche", Toast.LENGTH_LONG).show()
        }
        start(context, page)
    }

    /** Même chose à partir des identifiants seuls (cartes des rangées / de la watchlist). */
    fun open(context: Context, showId: String, videoId: String?) =
        open(context, showId.toIntOrNull()?.let { AdnShow(id = it) }, videoId?.toIntOrNull()?.let { AdnVideo(id = it) })

    fun openShow(context: Context, show: AdnShow) = start(context, firstAccepted(context, SHOW_TEMPLATES, show, null, "show"))

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
