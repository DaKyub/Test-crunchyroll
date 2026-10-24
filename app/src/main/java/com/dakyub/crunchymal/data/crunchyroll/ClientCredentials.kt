package com.dakyub.crunchymal.data.crunchyroll

import android.content.Context
import android.os.Build
import android.util.Base64
import com.dakyub.crunchymal.AppAnalyzer
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * Identifiants client (Basic) de l'app TV Crunchyroll retrouvés dans l'app officielle installée : quand
 * Crunchyroll refuse ceux de l'app, on cherche dans son code les chaînes qui y ressemblent, on teste chaque
 * couple auprès de Crunchyroll et on garde le premier accepté (avec le User-Agent de la version installée).
 */
class ClientCredentials(context: Context, private val settings: Settings) {
    private val context = context.applicationContext
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
        // Toute erreur (mémoire, lecture de l'app…) renvoie un échec au lieu de faire planter l'app.
        runCatching { search() }.getOrElse { Result.Failed("erreur interne : ${it.javaClass.simpleName}") }
    }

    private fun search(): Result {
        val installed = runCatching { context.packageManager.getPackageInfo(OfficialApp.PACKAGE, 0) }.getOrNull()
            ?: return Result.Failed("l'app Crunchyroll officielle n'est pas installée")

        // Chaînes « jeton » assez longues et contenant un chiffre : écarte la grande majorité du code
        // (noms de classes, chemins, mots), pour ne pas saturer la mémoire.
        val strings = AppAnalyzer.strings(context, OfficialApp.PACKAGE) { s ->
            s.length in 16..120 && s.any(Char::isDigit) && TOKEN.matches(s)
        }
        val userAgent = "Crunchyroll/ANDROIDTV/${installed.versionName}_${installed.longVersionCodeCompat()} " +
            "(Android ${Build.VERSION.RELEASE}; en-US; ${Build.MODEL})"
        val current = settings.basicAuth

        // 1) Valeurs base64 qui se décodent déjà en « identifiant:secret » : la valeur « Basic » toute faite,
        // acceptée sans imposer de format précis (c'est la plus fiable).
        val decoded = strings.asSequence().filter { B64.matches(it) }.mapNotNull { b64 ->
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull() ?: return@mapNotNull null
            val parts = text.split(':')
            val ok = parts.size == 2 && parts[0].length in 6..40 && parts[1].length in 6..64 &&
                text.all { it.code in 33..126 }
            if (ok) "Basic $b64" else null
        }
        val idsOnly = strings.asSequence().filter { ID.matches(it) }.map { basic("$it:") }

        // Mode diagnostic : on ne teste QUE la valeur déjà en place et les valeurs « Basic » toutes faites
        // (plus quelques identifiants seuls), sans marteler Crunchyroll avec des milliers de combinaisons.
        val candidates = (sequenceOf(current.takeIf { it.isNotBlank() }).filterNotNull() + decoded + idsOnly)
            .distinct().take(MAX_TRIES).toList()
        val found = "v${installed.versionName} · ${strings.size} chaînes · ${decoded.count()} base64"
        if (candidates.isEmpty()) return Result.Failed("aucune valeur à tester ($found)")

        // Répartition des codes HTTP et premier corps de réponse non-2xx (pour reconnaître un blocage Cloudflare).
        val codes = sortedMapOf<Int, Int>()
        var sample = ""
        for (candidate in candidates) {
            val probe = test(candidate, userAgent)
            if (probe.code in 200..299) {
                // On ne remplace la valeur en place que si ce n'est pas déjà elle.
                if (candidate != current) {
                    settings.basicAuthOverride = candidate
                    settings.userAgentOverride = userAgent
                }
                return Result.Found
            }
            codes[probe.code] = (codes[probe.code] ?: 0) + 1
            if (sample.isEmpty() && probe.body.isNotBlank()) sample = probe.body.take(140).replace(Regex("""\s+"""), " ")
        }
        val codeList = codes.entries.joinToString(" ") { "${it.key}×${it.value}" }
        return Result.Failed("codes $codeList · $found · « $sample »")
    }

    private data class Probe(val code: Int, val body: String)

    /**
     * Teste les identifiants sur le point d'accès réellement utilisé à la connexion (code d'appareil) :
     * un client accepté répond 2xx. Renvoie le code HTTP et un extrait du corps pour le diagnostic.
     */
    private fun test(basicAuth: String, userAgent: String): Probe = runCatching {
        val request = Request.Builder().url("${CrConfig.BASE}/auth/v1/device/code")
            .header("Authorization", basicAuth)
            .header("User-Agent", userAgent)
            .post(FormBody.Builder().build())
            .build()
        Http.client.newCall(request).execute().use { r ->
            // Sur 2xx le corps peut contenir un jeton : on ne le garde pas, seul l'échec nous intéresse.
            Probe(r.code, if (r.isSuccessful) "" else r.body?.string().orEmpty())
        }
    }.getOrElse { Probe(-1, it.javaClass.simpleName) }

    private fun basic(raw: String) = "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private companion object {
        const val RETRY_MS = 10 * 60_000L
        const val MAX_TRIES = 40
        /** Chaîne « jeton » : lettres, chiffres et séparateurs base64 / URL. */
        val TOKEN = Regex("""^[A-Za-z0-9+/=_-]+$""")
        /** Identifiant client : 14 à 28 caractères minuscules/chiffres, séparateurs éventuels (format observé ~20). */
        val ID = Regex("""^(?=.{14,28}$)[a-z0-9]+([_-][a-z0-9]+)*$""")
        /** Base64 « standard » d'une valeur d'au moins ~12 octets. */
        val B64 = Regex("""^[A-Za-z0-9+/]{16,160}={0,2}$""")
    }
}
