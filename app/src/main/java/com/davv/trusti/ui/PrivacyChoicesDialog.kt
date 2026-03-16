package com.davv.trusti.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.utils.ProfileManager

@Composable
fun PrivacyChoicesDialog(
    context: Context,
    contactPublicKey: String,
    onConfirm: (name: String, disambiguation: String, prefs: SharingPreferences) -> Unit,
    onCancel: () -> Unit
) {
    val defaultName = ProfileManager.getUsername(context)
    val defaultDisambig = ProfileManager.getDisambiguation(context)
    val defaultShareStatus = ProfileManager.getShareStatus(context)
    val defaultShareHistory = ProfileManager.getShareHistory(context)

    var name by remember { mutableStateOf(defaultName) }
    var disambiguation by remember { mutableStateOf(defaultDisambig) }
    var shareCurrentStatus by remember { mutableStateOf(defaultShareStatus) }
    var shareHistory by remember { mutableStateOf(defaultShareHistory) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Choose What to Share") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Explanatory text
                Text(
                    "Before bonding, choose what information you'd like to share with this peer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Name field
                Text(
                    "Your Name",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter your name") },
                    singleLine = true
                )

                // Disambiguation field
                Text(
                    "Disambiguation",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = disambiguation,
                        onValueChange = { disambiguation = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g., swift-tiger") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            disambiguation = ProfileManager.rollDisambiguation(context)
                        }
                    ) {
                        Text("Randomize")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sharing preferences
                Text(
                    "What to Share",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shareCurrentStatus,
                        onCheckedChange = { shareCurrentStatus = it }
                    )
                    Text(
                        "Share current health status",
                        style = MaterialTheme.typography.bodySmall
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
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val prefs = SharingPreferences(
                        shareCurrentStatus = shareCurrentStatus,
                        shareHistory = shareHistory
                    )
                    onConfirm(name, disambiguation, prefs)
                }
            ) {
                Text("Send Bond Request")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
