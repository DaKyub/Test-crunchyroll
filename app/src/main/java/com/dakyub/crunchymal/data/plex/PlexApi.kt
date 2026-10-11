package com.dakyub.crunchymal.data.plex

import android.content.Context
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class PlexPin(val id: Long = 0, val code: String = "", val authToken: String? = null)

@Serializable
private data class PlexConnection(val uri: String = "", val local: Boolean = false, val relay: Boolean = false)

@Serializable
private data class PlexResource(
    val name: String = "",
    val provides: String = "",
    val clientIdentifier: String = "",
    val accessToken: String? = null,
    val owned: Boolean = false,
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable
data class PlexDirectory(val key: String = "", val title: String = "", val type: String = "")

/** Série, saison ou épisode d'une bibliothèque Plex. */
@Serializable
data class PlexItem(
    val ratingKey: String = "",
    val type: String = "",
    val title: String = "",
    val originalTitle: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val summary: String? = null,
    /** Série : épisodes au total / vus. */
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    // Épisode
    val grandparentTitle: String? = null,
    val grandparentRatingKey: String? = null,
    val grandparentThumb: String? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val viewCount: Int = 0,
    val viewOffset: Long = 0,
    val duration: Long = 0,
) {
    val watched: Boolean get() = viewCount > 0
    val episodeLabel: String get() = "S${parentIndex ?: "?"} E${index ?: "?"}"
}

@Serializable
private data class PlexContainer(
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val items: List<PlexItem> = emptyList(),
)

@Serializable
private data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

/**
 * Serveur Plex de l'utilisateur : connexion par code (plex.tv/link), choix du serveur et de la meilleure
 * adresse (locale, distante puis relais), bibliothèques de séries, « Reprendre » (On Deck), épisodes.
 */
class PlexApi(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)
    private val quick = Http.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    private val _connected = MutableStateFlow(prefs.getString("server_uri", null) != null)
    val connected: StateFlow<Boolean> = _connected

    /** Connexion en cours : code à saisir, puis recherche du serveur (portée de l'app : survit au changement d'écran). */
    data class Login(val message: String, val code: String? = null, val qrUrl: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _login = MutableStateFlow<Login?>(null)
    val login: StateFlow<Login?> = _login

    fun startLogin() {
        scope.launch {
            runCatching {
                val pin = startPin()
                // Le QR code ouvre la page d'autorisation Plex avec le code déjà rempli.
                val qr = "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}&context%5Bdevice%5D%5Bproduct%5D=CrunchyMAL"
                _login.value = Login("Sur ton téléphone, va sur plex.tv/link et saisis ce code (ou scanne le QR code)", pin.code, qr)
                var token: String? = null
                for (attempt in 0 until 150) {
                    delay(2000)
                    token = runCatching { pinToken(pin) }.getOrNull()
                    if (token != null) break
                }
                val accountToken = token ?: error("Code expiré, recommence")
                _login.value = Login("Recherche de ton serveur Plex…")
                val name = connect(accountToken)
                _login.value = Login("Connecté à $name ✓")
            }.onFailure { _login.value = Login("Erreur : ${it.message}") }
        }
    }

    val serverName: String get() = prefs.getString("server_name", "").orEmpty()
    val machineId: String get() = prefs.getString("machine_id", "").orEmpty()
    private val serverUri: String get() = prefs.getString("server_uri", "").orEmpty()
    private val serverToken: String get() = prefs.getString("server_token", "").orEmpty()

    private val clientId: String
        get() = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_id", it).apply()
        }

    private fun plexTv(url: String) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("X-Plex-Product", "CrunchyMAL")
        .header("X-Plex-Client-Identifier", clientId)
        .header("X-Plex-Device-Name", "CrunchyMAL")

    /** Demande un code à saisir sur plex.tv/link. */
    suspend fun startPin(): PlexPin =
        Http.json.decodeFromString(Http.client.fetch(plexTv("https://plex.tv/api/v2/pins").post(ByteArray(0).toRequestBody(null)).build()))

    /** Jeton du compte une fois le code validé sur plex.tv/link, sinon null. */
    suspend fun pinToken(pin: PlexPin): String? =
        Http.json.decodeFromString<PlexPin>(Http.client.fetch(plexTv("https://plex.tv/api/v2/pins/${pin.id}").build()))
            .authToken?.takeIf { it.isNotBlank() }

    /** Trouve un serveur joignable du compte et l'enregistre ; renvoie son nom, ou lève une erreur. */
    suspend fun connect(accountToken: String): String = withContext(Dispatchers.IO) {
        val resources = Http.json.decodeFromString<List<PlexResource>>(
            Http.client.fetch(
                plexTv("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1").header("X-Plex-Token", accountToken).build()
            )
        )
        val servers = resources.filter { "server" in it.provides.split(',') }.sortedByDescending { it.owned }
        if (servers.isEmpty()) error("Aucun serveur Plex sur ce compte")
        for (server in servers) {
            val token = server.accessToken ?: accountToken
            // Adresse locale d'abord (plus rapide), puis distante, puis relais Plex.
            val ordered = server.connections.sortedBy { if (it.relay) 2 else if (it.local) 0 else 1 }
            for (connection in ordered) {
                val ok = runCatching {
                    quick.newCall(Request.Builder().url("${connection.uri}/identity").header("X-Plex-Token", token).build())
                        .execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) {
                    prefs.edit()
                        .putString("account_token", accountToken)
                        .putString("server_uri", connection.uri.trimEnd('/'))
                        .putString("server_token", token)
                        .putString("server_name", server.name)
                        .putString("machine_id", server.clientIdentifier)
                        .apply()
                    _connected.value = true
                    return@withContext server.name
                }
            }
        }
        error("Serveur Plex injoignable (ni en local, ni à distance)")
    }

    fun logout() {
        val keep = clientId
        prefs.edit().clear().putString("client_id", keep).apply()
        _connected.value = false
        _login.value = null
    }

    private suspend fun get(path: String): PlexContainer {
        val url = (serverUri + path).toHttpUrl()
        val body = Http.client.fetch(
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Plex-Token", serverToken)
                .header("X-Plex-Client-Identifier", clientId)
                .build()
        )
        return Http.json.decodeFromString<PlexResponse>(body).container
    }

    /** Bibliothèques de séries. */
    suspend fun libraries(): List<PlexDirectory> = get("/library/sections").directories.filter { it.type == "show" }

    suspend fun shows(sectionKey: String): List<PlexItem> = get("/library/sections/$sectionKey/all?type=2").items

    /** « Reprendre » : épisodes en cours ou suivants (On Deck). */
    suspend fun onDeck(): List<PlexItem> = get("/library/onDeck").items.filter { it.type == "episode" }

    suspend fun item(ratingKey: String): PlexItem? = get("/library/metadata/$ratingKey").items.firstOrNull()

    /** Tous les épisodes d'une série, dans l'ordre. */
    suspend fun episodes(ratingKey: String): List<PlexItem> = get("/library/metadata/$ratingKey/allLeaves").items

    /** Image redimensionnée par le serveur (affiche, vignette d'épisode). */
    fun image(path: String?, width: Int, height: Int): String? {
        if (path.isNullOrBlank() || serverUri.isBlank()) return null
        val encoded = URLEncoder.encode(path, "UTF-8")
        return "$serverUri/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=$encoded&X-Plex-Token=$serverToken"
    }
}
