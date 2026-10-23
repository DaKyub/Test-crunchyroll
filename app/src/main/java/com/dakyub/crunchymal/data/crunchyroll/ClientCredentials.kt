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
        val strings = AppAnalyzer.strings(context, OfficialApp.PACKAGE) { s ->
            s.length in 16..100 && (ID.matches(s) || SECRET.matches(s) || BASE64.matches(s))
        }
        val userAgent = "Crunchyroll/ANDROIDTV/${installed.versionName}_${installed.longVersionCodeCompat()} " +
            "(Android ${Build.VERSION.RELEASE}; en-US; ${Build.MODEL})"
        val current = settings.basicAuth
        // Valeur Base64 « identifiant:secret » toute faite, puis couples identifiant × secret.
        val encoded = strings.filter { BASE64.matches(it) }.mapNotNull { b64 ->
            runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
                ?.takeIf { decoded -> decoded.count { it == ':' } == 1 && ID.matches(decoded.substringBefore(':')) }
                ?.let { "Basic $b64" }
        }
        val ids = strings.filter { ID.matches(it) }
        val secrets = strings.filter { SECRET.matches(it) && it.any(Char::isUpperCase) && it.any(Char::isLowerCase) && it.any(Char::isDigit) }
        val pairs = ids.flatMap { id -> secrets.map { secret -> basic("$id:$secret") } }
        val candidates = (encoded + pairs).distinct().filter { it != current }.take(MAX_TRIES)
        if (candidates.isEmpty()) return@withContext Result.Failed("aucun identifiant trouvé dans l'app Crunchyroll")
        for (candidate in candidates) {
            when (test(candidate, userAgent)) {
                200 -> {
                    settings.basicAuthOverride = candidate
                    settings.userAgentOverride = userAgent
                    return@withContext Result.Found
                }
                400, 401 -> continue
                else -> return@withContext Result.Failed("Crunchyroll ne répond pas (Cloudflare ou réseau)")
            }
        }
        Result.Failed("${candidates.size} identifiant(s) testé(s), tous refusés")
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
        const val MAX_TRIES = 150
        /** Identifiant client : minuscules et chiffres, avec un « _ » ou « - » (ex. abcdefgh_1234567890ab). */
        val ID = Regex("""^(?=.{16,24}$)[a-z0-9]{3,}[_-][a-z0-9_-]{5,}$""")
        /** Secret : 32 caractères URL-safe. */
        val SECRET = Regex("""^[A-Za-z0-9_-]{32}$""")
        val BASE64 = Regex("""^[A-Za-z0-9+/]{48,96}={0,2}$""")
    }
}
