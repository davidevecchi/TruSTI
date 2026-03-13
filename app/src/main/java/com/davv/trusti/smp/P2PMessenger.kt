package com.davv.trusti.smp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.util.Base64
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.model.Contact
import com.davv.trusti.model.Message
import com.davv.trusti.model.TestResult
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.MessageStore
import com.davv.trusti.utils.PendingStatusStore
import com.davv.trusti.utils.ProfileManager
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P2P Messenger using WebRTC data channels and WebTorrent tracker signaling.
 *
 * Uses "vanilla ICE" — the SDP offer/answer includes all ICE candidates gathered before sending,
 * because the WebTorrent tracker does not relay separate trickle-ICE candidate messages.
 *
 * Room model:
 *  - Personal room  sha256(myPk)          — always announced; receives new-contact offers
 *  - Permanent room sha256(sorted(A+B))   — announced for each known contact; for reconnection
 *
 * Handshake flow (A scans B's QR):
 *  1. A calls startHandshake(B) → createOffer() → ICE gathers → announceWithOffer(sha256(B.pk))
 *  2. Tracker delivers offer to B (B announced sha256(B.pk) in initialize())
 *  3. B handleOffer → createAnswer → ICE gathers → sendAnswer(sha256(B.pk), A.trackerId, offerId)
 *  4. Tracker delivers answer to A → handleAnswer → WebRTC connected
 *  5. Both sides send an encrypted "identity" message (name/disambig) over the DataChannel
 */
class P2PMessenger private constructor(private val context: Context) {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception in scope", e) }
    )
    private val signaling = TorrentSignaling(scope = scope)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(msg: String) =
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    @Volatile private var initialized = false
    private lateinit var cachedMyPk: String

    private val transports = ConcurrentHashMap<String, WebRtcTransport>()   // contactPk → transport
    private val peerToContact = ConcurrentHashMap<String, String>()          // tracker peerId → contactPk
    private val offerIdToContact = ConcurrentHashMap<String, String>()       // offerId → contactPk

    // Offers from unknown peers awaiting user approval (approve/rejectIncomingRequest).
    private val pendingIncomingOffers = ConcurrentHashMap<String, OfferData>()

    // Encrypted messages queued while the DataChannel is not yet open.
    // ALL access must be inside synchronized(pendingMessages) to prevent the drain-before-enqueue race.
    private val pendingMessages = HashMap<String, MutableList<ByteArray>>()

    sealed class PeerEvent {
        data class ChannelOpened(val contact: Contact) : PeerEvent()
        data class ChannelClosed(val contact: Contact) : PeerEvent()
        data class StatusResponse(val fromPublicKey: String, val hasPositive: Boolean, val queuedAt: Long = 0L) : PeerEvent()
        data class IncomingRequest(val contactPk: String) : PeerEvent()
    }

    private data class OfferData(val offerId: String, val peerId: String, val sdp: String)

    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 128)
    val messageFlow: SharedFlow<Message> = _messageFlow

    private val _peerEventFlow = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 16)
    val peerEventFlow: SharedFlow<PeerEvent> = _peerEventFlow

    private val _isTrackerConnected = MutableStateFlow(false)
    val isTrackerConnected: StateFlow<Boolean> = _isTrackerConnected.asStateFlow()

    fun initialize() {
        if (initialized) {
            Log.w(TAG, "initialize() called again — skipping")
            return
        }
        initialized = true

        runCatching {
            WebRtcTransport.initializeWebRtc(context)
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize WebRTC", e)
            initialized = false
            return
        }

        cachedMyPk = KeyManager.publicKeyToBase64Url(KeyManager.getOrCreateKeyPair(context).public)

        signaling.onConnected    = { _isTrackerConnected.value = true  }
        signaling.onDisconnected = {
            _isTrackerConnected.value = false
            // Stale peerId→contact and offerId→contact mappings are invalid after reconnect
            // because the tracker assigns a new peerId on each connect() call.
            offerIdToContact.clear()
            peerToContact.clear()
        }
        signaling.onTrackerError = { reason -> Log.w(TAG, "Tracker error: $reason") }
        signaling.connect()

        scope.launch {
            signaling.events.collect { event ->
                runCatching { handleSignalingEvent(event) }
                    .onFailure { e -> Log.e(TAG, "Error handling signaling event", e) }
            }
        }

        scope.launch {
            // Personal room: always listen here so any peer can reach us.
            signaling.announce(sha256Hex(cachedMyPk))
            // Permanent rooms: one per known contact, used for reconnection offers.
            val contacts = withContext(Dispatchers.IO) { ContactStore.load(context) }
            contacts.forEach { contact ->
                signaling.announce(deriveRoomId(cachedMyPk, contact.publicKey))
            }
        }
    }

    private suspend fun handleSignalingEvent(event: TorrentSignaling.SignalingEvent) {
        when (event) {
            is TorrentSignaling.SignalingEvent.PeerDiscovered -> Unit

            is TorrentSignaling.SignalingEvent.Offer -> {
                val contactPk = event.fromPk ?: peerToContact[event.peerId] ?: run {
                    Log.w(TAG, "Ignoring offer: no fromPk and unknown peerId=${event.peerId}")
                    return
                }

                // Verify ECDSA signature when fromPk is present — prevents contact impersonation.
                if (event.fromPk != null) {
                    val pub = KeyManager.base64UrlToPublicKey(contactPk) ?: run {
                        Log.w(TAG, "Ignoring offer: invalid fromPk ${contactPk.take(8)}")
                        return
                    }
                    val sigB64 = event.sig ?: run {
                        Log.w(TAG, "Ignoring unsigned offer from ${contactPk.take(8)}")
                        return
                    }
                    val sigBytes = runCatching {
                        Base64.decode(sigB64, Base64.URL_SAFE or Base64.NO_WRAP)
                    }.getOrNull() ?: run {
                        Log.w(TAG, "Ignoring offer with malformed signature from ${contactPk.take(8)}")
                        return
                    }
                    if (!KeyManager.verify((event.offerId + event.sdp).toByteArray(), sigBytes, pub)) {
                        Log.w(TAG, "Ignoring offer with invalid signature from ${contactPk.take(8)}")
                        return
                    }
                    // Additional check: verify offerId format to prevent injection
                    if (!event.offerId.matches(Regex("^[a-zA-Z0-9]{8}$"))) {
                        Log.w(TAG, "Ignoring offer with malformed offerId from ${contactPk.take(8)}")
                        return
                    }
                }

                val isKnown = withContext(Dispatchers.IO) {
                    ContactStore.load(context).any { it.publicKey == contactPk }
                }

                if (!isKnown) {
                    Log.d(TAG, "Incoming request from unknown peer ${contactPk.take(8)}")
                    pendingIncomingOffers[contactPk] = OfferData(event.offerId, event.peerId, event.sdp)
                    _peerEventFlow.emit(PeerEvent.IncomingRequest(contactPk))
                    return
                }

                acceptOffer(contactPk, event.offerId, event.peerId, event.sdp)
            }

            is TorrentSignaling.SignalingEvent.Answer -> {
                val contactPk = offerIdToContact[event.offerId] ?: run {
                    Log.w(TAG, "Ignoring answer: unknown offerId")
                    return
                }
                peerToContact[event.peerId] = contactPk
                transports[contactPk]?.handleAnswer(event.sdp)
            }
        }
    }

    fun startHandshake(contact: Contact) {
        if (!initialized) { Log.e(TAG, "startHandshake() before initialize()"); return }
        scope.launch {
            runCatching {
                val existing = transports[contact.publicKey]
                if (existing != null && existing.isOpen()) {
                    toast("Already connected to ${contact.publicKey.take(8)}")
                    return@runCatching
                }
                if (existing != null) closeContact(contact.publicKey)
                // Join the permanent room so reconnection from the other side also works.
                signaling.announce(deriveRoomId(cachedMyPk, contact.publicKey))
                // Send offer to their personal room — they are always listening there.
                getOrCreateTransport(contact, sha256Hex(contact.publicKey)).createOffer()
            }.onFailure { e ->
                Log.e(TAG, "startHandshake failed for ${contact.publicKey.take(8)}", e)
                toast("Handshake failed: ${e.message}")
            }
        }
    }

    /** Accept a bond request that was previously paused for user approval. */
    fun approveIncomingRequest(contactPk: String) {
        val offer = pendingIncomingOffers.remove(contactPk) ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                if (ContactStore.load(context).none { it.publicKey == contactPk }) {
                    ContactStore.save(context, Contact("Peer ${contactPk.take(8)}", contactPk))
                }
            }
            acceptOffer(contactPk, offer.offerId, offer.peerId, offer.sdp)
        }
    }

    /** Discard a bond request without connecting. */
    fun rejectIncomingRequest(contactPk: String) {
        pendingIncomingOffers.remove(contactPk)
        Log.d(TAG, "Rejected incoming request from ${contactPk.take(8)}")
    }

    private suspend fun acceptOffer(contactPk: String, offerId: String, peerId: String, sdp: String) {
        // Join permanent room for future reconnection.
        signaling.announce(deriveRoomId(cachedMyPk, contactPk))

        transports[contactPk]?.close()
        transports.remove(contactPk)
        peerToContact[peerId] = contactPk

        // Load the contact so the closures in getOrCreateTransport capture the right name.
        val contact = withContext(Dispatchers.IO) {
            ContactStore.load(context).find { it.publicKey == contactPk }
                ?: Contact("Peer ${contactPk.take(8)}", contactPk)
        }
        // Answerers never call createOffer(), so offerRoom is not needed.
        getOrCreateTransport(contact, null).handleOffer(sdp, offerId, peerId)
    }

    suspend fun sendMessage(contact: Contact, content: String) {
        val payload = JSONObject()
            .put("type", "text")
            .put("content", content)
            .put("ts", System.currentTimeMillis())
            .toString().toByteArray()

        val recipientKey = KeyManager.base64UrlToPublicKey(contact.publicKey) ?: run {
            Log.e(TAG, "Invalid public key for ${contact.publicKey.take(8)}")
            return
        }
        val encrypted = Encryption.encrypt(payload, recipientKey)

        val msg = Message(
            id = UUID.randomUUID().toString(),
            contactPublicKey = contact.publicKey,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutbound = true
        )

        val transport = getOrCreateTransport(contact, sha256Hex(contact.publicKey))

        // Atomically check channel state and enqueue if not open.
        // synchronized(pendingMessages) ensures this is mutually exclusive with the drain
        // in onStatusChange(true), preventing the "channel opens, drains empty queue, message
        // then gets enqueued with nobody left to drain it" race.
        val sendNow: Boolean
        val needsConnect: Boolean
        synchronized(pendingMessages) {
            if (transport.isOpen()) {
                sendNow = true
                needsConnect = false
            } else {
                sendNow = false
                // Limit pending queue size to prevent memory exhaustion
                val queue = pendingMessages.getOrPut(contact.publicKey) { mutableListOf() }
                if (queue.size >= 100) {
                    Log.w(TAG, "Message queue full for ${contact.publicKey.take(8)}, dropping oldest")
                    queue.removeAt(0)
                }
                queue.add(encrypted)
                needsConnect = !transport.isConnecting()
            }
        }

        if (sendNow) {
            transport.sendMessage(encrypted)
        } else if (needsConnect) {
            closeContact(contact.publicKey)
            getOrCreateTransport(contact, sha256Hex(contact.publicKey)).createOffer()
        }

        withContext(Dispatchers.IO) { MessageStore.save(context, msg) }
        _messageFlow.emit(msg)
    }

    /** Tear down all state for a contact so the next connection starts fresh. */
    fun closeContact(contactPk: String) {
        transports[contactPk]?.close()
        transports.remove(contactPk)
        offerIdToContact.entries.removeIf { it.value == contactPk }
        peerToContact.entries.removeIf { it.value == contactPk }
        // pendingMessages intentionally not cleared — messages survive reconnection.
    }

    private fun getOrCreateTransport(
        contact: Contact,
        offerRoom: String?,
        targetPeerId: String? = null
    ): WebRtcTransport {
        transports[contact.publicKey]?.let { return it }
        // Construct outside the map lock — createPeerConnection can block.
        val candidate = WebRtcTransport(
                context = context,
                scope = scope,
                myPk = cachedMyPk,
                offerRoom = offerRoom,
                targetPeerId = targetPeerId,
                signaling = signaling,
                onOfferSent = { offerId -> offerIdToContact[offerId] = contact.publicKey },
                signOffer = { offerId, sdp ->
                    val kp = KeyManager.getOrCreateKeyPair(context)
                    val sig = KeyManager.sign((offerId + sdp).toByteArray(), kp.private)
                    Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_WRAP)
                },
                onMessage = { payload -> scope.launch { handleIncomingMessage(contact, payload) } },
                onStatusChange = { isOpen ->
                    if (isOpen) {
                        // Drain queued messages. synchronized is mutually exclusive with sendMessage's
                        // check+enqueue block, so no message can be lost between the two.
                        val queued = synchronized(pendingMessages) {
                            pendingMessages.remove(contact.publicKey)?.toList() ?: emptyList()
                        }
                        queued.forEach { data -> transports[contact.publicKey]?.sendMessage(data) }

                        scope.launch {
                            // Reload contact to pick up any name saved since this closure was created.
                            val fresh = withContext(Dispatchers.IO) {
                                ContactStore.load(context).find { it.publicKey == contact.publicKey } ?: contact
                            }
                            _peerEventFlow.emit(PeerEvent.ChannelOpened(fresh))
                            sendIdentityMessage(contact.publicKey)
                            deliverPendingStatusUpdate(contact.publicKey)
                        }
                    } else {
                        // Natural close (ICE failure, remote disconnect) — tear down and notify.
                        // Explicit close() sets closed=true which suppresses this callback,
                        // so we never call closeContact on a transport we already removed.
                        scope.launch { _peerEventFlow.emit(PeerEvent.ChannelClosed(contact)) }
                        closeContact(contact.publicKey)
                    }
                },
                iceServers = WebRtcTransport.DEFAULT_ICE_SERVERS
            )
        // If another thread won the race, discard our candidate and use theirs.
        val existing = transports.putIfAbsent(contact.publicKey, candidate)
        if (existing != null) { candidate.close(); return existing }
        return candidate
    }

    private suspend fun sendIdentityMessage(contactPk: String) {
        val contact = withContext(Dispatchers.IO) {
            ContactStore.load(context).find { it.publicKey == contactPk }
        } ?: return
        val payload = JSONObject()
            .put("type", "identity")
            .put("name", ProfileManager.getUsername(context))
            .put("disambig", ProfileManager.getDisambiguation(context))
            .toString().toByteArray()
        sendEncryptedMessage(contact, payload)
    }

    private suspend fun handleIncomingMessage(contact: Contact, payload: ByteArray) {
        runCatching {
            val myKeyPair = KeyManager.getOrCreateKeyPair(context)
            val decrypted = Encryption.decrypt(payload, myKeyPair.private, myKeyPair.public)
            val json = JSONObject(String(decrypted))

            when (json.optString("type")) {
                "text" -> {
                    val msg = Message(
                        id = UUID.randomUUID().toString(),
                        contactPublicKey = contact.publicKey,
                        content = json.getString("content"),
                        timestamp = json.getLong("ts"),
                        isOutbound = false
                    )
                    withContext(Dispatchers.IO) { MessageStore.save(context, msg) }
                    _messageFlow.emit(msg)
                }
                "identity" -> {
                    val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return@runCatching
                    val disambig = json.optString("disambig").takeIf { it.isNotEmpty() }
                    val existing = withContext(Dispatchers.IO) {
                        ContactStore.load(context).find { it.publicKey == contact.publicKey }
                    } ?: return@runCatching
                    withContext(Dispatchers.IO) {
                        ContactStore.save(context, existing.copy(name = name, disambiguation = disambig))
                    }
                }
                "status_request" -> {
                    val responsePayload = JSONObject()
                        .put("type", "status_response")
                        .put("hasPositive", calculateMyStatus())
                        .put("ts", System.currentTimeMillis())
                        .toString().toByteArray()
                    sendEncryptedMessage(contact, responsePayload)
                }
                "status_response", "status_update" -> {
                    val hasPositive = json.getBoolean("hasPositive")
                    val queuedAt = json.optLong("queuedAt", 0L)
                    val msgTs = json.optLong("ts", 0L)
                    val effectiveTs = if (queuedAt > 0L) queuedAt else msgTs
                    
                    // Enhanced replay protection: validate timestamp format and age
                    if (effectiveTs <= 0L || effectiveTs > System.currentTimeMillis() + 60_000L) {
                        Log.w(TAG, "Invalid timestamp in status from ${contact.publicKey.take(8)}: $effectiveTs")
                        return@runCatching
                    }
                    if (System.currentTimeMillis() - effectiveTs > STATUS_MAX_AGE_MS) {
                        Log.w(TAG, "Discarding stale status from ${contact.publicKey.take(8)}: " +
                                "age ${System.currentTimeMillis() - effectiveTs}ms")
                        return@runCatching
                    }
                    _peerEventFlow.emit(PeerEvent.StatusResponse(contact.publicKey, hasPositive, queuedAt))
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to handle incoming message", e)
            // Don't crash the connection on malformed messages
        }
    }

    fun sendStatusRequest(contact: Contact) {
        scope.launch {
            runCatching {
                val payload = JSONObject().put("type", "status_request").toString().toByteArray()
                if (!sendEncryptedMessage(contact, payload)) toast("Failed to send status request")
            }.onFailure { e ->
                Log.e(TAG, "sendStatusRequest failed", e)
                toast("Failed to send status request: ${e.message}")
            }
        }
    }

    /** Broadcast our current status to all contacts; queues for offline contacts. */
    fun broadcastStatusChange() {
        scope.launch {
            val myStatus = calculateMyStatus()
            val contacts = withContext(Dispatchers.IO) { ContactStore.load(context) }
            contacts.forEach { contact ->
                // sendStatusMsg already checks isOpen() — avoid a TOCTOU race by not
                // pre-checking here; if the channel closes between the two checks the
                // message would be silently dropped without re-queuing.
                val sent = sendStatusMsg(contact, myStatus)
                if (!sent) {
                    withContext(Dispatchers.IO) {
                        PendingStatusStore.addUpdate(context, contact.publicKey, myStatus)
                    }
                }
            }
        }
    }

    private fun deliverPendingStatusUpdate(publicKey: String) {
        scope.launch {
            val (update, contact) = withContext(Dispatchers.IO) {
                PendingStatusStore.consumeUpdate(context, publicKey) to
                ContactStore.load(context).find { it.publicKey == publicKey }
            }
            if (update == null || contact == null) return@launch
            val sent = sendStatusMsg(contact, update.hasPositive, update.timestamp)
            if (!sent) {
                // Channel closed between ChannelOpened event and delivery — re-queue with original timestamp.
                withContext(Dispatchers.IO) {
                    PendingStatusStore.addUpdate(context, publicKey, update.hasPositive, update.timestamp)
                }
                Log.w(TAG, "Re-queued status for ${publicKey.take(8)}: channel closed before delivery")
            }
        }
    }

    private fun sendStatusMsg(contact: Contact, hasPositive: Boolean, queuedAt: Long = 0L): Boolean {
        val json = JSONObject()
            .put("type", "status_update")
            .put("hasPositive", hasPositive)
        if (queuedAt > 0L) json.put("queuedAt", queuedAt)
        return sendEncryptedMessage(contact, json.toString().toByteArray())
    }

    private fun sendEncryptedMessage(contact: Contact, payload: ByteArray): Boolean {
        val transport = transports[contact.publicKey] ?: return false
        if (!transport.isOpen()) return false
        val recipientKey = KeyManager.base64UrlToPublicKey(contact.publicKey) ?: run {
            Log.e(TAG, "Invalid public key for ${contact.publicKey.take(8)}")
            return false
        }
        return transport.sendMessage(Encryption.encrypt(payload, recipientKey))
    }

    private fun calculateMyStatus(): Boolean {
        return try {
            TestsStore.load(context).any { record ->
                record.tests.any { test -> test.result == TestResult.POSITIVE }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate my status", e)
            false
        }
    }

    companion object {
        private const val TAG = "P2PMessenger"
        // Matches PendingStatusStore.TTL_MS — reject replayed status older than this.
        private const val STATUS_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        // applicationContext is safe to hold statically — suppress the generic lint warning.
        @Volatile @android.annotation.SuppressLint("StaticFieldLeak")
        private var instance: P2PMessenger? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: P2PMessenger(context.applicationContext).also { instance = it }
        }
    }
}
