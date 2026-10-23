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
        val installed = runCatching { context.packageManager.getPackageInfo(OfficialApp.PACKAGE, 0) }.getOrNull()
            ?: return@withContext Result.Failed("l'app Crunchyroll officielle n'est pas installée")

        // Toutes les chaînes « jeton » (lettres/chiffres et séparateurs) d'une taille plausible.
        val strings = AppAnalyzer.strings(context, OfficialApp.PACKAGE) { s -> s.length in 8..120 && TOKEN.matches(s) }
        val userAgent = "Crunchyroll/ANDROIDTV/${installed.versionName}_${installed.longVersionCodeCompat()} " +
            "(Android ${Build.VERSION.RELEASE}; en-US; ${Build.MODEL})"
        val current = settings.basicAuth

        // 1) Valeurs base64 qui se décodent déjà en « identifiant:secret » : la valeur « Basic » toute faite.
        val decoded = strings.filter { B64.matches(it) }.mapNotNull { b64 ->
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull() ?: return@mapNotNull null
            val parts = text.split(':')
            if (parts.size == 2 && ID.matches(parts[0]) && parts[1].length >= 10) "Basic $b64" else null
        }
        // 2) Sinon, chaque identifiant plausible associé à chaque secret plausible.
        val ids = strings.filter { ID.matches(it) }
        val secrets = strings.filter { SECRET.matches(it) && it.any(Char::isDigit) && it.any(Char::isLetter) }
        val pairs = ids.flatMap { id -> secrets.map { basic("$id:$it") } }
        // Beaucoup d'identifiants s'utilisent aussi sans secret (client « public ») : on les tente aussi.
        val idsOnly = if (ids.size <= 30) ids.map { basic("$it:") } else emptyList()

        val candidates = (decoded + pairs + idsOnly).distinct().filter { it != current }
        // Diagnostic affiché en cas d'échec (sans jamais montrer les valeurs elles-mêmes).
        val found = "appli v${installed.versionName} · ${strings.size} chaînes · ${decoded.size} base64 · " +
            "${ids.size} id × ${secrets.size} secret"
        if (candidates.isEmpty()) return@withContext Result.Failed("aucun identifiant trouvé ($found)")

        var tested = 0
        var reachErrors = 0
        for (candidate in candidates.take(MAX_TRIES)) {
            when (test(candidate, userAgent)) {
                200 -> {
                    settings.basicAuthOverride = candidate
                    settings.userAgentOverride = userAgent
                    return@withContext Result.Found
                }
                400, 401, 403 -> tested++
                else -> { reachErrors++; if (reachErrors >= 3) return@withContext Result.Failed("Crunchyroll injoignable (Cloudflare ou réseau)") }
            }
        }
        Result.Failed("$tested identifiant(s) testé(s), tous refusés ($found)")
    }

    /** Jeton anonyme : 200 si Crunchyroll accepte ces identifiants client. */
    private fun test(basicAuth: String, userAgent: String): Int = runCatching {
        val form = FormBody.Builder()
            .add("grant_type", "client_id")
            .add("device_id", settings.deviceId)
            .add("device_type", "ANDROIDTV")
            .build()
        val request = Request.Builder().url("${CrConfig.BASE}/auth/v1/token")
            .header("Authorization", basicAuth)
            .header("User-Agent", userAgent)
            .post(form)
            .build()
        Http.client.newCall(request).execute().use { it.code }
    }.getOrDefault(-1)

    private fun basic(raw: String) = "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private companion object {
        const val RETRY_MS = 10 * 60_000L
        const val MAX_TRIES = 200
        /** Chaîne « jeton » : lettres, chiffres et séparateurs base64 / URL. */
        val TOKEN = Regex("""^[A-Za-z0-9+/=_-]+$""")
        /**
         * Identifiant client : 12 à 40 caractères minuscules/chiffres avec séparateurs, et au moins un chiffre
         * ou un séparateur (pour écarter les mots courants du code).
         */
        val ID = Regex("""^(?=.{12,40}$)(?=.*[0-9_-])[a-z0-9]+([_-][a-z0-9]+)*$""")
        /** Secret : jeton d'au moins 20 caractères (lettres + chiffres). */
        val SECRET = Regex("""^[A-Za-z0-9_-]{20,64}$""")
        /** Base64 « standard » d'une valeur d'au moins ~12 octets. */
        val B64 = Regex("""^[A-Za-z0-9+/]{16,160}={0,2}$""")
    }
}
