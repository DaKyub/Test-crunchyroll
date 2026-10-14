package com.dakyub.crunchymal.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lecture des caches disque en arrière-plan : le démarrage n'attend plus le décodage des fichiers JSON.
 * L'état part vide et reçoit le contenu du fichier dès qu'il est lu (ce qui a été ajouté entre-temps garde
 * la priorité). Les recherches attendent la fin de la lecture pour ne pas refaire ce qui est en cache.
 */
object CacheLoads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = CopyOnWriteArrayList<Job>()

    fun <K, V> into(state: MutableStateFlow<Map<K, V>>, load: () -> Map<K, V>): Job =
        scope.launch {
            val saved = runCatching(load).getOrDefault(emptyMap())
            if (saved.isNotEmpty()) state.update { saved + it }
        }.also { jobs += it }

    /** Attend que tous les caches soient lus (tâches de fond lancées à froid). */
    suspend fun awaitAll() = jobs.joinAll()
}

/**
 * Écriture groupée d'un cache JSON sur le disque : au plus une écriture toutes les [delayMs], toujours avec
 * la dernière valeur ([encode] est appelé au moment d'écrire). Écrit dans un fichier temporaire puis le
 * renomme : un arrêt brutal de l'app ne laisse jamais de cache à moitié écrit.
 */
class CacheWriter(
    private val file: File,
    private val delayMs: Long = 2_000,
    /** Lecture du cache en cours : on ne réécrit pas le fichier avant de l'avoir lu. */
    private val ready: Job? = null,
    private val encode: () -> String,
) {
    private val pending = AtomicBoolean(false)

    fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMs)
            ready?.join()
            pending.set(false)
            writeNow()
        }
    }

    @Synchronized
    fun writeNow() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
