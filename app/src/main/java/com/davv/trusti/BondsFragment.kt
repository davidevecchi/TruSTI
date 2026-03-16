package com.davv.trusti

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.model.Contact
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.ui.StandardPageLayout
import com.davv.trusti.ui.StandardEmptyState
import com.davv.trusti.ui.ContactVisibilityDialog
import com.davv.trusti.utils.ContactStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch



class BondsFragment : Fragment() {

    private var bonds by mutableStateOf<List<Contact>>(emptyList())
    private var isRefreshing by mutableStateOf(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TruSTITheme {
                var bondToDelete by remember { mutableStateOf<Contact?>(null) }
                var bondToEditVisibility by remember { mutableStateOf<Contact?>(null) }

                if (bondToDelete != null) {
                    val bond = bondToDelete!!
                    AlertDialog(
                        onDismissRequest = { bondToDelete = null },
                        title = { Text(stringResource(R.string.contacts_delete_title)) },
                        text = { Text(stringResource(R.string.contacts_delete_message, bond.name)) },
                        confirmButton = {
                            TextButton(onClick = {
                                P2PMessenger.get(requireContext()).closeContact(bond.publicKey)
                                ContactStore.delete(requireContext(), bond.publicKey)
                                bonds = ContactStore.load(requireContext())
                                bondToDelete = null
                            }) { Text(stringResource(R.string.contacts_delete_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { bondToDelete = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }
                
                if (bondToEditVisibility != null) {
                    val contact = bondToEditVisibility!!
                    ContactVisibilityDialog(
                        contact = contact,
                        onDismiss = { bondToEditVisibility = null },
                        onSave = { newPrefs ->
                            // Update the contact with new sharing preferences
                            val updatedContact = contact.copy(ourSharingPrefs = newPrefs)
                            ContactStore.save(requireContext(), updatedContact)
                            // Update the local bonds list
                            bonds = bonds.map { 
                                if (it.publicKey == contact.publicKey) updatedContact else it 
                            }
                            bondToEditVisibility = null
                        }
                    )
                }
                
                StandardPageLayout(
                    title = stringResource(R.string.nav_bonds),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshBonds(showIndicator = true) }
                ) {
                    if (bonds.isEmpty()) {
                        item {
                            StandardEmptyState(
                                title = stringResource(R.string.contacts_empty_title),
                                subtitle = stringResource(R.string.contacts_empty_sub),
                                icon = {
                                    Text(
                                        text = "🤝",
                                        style = MaterialTheme.typography.displayMedium
                                    )
                                }
                            )
                        }
                    } else {
                        items(bonds, key = { it.publicKey }) { bond ->
                            BondCard(
                                bond = bond,
                                onClick = { ContactProfileActivity.start(requireContext(), bond) },
                                onDelete = { bondToDelete = bond },
                                onVisibilityClick = { bondToEditVisibility = bond }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadBonds()

        // Listen for peer events (status responses, channel state changes)
        P2PMessenger.get(requireContext()).peerEventFlow
            .onEach { event ->
                when (event) {
                    is P2PMessenger.PeerEvent.StatusResponse -> {
                        updateContactWithStatus(event.fromPublicKey, event.hasPositive, event.queuedAt)
                    }
                    is P2PMessenger.PeerEvent.ChannelOpened -> {
                        if (event.isNew) loadBonds()   // contact was just saved — reload the list
                        else updateContactConnectionStatus(event.contact.publicKey, true)
                    }
                    is P2PMessenger.PeerEvent.ChannelClosed -> {
                        updateContactConnectionStatus(event.contact.publicKey, false)
                    }
                    is P2PMessenger.PeerEvent.IncomingBondRequest -> Unit // handled in MainActivity
                    is P2PMessenger.PeerEvent.RequestRejected -> Unit // handled in MainActivity
                    is P2PMessenger.PeerEvent.BondRemoved -> {
                        loadBonds()
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        loadBonds()
        refreshBonds(showIndicator = false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadBonds()
            refreshBonds(showIndicator = false)
        }
    }

    private fun refreshBonds(showIndicator: Boolean) {
        if (showIndicator) {
            if (isRefreshing) return
            isRefreshing = true
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                isRefreshing = false
            }
        }
        val messenger = P2PMessenger.get(requireContext())
        // Only send status requests to peers with an open channel
        bonds.filter { it.isConnected }.forEach { bond ->
            messenger.sendStatusRequest(bond)
        }
    }
    
    private fun updateContactWithStatus(publicKey: String, hasPositive: Boolean, queuedAt: Long = 0L) {
        val previousStatus = bonds.find { it.publicKey == publicKey }?.diseaseStatus?.hasPositive
        
        val updatedContacts = bonds.map { contact ->
            if (contact.publicKey == publicKey) {
                contact.copy(
                    diseaseStatus = contact.diseaseStatus?.copy(hasPositive = hasPositive, lastUpdated = System.currentTimeMillis())
                        ?: com.davv.trusti.model.DiseaseStatus(hasPositive = hasPositive, lastUpdated = System.currentTimeMillis())
                )
            } else contact
        }
        bonds = updatedContacts
        
        // CRITICAL: Show immediate notification for red status change
        if (previousStatus == false && hasPositive == true) {
            showCriticalStatusNotification(publicKey, queuedAt)
        }
        
        // Save updated contact to storage
        val existingContact = updatedContacts.find { it.publicKey == publicKey }
        existingContact?.let { ContactStore.save(requireContext(), it) }
    }
    
    private fun updateContactConnectionStatus(publicKey: String, isConnected: Boolean) {
        val updatedContacts = bonds.map { contact ->
            if (contact.publicKey == publicKey) {
                contact.copy(isConnected = isConnected)
            } else contact
        }
        bonds = updatedContacts
    }
    
    private fun showCriticalStatusNotification(publicKey: String, queuedAt: Long = 0L) {
        val contact = bonds.find { it.publicKey == publicKey }
        val name = contact?.name ?: getString(R.string.notification_contact_fallback)
        val text = if (queuedAt > 0) {
            val minutesAgo = (System.currentTimeMillis() - queuedAt) / 60_000
            getString(R.string.notification_status_text_delayed, name, minutesAgo)
        } else {
            getString(R.string.notification_status_text, name)
        }
        runCatching {
            val notif = androidx.core.app.NotificationCompat.Builder(
                requireContext(), MainActivity.NOTIF_CHANNEL_STATUS
            )
                .setSmallIcon(R.drawable.siren_24px)
                .setContentTitle(getString(R.string.notification_status_title))
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(requireContext())
                .notify(publicKey.hashCode(), notif)
        }
    }

    internal fun loadBonds() {
        val connected = P2PMessenger.get(requireContext()).connectedPeers()
        bonds = ContactStore.load(requireContext()).map { contact ->
            if (contact.publicKey in connected) contact.copy(isConnected = true) else contact
        }
    }
}

@Composable
private fun BondCard(
    bond: Contact,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onVisibilityClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bond.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Connection status indicator
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = if (bond.isConnected) "Connected" else "Disconnected",
                            tint = if (bond.isConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(8.dp)
                        )
                    }
                    
                    // Show disambiguation handle as subtitle if available
                    bond.disambiguation?.let { handle ->
                        Text(
                            text = handle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(0.6f).padding(top = 2.dp)
                        )
                    }

                    // Status indicator with label
                    bond.diseaseStatus?.let { status ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (status.hasPositive) R.drawable.siren_24px
                                    else R.drawable.assignment_turned_in_24px
                                ),
                                contentDescription = null,
                                tint = if (status.hasPositive) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (status.hasPositive)
                                    stringResource(R.string.bond_status_positive)
                                else
                                    stringResource(R.string.bond_status_clear),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.hasPositive) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                // Three-dot menu
                Box {
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Privacy") },
                            onClick = {
                                expanded = false
                                onVisibilityClick()
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.visibility_lock_24px),
                                    contentDescription = "Privacy settings",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                expanded = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.heart_broken_24px),
                                    contentDescription = "Delete contact",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

