package com.dakyub.crunchymal.server

import android.content.Context
import android.util.Log
import com.dakyub.crunchymal.data.CompanionTags
import com.dakyub.crunchymal.data.CompanionsRepository
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.mal.MalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/** Avancement de l'enregistrement des marques sur MAL. */
@Serializable
data class TagJob(
    val total: Int = 0,
    val done: Int = 0,
    val running: Boolean = false,
    val failed: List<String> = emptyList(),
    val failedIds: List<Int> = emptyList(),
)

@Serializable
private data class WebItem(
    val id: Int,
    /** Titre affiché (anglais si connu), titre d'origine s'il diffère. */
    val t: String,
    val o: String? = null,
    val p: String? = null,
    val s: String,
    val m: Double? = null,
    val y: Int? = null,
    val u: String? = null,
    /** Personnes avec qui la série a été vue. */
    val w: List<String>,
)

@Serializable
private data class WebData(val alone: String, val people: List<String>, val items: List<WebItem>, val job: TagJob)

@Serializable
private data class WebChange(val id: Int, val w: List<String> = emptyList())

@Serializable
private data class WebSave(val people: List<String> = emptyList(), val changes: List<WebChange> = emptyList())

@Serializable
private data class WebError(val error: String)

/**
 * Petit serveur HTTP sur le réseau local : le téléphone (même Wi-Fi) ouvre une page qui liste toute la
 * liste MAL avec une case par personne, pour marquer d'un coup « vu seul » / « vu avec Prénom ».
 * Un jeton aléatoire, renouvelé à chaque démarrage, est exigé dans l'adresse.
 */
