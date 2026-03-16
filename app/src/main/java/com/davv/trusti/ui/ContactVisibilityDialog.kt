package com.davv.trusti.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.Contact
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.utils.ContactStore

@Composable
fun ContactVisibilityDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onSave: (SharingPreferences) -> Unit
) {
    // Initialize with current preferences or defaults
    val currentPrefs = contact.ourSharingPrefs ?: SharingPreferences()
    
    var shareStatus by remember { mutableStateOf(currentPrefs.shareCurrentStatus) }
    var shareHistory by remember { mutableStateOf(currentPrefs.shareHistory) }
    var shareCounter by remember { mutableStateOf(currentPrefs.shareCounter) }
    var shareVaccines by remember { mutableStateOf(currentPrefs.shareVaccines) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text("Visibility Settings")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Contact info
                Text(
                    "Configure what you share with:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                contact.disambiguation?.let { handle ->
                    Text(
                        handle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Visibility options
                Text(
                    "Sharing Options:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shareStatus,
                        onCheckedChange = { shareStatus = it }
                    )
                    Text(
                        "Share current health status",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shareHistory,
                        onCheckedChange = { shareHistory = it }
                    )
                    Text(
                        "Share health history",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shareCounter,
                        onCheckedChange = { shareCounter = it }
                    )
                    Text(
                        "Share test count",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shareVaccines,
                        onCheckedChange = { shareVaccines = it }
                    )
                    Text(
                        "Share vaccination status",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newPrefs = SharingPreferences(
                        shareCurrentStatus = shareStatus,
                        shareHistory = shareHistory,
                        shareCounter = shareCounter,
                        shareVaccines = shareVaccines
                    )
                    onSave(newPrefs)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
