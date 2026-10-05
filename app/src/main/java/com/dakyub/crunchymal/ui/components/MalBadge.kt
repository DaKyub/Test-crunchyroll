package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.mal.MalRecord
import com.dakyub.crunchymal.ui.theme.MalBlue
import java.util.Locale

fun formatScore(record: MalRecord?, failing: Boolean = false): String = when {
    record == null -> if (failing) "?" else "…"
    record.score != null -> String.format(Locale.US, "%.2f", record.score)
    record.malId != null -> "N/A"
    else -> "—"
}

/** Observe (et déclenche si besoin) la note MAL associée à [key]. */
@Composable
fun rememberMalRecord(key: String, titles: List<String>): MalRecord? {
    val mal = LocalGraph.current.mal
    val version by mal.version.collectAsState()
    LaunchedEffect(key, version) { mal.request(key, titles) }
    val records by mal.records.collectAsState()
    return records[key]
}

@Composable
fun MalBadge(key: String, titles: List<String>, modifier: Modifier = Modifier, large: Boolean = false) {
    val record = rememberMalRecord(key, titles)
    val error by LocalGraph.current.mal.lastError.collectAsState()
    val style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium
    Row(
        modifier = modifier
            .background(MalBlue, RoundedCornerShape(if (large) 8.dp else 4.dp))
            .padding(horizontal = if (large) 12.dp else 6.dp, vertical = if (large) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 4.dp),
    ) {
        Text("MAL", style = style, color = Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Bold)
        Text(formatScore(record, failing = error != null), style = style, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
