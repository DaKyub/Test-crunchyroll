package com.dakyub.crunchymal.data.crunchyroll

import kotlinx.serialization.decodeFromString
import android.content.Context
import android.os.Build
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object CrConfig {
    const val BASE = "https://www.crunchyroll.com"
    const val ACTIVATE_URL = "https://www.crunchyroll.com/activate"

    // Les identifiants client (Basic) ne sont pas dans le code : ils viennent de la variable
    // CR_BASIC_AUTH au build (voir README) ou des paramètres de l'app.
    const val DEFAULT_USER_AGENT = "Crunchyroll/ANDROIDTV/3.74.0_22364 (Android 14; en-US; Chromecast)"
}

class NotLoggedInException : Exception("Session Crunchyroll expirée, reconnecte-toi.")

sealed interface PollResult {
    data object Success : PollResult
    data object Pending : PollResult
    data object Expired : PollResult
    data class Error(val message: String) : PollResult
}

class CrAuth(context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _loggedIn = MutableStateFlow(
        !prefs.getString("refresh_token", null).isNullOrBlank() &&
            !prefs.getString("account_id", null).isNullOrBlank()
    )
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val accountId: String get() = prefs.getString("account_id", "")!!

    private fun clientRequest(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", settings.basicAuth)
        .header("User-Agent", settings.userAgent)

    suspend fun requestDeviceCode(): CrDeviceCode {
        val request = clientRequest("${CrConfig.BASE}/auth/v1/device/code")
            .post(FormBody.Builder().build())
            .build()
        val code = Http.json.decodeFromString<CrDeviceCode>(Http.client.fetch(request))
        if (code.deviceCode.isBlank() || code.userCode.isBlank()) error("Réponse de code d'appareil invalide")
        return code
    }

    suspend fun pollDeviceToken(deviceCode: String): PollResult {
        val body = buildJsonObject { put("device_code", deviceCode) }.toString()
            .toRequestBody("application/json".toMediaType())
        val request = clientRequest("${CrConfig.BASE}/auth/v1/device/token")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val (code, text) = withContext(Dispatchers.IO) {
            Http.client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        }
        return when {
            code == 204 -> PollResult.Pending
            code in 200..299 -> {
                val token = runCatching { Http.json.decodeFromString<CrToken>(text) }.getOrNull()
                if (token == null || token.accessToken.isBlank()) {
                    PollResult.Error("Réponse de jeton invalide")
                } else {
                    saveToken(token)
                    runCatching { ensureAccountId() }
                        .fold({ PollResult.Success }, { PollResult.Error(it.message ?: "Compte introuvable") })
                }
            }
            code == 400 || code == 401 || code == 403 -> {
                val err = runCatching { Http.json.decodeFromString<CrOAuthError>(text).error }.getOrDefault("")
                when {
                    "pending" in err || "slow_down" in err -> PollResult.Pending
                    "expired" in err -> PollResult.Expired
                    err.isNotBlank() -> PollResult.Error(err)
                    else -> PollResult.Error("HTTP $code (Cloudflare ou identifiants client refusés ?)")
                }
            }
            else -> PollResult.Error("HTTP $code")
        }
    }

    private fun saveToken(token: CrToken) {
        val editor = prefs.edit()
            .putString("access_token", token.accessToken)
            .putString("token_type", token.tokenType.ifBlank { "Bearer" })
            .putLong("expires_at", System.currentTimeMillis() + token.expiresIn * 1000)
        if (token.refreshToken.isNotBlank()) editor.putString("refresh_token", token.refreshToken)
        if (token.accountId.isNotBlank()) editor.putString("account_id", token.accountId)
        editor.apply()
    }

    private suspend fun ensureAccountId() {
        if (accountId.isBlank()) {
            val request = Request.Builder()
                .url("${CrConfig.BASE}/accounts/v1/me")
                .header("Authorization", "Bearer ${prefs.getString("access_token", "")}")
                .header("User-Agent", settings.userAgent)
                .build()
            val account = Http.json.decodeFromString<CrAccount>(Http.client.fetch(request))
            if (account.accountId.isBlank()) error("Identifiant de compte introuvable")
            prefs.edit().putString("account_id", account.accountId).apply()
        }
        _loggedIn.value = true
    }

    /** Renvoie la valeur complète de l'en-tête Authorization, en rafraîchissant le jeton si besoin. */
    suspend fun authorizationHeader(forceRefresh: Boolean = false): String = mutex.withLock {
        val token = prefs.getString("access_token", null)
        val expiresAt = prefs.getLong("expires_at", 0)
        if (!forceRefresh && !token.isNullOrBlank() && System.currentTimeMillis() < expiresAt - 60_000) {
            return "${prefs.getString("token_type", "Bearer")} $token"
        }
        refresh()
        "${prefs.getString("token_type", "Bearer")} ${prefs.getString("access_token", "")}"
    }

    private suspend fun refresh() {
        val refreshToken = prefs.getString("refresh_token", null)
        if (refreshToken.isNullOrBlank()) {
            logout()
            throw NotLoggedInException()
        }
        val form = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", "offline_access")
            .add("device_id", settings.deviceId)
            .add("device_name", Build.MODEL ?: "Android TV")
            .add("device_type", "ANDROIDTV")
            .build()
        val request = clientRequest("${CrConfig.BASE}/auth/v1/token").post(form).build()
        try {
            saveToken(Http.json.decodeFromString<CrToken>(Http.client.fetch(request)))
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) {
                logout()
                throw NotLoggedInException()
            }
            throw e
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _loggedIn.value = false
    }
}
