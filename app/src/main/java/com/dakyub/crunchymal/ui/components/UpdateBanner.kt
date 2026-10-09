package com.dakyub.crunchymal.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.update.UpdateState

/** Libellé de l'état de mise à jour, ou null s'il n'y a rien à afficher. */
fun UpdateState.label(): String? = when (this) {
    is UpdateState.Available -> "Mise à jour disponible (build $build)"
    is UpdateState.Downloading -> "Téléchargement de la mise à jour… ${(progress * 100).toInt()} %"
    UpdateState.Installing -> "Installation en cours…"
    is UpdateState.Error -> message
    else -> null
}

/** Bouton "Mettre à jour" : demande l'autorisation d'installer si besoin, puis télécharge et installe. */
@Composable
fun UpdateButton(text: String = "Mettre à jour") {
    val updates = LocalGraph.current.updates
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    val available = state as? UpdateState.Available ?: return
    Button(onClick = {
        if (!updates.canInstall()) {
            val opened = updates.openInstallPermissionSettings()
            Toast.makeText(
                context,
                if (opened) "Autorise CrunchyMAL à installer des applications, puis reviens appuyer sur « Mettre à jour »."
                else "Autorise CrunchyMAL dans Paramètres → Préférences de l'appareil → Sécurité et restrictions → Sources inconnues.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            updates.startDownloadAndInstall(available)
        }
    }) { Text(text) }
}

/** Bandeau affiché en haut de l'app quand une mise à jour est disponible ou en cours. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by LocalGraph.current.updates.state.collectAsState()
    val label = state.label() ?: return
    if (state is UpdateState.Error) return // les erreurs ne s'affichent que dans les paramètres
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        UpdateButton()
    }
}
