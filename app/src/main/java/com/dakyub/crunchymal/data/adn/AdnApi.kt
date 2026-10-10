package com.dakyub.crunchymal.data.adn

import android.content.Context
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.HttpException
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * API (non officielle) d'Animation Digital Network : https://gw.api.animationdigitalnetwork.fr
 * Le catalogue est public ; la connexion sert à la liste personnelle et à la progression.
 */
class AdnApi(context: Context) {
    private val prefs = context.getSharedPreferences("adn", Context.MODE_PRIVATE)

    private val _loggedIn = MutableStateFlow(!prefs.getString("token", null).isNullOrBlank())
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val username: String get() = prefs.getString("username", "")!!

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("X-Target-Distribution", "fr")
        .apply { prefs.getString("token", null)?.let { header("Authorization", "Bearer $it") } }

    /** Connexion ; renvoie null si OK, sinon le message d'erreur. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("username", username.trim())
            .add("password", password)
            .add("rememberMe", "true")
            .add("source", "Web")
            .build()
        val call = Http.client.newCall(Request.Builder().url("$BASE/authentication/login").post(form).build())
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { Http.json.decodeFromString<AdnLoginResponse>(body) }.getOrNull()
            val token = parsed?.accessToken
            if (response.isSuccessful && !token.isNullOrBlank()) {
                prefs.edit()
                    .putString("login_info", redact(body).take(3000))
                    .putString("token", token)
                    .putString("refresh_token", parsed?.refreshToken)
                    .putString("username", username.trim())
                    .putString("password", password)
                    .apply()
                _loggedIn.value = true
                null
            } else {
                parsed?.message ?: parsed?.code ?: "HTTP ${response.code}"
            }
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _loggedIn.value = false
    }

    /** GET authentifié si possible ; en cas de 401, reconnexion automatique avec les identifiants enregistrés. */
    private suspend fun get(url: String): String = try {
        Http.client.fetch(request(url).build())
    } catch (e: HttpException) {
        val user = prefs.getString("username", null)
        val pass = prefs.getString("password", null)
        if (e.code == 401 && user != null && pass != null && login(user, pass) == null) {
            Http.client.fetch(request(url).build())
        } else throw e
    }

    suspend fun catalog(
        order: String = "popular",
        genre: String? = null,
        search: String? = null,
        simulcastOnly: Boolean = false,
        limit: Int = 60,
        offset: Int = 0,
    ): List<AdnShow> {
        val url = "$BASE/show/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.coerceAtMost(100).toString())
            .addQueryParameter("order", order)
            .apply {
                genre?.let { addQueryParameter("genres", it) }
                search?.takeIf { it.isNotBlank() }?.let { addQueryParameter("search", it) }
                if (simulcastOnly) addQueryParameter("diffusion", "simulcast")
            }
            .build().toString()
        return Http.json.decodeFromString<AdnShowsResponse>(get(url)).shows
    }

    suspend fun show(id: String): AdnShow =
        Http.json.decodeFromString<AdnShowResponse>(get("$BASE/show/$id")).show

    suspend fun episodes(showId: String): List<AdnVideo> {
        val url = "$BASE/video/show/$showId".toHttpUrl().newBuilder()
            .addQueryParameter("order", "asc")
            .addQueryParameter("limit", "-1")
            .build().toString()
        return Http.json.decodeFromString<AdnVideosResponse>(get(url)).videos
    }

    /** Épisodes sortis (ou prévus) un jour donné, date au format yyyy-MM-dd. */
    suspend fun calendar(date: String): List<AdnVideo> {
        val url = "$BASE/video/calendar".toHttpUrl().newBuilder().addQueryParameter("date", date).build().toString()
        return Http.json.decodeFromString<AdnVideosResponse>(get(url)).videos
    }

    /** GET brut (code HTTP + corps), avec ou sans en-tête de profil, pour le diagnostic. */
    private suspend fun rawGet(url: String, profileId: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = request(url).apply { profileId?.let { header("X-Profile-ID", it) } }.build()
        Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    /**
     * Sonde les adresses de l'API utilisées par l'app ADN TV (liste, historique, profil) et renvoie
     * les réponses brutes (jetons, mots de passe et e-mails masqués) pour adapter le code à leur format.
     */
    suspend fun probe(): List<String> {
        val out = mutableListOf<String>()
        out += "== Réponse de connexion (masquée) =="
        out += prefs.getString("login_info", null) ?: "(non disponible : se reconnecter à ADN pour la capturer)"
        val showId = runCatching { catalog(limit = 1).firstOrNull()?.id?.toString() }.getOrNull() ?: "1"
        val videoId = runCatching { episodes(showId).firstOrNull()?.id?.toString() }.getOrNull() ?: "1"
        val paths = listOf(
            "/watchlist",
            "/watchlist?maxAgeCategory=18",
            "/viewing/history",
            "/viewing/history/show/$showId/last",
            "/viewing/history/video/$videoId",
            "/watchlist/show/$showId/status",
            "/show/$showId/season",
            "/profile",
            "/profile/1",
        )
        for (base in listOf(BASE, BASE_COM)) {
            for (path in paths) {
                for (profile in listOf(null, "1")) {
                    val label = "GET ${base.removePrefix("https://")}$path" + (profile?.let { " [X-Profile-ID: $it]" } ?: "")
                    val (code, body) = runCatching { rawGet(base + path, profile) }.getOrElse { 0 to "${it.javaClass.simpleName}: ${it.message}" }
                    out += "== $label → HTTP $code =="
                    out += redact(body).take(2500).ifBlank { "(vide)" }
                    // Inutile de doubler l'appel avec en-tête de profil si le premier a échoué pour une autre raison.
                    if (code == 404) break
                }
            }
        }
        return out
    }

    private fun redact(json: String): String = json.replace(
        Regex(""""([A-Za-z_]*(?:[Tt]oken|password|Password|email|Email|mail)[A-Za-z_]*)"\s*:\s*"[^"]*""""),
        "\"$1\":\"***\"",
    )

    companion object {
        const val BASE_COM = "https://gw.api.animationdigitalnetwork.com"
        const val BASE = "https://gw.api.animationdigitalnetwork.fr"
    }
}
