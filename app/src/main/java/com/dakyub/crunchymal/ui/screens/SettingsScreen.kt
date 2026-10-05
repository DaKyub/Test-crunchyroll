package com.dakyub.crunchymal.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.ui.components.TvTextField
import kotlinx.coroutines.launch

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
    var basic by remember { mutableStateOf(settings.basicAuthOverride) }
    var ua by remember { mutableStateOf(settings.userAgentOverride) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            SettingRow("Langue des titres") {
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
        item {
            SettingRow("Caches") {
                OutlinedButton(onClick = { scope.launch { graph.mal.clear(); toast("Cache MAL vidé") } }) {
                    Text("Vider les notes MAL")
                }
                OutlinedButton(onClick = { scope.launch { graph.progress.clear(); toast("Progression recalculée au prochain affichage") } }) {
                    Text("Vider la progression")
                }
            }
        }
        item {
            Text("Avancé : identifiants client de l'app TV (laisser vide pour la valeur par défaut)", style = MaterialTheme.typography.titleSmall)
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
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(180.dp))
        content()
    }
}
