package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.BuildConfig
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
class DiagnosticsUploader(private val settings: Settings) {
    val configured: Boolean get() = settings.githubToken.isNotBlank()

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