class TagServer(
    private val context: Context,
    private val mal: MalRepository,
    private val companions: CompanionsRepository,
) {
    data class State(val url: String? = null, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: ServerSocket? = null
    private var token = ""
    @Volatile private var lastRequest = 0L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _job = MutableStateFlow(TagJob())
    val job: StateFlow<TagJob> = _job

    /** Démarre le serveur (en arrière-plan : ouvrir un port est interdit sur le fil principal). */
    fun start() {
        scope.launch { open() }
    }

    @Synchronized
    private fun open() {
        if (socket != null) return
        val ip = LocalNetwork.ipv4()
        if (ip == null) {
            _state.value = State(error = "Aucune adresse sur le réseau local (Wi-Fi ou Ethernet) n'a été trouvée.")
            return
        }
        val server = runCatching { ServerSocket(PORT) }.recoverCatching { ServerSocket(0) }.getOrElse {
            _state.value = State(error = "Impossible d'ouvrir le serveur : ${it.message}")
            return
        }
        socket = server
        token = randomToken()
        lastRequest = System.currentTimeMillis()
        _state.value = State(url = "http://$ip:${server.localPort}/?k=$token")
        scope.launch {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) }.onFailure { Log.w("TagServer", "Requête en échec", it) } }
            }
        }
        // Arrêt automatique après une heure sans requête.
        scope.launch {
            while (socket === server) {
                delay(60_000)
                if (System.currentTimeMillis() - lastRequest > IDLE_STOP_MS && !_job.value.running) stop()
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        _state.value = State()
    }

    private suspend fun handle(client: Socket) {
        client.use { s -> serve(s) }
    }

    private suspend fun serve(s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        val method = requestLine.getOrNull(0).orEmpty()
        val target = requestLine.getOrNull(1).orEmpty()
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return respond(s, 413, "text/plain", "Trop gros".toByteArray())
        val body = if (length > 0) String(readExactly(input, length), Charsets.UTF_8) else ""
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        if (path == "/favicon.ico") return respond(s, 404, "text/plain", ByteArray(0))
        if (query["k"] != token) {
            return respond(s, 403, "text/html; charset=utf-8", EXPIRED.toByteArray())
        }
        lastRequest = System.currentTimeMillis()
        when {
            method == "GET" && path == "/" ->
                respond(s, 200, "text/html; charset=utf-8", context.assets.open("tags.html").use { it.readBytes() })
            method == "GET" && path == "/api/data" -> json(s) { Http.json.encodeToString(data()) }
            method == "GET" && path == "/api/job" -> json(s) { Http.json.encodeToString(_job.value) }
            method == "POST" && path == "/api/save" -> json(s) { save(Http.json.decodeFromString<WebSave>(body)) }
            else -> respond(s, 404, "text/plain", "Introuvable".toByteArray())
        }
    }

    private suspend fun json(s: Socket, produce: suspend () -> String) {
        val result = runCatching { produce() }
        val (code, text) = result.fold(
            onSuccess = { 200 to it },
            onFailure = { 500 to Http.json.encodeToString(WebError(it.message ?: it.javaClass.simpleName)) },
        )
        respond(s, code, "application/json; charset=utf-8", text.toByteArray())
    }

    private suspend fun data(): WebData {
        val list = mal.myListCached(maxAgeMs = 2 * 60_000)
        val items = list.map { e ->
            val title = e.anime.titleEnglish ?: e.anime.title
            WebItem(
                id = e.anime.malId,
                t = title,
                o = e.anime.title.takeIf { it != title },
                // Affiche moyenne plutôt que grande : la page en charge des centaines.
                p = e.anime.pictureUrl?.replace(Regex("""l\.(jpg|jpeg|webp)$"""), ".$1"),
                s = e.status.status ?: "plan_to_watch",
                m = e.anime.score,
                y = e.anime.startYear,
                u = e.status.updatedAt,
                // Marques lues dans la liste elle-même (les flux dérivés peuvent avoir un temps de retard).
                w = companions.peopleIn(e.status.tags).toList(),
            )
        }
        val people = (companions.people.value + items.flatMap { it.w }.filter { it != CompanionTags.ALONE })
            .distinctBy { it.lowercase() }
        return WebData(CompanionTags.ALONE, people, items, _job.value)
    }

    private fun save(request: WebSave): String {
        request.people.forEach { companions.addPerson(it) }
        if (_job.value.running) throw IllegalStateException("Un enregistrement est déjà en cours.")
        val changes = request.changes.distinctBy { it.id }
        _job.value = TagJob(total = changes.size, running = changes.isNotEmpty())
        if (changes.isNotEmpty()) scope.launch { applyChanges(changes) }
        return Http.json.encodeToString(_job.value)
    }

    private suspend fun applyChanges(changes: List<WebChange>) {
        val entries = runCatching { mal.myListCached(maxAgeMs = 10 * 60_000) }.getOrDefault(emptyList()).associateBy { it.anime.malId }
        changes.forEach { change ->
            val entry = entries[change.id]
            runCatching {
                companions.set(
                    malId = change.id,
                    people = change.w,
                    currentTags = mal.myListTags.value[change.id] ?: entry?.status?.tags.orEmpty(),
                    statusIfNew = if (entry == null) "completed" else null,
                )
            }.onFailure { error ->
                Log.w("TagServer", "Échec pour ${change.id}", error)
                val title = entry?.anime?.let { it.titleEnglish ?: it.title } ?: "#${change.id}"
                _job.update { it.copy(failed = it.failed + "$title (${error.message})", failedIds = it.failedIds + change.id) }
            }
            _job.update { it.copy(done = it.done + 1) }
            lastRequest = System.currentTimeMillis()
            // Reste sous la limite de débit de l'API MAL.
            delay(350)
        }
        _job.update { it.copy(running = false) }
    }

    private fun respond(s: Socket, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val out = s.getOutputStream()
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    /** En-têtes de la requête (jusqu'à la ligne vide), ou null si la connexion se ferme avant. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buffer.toString(Charsets.UTF_8.name()).trimEnd()
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    private fun randomToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private companion object {
        const val PORT = 8765
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 2 * 1024 * 1024
        const val IDLE_STOP_MS = 60L * 60 * 1000
        val EXPIRED = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="font-family:sans-serif;padding:24px">Lien expiré : rouvre « Marquer depuis le téléphone » dans les
            Réglages de CrunchyMAL et scanne le nouveau QR code.</body>""".trimIndent()
    }
}
