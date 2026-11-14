package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.data.adn.AdnApi
import com.dakyub.crunchymal.data.mal.MalRepository
import com.dakyub.crunchymal.data.ratings.RatingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Un champ de texte des réglages ; [value] est vide pour un champ secret (jamais renvoyé). */
@Serializable
data class SettingsField(
    val key: String,
    val label: String,
    val section: String,
    val value: String = "",
    val secret: Boolean = false,
    /** Champ secret déjà rempli (affiché « enregistré »). */
    val filled: Boolean = false,
    val hint: String? = null,
)

/**
 * Tous les champs de texte des réglages au même endroit : l'écran « Saisie des champs » de la TV et la page
 * web du téléphone (/fields) les lisent ici et les enregistrent avec les mêmes effets que les boutons des réglages.
 */
class SettingsFields(
    private val settings: Settings,
    private val companions: CompanionsRepository,
    private val adn: AdnApi,
    private val mal: MalRepository,
    private val ratings: RatingsRepository,
    private val watchPlatforms: WatchPlatformsRepository,
    private val diagnostics: DiagnosticsUploader,
) {
    /** Incrémenté à chaque enregistrement, pour que l'écran de la TV relise les valeurs. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun current(): List<SettingsField> = listOf(
        SettingsField(PEOPLE, "Prénoms (séparés par des virgules)", "Vu seul / vu avec…", companions.people.value.joinToString(", ")),
        SettingsField(ADN_USER, "E-mail ou identifiant ADN", "ADN", adn.username,
            hint = if (adn.loggedIn.value) "Connecté : remplir les deux champs pour changer de compte" else null),
        SettingsField(ADN_PASSWORD, "Mot de passe ADN", "ADN", secret = true),
        SettingsField(OMDB, "Clé OMDb (8 caractères)", "Notes des épisodes", settings.omdbKey),
        SettingsField(TMDB, "Clé API TMDB (v3) ou jeton de lecture (v4)", "Notes des épisodes", settings.tmdbKey),
        SettingsField(MAL_ID, "Client ID MyAnimeList (32 caractères)", "MyAnimeList", settings.malClientIdOverride,
            hint = "Laisser vide pour celui du build"),
        SettingsField(MAL_SECRET, "Client Secret MAL (seulement si ton app MAL en affiche un)", "MyAnimeList",
            secret = true, filled = settings.malClientSecret.isNotBlank()),
        SettingsField(GITHUB, "Jeton GitHub (github_pat_…)", "Diagnostics", secret = true,
            filled = settings.githubToken.isNotBlank(), hint = diagnostics.maskedToken()),
        SettingsField(BASIC, "Identifiants client Crunchyroll : Basic xxxxxxxx=", "Avancé", settings.basicAuthOverride,
            hint = "Laisser vide pour la valeur du build"),
        SettingsField(USER_AGENT, "User-Agent Crunchyroll", "Avancé", settings.userAgentOverride,
            hint = "Ex. Crunchyroll/ANDROIDTV/3.xx.x_xxxxx (Android 14; en-US; Chromecast)"),
    )

    /**
     * Enregistre les champs modifiés ([changes] : clé → valeur ; un champ absent n'est pas touché, un champ secret
     * vide non plus). Renvoie le compte rendu, une ligne par réglage.
     */
    suspend fun apply(changes: Map<String, String>): List<String> {
        val report = mutableListOf<String>()
        fun has(key: String) = changes.containsKey(key)
        fun value(key: String) = changes[key].orEmpty().trim()

        if (has(PEOPLE)) {
            val names = value(PEOPLE).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            companions.setPeople(names)
            report += "✓ Prénoms : " + names.joinToString(", ").ifBlank { "aucun" }
        }
        if (has(OMDB) || has(TMDB)) {
            val tmdbChanged = has(TMDB) && settings.tmdbKey != value(TMDB)
            if (has(OMDB)) settings.omdbKey = value(OMDB)
            if (has(TMDB)) settings.tmdbKey = value(TMDB)
            ratings.clear()
            // Les plateformes « où regarder » dépendent aussi de la clé TMDB.
            if (tmdbChanged) runCatching { watchPlatforms.clear() }
            report += "✓ Clés des notes enregistrées"
        }
        if (has(MAL_ID)) {
            settings.malClientIdOverride = value(MAL_ID)
            mal.configChanged()
            report += "✓ Client ID MAL enregistré"
        }
        if (value(MAL_SECRET).isNotEmpty()) {
            settings.malClientSecret = value(MAL_SECRET)
            report += "✓ Client Secret MAL enregistré"
        }
        if (has(BASIC) || has(USER_AGENT)) {
            if (has(BASIC)) settings.basicAuthOverride = value(BASIC)
            if (has(USER_AGENT)) settings.userAgentOverride = value(USER_AGENT)
            report += "✓ Identifiants Crunchyroll avancés enregistrés"
        }
        val adnUser = if (has(ADN_USER)) value(ADN_USER) else adn.username
        val adnPassword = changes[ADN_PASSWORD].orEmpty()
        if (adnPassword.isNotEmpty()) {
            report += if (adnUser.isBlank()) "✗ ADN : identifiant manquant" else {
                val error = runCatching { adn.login(adnUser, adnPassword) }.getOrElse { it.message }
                error?.let { "✗ ADN : échec de connexion ($it)" } ?: "✓ Connecté à ADN"
            }
        } else if (has(ADN_USER) && adnUser != adn.username) {
            report += "ADN : saisis aussi le mot de passe pour te connecter"
        }
        if (value(GITHUB).isNotEmpty()) {
            report += diagnostics.setTokenAndTest(value(GITHUB)).lines()
        }
        if (report.isEmpty()) report += "Aucun champ modifié"
        _revision.value++
        return report
    }

    companion object {
        const val PEOPLE = "people"
        const val ADN_USER = "adn_user"
        const val ADN_PASSWORD = "adn_password"
        const val OMDB = "omdb"
        const val TMDB = "tmdb"
        const val MAL_ID = "mal_client_id"
        const val MAL_SECRET = "mal_secret"
        const val GITHUB = "github_token"
        const val BASIC = "basic"
        const val USER_AGENT = "user_agent"
    }
}
