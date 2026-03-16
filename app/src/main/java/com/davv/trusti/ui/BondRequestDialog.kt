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
fun BondRequestDialog(
    context: Context,
    senderName: String,
    senderDisambig: String,
    senderSharingPrefs: SharingPreferences,
    onAccept: (myName: String, myDisambig: String, myPrefs: SharingPreferences) -> Unit,
    onReject: () -> Unit
) {
    val defaultName = ProfileManager.getUsername(context)
    val defaultDisambig = ProfileManager.getDisambiguation(context)
    val defaultShareStatus = ProfileManager.getShareStatus(context)
    val defaultShareHistory = ProfileManager.getShareHistory(context)

    var myName by remember { mutableStateOf(defaultName) }
    var myDisambig by remember { mutableStateOf(defaultDisambig) }
    var myShareStatus by remember { mutableStateOf(defaultShareStatus) }
    var myShareHistory by remember { mutableStateOf(defaultShareHistory) }

    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Bond Request") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Peer info
                Text(
                    "Bond request from:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    senderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    senderDisambig,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // What they'll share with us
                Text(
                    "They will share:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = senderSharingPrefs.shareCurrentStatus,
                        onCheckedChange = {},
                        enabled = false
                    )
                    Text(
                        "Current health status",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = senderSharingPrefs.shareHistory,
                        onCheckedChange = {},
                        enabled = false
                    )
                    Text(
                        "Health history",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Your info and preferences
                Text(
                    "Your Name",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                TextField(
                    value = myName,
                    onValueChange = { myName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter your name") },
                    singleLine = true
                )

                Text(
                    "Your Disambiguation",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = myDisambig,
                        onValueChange = { myDisambig = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g., swift-tiger") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            myDisambig = ProfileManager.rollDisambiguation(context)
                        }
                    ) {
                        Text("Randomize")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "What you'll share:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = myShareStatus,
                        onCheckedChange = { myShareStatus = it }
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
                        checked = myShareHistory,
                        onCheckedChange = { myShareHistory = it }
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
                    val myPrefs = SharingPreferences(
                        shareCurrentStatus = myShareStatus,
                        shareHistory = myShareHistory
                    )
                    onAccept(myName, myDisambig, myPrefs)
                }
            ) {
                Text("Accept Bond")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}
