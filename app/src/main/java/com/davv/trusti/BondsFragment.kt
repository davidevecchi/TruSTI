package com.davv.trusti

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.davv.trusti.utils.ContactStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BondsFragment : Fragment() {

    private var bonds by mutableStateOf<List<Contact>>(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TruSTITheme {
                var bondToDelete by remember { mutableStateOf<Contact?>(null) }

                if (bondToDelete != null) {
                    val bond = bondToDelete!!
                    AlertDialog(
                        onDismissRequest = { bondToDelete = null },
                        title = { Text(stringResource(R.string.tests_delete_title)) },
                        text = { Text(stringResource(R.string.tests_delete_message, bond.name)) },
                        confirmButton = {
                            TextButton(onClick = {
                                P2PMessenger.get(requireContext()).closeContact(bond.publicKey)
                                ContactStore.delete(requireContext(), bond.publicKey)
                                bonds = ContactStore.load(requireContext())
                                bondToDelete = null
                            }) { Text(stringResource(R.string.tests_delete_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { bondToDelete = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }
                
                BondsScreen(
                    bonds = bonds,
                    onBondClick = { bond ->
                        ConversationActivity.start(requireContext(), bond)
                    },
                    onBondLongClick = { bond ->
                        bondToDelete = bond
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadBonds()
        
        // Refresh when a new bond is added via handshake
        P2PMessenger.get(requireContext()).messageFlow
            .onEach { loadBonds() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        loadBonds()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadBonds()
    }

    private fun loadBonds() {
        bonds = ContactStore.load(requireContext())
    }
}

@Composable
private fun BondsScreen(
    bonds: List<Contact>,
    onBondClick: (Contact) -> Unit,
    onBondLongClick: (Contact) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    StandardPageLayout(
        title = stringResource(R.string.nav_bonds),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    dateFormat = dateFormat,
                    onClick = { onBondClick(bond) },
                    onLongClick = { onBondLongClick(bond) }
                )
            }
        }
    }
}

@Composable
private fun BondCard(
    bond: Contact,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
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
                    Text(
                        text = bond.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = "Key: ${bond.publicKey.take(8)}…  ·  ${dateFormat.format(Date(bond.lastSeen))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(0.7f).padding(top = 2.dp)
                    )
                }
                
                bond.disambiguation?.let { disambiguation ->
                    Text(
                        text = disambiguation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .alpha(0.5f)
                            .padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
