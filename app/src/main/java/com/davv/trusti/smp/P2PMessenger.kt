package com.davv.trusti.smp

import android.content.Context
import android.util.Log
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.model.Contact
import com.davv.trusti.model.DiseaseStatus
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.PendingStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class P2PMessenger private constructor(private val context: Context) {

    sealed class PeerEvent {
        data class ChannelOpened(val contact: Contact, val isNew: Boolean = false) : PeerEvent()
        data class ChannelClosed(val contact: Contact) : PeerEvent()
        data class StatusResponse(val fromPublicKey: String, val hasPositive: Boolean, val queuedAt: Long = 0L) : PeerEvent()
        data class IncomingBondRequest(
            val contactPk: String,
            val senderName: String,
            val senderDisambig: String,
            val senderSharingPrefs: SharingPreferences
        ) : PeerEvent()
        data class BondRemoved(val contactPk: String) : PeerEvent()
        data class RequestRejected(val contactPk: String) : PeerEvent()
    }

    private val _peerEventFlow = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 16)
    val peerEventFlow: SharedFlow<PeerEvent> = _peerEventFlow

    private val _isTrackerConnected = MutableStateFlow(false)
    val isTrackerConnected: StateFlow<Boolean> = _isTrackerConnected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var signaling: TorrentSignaling
    private lateinit var myPkB64: String
    private lateinit var myPublicKey: PublicKey
    private lateinit var myPrivateKey: PrivateKey

    data class PeerSession(
        val contact: Contact?,
        val transport: WebRtcTransport?,
        val state: SessionState
    )
    enum class SessionState { OFFERING, ANSWERING, ACCEPTING, CONNECTING, CONNECTED, RECONNECTING }

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val pendingContactByPk = ConcurrentHashMap<String, Contact>()
    private val pendingBondPrefs = ConcurrentHashMap<String, SharingPreferences>()  // our prefs for this contact
    private val pendingIncomingInfo = ConcurrentHashMap<String, Triple<String, String, SharingPreferences>>()  // (name, disambig, their prefs)
    private val pendingHandshakes = ConcurrentLinkedQueue<Pair<Contact, SharingPreferences>>()  // (contact, our prefs)
    private val newlyBondedContacts = ConcurrentHashMap.newKeySet<String>()
    private val pendingOffers = ConcurrentHashMap<String, String>()
    private var initialized = false

    fun initialize() {
        synchronized(this) {
            if (initialized) {
                Log.d(TAG, "initialize: already running — skipping")
                return
            }
            initialized = true
        }

        val kp = KeyManager.getOrCreateKeyPair(context)
        myPublicKey = kp.public
        myPrivateKey = kp.private
        myPkB64 = KeyManager.publicKeyToBase64Url(myPublicKey)
        Log.d(TAG, "initialize myPk=${myPkB64.take(8)}")

        WebRtcTransport.initializeWebRtc(context)

        signaling = TorrentSignaling(scope)
        scope.launch {
            signaling.isConnected.collect { connected ->
                _isTrackerConnected.value = connected
                if (connected) onTrackerConnected()
            }
        }

        scope.launch {
            signaling.events.collect { event ->
                when (event) {
                    is TorrentSignaling.Event.Offer -> handleIncomingOffer(event)
                    is TorrentSignaling.Event.Answer -> handleIncomingAnswer(event)
                }
            }
        }

        signaling.connect()
    }

    fun onDestroy() {
        synchronized(this) {
            if (!initialized) return
            initialized = false
        }

        sessions.values.forEach { it.transport?.close() }
        sessions.clear()
        signaling.disconnect()
        Log.d(TAG, "P2PMessenger destroyed")
    }

    fun startHandshakeWithPrefs(name: String, disambiguation: String, contactPk: String, prefs: SharingPreferences) {
        Log.d(TAG, "startHandshakeWithPrefs target=${contactPk.take(8)} name=$name")

        sessions.remove(contactPk)?.transport?.close()
        val contact = Contact(name = name, publicKey = contactPk, disambiguation = disambiguation)
        pendingContactByPk[contactPk] = contact
        pendingBondPrefs[contactPk] = prefs
        newlyBondedContacts.add(contactPk)

        if (!signaling.isConnected.value) {
            Log.d(TAG, "startHandshakeWithPrefs: tracker not ready — queuing")
            pendingHandshakes.add(contact to prefs)
            return
        }

        val room = sha256Hex(contactPk)
        createOffer(contactPk, room, name, disambiguation, prefs)
    }

    fun approveIncomingRequest(contactPk: String, myName: String, myDisambig: String, myPrefs: SharingPreferences) {
        val (theirName, theirDisambig, theirPrefs) = pendingIncomingInfo.remove(contactPk) ?: Triple("", "", SharingPreferences())
        Log.d(TAG, "approveIncomingRequest pk=${contactPk.take(8)} myName=$myName")

        newlyBondedContacts.add(contactPk)
        val contact = Contact(
            name = theirName,
            publicKey = contactPk,
            disambiguation = theirDisambig,
            sharingPreferences = theirPrefs,
            ourSharingPrefs = myPrefs
        )
        ContactStore.save(context, contact)
        signaling.announce(sha256Hex(listOf(myPkB64, contactPk).sorted().joinToString("")))

        val session = sessions[contactPk]
        if (session?.transport?.isOpen() == true) {
            sendBondResponse(contactPk, accepted = true, myName, myDisambig, myPrefs)
            val isNew = newlyBondedContacts.remove(contactPk)
            scope.launch {
                _peerEventFlow.emit(PeerEvent.ChannelOpened(contact, isNew))
                sendStatusRequest(contact)
            }
        }
    }

    fun rejectIncomingRequest(contactPk: String) {
        Log.d(TAG, "rejectIncomingRequest pk=${contactPk.take(8)}")
        val session = sessions.remove(contactPk)
        if (session?.transport?.isOpen() == true) {
            sendBondResponse(contactPk, accepted = false)
        }
        session?.transport?.close()
        cleanupContact(contactPk)
    }

    fun closeContact(contactPk: String) {
        Log.d(TAG, "closeContact pk=${contactPk.take(8)}")
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk)
        val session = sessions[contactPk]
        if (recipientPub != null && session?.transport?.isOpen() == true) {
            val payload = JSONObject().put("t", "bye").toString().toByteArray()
            encryptTo(payload, recipientPub.encoded)?.let { session.transport.sendMessage(it) }
        }
        ContactStore.delete(context, contactPk)
        cleanupContact(contactPk)
        signaling.removeRoom(sha256Hex(listOf(myPkB64, contactPk).sorted().joinToString("")))
        PendingStatusStore.consumeUpdate(context, contactPk)
    }

    fun connectedPeers(): Set<String> =
        sessions.entries.filter { it.value.transport?.isOpen() == true }.map { it.key }.toSet()

    fun pushMyStatusToAll() {
        Log.d(TAG, "pushMyStatusToAll")
        ContactStore.load(context).forEach { contact ->
            val session = sessions[contact.publicKey]
            if (session?.transport?.isOpen() == true) {
                pushMyStatus(contact.publicKey)
            } else {
                PendingStatusStore.addUpdate(context, contact.publicKey, hasPositive = false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private fun onTrackerConnected() {
        Log.d(TAG, "tracker connected — rejoining ${ContactStore.load(context).size} contacts")
        signaling.announce(sha256Hex(myPkB64))
        ContactStore.load(context).forEach { contact ->
            val iAmInitiator = myPkB64 < contact.publicKey
            if (iAmInitiator) {
                createOffer(contact.publicKey, sha256Hex(listOf(myPkB64, contact.publicKey).sorted().joinToString("")))
            } else {
                signaling.announce(sha256Hex(listOf(myPkB64, contact.publicKey).sorted().joinToString("")))
            }
        }

        var handshakePair = pendingHandshakes.poll()
        while (handshakePair != null) {
            val (contact, prefs) = handshakePair
            Log.d(TAG, "flushing queued handshake for ${contact.publicKey.take(8)}")
            startHandshakeWithPrefs(contact.name, contact.disambiguation, contact.publicKey, prefs)
            handshakePair = pendingHandshakes.poll()
        }
    }

    private fun handleIncomingOffer(event: TorrentSignaling.Event.Offer) {
        val fromPk = event.fromPk
            ?: event.sdp.lineSequence().firstOrNull { it.startsWith("a=x-trusti-pk:") }
                ?.removePrefix("a=x-trusti-pk:")?.trim()
            ?: run {
                Log.w(TAG, "offer missing from_pk")
                return
            }

        val myPersonalRoom = sha256Hex(myPkB64)
        val permanentRoom = sha256Hex(listOf(myPkB64, fromPk).sorted().joinToString(""))
        val isHandshake = event.roomId == myPersonalRoom
        val isPermanent = event.roomId == permanentRoom

        if (!isHandshake && !isPermanent) {
            Log.w(TAG, "offer on unknown room")
            return
        }

        if (sessions[fromPk]?.transport?.isOpen() == true) {
            Log.d(TAG, "already connected to ${fromPk.take(8)}")
            return
        }

        val cleanSdp = event.sdp.split("\r\n", "\n")
            .filter { it.isNotEmpty() && !it.startsWith("a=x-trusti-pk:") }
            .joinToString("\r\n")

        Log.d(TAG, "← offer from=${fromPk.take(8)} handshake=$isHandshake")

        val room = if (isPermanent) permanentRoom else myPersonalRoom
        sessions.remove(fromPk)?.transport?.close()
        val transport = WebRtcTransport(
            scope = scope,
            myPk = myPkB64,
            offerRoom = room,
            signaling = signaling,
            onMessage = { bytes -> handleRawMessage(fromPk, bytes) },
            onStatusChange = { open -> onTransportStatus(fromPk, open) }
        )
        sessions[fromPk] = PeerSession(contact = null, transport = transport, state = SessionState.ANSWERING)
        transport.handleOffer(cleanSdp, event.offerId, event.peerId)

        if (isHandshake) {
            val senderName = event.fromUsername ?: ""
            val senderDisambig = event.fromDisambig ?: ""
            // Try to extract prefs from SDP as fallback (in case tracker strips them from offer metadata)
            var senderPrefs = SharingPreferences()
            event.sdp.lineSequence().forEach { line ->
                if (line.startsWith("a=x-trusti-prefs:")) {
                    val prefsStr = line.removePrefix("a=x-trusti-prefs:")
                    runCatching {
                        val prefsJson = JSONObject(prefsStr)
                        senderPrefs = SharingPreferences(
                            shareCurrentStatus = prefsJson.optBoolean("shareCurrentStatus", true),
                            shareHistory = prefsJson.optBoolean("shareHistory", true)
                        )
                    }
                }
            }
            pendingIncomingInfo[fromPk] = Triple(senderName, senderDisambig, senderPrefs)
            Log.d(TAG, "handshake → emitting IncomingBondRequest from $senderName")
            scope.launch {
                _peerEventFlow.emit(PeerEvent.IncomingBondRequest(fromPk, senderName, senderDisambig, senderPrefs))
            }
        }
    }

    private fun handleIncomingAnswer(event: TorrentSignaling.Event.Answer) {
        val contactPk = pendingOffers.remove(event.offerId) ?: run {
            Log.w(TAG, "answer for unknown offerId")
            return
        }
        Log.d(TAG, "← answer for ${contactPk.take(8)}")
        sessions[contactPk]?.transport?.handleAnswer(event.sdp)
    }

    private fun sendBondResponse(contactPk: String, accepted: Boolean, myName: String = "", myDisambig: String = "", myPrefs: SharingPreferences = SharingPreferences()) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
        val payload = JSONObject().apply {
            put("t", "bresp")
            put("accepted", accepted)
            if (accepted) {
                put("name", myName)
                put("d", myDisambig)
                put("prefs", JSONObject().apply {
                    put("shareCurrentStatus", myPrefs.shareCurrentStatus)
                    put("shareHistory", myPrefs.shareHistory)
                })
            }
        }.toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub.encoded) ?: return
        sessions[contactPk]?.transport?.sendMessage(encrypted)
        Log.d(TAG, "sent bresp for ${contactPk.take(8)} accepted=$accepted")
    }

    private fun handleBondResponse(contactPk: String, json: JSONObject) {
        val accepted = json.optBoolean("accepted", false)
        Log.d(TAG, "received bresp from ${contactPk.take(8)} accepted=$accepted")

        if (!accepted) {
            sessions.remove(contactPk)?.transport?.close()
            pendingOffers.entries.removeIf { it.value == contactPk }
            scope.launch {
                _peerEventFlow.emit(PeerEvent.RequestRejected(contactPk))
            }
            return
        }

        // Accepted: extract their info and save contact
        val theirName = json.optString("name", "")
        val theirDisambig = json.optString("d", "")
        val prefsJson = json.optJSONObject("prefs")
        val theirPrefs = prefsJson?.let {
            SharingPreferences(
                shareCurrentStatus = it.optBoolean("shareCurrentStatus", true),
                shareHistory = it.optBoolean("shareHistory", true)
            )
        } ?: SharingPreferences()

        val ourPrefs = pendingBondPrefs.remove(contactPk) ?: SharingPreferences()
        val contact = Contact(
            name = theirName,
            publicKey = contactPk,
            disambiguation = theirDisambig,
            sharingPreferences = theirPrefs,
            ourSharingPrefs = ourPrefs
        )
        ContactStore.save(context, contact)
        signaling.announce(sha256Hex(listOf(myPkB64, contactPk).sorted().joinToString("")))

        val isNew = newlyBondedContacts.remove(contactPk)
        scope.launch {
            _peerEventFlow.emit(PeerEvent.ChannelOpened(contact, isNew))
            sendStatusRequest(contact)
        }
    }

    private fun createOffer(contactPk: String, room: String, myName: String = "", myDisambig: String = "", myPrefs: SharingPreferences = SharingPreferences()) {
        Log.d(TAG, "createOffer → ${contactPk.take(8)}")
        sessions.remove(contactPk)?.transport?.close()

        val transport = WebRtcTransport(
            scope = scope,
            myPk = myPkB64,
            offerRoom = room,
            signaling = signaling,
            onMessage = { bytes -> handleRawMessage(contactPk, bytes) },
            onStatusChange = { open -> onTransportStatus(contactPk, open) },
            onOfferSent = { offerId -> pendingOffers[offerId] = contactPk }
        )
        sessions[contactPk] = PeerSession(contact = null, transport = transport, state = SessionState.OFFERING)
        transport.createOffer()

        scope.launch {
            repeat(OFFER_RETRY_COUNT) {
                delay(OFFER_RETRY_INTERVAL_MS)
                val t = sessions[contactPk]?.transport ?: return@launch
                if (t.isOpen() || t.isClosed()) return@launch
                Log.d(TAG, "re-announcing offer for ${contactPk.take(8)} (attempt ${it + 2})")
                t.reannounce()
            }
            if (sessions[contactPk]?.transport?.isOpen() != true) {
                pendingOffers.entries.removeIf { it.value == contactPk }
                Log.d(TAG, "offer retries exhausted for ${contactPk.take(8)}")
                scope.launch {
                    _peerEventFlow.emit(PeerEvent.RequestRejected(contactPk))
                }
            }
        }
    }

    private fun onTransportStatus(contactPk: String, open: Boolean) {
        Log.d(TAG, "transport status pk=${contactPk.take(8)} open=$open")
        val session = sessions[contactPk] ?: return
        val contact = session.contact ?: run {
            ContactStore.load(context).find { it.publicKey == contactPk }
                ?: Contact(name = "Contact ${contactPk.take(8)}", publicKey = contactPk)
        }

        scope.launch {
            if (open) {
                signaling.announce(sha256Hex(listOf(myPkB64, contactPk).sorted().joinToString("")))
                sendStatusRequest(contact)
                deliverPendingStatus(contactPk)

                val isNew = newlyBondedContacts.remove(contactPk)
                _peerEventFlow.emit(PeerEvent.ChannelOpened(contact, isNew))
            } else {
                _peerEventFlow.emit(PeerEvent.ChannelClosed(contact))
            }
        }
    }

    private fun handleRawMessage(contactPk: String, bytes: ByteArray) {
        val plaintext = decryptMessage(contactPk, bytes) ?: return
        val json = runCatching { JSONObject(String(plaintext)) }.getOrNull() ?: return

        val messageType = json.optString("t")
        Log.d(TAG, "← type=$messageType from=${contactPk.take(8)}")

        when (messageType) {
            "sreq" -> handleStatusRequest(contactPk)
            "srsp" -> {
                val hasPositive = json.optBoolean("pos", false)
                scope.launch {
                    _peerEventFlow.emit(PeerEvent.StatusResponse(contactPk, hasPositive, System.currentTimeMillis()))
                    saveStatusToContact(contactPk, hasPositive)
                }
            }
            "bresp" -> handleBondResponse(contactPk, json)
            "bye" -> {
                Log.d(TAG, "← bye from ${contactPk.take(8)}")
                ContactStore.delete(context, contactPk)
                sessions.remove(contactPk)?.transport?.close()
                signaling.removeRoom(sha256Hex(listOf(myPkB64, contactPk).sorted().joinToString("")))
                PendingStatusStore.consumeUpdate(context, contactPk)
                scope.launch {
                    _peerEventFlow.emit(PeerEvent.BondRemoved(contactPk))
                }
            }
        }
    }

    fun sendStatusRequest(contact: Contact) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contact.publicKey) ?: return
        val payload = JSONObject().put("t", "sreq").toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub.encoded) ?: return
        sessions[contact.publicKey]?.transport?.sendMessage(encrypted)
    }

    private fun pushMyStatus(contactPk: String) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
        val payload = JSONObject().put("t", "srsp").put("pos", false).toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub.encoded) ?: return
        sessions[contactPk]?.transport?.sendMessage(encrypted)
    }

    private fun deliverPendingStatus(contactPk: String) {
        val pending = PendingStatusStore.consumeUpdate(context, contactPk) ?: return
        Log.d(TAG, "delivering queued status to ${contactPk.take(8)}")
        pushMyStatus(contactPk)
    }

    private fun sendAcc(contactPk: String) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
        val payload = JSONObject().put("t", "acc").toString().toByteArray()
        encryptTo(payload, recipientPub.encoded)?.let { sessions[contactPk]?.transport?.sendMessage(it) }
    }

    private fun handleStatusRequest(contactPk: String) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
        val payload = JSONObject().put("t", "srsp").put("pos", false).toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub.encoded) ?: return
        sessions[contactPk]?.transport?.sendMessage(encrypted)
    }

    private fun saveStatusToContact(contactPk: String, hasPositive: Boolean) {
        val contacts = ContactStore.load(context).toMutableList()
        val idx = contacts.indexOfFirst { it.publicKey == contactPk }
        if (idx >= 0) {
            contacts[idx] = contacts[idx].copy(diseaseStatus = DiseaseStatus(hasPositive, System.currentTimeMillis()))
            ContactStore.save(context, contacts[idx])
        }
    }

    private fun decryptMessage(contactPk: String, bytes: ByteArray): ByteArray? =
        runCatching {
            Encryption.decrypt(bytes, myPrivateKey)
        }.getOrElse { exception ->
            Log.e(TAG, "decrypt failed: ${exception.message}")
            null
        }

    private fun encryptTo(payload: ByteArray, pub: ByteArray): ByteArray? =
        runCatching { Encryption.encrypt(payload, pub) }.getOrElse {
            Log.e(TAG, "encrypt failed: ${it.message}")
            null
        }

    private fun cleanupContact(contactPk: String) {
        sessions.remove(contactPk)?.transport?.close()
        pendingContactByPk.remove(contactPk)
        pendingIncomingInfo.remove(contactPk)
        newlyBondedContacts.remove(contactPk)
        pendingOffers.entries.removeIf { it.value == contactPk }
    }

    companion object {
        private const val TAG = "P2PMessenger"
        private const val OFFER_RETRY_COUNT = 6
        private const val OFFER_RETRY_INTERVAL_MS = 5_000L

        @Volatile private var instance: P2PMessenger? = null
        fun get(context: Context): P2PMessenger =
            instance ?: synchronized(this) {
                instance ?: P2PMessenger(context.applicationContext).also { instance = it }
            }
    }
}
