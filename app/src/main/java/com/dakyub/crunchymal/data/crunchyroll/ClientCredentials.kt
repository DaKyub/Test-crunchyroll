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
        // 2) Sinon, chaque identifiant plausible associé à chaque secret plausible (listes bornées).
        val ids = strings.filter { ID.matches(it) }.take(MAX_IDS)
        val secrets = strings.filter { SECRET.matches(it) && it.any(Char::isLetter) }.take(MAX_SECRETS)
        val pairs = ids.asSequence().flatMap { id -> secrets.asSequence().map { basic("$id:$it") } }
        val idsOnly = ids.asSequence().map { basic("$it:") }

        val found = "appli v${installed.versionName} · ${strings.size} chaînes · ${ids.size} id · ${secrets.size} secret"
        var tested = 0
        var reachErrors = 0
        var any = false
        // Séquence paresseuse : on ne construit jamais tout le produit en mémoire.
        for (candidate in (decoded + pairs + idsOnly).distinct().filter { it != current }.take(MAX_TRIES)) {
            any = true
            val code = test(candidate, userAgent)
            when {
                code in 200..299 -> {
                    settings.basicAuthOverride = candidate
                    settings.userAgentOverride = userAgent
                    return Result.Found
                }
                code in 400..403 -> tested++
                else -> {
                    reachErrors++
                    if (reachErrors >= 3) return Result.Failed("Crunchyroll injoignable (Cloudflare ou réseau)")
                }
            }
        }
        return if (!any) Result.Failed("aucun identifiant trouvé ($found)")
        else Result.Failed("$tested identifiant(s) testé(s), tous refusés ($found)")
    }

    /**
     * Teste les identifiants sur le point d'accès réellement utilisé à la connexion (code d'appareil) :
     * un client accepté répond 2xx, un client refusé 400/401/403. On valide donc exactement ce qui échoue.
     */
    private fun test(basicAuth: String, userAgent: String): Int = runCatching {
        val request = Request.Builder().url("${CrConfig.BASE}/auth/v1/device/code")
            .header("Authorization", basicAuth)
            .header("User-Agent", userAgent)
            .post(FormBody.Builder().build())
            .build()
        Http.client.newCall(request).execute().use { it.code }
    }.getOrDefault(-1)

    private fun basic(raw: String) = "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private companion object {
        const val RETRY_MS = 10 * 60_000L
        const val MAX_TRIES = 400
        const val MAX_IDS = 60
        const val MAX_SECRETS = 60
        /** Chaîne « jeton » : lettres, chiffres et séparateurs base64 / URL. */
        val TOKEN = Regex("""^[A-Za-z0-9+/=_-]+$""")
        /** Identifiant client : 14 à 28 caractères minuscules/chiffres, séparateurs éventuels (format observé ~20). */
        val ID = Regex("""^(?=.{14,28}$)[a-z0-9]+([_-][a-z0-9]+)*$""")
        /** Secret : jeton de ~32 caractères (28 à 44). */
        val SECRET = Regex("""^[A-Za-z0-9_-]{28,44}$""")
        /** Base64 « standard » d'une valeur d'au moins ~12 octets. */
        val B64 = Regex("""^[A-Za-z0-9+/]{16,160}={0,2}$""")
    }
}
