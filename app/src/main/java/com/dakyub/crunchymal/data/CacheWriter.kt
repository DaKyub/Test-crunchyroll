package com.dakyub.crunchymal.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Écriture groupée d'un cache JSON sur le disque : au plus une écriture toutes les [delayMs], toujours avec
 * la dernière valeur ([encode] est appelé au moment d'écrire). Écrit dans un fichier temporaire puis le
 * renomme : un arrêt brutal de l'app ne laisse jamais de cache à moitié écrit.
 */
class CacheWriter(private val file: File, private val delayMs: Long = 2_000, private val encode: () -> String) {
    private val pending = AtomicBoolean(false)

    fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMs)
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
