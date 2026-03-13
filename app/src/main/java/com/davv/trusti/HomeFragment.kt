package com.davv.trusti

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.davv.trusti.connection.QrHelper
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.databinding.FragmentHomeBinding
import com.davv.trusti.model.Contact
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.FontExtensions
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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

            val contact = Contact(
                name = "Peer ${info.publicKey.take(8)}",
                publicKey = info.publicKey
            )
            ContactStore.save(requireContext(), contact)
            Log.d(TAG, "Contact saved: ${contact.publicKey.take(8)}")

            Toast.makeText(
                requireContext(),
                "Added contact: ${info.publicKey.take(8)}",
                Toast.LENGTH_SHORT
            ).show()

            val messenger = P2PMessenger.get(requireContext())
            Log.d(TAG, "isTrackerConnected=${messenger.isTrackerConnected.value} — starting handshake")
            messenger.startHandshake(contact)

            updateQr()
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
        
        messenger.isTrackerConnected
            .onEach { if (it) updateQr() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.btnScan.setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a TruSTI QR code")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }
        
        // Apply custom font to title and button
        FontExtensions.applyTsukimiFontFromResources(binding.txtTitle, requireContext(), 400)
        FontExtensions.applyTsukimiFontFromResources(binding.btnScan, requireContext(), 500)
    }

    override fun onResume() {
        super.onResume()
        updateQr()
    }

    private fun updateQr() {
        val keyPair = KeyManager.getOrCreateKeyPair(requireContext())
        val pubKeyB64 = KeyManager.publicKeyToBase64Url(keyPair.public)
        binding.imgQr.setImageBitmap(QrHelper.generateBitmap(requireContext(), pubKeyB64, null, null))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
