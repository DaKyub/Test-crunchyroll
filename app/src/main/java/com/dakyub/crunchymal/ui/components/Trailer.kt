package com.dakyub.crunchymal.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import kotlinx.coroutines.launch

/** Bouton « ▶ Bande-annonce » des fiches : vidéo trouvée sur TMDB (au clic), lue dans l'app YouTube. */
@Composable
fun TrailerButton(titles: List<String>, movie: Boolean = false, tmdbId: Int? = null) {
    val graph = LocalGraph.current
    if (graph.settings.tmdbKey.isBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                val key = runCatching { graph.watchPlatforms.trailer(titles, movie, tmdbId) }.getOrNull()
                busy = false
                if (key == null) Toast.makeText(context, "Pas de bande-annonce trouvée sur TMDB.", Toast.LENGTH_SHORT).show()
                else openYoutube(context, key)
            }
        },
    ) { Text(if (busy) "Recherche…" else "▶ Bande-annonce") }
}

/**
 * Ouvre la vidéo YouTube [key] : lien « watch » dans l'app YouTube pour Android TV (le lien vnd.youtube n'y
 * ouvre que l'accueil), puis l'app YouTube mobile, puis toute app qui sait lire le lien.
 */
fun openYoutube(context: Context, key: String) {
    val watch = Uri.parse("https://www.youtube.com/watch?v=$key")
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, watch).setPackage(YOUTUBE_TV),
        Intent(Intent.ACTION_VIEW, watch).setPackage(YOUTUBE),
        Intent(Intent.ACTION_VIEW, watch),
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    Toast.makeText(context, "Aucune app ne sait lire YouTube sur cet appareil.", Toast.LENGTH_LONG).show()
}

private const val YOUTUBE_TV = "com.google.android.youtube.tv"
private const val YOUTUBE = "com.google.android.youtube"
