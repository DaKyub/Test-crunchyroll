package com.dakyub.crunchymal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dakyub.crunchymal.data.Perf

/**
 * Journal de performances d'un écran : combien de temps chaque message (« Chargement… ») est resté affiché,
 * et le délai entre l'ouverture de l'écran et l'apparition des premières cartes.
 */
@Composable
fun PerfScreenTimer(group: String, message: String?, hasItems: Boolean) {
    val openedAt = remember { Perf.now() }
    var shown by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableStateOf(openedAt) }
    var itemsSeen by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        val now = Perf.now()
        shown?.let { Perf.record(group, "message « ${it.take(28)} »", now - shownAt) }
        shown = message
        shownAt = now
    }
    LaunchedEffect(hasItems) {
        if (hasItems && !itemsSeen) {
            itemsSeen = true
            Perf.record(group, "cartes affichées après ouverture", Perf.now() - openedAt)
        }
    }
    DisposableEffect(Unit) {
        onDispose { shown?.let { Perf.record(group, "message « ${it.take(28)} » (quitté)", Perf.now() - shownAt) } }
    }
}
