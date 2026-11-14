package com.dakyub.crunchymal.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Ce que l'utilisateur regarde : l'onglet ou l'écran au premier plan, et les fiches MAL dont le badge est
 * affiché. Sert aux files [PriorityWorkQueue] à choisir quoi chercher en premier.
 */
object Foreground {
    private val _owner = MutableStateFlow<String?>(null)
    /** Onglet ou écran au premier plan (« tab:2 », « page »…). */
    val owner: StateFlow<String?> = _owner

    /** Clés affichées à l'écran → nombre de badges qui les montrent. */
    private val visible = ConcurrentHashMap<String, Int>()

    fun set(owner: String) {
        _owner.value = owner
    }

    fun show(key: String) {
        visible.merge(key, 1, Int::plus)
    }

    fun hide(key: String) {
        visible.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
    }

    fun isVisible(key: String): Boolean = (visible[key] ?: 0) > 0
}

/**
 * File de recherches de fond (notes MAL, disponibilités, plateformes) servie dans cet ordre :
 * 1. ce qui est affiché à l'écran ([Foreground.isVisible]),
 * 2. ce que l'onglet au premier plan a demandé,
 * 3. le travail de fond sans onglet (tâches planifiées…),
 * en prenant à chaque fois la demande la plus récente : les cartes que l'on regarde passent avant celles
 * demandées plus tôt. Les demandes d'un onglet quitté sont mises en pause (ni lancées ni perdues) et
 * reprennent quand on y revient ; une nouvelle demande de la même clé les rattache à l'onglet courant.
 */
class PriorityWorkQueue(private val scope: CoroutineScope, private val workers: Int, private val name: String) {
    private class Task(val key: String, var owner: String?, var seq: Long, var work: suspend () -> Unit, val submittedAt: Long = Perf.now())

    private val tasks = LinkedHashMap<String, Task>()
    private val running = HashSet<String>()
    private var seq = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        Perf.register(this)
        repeat(workers) {
            scope.launch {
                while (true) {
                    val task = take()
                    if (task == null) {
                        wake.receive()
                        continue
                    }
                    val started = Perf.now()
                    Perf.record("File $name", "attente", started - task.submittedAt)
                    try {
                        task.work()
                        Perf.record("File $name", "travail", Perf.now() - started)
                    } catch (e: Exception) {
                        Log.w("PriorityWorkQueue", "$name : échec pour ${task.key}", e)
                    } finally {
                        synchronized(this@PriorityWorkQueue) { running.remove(task.key) }
                        wake.trySend(Unit)
                    }
                }
            }
        }
        // Changement d'onglet : les demandes en pause de l'onglet retrouvé peuvent repartir.
        scope.launch { Foreground.owner.collect { wake.trySend(Unit) } }
    }

    /**
     * Ajoute une recherche ([work]) pour [key], sauf si elle est en cours. Déjà en attente : elle repasse
     * en tête et appartient désormais à l'onglet courant. [background] : travail sans onglet (jamais en pause).
     */
    fun submit(key: String, background: Boolean = false, work: suspend () -> Unit) {
        val owner = if (background) null else Foreground.owner.value ?: OWNERLESS
        synchronized(this) {
            if (key in running) return
            val existing = tasks[key]
            if (existing != null) {
                existing.seq = ++seq
                // Une demande d'onglet l'emporte sur une demande de fond (pour ne pas la mettre en pause).
                if (!background || existing.owner == null) existing.owner = owner
                existing.work = work
            } else {
                tasks[key] = Task(key, owner, ++seq, work)
            }
        }
        wake.trySend(Unit)
    }

    /** État de la file pour le journal de performances. */
    @Synchronized
    fun snapshot(): String {
        val current = Foreground.owner.value ?: OWNERLESS
        val visible = tasks.values.count { Foreground.isVisible(it.key) }
        val tab = tasks.values.count { !Foreground.isVisible(it.key) && (it.owner == current || it.owner == OWNERLESS) }
        val background = tasks.values.count { it.owner == null }
        val paused = tasks.size - visible - tab - background
        return "File $name : $workers exécutants, ${running.size} en cours, ${tasks.size} en attente " +
            "(à l'écran $visible, onglet $tab, fond $background, en pause $paused)"
    }

    /** En attente ou en cours pour [key]. */
    fun isPending(key: String): Boolean = synchronized(this) { key in running || key in tasks }

    @Synchronized
    private fun take(): Task? {
        val current = Foreground.owner.value ?: OWNERLESS
        fun rank(task: Task): Int? = when {
            Foreground.isVisible(task.key) -> 0
            task.owner == current || task.owner == OWNERLESS -> 1
            task.owner == null -> 2
            else -> null // onglet quitté : en pause
        }
        val best = tasks.values
            .mapNotNull { task -> rank(task)?.let { it to task } }
            .minWithOrNull(compareBy<Pair<Int, Task>>({ it.first }, { -it.second.seq }))
            ?.second ?: return null
        tasks.remove(best.key)
        running += best.key
        // D'autres demandes attendent : on réveille un autre exécutant.
        if (tasks.isNotEmpty()) wake.trySend(Unit)
        return best
    }

    private companion object {
        /** Demande faite avant que l'interface ait indiqué l'onglet au premier plan. */
        const val OWNERLESS = "-"
    }
}
