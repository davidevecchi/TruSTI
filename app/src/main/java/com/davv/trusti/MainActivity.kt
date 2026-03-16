package com.davv.trusti

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.databinding.ActivityMainBinding
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.ui.BondRequestDialog
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.utils.FontExtensions
import com.davv.trusti.utils.ProfileManager
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingBondRequest: P2PMessenger.PeerEvent.IncomingBondRequest? = null
    private val showBondRequestDialog = mutableStateOf(false)
    private var dialogComposeView: ComposeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before inflating views
        val savedMode = getSharedPreferences(SettingsFragmentCompanion.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(SettingsFragmentCompanion.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { P2PMessenger.get(this@MainActivity).initialize() }

        // Create notification channel for status alerts (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_STATUS,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.notification_channel_desc) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

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

        // Setup BondRequestDialog ComposeView
        dialogComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TruSTITheme {
                    Log.d("MainActivity", "=== COMPOSE VIEW UPDATE ===")
                    Log.d("MainActivity", "showBondRequestDialog.value: ${showBondRequestDialog.value}")
                    Log.d("MainActivity", "pendingBondRequest != null: ${pendingBondRequest != null}")
                    if (showBondRequestDialog.value && pendingBondRequest != null) {
                        Log.d("MainActivity", "=== SHOWING BOND REQUEST DIALOG ===")
                        val event = pendingBondRequest!!
                        BondRequestDialog(
                            context = this@MainActivity,
                            senderName = event.senderName,
                            senderDisambig = event.senderDisambig,
                            senderSharingPrefs = event.senderSharingPrefs,
                            onAccept = { myName, myDisambig, myPrefs ->
                                P2PMessenger.get(this@MainActivity).approveIncomingRequest(
                                    event.contactPk,
                                    myName,
                                    myDisambig,
                                    myPrefs
                                )
                                showBondRequestDialog.value = false
                                pendingBondRequest = null
                            },
                            onReject = {
                                P2PMessenger.get(this@MainActivity).rejectIncomingRequest(event.contactPk)
                                showBondRequestDialog.value = false
                                pendingBondRequest = null
                            }
                        )
                    }
                }
            }
        }
        (window.decorView as? android.view.ViewGroup)?.addView(dialogComposeView)
    }

    private fun handlePeerEvent(event: P2PMessenger.PeerEvent) {
        when (event) {
            is P2PMessenger.PeerEvent.ChannelOpened -> {
                if (!event.isNew) return  // skip reconnections — no popup for existing bonds
                val contact = event.contact
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.bond_established_title))
                    .setMessage(getString(R.string.bond_established_message, contact.name))
                    .setPositiveButton(getString(R.string.view_profile)) { _, _ ->
                        ContactProfileActivity.start(this, contact)
                    }
                    .setNegativeButton(getString(R.string.later), null)
                    .setCancelable(false)
                    .show()
            }

            is P2PMessenger.PeerEvent.IncomingBondRequest -> {
                Log.d("MainActivity", "=== RECEIVED INCOMING BOND REQUEST ===")
                Log.d("MainActivity", "contactPk: ${event.contactPk.take(8)}")
                Log.d("MainActivity", "senderName: ${event.senderName}")
                Log.d("MainActivity", "senderDisambig: ${event.senderDisambig}")
                Log.d("MainActivity", "senderSharingPrefs: ${event.senderSharingPrefs}")
                pendingBondRequest = event
                showBondRequestDialog.value = true
                Log.d("MainActivity", "=== BOND REQUEST DIALOG SHOULD SHOW ===")
            }

            is P2PMessenger.PeerEvent.ChannelClosed -> {
                // Handled in BondsFragment
            }
            is P2PMessenger.PeerEvent.StatusResponse -> {
                // Handled in BondsFragment
            }
            is P2PMessenger.PeerEvent.BondRemoved -> {
                // Handled in BondsFragment
            }
            is P2PMessenger.PeerEvent.RequestRejected -> {
                // Handled in BondsFragment
            }
        }
    }

    fun navigateToBonds() {
        binding.bottomNav.selectedItemId = R.id.nav_bonds
    }

    private fun showTab(tag: String) {
        supportFragmentManager.beginTransaction().apply {
            listOf(TAG_HOME, TAG_CONTACTS, TAG_TESTS, TAG_PROFILE, TAG_SETTINGS).forEach { t ->
                val f = supportFragmentManager.findFragmentByTag(t) ?: return@forEach
                if (t == tag) show(f) else hide(f)
            }
        }.commit()
    }

    override fun onDestroy() {
        dialogComposeView?.let {
            (window.decorView as? android.view.ViewGroup)?.removeView(it)
        }
        dialogComposeView = null
        super.onDestroy()
        if (isFinishing) P2PMessenger.get(this).onDestroy()
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
        const val NOTIF_CHANNEL_STATUS = "trusti_status_alerts"
    }
}
