package com.dakyub.crunchymal.data

import android.content.Context
import android.widget.Toast
import com.dakyub.crunchymal.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class CreatedIssue(val number: Int = 0, @SerialName("html_url") val htmlUrl: String = "")

/**
 * Envoie un diagnostic sous forme d'issue GitHub dans le dépôt de l'app, pour qu'il soit lu
 * directement (au lieu de photos de l'écran). Nécessite un jeton GitHub (permission Issues).
 */
class DiagnosticsUploader(context: Context, private val settings: Settings) {
    private val appContext = context.applicationContext
    // Portée de l'app : l'envoi continue même si l'écran Réglages est quitté (changement d'onglet…).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val configured: Boolean get() = settings.githubToken.isNotBlank()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Dernier diagnostic préparé (titre, lignes), pour l'afficher aussi à l'écran. */
    private val _last = MutableStateFlow<Pair<String, List<String>>?>(null)
    val last: StateFlow<Pair<String, List<String>>?> = _last

    /** Prépare le contenu ([lines], en arrière-plan) puis l'envoie sur GitHub. */
    fun launch(title: String, lines: suspend () -> List<String>) {
        if (!configured) {
            _status.value = "Ajoute d'abord un jeton GitHub (Réglages → Diagnostics) pour l'envoi automatique."
            return
        }
        _status.value = "Préparation de « $title »…"
        scope.launch {
            val content = runCatching { withContext(Dispatchers.IO) { lines() } }
                .getOrElse { listOf("Erreur pendant la préparation : ${it.javaClass.simpleName} ${it.message}") }
            _last.value = title to content
            _status.value = "Envoi de « $title » sur GitHub…"
            val result = runCatching { send(title, content) }
                .fold({ "« $title » envoyé sur GitHub : issue $it" }, { "Échec de l'envoi sur GitHub : ${it.message?.take(150)}" })
            _status.value = result
            Toast.makeText(appContext, result, Toast.LENGTH_LONG).show()
        }
    }

    /** Crée l'issue et renvoie son numéro (ex. "#12"), ou lève une exception. */
    suspend fun send(title: String, lines: List<String>): String {
        val text = lines.joinToString("\n")
        val body = buildString {
            append("Diagnostic envoyé par CrunchyMAL (build ${BuildConfig.VERSION_CODE}).\n\n```\n")
            append(text.take(60_000))
            append("\n```\n")
        }
        val json = buildJsonObject {
            put("title", "[Diagnostic] $title")
            put("body", body)
        }.toString()
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/issues")
            .header("Authorization", "Bearer ${settings.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val created = Http.json.decodeFromString<CreatedIssue>(Http.client.fetch(request))
        return "#${created.number}"
    }
}
