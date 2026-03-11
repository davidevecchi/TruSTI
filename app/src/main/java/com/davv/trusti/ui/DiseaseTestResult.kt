package com.davv.trusti.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.davv.trusti.R

private val ColorGray   = Color(0xFF9E9E9E)
private val ColorGreen  = Color(0xFF4CAF50)
private val ColorRed    = Color(0xFFE53935)
private val ColorBlue   = Color(0xFF1E88E5)
private val ColorYellow = Color(0xFFFFC107)

enum class DiseaseTestState {
    EMPTY, NEGATIVE, POSITIVE, TAKE_CARE, VACCINATED
}

private fun DiseaseTestState.label() = when (this) {
    DiseaseTestState.EMPTY      -> "Not tested"
    DiseaseTestState.NEGATIVE   -> "Negative"
    DiseaseTestState.POSITIVE   -> "Positive"
    DiseaseTestState.VACCINATED -> "Vaccinated"
    DiseaseTestState.TAKE_CARE  -> "Take care"
}

private fun DiseaseTestState.iconRes() = when (this) {
    DiseaseTestState.EMPTY      -> R.drawable.indeterminate_question_box_24px
    DiseaseTestState.NEGATIVE   -> R.drawable.assignment_turned_in_24px
    DiseaseTestState.POSITIVE   -> R.drawable.health_cross_24px
    DiseaseTestState.VACCINATED -> R.drawable.syringe_24px
    DiseaseTestState.TAKE_CARE  -> R.drawable.gpp_maybe_24px
}

private fun DiseaseTestState.iconColor() = when (this) {
    DiseaseTestState.POSITIVE   -> ColorRed
    DiseaseTestState.NEGATIVE   -> ColorGreen
    DiseaseTestState.VACCINATED -> ColorBlue
    DiseaseTestState.TAKE_CARE  -> ColorYellow
    DiseaseTestState.EMPTY      -> ColorGray
}

@Composable
fun DiseaseTestResult(
    disease: String,
    state: DiseaseTestState = DiseaseTestState.EMPTY,
    onStateChange: (DiseaseTestState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = disease,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f)
        )

        Box(modifier = Modifier.weight(0.4f)) {
            AssistChip(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(state.label(), style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(state.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = state.iconColor(),
                    leadingIconContentColor = state.iconColor(),
                    trailingIconContentColor = state.iconColor()
                ),
                border = BorderStroke(1.dp, state.iconColor())
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                DiseaseTestState.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(option.iconRes()),
                                contentDescription = null,
                                tint = option.iconColor(),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            onStateChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
