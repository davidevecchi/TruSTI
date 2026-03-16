package com.davv.trusti

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.davv.trusti.connection.QrHelper
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.databinding.FragmentHomeBinding
import com.davv.trusti.model.Contact
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.ui.PrivacyChoicesDialog
import com.davv.trusti.ui.TruSTITheme
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.FontExtensions
import com.davv.trusti.utils.ProfileManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var pendingContactPk: String? = null
    private val showPrivacyDialog = mutableStateOf(false)
    private var dialogComposeView: ComposeView? = null

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        runCatching {
            val raw = result.contents ?: return@registerForActivityResult
            Log.d(TAG, "QR scanned: ${raw.take(60)}")

            val info = QrHelper.parse(raw)
            if (info == null) {
                Log.w(TAG, "QR parse failed for: $raw")
                Toast.makeText(requireContext(), "Invalid TruSTI QR code", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            Log.d(TAG, "QR parsed: pk=${info.publicKey.take(8)}")

            // If already bonded, inform the user instead of re-adding
            val existing = ContactStore.load(requireContext()).find { it.publicKey == info.publicKey }
            if (existing != null) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.already_bonded_title)
                    .setMessage(getString(R.string.already_bonded_message, existing.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        (requireActivity() as? MainActivity)?.navigateToBonds()
                    }
                    .show()
                return@registerForActivityResult
            }

            // Show PrivacyChoicesDialog
            pendingContactPk = info.publicKey
            showPrivacyDialog.value = true
        }.onFailure { e ->
            Log.e(TAG, "Crash in scan result handler", e)
            Toast.makeText(requireContext(), "Scan error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "HomeFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateQr()
        val messenger = P2PMessenger.get(requireContext())

        // Set initial connection status text
        binding.txtConnectStatus.text = getString(
            if (messenger.isTrackerConnected.value) R.string.connect_status_connected
            else R.string.connect_status_connecting
        )

        messenger.isTrackerConnected
            .onEach { connected ->
                if (connected) updateQr()
                binding.txtConnectStatus.text = getString(
                    if (connected) R.string.connect_status_connected
                    else R.string.connect_status_connecting
                )
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.btnScan.setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a TruSTI QR code")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }

        // Show QR content and explanation when clicked
        binding.imgQr.setOnClickListener {
            showQrContentDialog()
        }

        // Apply custom font to title and button
        FontExtensions.applyTsukimiFontFromResources(binding.txtTitle, requireContext(), 400)
        FontExtensions.applyTsukimiFontFromResources(binding.btnScan, requireContext(), 500)

        // Add ComposeView for PrivacyChoicesDialog to activity's root
        dialogComposeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TruSTITheme {
                    if (showPrivacyDialog.value && pendingContactPk != null) {
                        PrivacyChoicesDialog(
                            context = requireContext(),
                            contactPublicKey = pendingContactPk!!,
                            onConfirm = { name, disambiguation, prefs ->
                                Log.d(TAG, "Starting handshake for ${pendingContactPk!!.take(8)}")
                                P2PMessenger.get(requireContext()).startHandshakeWithPrefs(
                                    name, disambiguation, pendingContactPk!!, prefs
                                )
                                showPrivacyDialog.value = false
                                pendingContactPk = null
                                (requireActivity() as? MainActivity)?.navigateToBonds()
                            },
                            onCancel = {
                                showPrivacyDialog.value = false
                                pendingContactPk = null
                            }
                        )
                    }
                }
            }
        }
        val activity = requireActivity() as? MainActivity
        activity?.let {
            (it.window.decorView as? android.view.ViewGroup)?.addView(dialogComposeView)
        }
    }

    override fun onResume() {
        super.onResume()
        updateQr()
    }

    private fun updateQr() {
        val keyPair = KeyManager.getOrCreateKeyPair(requireContext())
        val pubKeyB64 = KeyManager.publicKeyToBase64Url(keyPair.public)
        binding.imgQr.setImageBitmap(QrHelper.generateBitmap(requireContext(), pubKeyB64))
    }

    private fun showQrContentDialog() {
        val keyPair = KeyManager.getOrCreateKeyPair(requireContext())
        val pubKeyB64 = KeyManager.publicKeyToBase64Url(keyPair.public)

        // Build the QR URI content
        val qrUri = buildQrUri(pubKeyB64)

        // Build the explanation text
        val explanation = buildQrExplanation(pubKeyB64)

        AlertDialog.Builder(requireContext())
            .setTitle("Your TruSTI QR Code")
            .setMessage(explanation)
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard(qrUri)
                Toast.makeText(requireContext(), "QR code copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildQrUri(pubKeyB64: String): String {
        return "trusti://peer?pk=$pubKeyB64"
    }

    private fun buildQrExplanation(pubKeyB64: String): String {
        return """
📱 Your Personal QR Code

Your unique identity code that others scan to bond with you.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 WHAT'S ENCODED:

🔑 Public Key (pk):
${pubKeyB64.take(32)}...

💬 Your name & preferences are shared during bonding

━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ℹ️ HOW IT WORKS:

1. Share this QR code with someone you trust
2. They scan it with the TruSTI app
3. You both choose what to share (name, status, history)
4. Your devices establish a secure connection
5. Exchange encrypted health information

Your private key NEVER leaves your phone.
The QR contains only your public key.
        """.trim()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("TruSTI QR", text)
        clipboard.setPrimaryClip(clip)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
