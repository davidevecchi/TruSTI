package com.davv.trusti

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.databinding.ActivityMainBinding
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.FontExtensions
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
        val savedMode = getSharedPreferences(SettingsFragmentCompanion.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(SettingsFragmentCompanion.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { P2PMessenger.get(this@MainActivity).initialize() }

        // Update test count badge when tests change
        updateTestCountBadge()

        P2PMessenger.get(this).peerEventFlow
            .onEach { event -> handlePeerEvent(event) }
            .launchIn(lifecycleScope)

        if (savedInstanceState == null) {
            val home = HomeFragment()
            val bonds = BondsFragment()
            val tests = TestsFragment()
            val profile = ProfileFragment()
            val settings = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, home, TAG_HOME)
                .add(R.id.fragmentContainer, bonds, TAG_CONTACTS)
                .add(R.id.fragmentContainer, tests, TAG_TESTS)
                .add(R.id.fragmentContainer, profile, TAG_PROFILE)
                .add(R.id.fragmentContainer, settings, TAG_SETTINGS)
                .hide(bonds)
                .hide(tests)
                .hide(profile)
                .hide(settings)
                .commit()
            binding.bottomNav.selectedItemId = R.id.nav_connect
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(when (item.itemId) {
                R.id.nav_bonds    -> TAG_CONTACTS
                R.id.nav_tests    -> TAG_TESTS
                R.id.nav_profile  -> TAG_PROFILE
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

            is P2PMessenger.PeerEvent.IncomingRequest -> {
                val pk = event.contactPk
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.bond_request_title))
                    .setMessage(getString(R.string.bond_request_message, pk.take(12)))
                    .setPositiveButton(getString(R.string.bond_request_accept)) { _, _ ->
                        P2PMessenger.get(this).approveIncomingRequest(pk)
                    }
                    .setNegativeButton(getString(R.string.bond_request_decline)) { _, _ ->
                        P2PMessenger.get(this).rejectIncomingRequest(pk)
                    }
                    .setCancelable(false)
                    .show()
            }

            is P2PMessenger.PeerEvent.ChannelClosed -> {
                // Handled in BondsFragment
            }
            is P2PMessenger.PeerEvent.StatusResponse -> {
                // Handled in BondsFragment
            }
        }
    }

    private fun showTab(tag: String) {
        supportFragmentManager.beginTransaction().apply {
            listOf(TAG_HOME, TAG_CONTACTS, TAG_TESTS, TAG_PROFILE, TAG_SETTINGS).forEach { t ->
                val f = supportFragmentManager.findFragmentByTag(t) ?: return@forEach
                if (t == tag) show(f) else hide(f)
            }
        }.commit()
    }

    override fun onResume() {
        super.onResume()
        updateTestCountBadge()
    }

    private fun updateTestCountBadge() {
        val testCount = TestsStore.load(this).size
        val testsMenuItem = binding.bottomNav.menu.findItem(R.id.nav_tests)
        
        if (testCount > 0) {
            // Create badge text (show "99+" for counts > 99)
            val badgeText = if (testCount > 99) "99+" else testCount.toString()
            
            // Use a custom view for the menu item with badge
            val badgeView = layoutInflater.inflate(R.layout.badge_indicator, null)
            val iconView = badgeView.findViewById<android.widget.ImageView>(R.id.icon)
            val badgeTextView = badgeView.findViewById<android.widget.TextView>(R.id.badge)
            
            iconView.setImageResource(R.drawable.diagnosis_24px)
            badgeTextView.text = badgeText
            badgeTextView.visibility = android.view.View.VISIBLE
            
            // Apply Tsukimi font to the badge text
            FontExtensions.applyTsukimiFont(badgeTextView, Typeface.BOLD)
            
            testsMenuItem.actionView = badgeView
        } else {
            testsMenuItem.actionView = null
        }
    }

    companion object {
        private const val TAG_HOME     = "home"
        private const val TAG_CONTACTS = "contacts"
        private const val TAG_TESTS    = "tests"
        private const val TAG_PROFILE  = "profile"
        private const val TAG_SETTINGS = "settings"
    }
}
