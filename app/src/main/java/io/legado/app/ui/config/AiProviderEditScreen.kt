package io.legado.app.ui.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
fun FetchedModelSelectorContent(
    modelIds: List<String>,
    existingIds: Set<String>,
    onAddSingle: (String) -> Unit,
    onAddSelected: (List<String>) -> Unit,
    onAddAll: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val filteredModels = remember(modelIds, searchQuery) {
        if (searchQuery.isBlank()) modelIds
        else modelIds.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    fun toggleSelection(id: String) {
        selectionMode = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    val summaryText = if (selectionMode) {
        stringResource(R.string.ai_fetch_models_selected_hint, selectedIds.size)
    } else {
        stringResource(R.string.ai_fetch_models_long_press_hint)
    }

    AppDialogFrame(
        title = stringResource(R.string.ai_add_model_from_list),
        scrollContent = false,
        content = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.screen_find),
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                },
                shape = RoundedCornerShape(style.actionRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    cursorColor = style.accent,
                    focusedBorderColor = style.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = style.stroke
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = summaryText,
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontFamily = style.bodyFontFamily,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredModels, key = { it }) { modelId ->
                    val isExisting = modelId in existingIds
                    val isSelected = modelId in selectedIds
                    FetchedModelRow(
                        modelId = modelId,
                        existing = isExisting,
                        selected = isSelected,
                        selectionMode = selectionMode,
                        style = style,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(modelId)
                            } else {
                                onDismiss()
                                onAddSingle(modelId)
                            }
                        },
                        onLongClick = { toggleSelection(modelId) }
                    )
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.ai_add_all_models),
                palette = palette,
                onClick = {
                    onDismiss()
                    onAddAll(modelIds)
                },
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = if (selectedIds.isEmpty()) {
                    stringResource(R.string.ai_fetch_models_add_selected_empty)
                } else {
                    stringResource(R.string.ai_fetch_models_add_selected, selectedIds.size)
                },
                palette = palette,
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        onDismiss()
                        onAddSelected(selectedIds.toList())
                    }
                },
                primary = selectedIds.isNotEmpty(),
                cornerRadius = style.actionRadius
            )
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FetchedModelRow(
    modelId: String,
    existing: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val rowColor = if (selected) style.fieldSurface else style.surface
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = rowColor,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = style.accent,
                        uncheckedColor = style.secondaryText,
                        checkmarkColor = style.surface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelId,
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontWeight = FontWeight.Medium,
                    fontFamily = style.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        existing -> stringResource(R.string.ai_fetch_models_existing)
                        selectionMode -> stringResource(R.string.ai_fetch_models_click_toggle)
                        else -> stringResource(R.string.ai_fetch_models_click_add_long_select)
                    },
                    color = if (existing) style.accent else style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontFamily = style.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
