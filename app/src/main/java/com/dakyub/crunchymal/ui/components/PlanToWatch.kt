package com.dakyub.crunchymal.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.mal.MalStatuses
import kotlinx.coroutines.launch

/** Ajoute l'anime à « À voir » sur MAL (s'il n'est pas déjà dans la liste) et l'annonce par un message. */
suspend fun Graph.addToPlanToWatch(context: Context, malId: Int, title: String) {
    if (!malAuth.loggedIn.value) {
        Toast.makeText(context, "Connecte ton compte MAL dans Réglages pour ajouter des séries à voir.", Toast.LENGTH_LONG).show()
        return
    }
    runCatching { mal.addToPlanToWatch(malId) }
        .onSuccess { existing ->
            val message = if (existing == null) "« $title » ajoutée à « À voir » sur MAL"
            else "« $title » est déjà dans ta liste MAL (${MalStatuses.firstOrNull { it.first == existing }?.second ?: existing})"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        .onFailure { Toast.makeText(context, "Ajout impossible : ${it.message}", Toast.LENGTH_LONG).show() }
}

/** Bouton « + À voir » des fiches ; rien si la série est déjà vue ou en cours sur MAL. */
@Composable
fun PlanToWatchButton(malKey: String, title: String) {
    val graph = LocalGraph.current
    val loggedIn by graph.malAuth.loggedIn.collectAsState()
    val records by graph.mal.records.collectAsState()
    val statuses by graph.mal.myListStatuses.collectAsState()
    val malId = records[malKey]?.malId
    if (!loggedIn || malId == null) return
    val status = statuses[malId]
    if (status != null && status != "plan_to_watch") return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(
        enabled = status == null && !busy,
        onClick = {
            busy = true
            scope.launch {
                graph.addToPlanToWatch(context, malId, title)
                busy = false
            }
        },
    ) { Text(if (status == "plan_to_watch") "✓ À voir" else "+ À voir") }
}
