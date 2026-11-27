package com.sleepanalysis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Label-left / control-right setting row (plan T4f) — the one Row the Switch, Checkbox and
 * time-button rows share; each screen keeps only its control.
 */
@Composable
fun LabeledRow(
    label: String,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = labelStyle)
        control()
    }
}
