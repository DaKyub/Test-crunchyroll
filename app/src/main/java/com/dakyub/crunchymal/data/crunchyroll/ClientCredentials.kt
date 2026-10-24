package com.dakyub.crunchymal.data.crunchyroll

import android.content.Context
import android.util.Base64
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * Identifiants client (Basic) de l'app TV Crunchyroll récupérés depuis une source publique tenue à jour
 * (le fichier auth.py du projet smirgol/plugin.video.crunchyroll). Quand Crunchyroll refuse ceux de l'app,
 * on télécharge le fichier, on en extrait la valeur « Basic … », on la vérifie et on la garde.
 */
class ClientCredentials(@Suppress("UNUSED_PARAMETER") context: Context, private val settings: Settings) {
    @Volatile private var lastAttempt = 0L

    sealed interface Result {
        data object Found : Result
        data class Failed(val reason: String) : Result
    }

    /** [manual] : demandé depuis les réglages (sinon au plus une recherche toutes les 10 min). */
    suspend fun recover(manual: Boolean = false): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastAttempt < RETRY_MS) return@withContext Result.Failed("recherche déjà faite il y a peu")
        lastAttempt = now
        runCatching { fetchAndApply() }.getOrElse { Result.Failed("erreur : ${it.javaClass.simpleName}") }
    }

    private fun fetchAndApply(): Result {
        val current = settings.basicAuth
        val userAgent = settings.userAgent
        val codes = mutableListOf<Int>()
        var reached = false
        for (url in SOURCES) {
            val text = runCatching { fetch(url) }.getOrNull() ?: continue
            reached = true
            // Valeurs « Basic … » (ou base64 d'un « identifiant:secret ») trouvées dans le fichier.
            for (candidate in extractBasics(text).filter { it != current }) {
                val code = test(candidate, userAgent)
                if (code in 200..299) {
                    settings.basicAuthOverride = candidate
                    return Result.Found
                }
                codes += code
            }
        }
        return when {
            !reached -> Result.Failed("source en ligne injoignable (réseau)")
            codes.isEmpty() -> Result.Failed("aucune valeur trouvée dans la source en ligne")
            else -> Result.Failed("valeur en ligne refusée par Crunchyroll (codes ${codes.joinToString(", ")})")
        }
    }

    /** Extrait les valeurs « Basic … » d'un texte (fichier source), normalisées avec le préfixe « Basic ». */
    private fun extractBasics(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // Forme « Basic xxxxx= » directement présente.
        BASIC.findAll(text).forEach { out += "Basic " + it.groupValues[1] }
        // Forme « VARIABLE = "xxxxx=" » dont le base64 se décode en « identifiant:secret ».
        ASSIGN.findAll(text).forEach { m ->
            val b64 = m.groupValues[1]
            val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
            if (decoded != null && decoded.count { it == ':' } == 1 && decoded.all { it.code in 33..126 }) out += "Basic $b64"
        }
        return out.toList()
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "CrunchyMAL").build()
        return Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /**
     * Teste les identifiants sur le point d'accès réellement utilisé à la connexion (code d'appareil) :
     * un client accepté répond 2xx, un client refusé 400/401/403.
     */
    private fun test(basicAuth: String, userAgent: String): Int = runCatching {
        val request = Request.Builder().url("${CrConfig.BASE}/auth/v1/device/code")
            .header("Authorization", basicAuth)
            .header("User-Agent", userAgent)
            .post(FormBody.Builder().build())
            .build()
        Http.client.newCall(request).execute().use { it.code }
    }.getOrDefault(-1)

    private companion object {
        const val RETRY_MS = 10 * 60_000L
        /** Fichier auth.py du projet smirgol (branche master puis main par sécurité). */
        val SOURCES = listOf(
            "https://raw.githubusercontent.com/smirgol/plugin.video.crunchyroll/master/resources/lib/auth.py",
            "https://raw.githubusercontent.com/smirgol/plugin.video.crunchyroll/main/resources/lib/auth.py",
        )
        /** « Basic » suivi d'une valeur base64. */
        val BASIC = Regex("""Basic\s+([A-Za-z0-9+/]{24,}={0,2})""")
        /** Affectation d'une chaîne base64 à une variable (AUTHORIZATION = "…"). */
        val ASSIGN = Regex("""["']([A-Za-z0-9+/]{32,}={0,2})["']""")
    }
}
