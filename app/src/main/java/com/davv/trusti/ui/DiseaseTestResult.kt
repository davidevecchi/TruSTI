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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.TestResult

@Composable
private fun TestResult.label() = this.getStatusLabel()

private fun TestResult.iconRes() = this.getStatusIcon()

private fun TestResult.iconColor() = this.getStatusColor()

@Composable
fun DiseaseTestResult(
    disease: String,
    state: TestResult = TestResult.NOT_TESTED,
    onStateChange: (TestResult) -> Unit = {},
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
                TestResult.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label()) }, leadingIcon = {
                        Icon(
                            painter = painterResource(option.iconRes()),
                            contentDescription = null,
                            tint = option.iconColor(),
                            modifier = Modifier.size(20.dp)
                        )
                    }, onClick = {
                        onStateChange(option)
                        expanded = false
                    })
                }
            }
        }
    }
}
