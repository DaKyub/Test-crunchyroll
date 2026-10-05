package com.dakyub.crunchymal.data.mal

import android.content.Context
import android.net.Uri
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.Request
import java.security.SecureRandom

@Serializable
private data class MalToken(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
private data class MalUser(val name: String = "")

/**
 * Connexion OAuth2 (PKCE) au compte MyAnimeList, nécessaire pour modifier la liste de l'utilisateur.
 * Le code d'autorisation est récupéré à la main : après validation sur le téléphone, MAL redirige vers
 * l'URL de redirection de l'app (http://localhost), dont l'adresse contient ?code=…
 */
class MalAuth(context: Context, private val clientId: () -> String, private val clientSecret: () -> String) {
    private val prefs = context.getSharedPreferences("mal_auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _loggedIn = MutableStateFlow(!prefs.getString("refresh_token", null).isNullOrBlank())
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val username: String get() = prefs.getString("username", "")!!

    /** Nouvelle URL d'autorisation (un nouveau code_verifier est généré à chaque appel). */
    fun authorizeUrl(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        val verifier = (1..64).map { chars[random.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("verifier", verifier).apply()
        return Uri.parse("https://myanimelist.net/v1/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("code_challenge", verifier) // méthode "plain", la seule acceptée par MAL
            .appendQueryParameter("code_challenge_method", "plain")
            .appendQueryParameter("state", "crunchymal")
            .build().toString()
    }

    /** Échange le code (ou l'adresse complète collée depuis le navigateur) contre des jetons. */
    suspend fun exchange(codeOrUrl: String): String? {
        val input = codeOrUrl.trim()
        val code = if ("code=" in input) Uri.parse(input).getQueryParameter("code") ?: input.substringAfter("code=").substringBefore('&') else input
        val verifier = prefs.getString("verifier", null) ?: return "Recommence la connexion (code expiré)."
        val form = FormBody.Builder()
            .add("client_id", clientId())
            .apply { clientSecret().takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .build()
        return try {
            save(Http.json.decodeFromString(Http.client.fetch(Request.Builder().url(TOKEN_URL).post(form).build())))
            runCatching { fetchUsername() }
            _loggedIn.value = true
            null
        } catch (e: HttpException) {
            "Refusé par MAL (HTTP ${e.code}) : ${e.body.take(150)}"
        } catch (e: Exception) {
            e.message ?: "Erreur"
        }
    }

    private fun save(token: MalToken) {
        prefs.edit()
            .putString("access_token", token.accessToken)
            .putString("refresh_token", token.refreshToken.ifBlank { prefs.getString("refresh_token", "") })
            .putLong("expires_at", System.currentTimeMillis() + token.expiresIn * 1000)
            .apply()
    }

    private suspend fun fetchUsername() {
        val request = Request.Builder().url("https://api.myanimelist.net/v2/users/@me")
            .header("Authorization", "Bearer ${accessToken()}").build()
        val user = Http.json.decodeFromString<MalUser>(Http.client.fetch(request))
        prefs.edit().putString("username", user.name).apply()
    }

    /** Jeton d'accès valide (rafraîchi si besoin). */
    suspend fun accessToken(): String = mutex.withLock {
        val token = prefs.getString("access_token", null)
        if (!token.isNullOrBlank() && System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) return token
        val refresh = prefs.getString("refresh_token", null) ?: error("Non connecté à MAL")
        val form = FormBody.Builder()
            .add("client_id", clientId())
            .apply { clientSecret().takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .build()
        try {
            save(Http.json.decodeFromString(Http.client.fetch(Request.Builder().url(TOKEN_URL).post(form).build())))
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) logout()
            throw e
        }
        prefs.getString("access_token", "")!!
    }

    fun logout() {
        prefs.edit().clear().apply()
        _loggedIn.value = false
    }

    private companion object {
        const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
    }
}
