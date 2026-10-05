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

    /** Format de lien direct choisi pour l'app officielle (null = automatique). */
    var linkFormat: com.dakyub.crunchymal.LinkFormat?
        get() = prefs.getString("link_format", null)?.let { name ->
            com.dakyub.crunchymal.LinkFormat.entries.firstOrNull { it.name == name }
        }
        set(value) = prefs.edit().putString("link_format", value?.name).apply()

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
