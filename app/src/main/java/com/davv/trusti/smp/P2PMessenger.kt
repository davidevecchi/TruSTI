package com.davv.trusti.smp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.model.Contact
import com.davv.trusti.model.Message
import com.davv.trusti.model.TestResult
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.MessageStore
import com.davv.trusti.utils.PendingStatusStore
import com.davv.trusti.utils.ProfileManager
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class P2PMessenger private constructor(private val ctx: Context) {

    sealed class PeerEvent {
        data class ChannelOpened(val contact: Contact) : PeerEvent()
        data class ChannelClosed(val contact: Contact) : PeerEvent()
        data class StatusResponse(val fromPublicKey: String, val hasPositive: Boolean, val queuedAt: Long = 0L) : PeerEvent()
        data class IncomingRequest(val contactPk: String) : PeerEvent()
        data class BondRemoved(val contactPk: String) : PeerEvent()
    }

    private val _messageFlow    = MutableSharedFlow<Message>(extraBufferCapacity = 128)
    val messageFlow: SharedFlow<Message> = _messageFlow

    private val _peerEventFlow  = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 16)
    val peerEventFlow: SharedFlow<PeerEvent> = _peerEventFlow

    private val _isTrackerConnected = MutableStateFlow(false)
    val isTrackerConnected: StateFlow<Boolean> = _isTrackerConnected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var signaling:    TorrentSignaling
    private lateinit var myPkB64:      String
    private lateinit var myPublicKey:  PublicKey
    private lateinit var myPrivateKey: PrivateKey

    // contactPk → active transport
    private val transports    = ConcurrentHashMap<String, WebRtcTransport>()
    // offerId → contactPk  (so we can match incoming answers)
    private val pendingOffers = ConcurrentHashMap<String, String>()
    // contacts waiting for user approval (IncomingRequest not yet approved)
    private val pendingApproval = ConcurrentHashMap.newKeySet<String>()
    // handshakes requested before tracker was ready
    private val pendingHandshakes = ConcurrentLinkedQueue<Contact>()
    // active offer-retry job per contact (cancelled when a new offer starts or channel opens)
    private val retryJobs = ConcurrentHashMap<String, Job>()
    // last offerId already handled per peer — prevents re-processing retried offers
    private val handledOffers = ConcurrentHashMap<String, String>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun initialize() {
        val kp = KeyManager.getOrCreateKeyPair(ctx)
        myPublicKey  = kp.public
        myPrivateKey = kp.private
        myPkB64      = KeyManager.publicKeyToBase64Url(myPublicKey)
        Log.d(TAG, "initialize myPk=${myPkB64.take(8)}")

        WebRtcTransport.initializeWebRtc(ctx)

        signaling = TorrentSignaling(scope)
        signaling.onConnected    = { onTrackerConnected() }
        signaling.onDisconnected = {
            Log.d(TAG, "tracker disconnected")
            _isTrackerConnected.value = false
        }
        signaling.onTrackerError = { err -> Log.e(TAG, "tracker error: $err") }

        scope.launch {
            signaling.events.collect { event ->
                when (event) {
                    is TorrentSignaling.Event.Offer  -> handleIncomingOffer(event)
                    is TorrentSignaling.Event.Answer -> handleIncomingAnswer(event)
                }
            }
        }

        signaling.connect()
    }

    fun onDestroy() {
        transports.values.forEach { it.close() }
        transports.clear()
        signaling.disconnect()
    }

    /** Called when user scans a QR code and wants to connect. */
    fun startHandshake(contact: Contact) {
        if (!signaling.isReady) {
            Log.d(TAG, "startHandshake: tracker not ready — queuing for ${contact.publicKey.take(8)}")
            pendingHandshakes.add(contact)
            return
        }
        val room = sha256Hex(contact.publicKey)   // Bob's personal room
        Log.d(TAG, "startHandshake target=${contact.publicKey.take(8)} room=${room.take(16)}")
        createOffer(contact.publicKey, room, force = true)
    }

    /** User tapped Accept on the IncomingRequest dialog. */
    fun approveIncomingRequest(contactPk: String, name: String) {
        Log.d(TAG, "approveIncomingRequest pk=${contactPk.take(8)} name=$name")
        val contact = Contact(
            name           = name,
            publicKey      = contactPk,
            disambiguation = ProfileManager.getDisambiguation(ctx)
        )
        ContactStore.save(ctx, contact)
        pendingApproval.remove(contactPk)

        // If DataChannel already opened while user was deciding, emit now
        if (transports[contactPk]?.isOpen() == true) {
            Log.d(TAG, "DC already open at approval — emitting ChannelOpened")
            scope.launch { _peerEventFlow.emit(PeerEvent.ChannelOpened(contact)) }
        }

        // Join permanent room so we can reconnect later
        signaling.announce(deriveRoomId(myPkB64, contactPk))
    }

    /** User tapped Decline. */
    fun rejectIncomingRequest(contactPk: String) {
        Log.d(TAG, "rejectIncomingRequest pk=${contactPk.take(8)}")
        pendingApproval.remove(contactPk)
        // handledOffers is intentionally kept: retries of the same offerId stay blocked.
        // A new dialog only appears when A generates a fresh offer (new offerId) by rescanning.
        transports.remove(contactPk)?.close()
    }

    fun closeContact(contactPk: String) {
        Log.d(TAG, "closeContact pk=${contactPk.take(8)}")
        retryJobs.remove(contactPk)?.cancel()
        // Notify the peer so they can remove us from their bonds too
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk)
        if (recipientPub != null && transports[contactPk]?.isOpen() == true) {
            val payload = JSONObject().put("t", "bye").toString().toByteArray()
            encryptTo(payload, recipientPub)?.let { transports[contactPk]?.sendMessage(it) }
        }
        transports.remove(contactPk)?.close()
        signaling.removeRoom(deriveRoomId(myPkB64, contactPk))
        PendingStatusStore.consumeUpdate(ctx, contactPk) // clean up queued status
    }

    fun sendMessage(contactPk: String, text: String) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: run {
            Log.e(TAG, "sendMessage: cannot decode public key for ${contactPk.take(8)}")
            return
        }
        val payload  = JSONObject().put("t", "msg").put("c", text).toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub) ?: run {
            Log.e(TAG, "sendMessage: encryption failed")
            return
        }
        if (transports[contactPk]?.sendMessage(encrypted) != true) {
            Log.w(TAG, "sendMessage: channel not open for ${contactPk.take(8)}")
            return
        }
        Log.d(TAG, "→ msg to ${contactPk.take(8)}: \"${text.take(40)}\"")

        val msg = Message(
            id               = UUID.randomUUID().toString(),
            contactPublicKey = contactPk,
            content          = text,
            timestamp        = System.currentTimeMillis(),
            isOutbound       = true
        )
        MessageStore.save(ctx, msg)
        scope.launch { _messageFlow.emit(msg) }
    }

    fun queryStatus(contactPk: String) {
        val contact = ContactStore.load(ctx).find { it.publicKey == contactPk } ?: return
        sendStatusRequest(contact)
    }

    fun sendStatusRequest(contact: Contact) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contact.publicKey) ?: return
        val payload   = JSONObject().put("t", "sreq").toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub) ?: return
        transports[contact.publicKey]?.sendMessage(encrypted)
    }

    /** Push my current test status to all contacts — open channels immediately, others queued. */
    fun pushMyStatusToAll() {
        val hasPositive = myHasPositive()
        ContactStore.load(ctx).forEach { contact ->
            if (transports[contact.publicKey]?.isOpen() == true) {
                pushMyStatus(contact.publicKey, hasPositive)
            } else {
                PendingStatusStore.addUpdate(ctx, contact.publicKey, hasPositive)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — signaling events
    // -------------------------------------------------------------------------

    private fun onTrackerConnected() {
        _isTrackerConnected.value = true
        Log.d(TAG, "tracker connected — announcing personal room, rejoining ${ContactStore.load(ctx).size} contacts")
        // Listen on our personal room so others can initiate handshakes
        signaling.announce(sha256Hex(myPkB64))
        // Rejoin permanent rooms for all known contacts
        ContactStore.load(ctx).forEach { reconnect(it) }
        // Flush handshakes that were queued before tracker was ready
        var contact = pendingHandshakes.poll()
        while (contact != null) {
            Log.d(TAG, "flushing queued handshake for ${contact.publicKey.take(8)}")
            startHandshake(contact)
            contact = pendingHandshakes.poll()
        }
    }

    private fun handleIncomingOffer(event: TorrentSignaling.Event.Offer) {
        // Trackers strip unknown fields from the offer object — fall back to the SDP attribute
        val fromPk = event.fromPk
            ?: event.sdp.lineSequence().firstOrNull { it.startsWith("a=x-trusti-pk:") }
                ?.removePrefix("a=x-trusti-pk:")?.trim()
            ?: run { Log.w(TAG, "offer missing from_pk — ignored"); return }

        val myPersonalRoom = sha256Hex(myPkB64)
        val permanentRoom  = deriveRoomId(myPkB64, fromPk)

        val isHandshake = event.roomId == myPersonalRoom
        val isPermanent = event.roomId == permanentRoom
        if (!isHandshake && !isPermanent) {
            Log.w(TAG, "offer on unknown room=${event.roomId.take(16)} — ignored")
            return
        }

        val isKnown = ContactStore.load(ctx).any { it.publicKey == fromPk }

        // Deduplication: same offerId from same peer = retry announce, ignore entirely
        if (handledOffers[fromPk] == event.offerId) {
            Log.d(TAG, "duplicate offer ${event.offerId.take(8)} from ${fromPk.take(8)} — skipping")
            return
        }
        handledOffers[fromPk] = event.offerId
        Log.d(TAG, "← offer from=${fromPk.take(8)} room=${if (isHandshake) "handshake" else "permanent"} known=$isKnown")

        // Strip the identity attribute before passing to WebRTC
        val cleanSdp = event.sdp.lines()
            .filter { !it.startsWith("a=x-trusti-pk:") }
            .joinToString("\r\n")

        val room = if (isPermanent) permanentRoom else myPersonalRoom
        transports.remove(fromPk)?.close()
        val transport = buildAnswerTransport(fromPk, room)
        transports[fromPk] = transport
        transport.handleOffer(cleanSdp, event.offerId, event.peerId)

        if (isHandshake && !isKnown) {
            pendingApproval.add(fromPk)
            Log.d(TAG, "unknown peer — emitting IncomingRequest for ${fromPk.take(8)}")
            scope.launch { _peerEventFlow.emit(PeerEvent.IncomingRequest(fromPk)) }
        }
    }

    private fun handleIncomingAnswer(event: TorrentSignaling.Event.Answer) {
        val contactPk = pendingOffers.remove(event.offerId) ?: run {
            Log.w(TAG, "answer for unknown offerId=${event.offerId.take(8)} — ignored")
            return
        }
        Log.d(TAG, "← answer for ${contactPk.take(8)} offerId=${event.offerId.take(8)}")
        // B received the offer and responded — stop re-announcing regardless of outcome
        retryJobs.remove(contactPk)?.cancel()
        transports[contactPk]?.handleAnswer(event.sdp)
    }

    // -------------------------------------------------------------------------
    // Private — transport management
    // -------------------------------------------------------------------------

    private fun reconnect(contact: Contact) {
        val iAmInitiator = myPkB64 < contact.publicKey
        Log.d(TAG, "reconnect ${contact.publicKey.take(8)} initiator=$iAmInitiator")
        if (iAmInitiator) {
            // Always use the peer's personal room so the offer reaches them even if they deleted us.
            // They still always listen there; a known contact simply skips the IncomingRequest dialog.
            createOffer(contact.publicKey, sha256Hex(contact.publicKey))
        } else {
            // Answerer side: listen on the permanent room for the initiator's offer
            signaling.announce(deriveRoomId(myPkB64, contact.publicKey))
        }
    }

    private fun createOffer(contactPk: String, room: String, force: Boolean = false) {
        if (!force && retryJobs[contactPk]?.isActive == true) {
            Log.d(TAG, "createOffer: retry already active for ${contactPk.take(8)} — skipping")
            return
        }
        Log.d(TAG, "createOffer → ${contactPk.take(8)} room=${room.take(16)}")
        transports.remove(contactPk)?.close()
        val transport = WebRtcTransport(
            scope          = scope,
            myPk           = myPkB64,
            offerRoom      = room,
            signaling      = signaling,
            onMessage      = { bytes -> handleRawMessage(contactPk, bytes) },
            onStatusChange = { open  -> onTransportStatus(contactPk, open) },
            onOfferSent    = { offerId -> pendingOffers[offerId] = contactPk },
            signOffer      = { offerId, sdp ->
                Base64.encodeToString(
                    KeyManager.sign("$offerId$sdp".toByteArray(), myPrivateKey),
                    Base64.NO_WRAP
                )
            }
        )
        transports[contactPk] = transport
        transport.createOffer()
        // Cancel any previous retry loop for this contact, then start a fresh one.
        // Tracker only delivers offers to currently connected peers, so B may miss the first
        // announce if it reconnects later — we re-announce until the channel opens.
        retryJobs.remove(contactPk)?.cancel()
        retryJobs[contactPk] = scope.launch {
            repeat(OFFER_RETRY_COUNT) {
                delay(OFFER_RETRY_INTERVAL_MS)
                val t = transports[contactPk]
                if (t == null || t.isOpen() || t.isClosed()) return@launch
                Log.d(TAG, "re-announcing offer for ${contactPk.take(8)} (attempt ${it + 2})")
                t.reannounce()
            }
            retryJobs.remove(contactPk)
        }
    }

    private fun buildAnswerTransport(contactPk: String, room: String) = WebRtcTransport(
        scope          = scope,
        myPk           = myPkB64,
        offerRoom      = room,
        signaling      = signaling,
        onMessage      = { bytes -> handleRawMessage(contactPk, bytes) },
        onStatusChange = { open  -> onTransportStatus(contactPk, open) }
    )

    private fun onTransportStatus(contactPk: String, open: Boolean) {
        Log.d(TAG, "transport status pk=${contactPk.take(8)} open=$open pendingApproval=${pendingApproval.contains(contactPk)}")
        val contact = ContactStore.load(ctx).find { it.publicKey == contactPk }
            ?: Contact(name = contactPk.take(8), publicKey = contactPk)
        scope.launch {
            if (open) {
                // Gate ChannelOpened until user approves for unknown contacts
                if (pendingApproval.contains(contactPk)) {
                    Log.d(TAG, "DC open but waiting for user approval — holding ChannelOpened")
                    return@launch
                }
                Log.d(TAG, "ChannelOpened → ${contactPk.take(8)}")
                retryJobs.remove(contactPk)?.cancel()
                // Join permanent room now that we're connected
                signaling.announce(deriveRoomId(myPkB64, contactPk))
                _peerEventFlow.emit(PeerEvent.ChannelOpened(contact))
                // Request their status and deliver any queued status update for them
                sendStatusRequest(contact)
                deliverPendingStatus(contactPk)
            } else {
                Log.d(TAG, "ChannelClosed → ${contactPk.take(8)}")
                _peerEventFlow.emit(PeerEvent.ChannelClosed(contact))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — message protocol
    // -------------------------------------------------------------------------

    private fun handleRawMessage(contactPk: String, bytes: ByteArray) {
        val plain = runCatching { Encryption.decrypt(bytes, myPrivateKey) }.getOrElse {
            Log.e(TAG, "decrypt failed from ${contactPk.take(8)}: ${it.message}")
            return
        }
        val json = runCatching { JSONObject(String(plain)) }.getOrElse {
            Log.e(TAG, "JSON parse failed from ${contactPk.take(8)}: ${it.message}")
            return
        }
        Log.d(TAG, "← type=${json.optString("t")} from=${contactPk.take(8)}")

        when (json.optString("t")) {
            "msg" -> {
                val text = json.optString("c").takeIf { it.isNotEmpty() } ?: return
                val msg  = Message(
                    id               = UUID.randomUUID().toString(),
                    contactPublicKey = contactPk,
                    content          = text,
                    timestamp        = System.currentTimeMillis(),
                    isOutbound       = false
                )
                MessageStore.save(ctx, msg)
                scope.launch { _messageFlow.emit(msg) }
            }

            "sreq" -> {
                // Respond with our health status
                val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
                val hasPositive  = TestsStore.load(ctx).any { record ->
                    record.tests.any { it.result == TestResult.POSITIVE }
                }
                val payload   = JSONObject().put("t", "srsp").put("pos", hasPositive).toString().toByteArray()
                val encrypted = encryptTo(payload, recipientPub) ?: return
                transports[contactPk]?.sendMessage(encrypted)
            }

            "srsp" -> {
                val hasPositive = json.optBoolean("pos", false)
                scope.launch {
                    _peerEventFlow.emit(PeerEvent.StatusResponse(contactPk, hasPositive))
                }
            }

            "bye" -> {
                Log.d(TAG, "← bye from ${contactPk.take(8)} — removing bond")
                ContactStore.delete(ctx, contactPk)
                transports.remove(contactPk)?.close()
                signaling.removeRoom(deriveRoomId(myPkB64, contactPk))
                PendingStatusStore.consumeUpdate(ctx, contactPk)
                scope.launch { _peerEventFlow.emit(PeerEvent.BondRemoved(contactPk)) }
            }
        }
    }

    private fun myHasPositive(): Boolean =
        TestsStore.load(ctx).any { record -> record.tests.any { it.result == TestResult.POSITIVE } }

    private fun pushMyStatus(contactPk: String, hasPositive: Boolean = myHasPositive()) {
        val recipientPub = KeyManager.base64UrlToPublicKey(contactPk) ?: return
        val payload  = JSONObject().put("t", "srsp").put("pos", hasPositive).toString().toByteArray()
        val encrypted = encryptTo(payload, recipientPub) ?: return
        if (transports[contactPk]?.sendMessage(encrypted) == true)
            Log.d(TAG, "→ pushed status (pos=$hasPositive) to ${contactPk.take(8)}")
    }

    private fun deliverPendingStatus(contactPk: String) {
        val pending = PendingStatusStore.consumeUpdate(ctx, contactPk) ?: return
        Log.d(TAG, "delivering queued status (pos=${pending.hasPositive}) to ${contactPk.take(8)}")
        pushMyStatus(contactPk, pending.hasPositive)
    }

    private fun encryptTo(payload: ByteArray, pub: PublicKey): ByteArray? =
        runCatching { Encryption.encrypt(payload, pub) }.getOrElse {
            Log.e(TAG, "encrypt failed: ${it.message}")
            null
        }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "P2PMessenger"
        private const val OFFER_RETRY_COUNT       = 6          // retries after the first attempt
        private const val OFFER_RETRY_INTERVAL_MS = 5_000L    // 5 s between retries (30 s total)

        @Volatile private var instance: P2PMessenger? = null
        fun get(context: Context): P2PMessenger =
            instance ?: synchronized(this) {
                instance ?: P2PMessenger(context.applicationContext).also { instance = it }
            }
        fun getInstance(context: Context) = get(context)

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
