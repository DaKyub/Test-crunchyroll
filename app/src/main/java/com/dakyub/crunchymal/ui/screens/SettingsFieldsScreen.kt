package com.dakyub.crunchymal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.SettingsField
import com.dakyub.crunchymal.ui.components.QrCode
import com.dakyub.crunchymal.ui.components.TvTextField
import kotlinx.coroutines.launch

/**
 * « Saisie des champs » : tous les champs de texte des réglages sur un seul écran, avec en tête un QR code
 * pour les remplir depuis le téléphone (page /fields du serveur local).
 */
@Composable
fun SettingsFieldsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var fields by remember { mutableStateOf(graph.settingsFields.current()) }
    // Valeurs saisies sur la TV (clé → texte), comparées aux valeurs enregistrées.
    val edits = remember { mutableStateMapOf<String, String>() }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Enregistrement depuis le téléphone : on relit les valeurs.
    val revision by graph.settingsFields.revision.collectAsState()
    LaunchedEffect(revision) {
        fields = graph.settingsFields.current()
        edits.clear()
    }
    LaunchedEffect(Unit) { graph.tagServer.start() }
    val server by graph.tagServer.state.collectAsState()

    fun changes(): Map<String, String> = edits.filter { (key, value) ->
        val field = fields.firstOrNull { it.key == key } ?: return@filter false
        if (field.secret) value.isNotEmpty() else value != field.value
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← Retour aux réglages") }
                Text("Saisie des champs", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            val url = server.url?.replace("/?k=", "/fields?k=")
            if (url != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(url, size = 180.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Plus simple : remplis tout depuis ton téléphone (même Wi-Fi).", style = MaterialTheme.typography.titleMedium)
                        Text("Scanne ce QR code ou ouvre :", style = MaterialTheme.typography.bodyMedium)
                        Text(url, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Les valeurs enregistrées depuis le téléphone apparaissent aussitôt ici.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(server.error ?: "Démarrage de la page web…", style = MaterialTheme.typography.bodySmall)
            }
        }
        report?.let { lines ->
            item {
                Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        items(fields, key = { it.key }) { field -> FieldRow(field, edits[field.key] ?: field.value) { edits[field.key] = it } }
        item {
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    report = graph.settingsFields.apply(changes())
                    saving = false
                }
            }) { Text(if (saving) "Enregistrement…" else "Enregistrer les champs modifiés") }
        }
    }
}

@Composable
private fun FieldRow(field: SettingsField, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${field.section} · ${field.label}", style = MaterialTheme.typography.labelMedium)
        TvTextField(
            value,
            onChange,
            if (field.secret && field.filled) "(enregistré — laisser vide pour garder)" else field.label,
            password = field.secret,
        )
        field.hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
