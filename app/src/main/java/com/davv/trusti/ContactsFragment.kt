package com.davv.trusti

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.databinding.FragmentContactsBinding
import com.davv.trusti.model.Contact
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.ContactStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadContacts()
        
        // Refresh when a new contact is added via handshake
        P2PMessenger.get(requireContext()).messageFlow
            .onEach { loadContacts() }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadContacts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadContacts() {
        val contacts = ContactStore.load(requireContext())
        binding.contactsContainer.removeAllViews()
        val hasContacts = contacts.isNotEmpty()
        binding.emptyState.visibility = if (hasContacts) View.GONE else View.VISIBLE
        binding.contactsScroll.visibility = if (hasContacts) View.VISIBLE else View.GONE
        contacts.forEach { addContactCard(it) }
    }

    private fun addContactCard(contact: Contact) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val card = MaterialCardView(ctx).apply {
            radius = 16 * dp
            cardElevation = 2 * dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        header.addView(TextView(ctx).apply {
            text = contact.name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (contact.disambiguation != null) {
            header.addView(TextView(ctx).apply {
                text = contact.disambiguation
                textSize = 12f
                alpha = 0.5f
                setPadding((8 * dp).toInt(), 0, 0, 0)
            })
        }

        inner.addView(header)

        inner.addView(TextView(ctx).apply {
            text = "Key: ${contact.publicKey.take(20)}…  ·  ${dateFormat.format(Date(contact.lastSeen))}"
            textSize = 12f
            alpha = 0.6f
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })

        card.setOnClickListener { ConversationActivity.start(requireContext(), contact) }
        card.setOnLongClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete contact")
                .setMessage("Remove \"${contact.name}\"?")
                .setPositiveButton("Delete") { _, _ ->
                    P2PMessenger.get(requireContext()).closeContact(contact.publicKey)
                    ContactStore.delete(requireContext(), contact.publicKey)
                    loadContacts()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        card.addView(inner)
        binding.contactsContainer.addView(card)
    }
}
