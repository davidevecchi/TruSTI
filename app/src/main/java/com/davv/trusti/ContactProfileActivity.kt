package com.davv.trusti

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davv.trusti.model.Contact
import com.davv.trusti.model.DiseaseStatus
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.utils.ContactStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pk = intent.getStringExtra(EXTRA_CONTACT_PK) ?: run { finish(); return }
        // Intent JSON carries live in-memory state (isConnected, diseaseStatus).
        // ContactStore is the fallback if the activity is recreated without the intent.
        val contact = intent.getStringExtra(EXTRA_CONTACT_JSON)?.let { parseContactJson(it) }
            ?: ContactStore.load(this).find { it.publicKey == pk }
            ?: run { finish(); return }

        setContent {
            TruSTITheme {
                ContactProfileScreen(contact = contact, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_CONTACT_PK   = "contact_pk"
        private const val EXTRA_CONTACT_JSON = "contact_json"

        fun start(context: Context, contact: Contact) {
            val json = JSONObject().apply {
                put("name", contact.name)
                put("publicKey", contact.publicKey)
                put("disambiguation", contact.disambiguation ?: "")
                put("isConnected", contact.isConnected)
                contact.diseaseStatus?.let { s ->
                    put("diseaseStatus", JSONObject().apply {
                        put("hasPositive", s.hasPositive)
                        put("lastUpdated", s.lastUpdated)
                    })
                }
            }.toString()
            context.startActivity(
                Intent(context, ContactProfileActivity::class.java)
                    .putExtra(EXTRA_CONTACT_PK, contact.publicKey)
                    .putExtra(EXTRA_CONTACT_JSON, json)
            )
        }

        private fun parseContactJson(json: String): Contact? = runCatching {
            val o = JSONObject(json)
            val diseaseStatus = o.optJSONObject("diseaseStatus")?.let { s ->
                com.davv.trusti.model.DiseaseStatus(
                    hasPositive = s.optBoolean("hasPositive", false),
                    lastUpdated = s.optLong("lastUpdated", System.currentTimeMillis())
                )
            }
            Contact(
                name           = o.getString("name"),
                publicKey      = o.getString("publicKey"),
                disambiguation = o.optString("disambiguation", ""),
                isConnected    = o.optBoolean("isConnected", false),
                diseaseStatus  = diseaseStatus
            )
        }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactProfileScreen(contact: Contact, onBack: () -> Unit) {
    val timeFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Profile card — mirrors ProfilePreview layout
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = contact.name.ifBlank { "Anonymous" },
                        style = MaterialTheme.typography.titleLarge
                    )

                    // Disambiguation chip — only shown if present
                    contact.disambiguation?.let { disambig ->
                        Spacer(Modifier.height(4.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text(disambig) },
                            enabled = false,
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                disabledLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Connection status row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = null,
                            tint = if (contact.isConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(8.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (contact.isConnected) R.string.profile_status_connected
                                else R.string.profile_status_offline
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (contact.isConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Health status row — mirrors ProfilePreview status row
                    val status = contact.diseaseStatus
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        when {
                            status == null -> {
                                Text(
                                    stringResource(R.string.profile_status_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            status.hasPositive -> {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Has positive results",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE53935)
                                )
                            }
                            else -> {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF43A047),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "No positive results",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF43A047)
                                )
                            }
                        }
                    }

                    // Last updated timestamp — shown when status is available
                    status?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.profile_updated,
                                timeFmt.format(Date(it.lastUpdated))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(0.6f)
                        )
                    }
                }
            }

            // Public key card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_key_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${contact.publicKey.take(24)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(0.6f)
                    )
                }
            }
        }
    }
}
