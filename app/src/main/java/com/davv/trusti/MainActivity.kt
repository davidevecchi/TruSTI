package com.davv.trusti

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.databinding.ActivityMainBinding
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before inflating views
        val savedMode = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(SettingsFragment.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { P2PMessenger.get(this@MainActivity).initialize() }

        P2PMessenger.get(this).peerEventFlow
            .onEach { event -> handlePeerEvent(event) }
            .launchIn(lifecycleScope)

        if (savedInstanceState == null) {
            val home = HomeFragment()
            val contacts = ContactsFragment()
            val Tests = TestsFragment()
            val settings = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, home, TAG_HOME)
                .add(R.id.fragmentContainer, contacts, TAG_CONTACTS)
                .add(R.id.fragmentContainer, Tests, TAG_TESTS)
                .add(R.id.fragmentContainer, settings, TAG_SETTINGS)
                .hide(contacts)
                .hide(Tests)
                .hide(settings)
                .commit()
            binding.bottomNav.selectedItemId = R.id.nav_connect
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(when (item.itemId) {
                R.id.nav_bonds    -> TAG_CONTACTS
                R.id.nav_tests  -> TAG_TESTS
                R.id.nav_settings -> TAG_SETTINGS
                else              -> TAG_HOME
            })
            true
        }
    }

    private fun handlePeerEvent(event: P2PMessenger.PeerEvent) {
        when (event) {
            is P2PMessenger.PeerEvent.ChannelOpened -> {
                val contact = event.contact
                AlertDialog.Builder(this)
                    .setTitle("${contact.name} connected")
                    .setMessage(contact.disambiguation ?: contact.publicKey.take(12))
                    .setPositiveButton("Connect") { _, _ ->
                        ConversationActivity.start(this, contact)
                    }
                    .setNeutralButton("See status") { _, _ ->
                        P2PMessenger.get(this).sendStatusRequest(contact)
                        Toast.makeText(this, "Status request sent to ${contact.name}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Dismiss", null)
                    .show()
            }

            is P2PMessenger.PeerEvent.StatusRequest -> {
                val contact = ContactStore.load(this)
                    .firstOrNull { it.publicKey == event.fromPublicKey }
                val name = contact?.name ?: event.fromPublicKey.take(12)
                AlertDialog.Builder(this)
                    .setTitle("Status request")
                    .setMessage("$name wants to see your test results.")
                    .setPositiveButton("Share") { _, _ ->
                        if (contact != null) {
                            P2PMessenger.get(this).sendStatusResponse(contact, TestsStore.load(this))
                        }
                    }
                    .setNegativeButton("Decline", null)
                    .show()
            }

            is P2PMessenger.PeerEvent.StatusResponse -> {
                val contact = ContactStore.load(this)
                    .firstOrNull { it.publicKey == event.fromPublicKey }
                val name = contact?.name ?: event.fromPublicKey.take(12)
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                val body = if (event.records.isEmpty()) "No records shared." else
                    event.records.joinToString("\n\n") { r ->
                        val date = sdf.format(Date(r.date))
                        val tests = r.tests.joinToString("\n") { t ->
                            "  ${t.disease}: ${t.result.name.lowercase().replace('_', ' ')}"
                        }
                        "$date\n$tests"
                    }
                AlertDialog.Builder(this)
                    .setTitle("$name's test results")
                    .setMessage(body)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showTab(tag: String) {
        supportFragmentManager.beginTransaction().apply {
            listOf(TAG_HOME, TAG_CONTACTS, TAG_TESTS, TAG_SETTINGS).forEach { t ->
                val f = supportFragmentManager.findFragmentByTag(t) ?: return@forEach
                if (t == tag) show(f) else hide(f)
            }
        }.commit()
    }

    companion object {
        private const val TAG_HOME     = "home"
        private const val TAG_CONTACTS = "contacts"
        private const val TAG_TESTS  = "Tests"
        private const val TAG_SETTINGS = "settings"
    }
}
