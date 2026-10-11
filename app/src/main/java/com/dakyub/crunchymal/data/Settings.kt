package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.BuildConfig
import com.dakyub.crunchymal.data.crunchyroll.CrConfig
import java.util.UUID

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Langue des titres / descriptions renvoyés par Crunchyroll. */
    var locale: String
        get() = prefs.getString("locale", "fr-FR")!!
        set(value) = prefs.edit().putString("locale", value).apply()

    var preferredAudio: String
        get() = prefs.getString("audio", "ja-JP")!!
        set(value) = prefs.edit().putString("audio", value).apply()

    /** Surcharge de l'en-tête Basic (les identifiants client de l'app TV changent de temps en temps). */
    var basicAuthOverride: String
        get() = prefs.getString("basic_auth", "")!!
        set(value) = prefs.edit().putString("basic_auth", value.trim()).apply()

    var userAgentOverride: String
        get() = prefs.getString("user_agent", "")!!
        set(value) = prefs.edit().putString("user_agent", value.trim()).apply()

    /** Services affichés (Crunchyroll, ADN ou les deux). */
    var providers: Set<Provider>
        get() = prefs.getStringSet("providers", null)
            ?.mapNotNull { name -> Provider.entries.firstOrNull { it.name == name } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(Provider.CRUNCHYROLL)
        set(value) = prefs.edit().putStringSet("providers", value.map { it.name }.toSet()).apply()

    /** Format de lien choisi pour ouvrir un contenu dans l'app ADN (null = ouvrir l'app). */
    var adnLinkTemplate: String?
        get() = prefs.getString("adn_link_template", null)
        set(value) = prefs.edit().putString("adn_link_template", value).apply()

    /** Jeton GitHub (permission Issues sur le dépôt de l'app) pour l'envoi des diagnostics. */
    var githubToken: String
        get() = prefs.getString("github_token", "")!!
        set(value) = prefs.edit().putString("github_token", value.trim()).apply()

    /** Clé OMDb (notes IMDb des épisodes), gratuite sur omdbapi.com. */
    var omdbKey: String
        get() = prefs.getString("omdb_key", "")!!
        set(value) = prefs.edit().putString("omdb_key", value.trim()).apply()

    /** Clé TMDB (clé API v3 ou jeton de lecture v4), gratuite sur themoviedb.org. */
    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "")!!
        set(value) = prefs.edit().putString("tmdb_key", value.trim()).apply()

    /** Client ID de l'API officielle MyAnimeList (myanimelist.net/apiconfig). */
    var malClientIdOverride: String
        get() = prefs.getString("mal_client_id", "")!!
        set(value) = prefs.edit().putString("mal_client_id", value.trim()).apply()

    /** Client Secret MAL (facultatif : seulement si l'app MAL en a un). */
    var malClientSecret: String
        get() = prefs.getString("mal_client_secret", "")!!
        set(value) = prefs.edit().putString("mal_client_secret", value.trim()).apply()

    /** Mettre à jour la liste MAL d'après ce qui est regardé sur Crunchyroll / ADN. */
    var malAutoSync: Boolean
        get() = prefs.getBoolean("mal_auto_sync", true)
        set(value) = prefs.edit().putBoolean("mal_auto_sync", value).apply()

    /** Chaîne « CrunchyMAL » sur l'écran d'accueil Android TV. */
    var homeChannel: Boolean
        get() = prefs.getBoolean("home_channel", true)
        set(value) = prefs.edit().putBoolean("home_channel", value).apply()

    /** Notification quand un anime « À voir » de la liste MAL arrive sur Crunchyroll / ADN. */
    var arrivalAlerts: Boolean
        get() = prefs.getBoolean("arrival_alerts", true)
        set(value) = prefs.edit().putBoolean("arrival_alerts", value).apply()

    /** Format de lien utilisé pour ouvrir un épisode dans l'app Plex (voir PlexApp.formats). */
    var plexLinkFormat: Int
        get() = prefs.getInt("plex_link_format", 0)
        set(value) = prefs.edit().putInt("plex_link_format", value).apply()

    /** Notification quand une série suivie sort un nouvel épisode. */
    var newEpisodeNotifications: Boolean
        get() = prefs.getBoolean("new_episode_notifications", true)
        set(value) = prefs.edit().putBoolean("new_episode_notifications", value).apply()

    /** Prénoms des personnes avec qui les séries sont regardées (marques « vu avec »). */
    var companions: List<String>
        get() = prefs.getString("companions", "")!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("companions", value.joinToString("\n")).apply()

    val malClientId: String
        get() = malClientIdOverride.ifBlank { BuildConfig.MAL_CLIENT_ID }

    val basicAuth: String
        get() = basicAuthOverride.ifBlank { BuildConfig.CR_BASIC_AUTH }.let {
            if (it.isBlank() || it.startsWith("Basic ")) it else "Basic $it"
        }

    val hasClientCredentials: Boolean get() = basicAuth.isNotBlank()

    val userAgent: String
        get() = userAgentOverride.ifBlank { CrConfig.DEFAULT_USER_AGENT }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
}
