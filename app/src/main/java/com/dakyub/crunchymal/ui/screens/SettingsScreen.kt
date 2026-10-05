package com.dakyub.crunchymal.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.AppAnalyzer
import com.dakyub.crunchymal.CrunchyMalApp
import com.dakyub.crunchymal.data.WatchlistEntry
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.OfficialApp
import com.dakyub.crunchymal.ui.components.TvTextField
import com.dakyub.crunchymal.ui.components.UpdateButton
import com.dakyub.crunchymal.ui.components.label
import com.dakyub.crunchymal.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Locales = listOf("fr-FR" to "Français", "en-US" to "English")
private val Audios = listOf("ja-JP" to "Japonais", "fr-FR" to "Français", "en-US" to "Anglais")

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val settings = graph.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var locale by remember { mutableStateOf(settings.locale) }
    var audio by remember { mutableStateOf(settings.preferredAudio) }
    var malId by remember { mutableStateOf(settings.malClientIdOverride) }
    var linkTemplate by remember { mutableStateOf(settings.linkTemplate ?: OfficialApp.DEFAULT_TEMPLATE) }
    // Série / épisode d'exemple pour le banc d'essai : premier élément de la watchlist.
    val sample by produceState<WatchlistEntry?>(null) {
        value = runCatching { graph.watchlist.get().firstOrNull { it.nextEpisodeId != null } }.getOrNull()
    }
    var basic by remember { mutableStateOf(settings.basicAuthOverride) }
    var ua by remember { mutableStateOf(settings.userAgentOverride) }
    val resolvedActivity = remember { OfficialApp.resolve(context) }
    val activities = remember { OfficialApp.exportedActivities(context) }
    var analysis by remember { mutableStateOf<List<String>?>(null) }
    var analyzing by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var lastCrash by remember { mutableStateOf(CrunchyMalApp.lastCrash(context)) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        lastCrash?.let { crash ->
            item { Section("Dernier plantage") }
            item { Text(crash, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            item {
                OutlinedButton(onClick = {
                    CrunchyMalApp.clearCrash(context)
                    lastCrash = null
                }) { Text("Effacer le rapport") }
            }
        }
        item { Section("Mises à jour") }
        item {
            val updateState by graph.updates.state.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Version installée : build ${graph.updates.currentBuild}" + when (updateState) {
                        UpdateState.Checking -> " · vérification…"
                        UpdateState.UpToDate -> " · à jour"
                        else -> updateState.label()?.let { " · $it" } ?: ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { scope.launch { graph.updates.check() } }) { Text("Vérifier") }
                UpdateButton()
            }
        }
        item { Section("MyAnimeList") }
        item {
            Text(
                "Client ID de l'API officielle : crée-le sur myanimelist.net/apiconfig (type « other »), puis colle-le ici." +
                    if (settings.malClientIdOverride.isBlank() && settings.malClientId.isNotBlank()) " Un Client ID est déjà intégré au build." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(malId, { malId = it }, "Client ID MyAnimeList (32 caractères)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.malClientIdOverride = malId
                    graph.mal.configChanged()
                    toast("Client ID MAL enregistré")
                }) { Text("Enregistrer le Client ID") }
                OutlinedButton(onClick = { scope.launch { graph.mal.clear(); graph.mal.configChanged(); toast("Cache MAL vidé") } }) {
                    Text("Vider les notes MAL")
                }
            }
        }

        item { Section("Lecture dans l'app Crunchyroll") }
        item {
            Text(
                "Banc d'essai : « Essayer » ouvre Crunchyroll avec ce format sur « ${sample?.series?.title ?: "…"} ». " +
                    "Quand un format ouvre la bonne fiche ou l'épisode, appuie sur « Choisir ». " +
                    (resolvedActivity?.let { "Liens crunchyroll:// reçus par $it." } ?: "Liens crunchyroll:// refusés par l'app officielle."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(OfficialApp.CANDIDATES) { index, template ->
            val entry = sample
            val intent = entry?.let { OfficialApp.buildIntent(template, it.series.id, it.nextEpisodeId) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { OfficialApp.start(context, intent) }, enabled = intent != null) {
                    Text("Essayer ${index + 1}")
                }
                FilterChip(selected = linkTemplate == template, onClick = {
                    linkTemplate = template
                    settings.linkTemplate = template
                    toast("Format ${index + 1} choisi")
                }) { Text(if (linkTemplate == template) "✓ Choisi" else "Choisir") }
                Text(template, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            Text(
                if (activities.isEmpty()) "App Crunchyroll introuvable." else "Activités de l'app officielle : " + activities.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedButton(onClick = {
                analyzing = true
                scope.launch {
                    analysis = withContext(Dispatchers.IO) {
                        try {
                            AppAnalyzer.analyze(context)
                        } catch (t: Throwable) {
                            listOf("Analyse interrompue : ${t.javaClass.simpleName} ${t.message}") +
                                generateSequence(t.cause) { it.cause }.map { "Cause : ${it.javaClass.simpleName} ${it.message}" }.toList()
                        }
                    }
                    analyzing = false
                }
            }) { Text(if (analyzing) "Analyse en cours…" else "Analyser les liens de l'app Crunchyroll") }
        }
        analysis?.let { lines ->
            // Affichage dense sur deux colonnes pour tenir en quelques photos.
            items(lines.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    pair.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item { Section("Langues") }
        item {
            SettingRow("Titres") {
                Locales.forEach { (code, label) ->
                    FilterChip(selected = locale == code, onClick = {
                        locale = code
                        settings.locale = code
                        graph.watchlist.invalidate()
                    }) { Text(label) }
                }
            }
        }
        item {
            SettingRow("Audio préféré") {
                Audios.forEach { (code, label) ->
                    FilterChip(selected = audio == code, onClick = {
                        audio = code
                        settings.preferredAudio = code
                    }) { Text(label) }
                }
            }
        }

        item { Section("Compte et avancé") }
        item {
            OutlinedButton(onClick = { scope.launch { graph.progress.clear(); toast("Progression recalculée au prochain affichage") } }) {
                Text("Vider le cache de progression")
            }
        }
        item {
            Text(
                "Identifiants client de l'app TV Crunchyroll (laisser vide pour la valeur du build)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(basic, { basic = it }, "Basic xxxxxxxx= (identifiant:secret en base64)") }
        item { TvTextField(ua, { ua = it }, "User-Agent, ex. Crunchyroll/ANDROIDTV/3.xx.x_xxxxx (Android 14; en-US; Chromecast)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.basicAuthOverride = basic
                    settings.userAgentOverride = ua
                    toast("Enregistré")
                }) { Text("Enregistrer") }
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.progress.clear()
                        graph.watchlist.invalidate()
                        graph.auth.logout()
                    }
                }) { Text("Se déconnecter") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(180.dp))
        content()
    }
}
