package com.dakyub.crunchymal.data

import android.os.SystemClock
import java.util.Locale

/**
 * Journal de performances (en mémoire, depuis le lancement de l'app) : durée des requêtes HTTP par service,
 * attente dans les files de recherche et derrière la limite de débit MAL, temps de chargement des onglets.
 * Envoyé sur GitHub par « Benchmark de chargement » (Réglages → Diagnostics). Aucun identifiant n'y figure :
 * seulement des hôtes, des chemins d'URL sans paramètres (numéros remplacés par *) et des durées.
 */
object Perf {
    private class Sample(val at: Long, val group: String, val label: String, val ms: Long, val extra: String)

    private val samples = ArrayDeque<Sample>()
    private val start = SystemClock.elapsedRealtime()
    private val queues = mutableListOf<PriorityWorkQueue>()

    fun now(): Long = SystemClock.elapsedRealtime()

    @Synchronized
    fun record(group: String, label: String, ms: Long, extra: String = "") {
        samples.addLast(Sample(now() - start, group, label, ms, extra))
        while (samples.size > MAX) samples.removeFirst()
    }

    /** Requête HTTP terminée : hôte + chemin normalisé, code, taille de la réponse. */
    fun http(url: okhttp3.HttpUrl, code: Int, bytes: Int, ms: Long) {
        val path = url.pathSegments.joinToString("/") { seg -> if (seg.any { it.isDigit() } || seg.length > 24) "*" else seg }
        // Serveur Plex : l'adresse contient l'identifiant du serveur, elle n'est pas reprise.
        val host = when {
            url.host.endsWith("plex.direct") -> "serveur Plex"
            url.host.all { it.isDigit() || it == '.' } -> "réseau local"
            else -> url.host
        }
        record("HTTP $host", "/$path", ms, "$code · ${bytes / 1024} Ko")
    }

    /** Mesure [block] sous [group] / [label] (chargement d'un onglet…). */
    suspend fun <T> span(group: String, label: String, block: suspend () -> T): T {
        val t0 = now()
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            record(group, label, now() - t0, if (ok) "" else "échec")
        }
    }

    @Synchronized
    fun register(queue: PriorityWorkQueue) {
        queues += queue
    }

    @Synchronized
    fun queueStates(): List<String> = queues.map { it.snapshot() }

    @Synchronized
    fun report(): List<String> {
        val out = mutableListOf<String>()
        out += "Journal depuis ${(now() - start) / 1000} s (${samples.size} mesures, ${MAX} au plus)"
        out += ""
        out += "== Files de recherche (en ce moment) =="
        queues.forEach { out += it.snapshot() }
        out += ""
        out += "== Synthèse par groupe (n · médiane · p90 · max, en ms) =="
        samples.groupBy { it.group to it.label }
            .entries.sortedWith(compareBy({ it.key.first }, { -it.value.size }))
            .forEach { (key, list) ->
                val ms = list.map { it.ms }.sorted()
                val failures = list.count { it.extra == "échec" || it.extra.startsWith("-1") || it.extra.firstOrNull() in '4'..'5' }
                out += String.format(
                    Locale.FRANCE, "%-28s %-38s n=%-4d med=%-6d p90=%-6d max=%-6d%s",
                    key.first.take(28), key.second.take(38), ms.size, ms[ms.size / 2], ms[(ms.size * 9 / 10).coerceAtMost(ms.size - 1)], ms.last(),
                    if (failures > 0) " échecs=$failures" else "",
                )
            }
        out += ""
        out += "== 200 dernières mesures (s depuis le lancement · groupe · libellé · ms · détail) =="
        samples.toList().takeLast(200).forEach { s ->
            out += String.format(Locale.FRANCE, "%7.1f  %-24s %-36s %6d  %s", s.at / 1000.0, s.group.take(24), s.label.take(36), s.ms, s.extra)
        }
        return out
    }

    private const val MAX = 3000
}
