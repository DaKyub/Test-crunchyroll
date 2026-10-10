package com.dakyub.crunchymal.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
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

/** Champ texte utilisable à la télécommande (le clavier système s'ouvre sur OK). */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interaction,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .border(
                BorderStroke(2.dp, if (focused) colors.primary else colors.surfaceVariant),
                RoundedCornerShape(8.dp),
            )
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
}
