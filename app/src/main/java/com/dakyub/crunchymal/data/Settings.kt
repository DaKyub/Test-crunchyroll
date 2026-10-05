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
