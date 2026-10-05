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
