package com.sleepanalysis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The six user-label chips under a clip/event row (plan T4f) — one composable for the
 * report's clip rows, its selected-event card and the bulk-labeling screen (same dataset
 * contract: user ground truth, never a score).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventLabelChips(
    isSelected: (com.sleepanalysis.domain.labels.EventUserLabel) -> Boolean,
    onLabel: (com.sleepanalysis.domain.labels.EventUserLabel) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    labelStyle: TextStyle? = null,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        com.sleepanalysis.domain.labels.EventUserLabel.entries.forEach { label ->
            FilterChip(
                selected = isSelected(label),
                onClick = { onLabel(label) },
                label = { Text(label.label, style = labelStyle ?: TextStyle.Default) },
            )
        }
    }
}
