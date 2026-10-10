package com.dakyub.crunchymal

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Lance l'app d'une plateforme (Netflix, Disney+…) installée sur l'appareil. Ces apps n'acceptent pas
 * de lien fiable vers une série précise : elles s'ouvrent sur leur accueil.
 */
object PlatformApps {
    private val packages = mapOf(
        "Netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "Disney+" to listOf("com.disney.disneyplus"),
        "Prime Video" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "Apple TV+" to listOf("com.apple.atve.androidtv.appletv"),
        "Canal+" to listOf("com.canal.android.canal"),
        "Max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
        "Paramount+" to listOf("com.cbs.ott", "com.cbs.app"),
        "Crunchyroll" to listOf(OfficialApp.PACKAGE),
        "ADN" to listOf("fr.anidn"),
    )

    /** Mot cherché dans le nom des apps installées quand aucun paquet connu n'est trouvé. */
    private fun keyword(platform: String) = when (platform) {
        "Disney+" -> "disney"
        "Prime Video" -> "prime video"
        "Apple TV+" -> "apple tv"
        "Canal+" -> "canal"
        else -> platform.lowercase()
    }

    fun launchIntent(context: Context, platform: String): Intent? {
        val pm = context.packageManager
        fun launch(pkg: String) = runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) }.getOrNull()
        packages[platform].orEmpty().firstNotNullOfOrNull { launch(it) }?.let { return it }
        val word = keyword(platform)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        @Suppress("DEPRECATION")
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(word) }
        return match?.activityInfo?.packageName?.let { launch(it) }
    }

    fun open(context: Context, platform: String) {
        val intent = launchIntent(context, platform)
        if (intent == null) {
            Toast.makeText(context, "L'app $platform n'est pas installée sur cet appareil", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Impossible d'ouvrir $platform", Toast.LENGTH_LONG).show() }
    }
}
