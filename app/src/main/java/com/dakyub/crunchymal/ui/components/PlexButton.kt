package com.dakyub.crunchymal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph

/** Bouton des fiches quand la série est sur le serveur Plex : ouvre sa fiche Plex (« Plex · 1/3 saisons »). */
@Composable
fun PlexButton(malKey: String, titles: List<String>, onOpenPlex: (String) -> Unit) {
    val graph = LocalGraph.current
    val records by graph.mal.records.collectAsState()
    val index by graph.plexLibrary.index.collectAsState()
    val match = index.find(records[malKey]?.malId, titles) ?: return
    OutlinedButton(onClick = { onOpenPlex(match.ratingKey) }) {
        Text(if (match.partial) "Plex · ${match.have}/${match.total} saisons" else "Sur Plex")
    }
}
