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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Dernier diagnostic préparé (titre, lignes), pour l'afficher aussi à l'écran. */
    private val _last = MutableStateFlow<Pair<String, List<String>>?>(null)
    val last: StateFlow<Pair<String, List<String>>?> = _last

    /** Incrémenté quand le jeton change ailleurs que dans le champ (page web du téléphone). */
    private val _tokenRevision = MutableStateFlow(0)
    val tokenRevision: StateFlow<Int> = _tokenRevision

    /** Enregistre le jeton (nettoyé) puis le teste ; renvoie le compte rendu du test. */
    suspend fun setTokenAndTest(token: String): String {
        settings.githubToken = token
        _tokenRevision.value++
        return testToken().also { _status.value = it }
    }

    /** Début et fin du jeton enregistré, pour le comparer à celui affiché par GitHub sans le dévoiler. */
    fun maskedToken(): String {
        val t = settings.githubToken
        if (t.isBlank()) return "aucun jeton enregistré"
        val head = t.take(if (t.startsWith("github_pat_")) 15 else 8)
        return "$head…${t.takeLast(4)} (${t.length} caractères)"
    }

    /** Lance [testToken] et affiche le résultat dans le statut. */
    fun launchTest() {
        _status.value = "Test du jeton GitHub…"
        scope.launch { _status.value = testToken() }
    }

    /**
     * Vérifie le jeton auprès de GitHub (lecture du dépôt de l'app, rien n'est créé) et explique le résultat :
     * jeton invalide, dépôt inaccessible, permission Issues manquante, date d'expiration.
     */
    suspend fun testToken(): String = withContext(Dispatchers.IO) {
        val token = settings.githubToken
        val lines = mutableListOf("Jeton enregistré : ${maskedToken()}")
        if (token.isBlank()) return@withContext lines.joinToString("\n")
        if (!token.startsWith("github_pat_") && !token.startsWith("ghp_")) {
            lines += "⚠ Il ne commence ni par « github_pat_ » ni par « ghp_ » : il est probablement incomplet."
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                lines += explain(response, body)
            }
        }.onFailure { lines += "Impossible de joindre GitHub : ${it.javaClass.simpleName} ${it.message}" }
        lines.joinToString("\n")
    }

    private fun explain(response: Response, body: String): List<String> {
        val out = mutableListOf<String>()
        response.header("github-authentication-token-expiration")?.let { out += "Expire le : $it" }
        when (response.code) {
            200 -> {
                val json = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
                val push = (json?.get("permissions") as? JsonObject)?.get("push")?.jsonPrimitive?.booleanOrNull
                out += "✓ GitHub accepte le jeton et voit le dépôt ${BuildConfig.UPDATE_REPO}."
                if (push == false) out += "Accès en lecture seule : vérifie la permission « Issues : Read and write »."
                out += "Si l'envoi échoue encore en 403, c'est la permission Issues qui manque."
            }
            401 -> out += "✗ 401 : GitHub ne reconnaît pas ce jeton (mal copié, expiré ou révoqué). " +
                "Compare le début et la fin ci-dessus avec ceux affichés sur GitHub, ou saisis-le depuis le téléphone."
            403 -> out += "✗ 403 : jeton reconnu mais accès refusé (${body.take(150)})."
            404 -> out += "✗ 404 : jeton reconnu mais il n'a pas accès au dépôt ${BuildConfig.UPDATE_REPO}. " +
                "Dans les réglages du jeton, choisis « Only select repositories » → Test-crunchyroll."
            else -> out += "✗ HTTP ${response.code} : ${body.take(150)}"
        }
        return out
    }

    /** Prépare le contenu ([lines], en arrière-plan) puis l'envoie sur GitHub. */
    fun launch(title: String, lines: suspend () -> List<String>) {
        if (!configured) {
            _status.value = "Ajoute d'abord un jeton GitHub (Réglages → Diagnostics) pour l'envoi automatique."
            return
        }
        // Un seul envoi à la fois : plusieurs appuis lançaient autant de benchmarks en même temps, qui se gênaient.
        if (!busy.compareAndSet(false, true)) {
            _status.value = "Un envoi est déjà en cours, patiente quelques secondes…"
            return
        }
        _status.value = "Préparation de « $title »…"
        scope.launch {
            val content = runCatching { withContext(Dispatchers.IO) { lines() } }
                .getOrElse { listOf("Erreur pendant la préparation : ${it.javaClass.simpleName} ${it.message}") }
            _last.value = title to content
            _status.value = "Envoi de « $title » sur GitHub…"
            val result = runCatching { send(title, content) }
                .fold({ "« $title » envoyé sur GitHub : issue $it" }, { failure(it) })
            _status.value = result
            busy.set(false)
            Toast.makeText(appContext, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun failure(error: Throwable): String = when ((error as? HttpException)?.code) {
        401 -> "Échec de l'envoi : GitHub refuse le jeton (401). Jeton enregistré : ${maskedToken()}. " +
            "Utilise « Tester le jeton » ou saisis-le depuis le téléphone."
        403 -> "Échec de l'envoi (403) : le jeton n'a pas la permission « Issues : Read and write »."
        404 -> "Échec de l'envoi (404) : le jeton n'a pas accès au dépôt ${BuildConfig.UPDATE_REPO}."
        else -> "Échec de l'envoi sur GitHub : ${error.message?.take(150)}"
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
