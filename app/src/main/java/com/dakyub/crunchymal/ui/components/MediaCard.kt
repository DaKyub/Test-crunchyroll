package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dakyub.crunchymal.data.CardItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.ui.theme.AdnBlue
import com.dakyub.crunchymal.ui.theme.CrunchyOrange

val PosterWidth = 140.dp
val GridPosterWidth = 112.dp
val WideWidth = 260.dp

@Composable
fun MediaCard(
    item: CardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = if (item.wide) WideWidth else PosterWidth,
    showMal: Boolean = true,
    showProviderBadge: Boolean = true,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.wide) 16f / 9f else 2f / 3f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.series.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showMal) {
                    MalBadge(
                        key = item.series.malKey,
                        titles = item.series.malTitles,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
                // Badges de plateformes (liste MAL), sinon badge du service quand plusieurs sont affichés.
                val providers by LocalGraph.current.providers.selected.collectAsState()
                if (item.badges.isNotEmpty()) {
                    PlatformBadges(item.badges, Modifier.align(Alignment.TopStart).padding(6.dp))
                } else if (showMal && showProviderBadge && providers.size > 1 && item.series.id.isNotBlank()) {
                    Text(
                        (listOf(item.series.provider) + item.series.alsoOn).distinct().joinToString("+") { it.badge },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(
                                if (item.series.provider == Provider.ADN) AdnBlue else CrunchyOrange,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                item.progress?.let { p ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(CrunchyOrange)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.series.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Libellé court et couleur du badge d'une plateforme. */
fun platformBadge(name: String): Pair<String, Color> = when (name) {
    "Crunchyroll" -> "CR" to CrunchyOrange
    "ADN" -> "ADN" to AdnBlue
    "Netflix" -> "Netflix" to Color(0xFFE50914)
    "Disney+" -> "Disney+" to Color(0xFF113CCF)
    "Prime Video" -> "Prime" to Color(0xFF00A8E1)
    "Apple TV+" -> "Apple TV+" to Color(0xFF3A3A3C)
    "Canal+" -> "Canal+" to Color(0xFF1B1B1B)
    "Max" -> "Max" to Color(0xFF002BE7)
    "HIDIVE" -> "HIDIVE" to Color(0xFF00A3E0)
    else -> name.take(10) to Color(0xFF5A5A5A)
}

/** Badges empilés (3 au plus, puis « +n »). */
@Composable
fun PlatformBadges(names: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val shown = if (names.size > 3) names.take(2) else names
        shown.forEach { name ->
            val (label, color) = platformBadge(name)
            BadgeText(label, color)
        }
        if (names.size > 3) BadgeText("+${names.size - 2}", Color(0xFF5A5A5A))
    }
}

@Composable
private fun BadgeText(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
