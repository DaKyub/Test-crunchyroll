package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.SelectableChipColors
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun CenteredMessage(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * Grille d'affiches dont l'en-tête (filtres, compteurs…) défile avec les séries : quand on descend,
 * il laisse toute la place à la grille. [message] (chargement, erreur, liste vide) remplace les cartes.
 */
@Composable
fun <T> PosterGrid(
    items: List<T>,
    message: String?,
    header: @Composable ColumnScope.() -> Unit,
    key: ((T) -> Any)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GridPosterWidth + 12.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(content = header)
        }
        if (message != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(message, style = MaterialTheme.typography.titleMedium)
                        if (actionLabel != null && onAction != null) {
                            Button(onClick = onAction) { Text(actionLabel) }
                        }
                    }
                }
            }
        } else {
            items(items, key = key) { itemContent(it) }
        }
    }
}

/**
 * Résumé dépliable (OK l'affiche en entier ou le réduit). Quand il reçoit le focus, [onFocused] sert à
 * remonter la fiche tout en haut (titre, note MAL) ; plus bas, la fiche défile pour montrer les épisodes.
 */
@Composable
fun ExpandableSynopsis(
    text: String,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 3,
    /** Résumé complet de secours (MAL, en anglais), chargé au dépliage quand [text] n'est qu'une accroche « … ». */
    loadFull: (suspend () -> String?)? = null,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val teaser = loadFull != null && text.trimEnd().let { it.endsWith("...") || it.endsWith("…") }
    val full by produceState<String?>(null, expanded, text) {
        if (expanded && teaser) value = runCatching { loadFull?.invoke() }.getOrNull() ?: ""
    }
    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded && teaser) {
                Text(
                    "Résumé complet (MyAnimeList, en anglais) :",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    when (val f = full) {
                        null -> "Chargement…"
                        "" -> "Pas de résumé sur MAL."
                        else -> f
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (focused) {
                Text(
                    if (expanded) "OK : réduire le résumé" else "OK : lire tout le résumé",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Puce de saison : la saison affichée prend la couleur du service ([accent]). */
@Composable
fun seasonChipColors(accent: Color): SelectableChipColors = FilterChipDefaults.colors(
    selectedContainerColor = accent,
    selectedContentColor = Color.White,
    focusedSelectedContainerColor = Color.White,
    focusedSelectedContentColor = accent,
)

/**
 * Champ texte utilisable à la télécommande. Au repos, c'est une case qu'on peut survoler sans ouvrir le
 * clavier ; le clavier système ne s'ouvre qu'avec OK, et se referme avec Retour ou la touche de validation.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    password: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Retour à la case (et non au premier élément de l'écran) une fois la saisie terminée.
    var returnFocus by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        } else if (returnFocus) {
            returnFocus = false
            runCatching { boxFocus.requestFocus() }
        }
    }
    fun stop() {
        returnFocus = true
        editing = false
    }

    val shape = RoundedCornerShape(8.dp)
    val frame = modifier.fillMaxWidth()
    if (editing) {
        var hadFocus by remember { mutableStateOf(false) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { onSubmit(); stop() },
                onDone = { onSubmit(); stop() },
            ),
            modifier = frame
                .focusRequester(fieldFocus)
                .onFocusChanged { state ->
                    if (state.isFocused) hadFocus = true else if (hadFocus) stop()
                }
                .border(BorderStroke(2.dp, colors.primary), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
    } else {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            frame
                .focusRequester(boxFocus)
                .border(BorderStroke(2.dp, if (focused) colors.primary else colors.surfaceVariant), shape)
                .clickable(interactionSource = interaction, indication = null) { editing = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val shown = if (password) "•".repeat(value.length.coerceAtMost(24)) else value
            Text(
                shown.ifEmpty { placeholder },
                style = MaterialTheme.typography.titleMedium,
                color = if (value.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
