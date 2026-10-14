package com.dakyub.crunchymal.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.LocalGraph
import kotlinx.coroutines.launch

/** Libellés MAL des notes. */
private val ScoreLabels = mapOf(
    10 to "Chef-d'œuvre", 9 to "Excellent", 8 to "Très bien", 7 to "Bien", 6 to "Correct",
    5 to "Moyen", 4 to "Mauvais", 3 to "Très mauvais", 2 to "Horrible", 1 to "Catastrophique",
)

/**
 * « Série terminée » : quand la synchro vient de passer une série « Terminé » sur MAL sans note, propose
 * de la noter tout de suite (une série à la fois).
 */
@Composable
fun RatingPrompt() {
    val graph = LocalGraph.current
    val queue by graph.malSync.toRate.collectAsState()
    val request = queue.firstOrNull() ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(request.malId) { mutableStateOf(false) }
    var focusedScore by remember(request.malId) { mutableStateOf<Int?>(null) }
    val picture by produceState<String?>(null, request.malId) {
        value = runCatching { graph.mal.details(request.malId).pictureUrl }.getOrNull()
    }

    Dialog(
        onDismissRequest = { graph.malSync.dismissRating(request.malId) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .width(900.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
        ) {
            picture?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(120.dp).height(170.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Série terminée 🎉", style = MaterialTheme.typography.titleLarge)
                Text(request.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Ta note sur MAL ?" + (focusedScore?.let { " · $it = ${ScoreLabels[it]}" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (10 downTo 1).forEach { score ->
                        OutlinedButton(
                            enabled = !saving,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.onFocusChanged { if (it.isFocused) focusedScore = score },
                            onClick = {
                                saving = true
                                scope.launch {
                                    runCatching { graph.mal.updateMyStatus(request.malId, "completed", score, null) }
                                        .onSuccess {
                                            Toast.makeText(context, "Noté $score/10 sur MAL ✓", Toast.LENGTH_SHORT).show()
                                            graph.malSync.dismissRating(request.malId)
                                        }
                                        .onFailure {
                                            Toast.makeText(context, "Erreur MAL : ${it.message}", Toast.LENGTH_LONG).show()
                                            saving = false
                                        }
                                }
                            },
                        ) { Text("$score") }
                    }
                }
                OutlinedButton(onClick = { graph.malSync.dismissRating(request.malId) }) { Text("Plus tard") }
            }
        }
    }
}
