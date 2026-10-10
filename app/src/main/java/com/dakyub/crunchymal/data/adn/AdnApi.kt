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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    private var genresCache: List<String>? = null

    /**
     * Genres acceptés par le catalogue. L'API ne les liste nulle part : on les lit dans son message
     * d'erreur de validation (« Must be one of : action, adventure… »), ce qui suit ses changements.
     */
    suspend fun genres(): List<String> {
        genresCache?.let { return it }
        val parsed = try {
            get("$BASE/show/catalog?genres=x&limit=1")
            null
        } catch (e: HttpException) {
            Regex("""Must be one of\s*:\s*([^"\\]+)""").find(e.body)?.groupValues?.get(1)
                ?.split(',')
                ?.map { it.trim().trimEnd('.') }
                ?.filter { it.matches(Regex("[a-z0-9-]+")) }
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        parsed?.let { genresCache = it }
        return parsed ?: AdnGenreFallback
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

    /** Watchlist : une vidéo par série (celle où l'on en est), avec la série et la progression. */
    suspend fun watchlist(): List<AdnVideo> =
        Http.json.decodeFromString<AdnVideosResponse>(get("$BASE/watchlist")).videos

    /** Historique de visionnage (vidéos les plus récentes d'abord), avec la progression. */
    suspend fun viewingHistory(): List<AdnVideo> =
        Http.json.decodeFromString<AdnVideosResponse>(get("$BASE/viewing/history")).videos

    /** Dernière vidéo regardée d'une série, ou null si elle n'a jamais été commencée. */
    suspend fun lastWatched(showId: String): AdnVideo? = try {
        get("$BASE/viewing/history/show/$showId/last").takeIf { it.isNotBlank() }
            ?.let { Http.json.decodeFromString<AdnVideoResponse>(it).video }
    } catch (e: HttpException) {
        if (e.code == 404 || e.code == 204) null else throw e
    }

    suspend fun inWatchlist(showId: String): Boolean =
        Http.json.decodeFromString<AdnStatusResponse>(get("$BASE/watchlist/show/$showId/status")).status

    /** Résultat d'un ajout / retrait : [ok] si le statut a bien changé ; [log] détaille chaque essai. */
    data class WatchlistChange(val ok: Boolean, val log: List<String>)

    private data class WriteVariant(val method: String, val path: String, val json: String?)

    /** Façons plausibles d'écrire dans la watchlist (l'API n'est pas documentée). */
    private fun watchlistVariants(showId: String, add: Boolean): List<WriteVariant> = if (add) listOf(
        WriteVariant("POST", "/watchlist/show/$showId", null),
        WriteVariant("PUT", "/watchlist/show/$showId", null),
        WriteVariant("PUT", "/watchlist/show/$showId/status", """{"status":true}"""),
        WriteVariant("POST", "/watchlist/show/$showId/status", """{"status":true}"""),
        WriteVariant("POST", "/watchlist", """{"showId":$showId}"""),
        WriteVariant("PUT", "/watchlist", """{"showId":$showId}"""),
    ) else listOf(
        WriteVariant("DELETE", "/watchlist/show/$showId", null),
        WriteVariant("PUT", "/watchlist/show/$showId/status", """{"status":false}"""),
        WriteVariant("POST", "/watchlist/show/$showId/status", """{"status":false}"""),
        WriteVariant("DELETE", "/watchlist/show/$showId/status", null),
        WriteVariant("DELETE", "/watchlist", """{"showId":$showId}"""),
    )

    /**
     * Ajoute ou retire une série de la watchlist : essaie chaque variante jusqu'à ce que le statut
     * relu change, et retient celle qui a marché pour la fois suivante.
     */
    suspend fun setInWatchlist(showId: String, add: Boolean): WatchlistChange {
        val prefKey = if (add) "wl_add_variant" else "wl_remove_variant"
        val variants = watchlistVariants(showId, add)
        val remembered = prefs.getInt(prefKey, -1)
        val order = (listOf(remembered) + variants.indices).filter { it in variants.indices }.distinct()
        val profile = runCatching { profileId() }.getOrNull()
        val log = mutableListOf("${if (add) "Ajout" else "Retrait"} de la série $showId · profil : ${profile ?: "inconnu"}")
        for (i in order) {
            val variant = variants[i]
            val (code, body) = runCatching { write(variant, profile) }
                .getOrElse { 0 to "${it.javaClass.simpleName}: ${it.message}" }
            val now = if (code in 200..299) runCatching { inWatchlist(showId) }.getOrNull() else null
            log += "${variant.method} ${variant.path}${variant.json?.let { " $it" }.orEmpty()} → HTTP $code · " +
                "statut relu : ${now ?: "-"} · ${redact(body).take(300).ifBlank { "(vide)" }}"
            if (now == add) {
                prefs.edit().putInt(prefKey, i).apply()
                return WatchlistChange(true, log)
            }
        }
        return WatchlistChange(false, log)
    }

    private suspend fun write(variant: WriteVariant, profile: String?): Pair<Int, String> {
        suspend fun once(): Pair<Int, String> = withContext(Dispatchers.IO) {
            val body = variant.json?.toRequestBody("application/json".toMediaType())
                ?: if (variant.method == "DELETE") null else ByteArray(0).toRequestBody(null)
            val request = request(BASE + variant.path)
                .header("Origin", "https://animationdigitalnetwork.com")
                .header("Referer", "https://animationdigitalnetwork.com/")
                .apply { profile?.let { header("X-Profile-ID", it) } }
                .method(variant.method, body)
                .build()
            Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        }
        val first = once()
        val user = prefs.getString("username", null)
        val pass = prefs.getString("password", null)
        return if (first.first == 401 && user != null && pass != null && login(user, pass) == null) once() else first
    }

    /** Identifiant du profil ADN actif (premier profil du compte), mis en cache. */
    private suspend fun profileId(): String? {
        prefs.getString("profile_id", null)?.let { return it }
        val id = Regex(""""id"\s*:\s*(\d+)""").find(get("$BASE/profile"))?.groupValues?.get(1)
        id?.let { prefs.edit().putString("profile_id", it).apply() }
        return id
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
        // Une série de la watchlist de préférence : elle a une progression à observer.
        val showId = runCatching { watchlist().firstOrNull()?.show?.id?.toString() }.getOrNull()
            ?: runCatching { catalog(limit = 1).firstOrNull()?.id?.toString() }.getOrNull() ?: "1"
        val videoId = runCatching { episodes(showId).firstOrNull()?.id?.toString() }.getOrNull() ?: "1"
        out += "== Progression par épisode (série $showId) =="
        out += runCatching {
            val list = episodes(showId)
            "${list.count { it.user != null }}/${list.size} épisodes avec progression · dernier vu : " +
                (lastWatched(showId)?.let { "${it.id} ${it.number} vu=${it.user?.isFullyWatched} (${it.user?.stoptime}/${it.duration} s)" } ?: "aucun")
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
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
