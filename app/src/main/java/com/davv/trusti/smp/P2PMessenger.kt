package com.davv.trusti.smp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.model.Contact
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.model.TestResult
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.DebugSettings
import com.davv.trusti.utils.ProfileManager
import com.davv.trusti.utils.TestsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton P2P messenger. Orchestrates signaling, WebRTC, and encryption.
 *
 * Lifecycle: get(context) → initialize() → startHandshake / handle incoming → send/receive
 *
 * Key design decisions (per README):
 *  - Vanilla ICE: SDP contains all candidates; no trickle.
 *  - Personal room: sha256(my_pk) — new peers find us after scanning our QR.
 *  - Permanent room: sha256(sorted(A_pk + B_pk)) — reconnection without QR.
 *  - Protocol messages (acc/rej/bye) over DataChannel after connection.
 *  - Offer retry: re-announce every 5 s, up to 6 times.
 *  - Dual-offer tiebreaker: lower PK is always the offerer.
 */
class P2PMessenger private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "P2PMessenger"
        private const val RETRY_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES = 6
        private const val HANDLED_OFFERS_CAP = 100

        @Volatile private var instance: P2PMessenger? = null

        fun get(context: Context): P2PMessenger {
            return instance ?: synchronized(this) {
                instance ?: P2PMessenger(context.applicationContext).also { instance = it }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    sealed class PeerEvent {
        data class ChannelOpened(val contact: Contact, val isNew: Boolean) : PeerEvent()
        data class ChannelClosed(val contact: Contact) : PeerEvent()
        data class IncomingBondRequest(
            val contactPk: String,
            val senderName: String,
            val senderDisambig: String,
            val senderSharingPrefs: SharingPreferences
        ) : PeerEvent()
        data class StatusResponse(
            val fromPublicKey: String,
            val hasPositive: Boolean,
            val queuedAt: Long = 0L
        ) : PeerEvent()
        data class BondRemoved(val publicKey: String) : PeerEvent()
        data class RequestRejected(val publicKey: String) : PeerEvent()
    }

    private val _peerEventFlow = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64)
    val peerEventFlow = _peerEventFlow.asSharedFlow()

    private val _isTrackerConnected = MutableStateFlow(false)
    val isTrackerConnected: StateFlow<Boolean> = _isTrackerConnected

    // ── Internal state ──────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var myPkB64: String
    private lateinit var keyPair: java.security.KeyPair
    private lateinit var signaling: TorrentSignaling

    /** contactPk → WebRtcTransport (one active connection per peer). */
    private val transports = ConcurrentHashMap<String, WebRtcTransport>()

    /** offerId → contactPk (offers we sent, waiting for answer). */
    private val pendingOffers = ConcurrentHashMap<String, String>()

    /** Pending incoming bond requests awaiting user approval. */
    private data class PendingIncoming(
        val fromPeerId: String,
        val offerId: String,
        val infoHash: String,
        val sdp: String,
        val fromPk: String
    )
    private val pendingApproval = ConcurrentHashMap<String, PendingIncoming>()

    /** peerPk → signaling peer_id (so we know which tracker peer_id maps to which contact). */
    private val peerIdMap = ConcurrentHashMap<String, String>()

    /** Offer retry coroutines (contactPk → Job). */
    private val retryJobs = ConcurrentHashMap<String, Job>()

    /** Dedup: last offer ID we processed per peer PK. Capped at [HANDLED_OFFERS_CAP]. */
    private val handledOffers = ConcurrentHashMap<String, String>()

    /**
     * Contacts we bonded in this session but have not yet received "acc" for.
     * contactPk → Contact (initiator side: saved only after receiving "acc").
     */
    private val pendingContactByPk = ConcurrentHashMap<String, Contact>()

    /**
     * B-side: contacts approved by user, waiting for DataChannel open to send "acc".
     * contactPk → Contact.
     */
    private val pendingAccepts = ConcurrentHashMap<String, Contact>()

    /** Set of peers that we newly bonded in this session (for isNew flag). */
    private val newlyBondedContacts = ConcurrentHashMap.newKeySet<String>()

    private var initialized = false

    // ── Initialization ──────────────────────────────────────────────────

    suspend fun initialize() {
        if (initialized) return
        initialized = true

        keyPair = KeyManager.getOrCreateKeyPair(appContext)
        myPkB64 = KeyManager.publicKeyToBase64Url(keyPair.public)

        WebRtcTransport.initFactory(appContext)

        signaling = TorrentSignaling(
            peerId = RoomIds.randomPeerId(),
            onToast = { if (DebugSettings.isMessageLoggingEnabled(appContext)) toast(it) }
        )

        // Forward tracker connection state
        signaling.connected
            .onEach { _isTrackerConnected.value = it }
            .launchIn(scope)

        // Process incoming signals
        signaling.signals
            .onEach { handleSignal(it) }
            .launchIn(scope)

        signaling.connect()

        // Once tracker connects, announce in rooms
        scope.launch {
            signaling.connected.collect { connected ->
                if (connected) {
                    announceInPersonalRoom()
                    announceInPermanentRooms()
                    return@collect
                }
            }
        }

        Log.d(TAG, "Initialized. My pk: ${myPkB64.take(12)}…")
        toast("P2P initialized (pk=${myPkB64.take(8)})")
    }

    // ── Initiator: scan QR → choose prefs → start handshake ─────────────

    fun startHandshakeWithPrefs(
        myName: String,
        myDisambig: String,
        contactPkB64: String,
        prefs: SharingPreferences
    ) {
        // Dual-offer tiebreaker: only lower PK initiates
        if (myPkB64 > contactPkB64) {
            Log.d(TAG, "Tiebreaker: my PK > peer PK, I should not initiate first. Proceeding anyway for new bond.")
        }

        toast("Handshake → ${contactPkB64.take(8)}")

        // Prepare the pending contact (saved only after receiving "acc")
        val pendingContact = Contact(
            name = myName, // will be overwritten with peer's name from "acc"
            publicKey = contactPkB64,
            ourSharingPrefs = prefs
        )
        pendingContactByPk[contactPkB64] = pendingContact
        newlyBondedContacts.add(contactPkB64)

        // Launch retry loop: announce offer every 5s, up to 6 times
        val job = scope.launch {
            var lastOfferId: String? = null
            repeat(MAX_RETRIES) { attempt ->
                if (!transports.containsKey(contactPkB64) || transports[contactPkB64]?.isConnected != true) {
                    lastOfferId = sendOffer(contactPkB64, myName, myDisambig, prefs, lastOfferId)
                    Log.d(TAG, "Offer attempt ${attempt + 1}/$MAX_RETRIES for ${contactPkB64.take(8)}")
                    toast("Offer #${attempt + 1} → ${contactPkB64.take(8)}")
                } else {
                    Log.d(TAG, "Already connected to ${contactPkB64.take(8)}, stopping retries")
                    return@launch
                }
                delay(RETRY_INTERVAL_MS)
            }
            Log.w(TAG, "All $MAX_RETRIES offer attempts exhausted for ${contactPkB64.take(8)}")
            toast("Handshake timeout ← ${contactPkB64.take(8)}")
        }
        retryJobs[contactPkB64] = job
    }

    /**
     * Create a transport, generate an offer, and announce it in the peer's
     * handshake room. Returns the offerId used.
     */
    private fun sendOffer(
        contactPkB64: String,
        myName: String,
        myDisambig: String,
        prefs: SharingPreferences,
        reuseOfferId: String?
    ): String {
        val offerId = reuseOfferId ?: UUID.randomUUID().toString()

        // Reuse transport on retry instead of creating a new one every time
        val transport = if (reuseOfferId != null && transports.containsKey(contactPkB64)) {
            transports[contactPkB64]!!
        } else {
            createTransport(contactPkB64)
        }

        transport.createOffer { sdp ->
            pendingOffers[offerId] = contactPkB64

            val room = RoomIds.handshakeRoom(contactPkB64)
            val fromPkPayload = buildFromPkPayload(myName, myDisambig, prefs)
            signaling.announceWithOffer(room, offerId, sdp, fromPkPayload)
        }

        return offerId
    }

    // ── Responder: approve / reject incoming bond request ────────────────

    fun approveIncomingRequest(
        contactPkB64: String,
        myName: String,
        myDisambig: String,
        myPrefs: SharingPreferences
    ) {
        val pending = pendingApproval.remove(contactPkB64) ?: return
        Log.d(TAG, "Approving bond from ${contactPkB64.take(8)}")
        toast("Approve ← ${contactPkB64.take(8)}")

        // Parse sender info
        val senderInfo = parsePkPayload(pending.fromPk)
        val contact = Contact(
            name = senderInfo?.optString("name", "") ?: "",
            publicKey = contactPkB64,
            disambiguation = senderInfo?.optString("disambig", ""),
            ourSharingPrefs = myPrefs,
            theirSharingPrefs = senderInfo?.optJSONObject("prefs")?.let { parseSharingPrefs(it) }
        )

        // B-side: save contact immediately (per README)
        ContactStore.save(appContext, contact)
        // Queue "acc" to be sent when DataChannel opens
        pendingAccepts[contactPkB64] = contact
        newlyBondedContacts.add(contactPkB64)

        scope.launch {
            val transport = createTransport(contactPkB64)
            transport.handleOffer(pending.sdp) { answerSdp ->
                signaling.sendAnswer(pending.infoHash, pending.fromPeerId, pending.offerId, answerSdp)
                peerIdMap[contactPkB64] = pending.fromPeerId
            }
        }
    }

    fun rejectIncomingRequest(contactPkB64: String) {
        val pending = pendingApproval.remove(contactPkB64)
        Log.d(TAG, "Rejected bond from ${contactPkB64.take(8)}")
        toast("Reject ← ${contactPkB64.take(8)}")
        // If there's already a transport (shouldn't be for new bonds), close it
        transports.remove(contactPkB64)?.close()
    }

    // ── Messaging ────────────────────────────────────────────────────────

    /** Send a status request to a connected peer. */
    fun sendStatusRequest(contact: Contact) {
        val transport = transports[contact.publicKey] ?: return
        if (!transport.isConnected) return
        val msg = JSONObject().apply {
            put("t", "sreq")
            put("ts", System.currentTimeMillis())
        }
        sendEncrypted(transport, contact.publicKey, msg.toString())
        toast("TX sreq → ${contact.publicKey.take(8)}")
    }

    /** Push our current status to all connected peers. */
    fun pushMyStatusToAll() {
        val tests = TestsStore.load(appContext)
        val hasPositive = tests.any { record ->
            record.tests.any { it.result == TestResult.POSITIVE }
        }
        val response = JSONObject().apply {
            put("t", "srsp")
            put("pos", hasPositive)
            put("ts", System.currentTimeMillis())
        }
        val msg = response.toString()
        transports.forEach { (pk, transport) ->
            if (transport.isConnected) {
                val contact = ContactStore.load(appContext).find { it.publicKey == pk }
                val prefs = contact?.ourSharingPrefs ?: SharingPreferences()
                if (prefs.shareCurrentStatus) {
                    sendEncrypted(transport, pk, msg)
                    toast("TX srsp → ${pk.take(8)}")
                }
            }
        }
    }

    /** Close and remove a contact's connection. Sends "bye" first if connected. */
    fun closeContact(publicKey: String) {
        val transport = transports[publicKey]
        if (transport != null && transport.isConnected) {
            val bye = JSONObject().apply { put("t", "bye") }
            sendEncrypted(transport, publicKey, bye.toString())
            toast("TX bye → ${publicKey.take(8)}")
        }
        transports.remove(publicKey)?.close()
        peerIdMap.remove(publicKey)
        retryJobs.remove(publicKey)?.cancel()
        pendingContactByPk.remove(publicKey)
        pendingAccepts.remove(publicKey)
    }

    /** Returns the set of currently connected peer public keys. */
    fun connectedPeers(): Set<String> =
        transports.entries.filter { it.value.isConnected }.map { it.key }.toSet()

    fun onDestroy() {
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        transports.values.forEach { it.close() }
        transports.clear()
        signaling.disconnect()
    }

    // ── Signal handling ─────────────────────────────────────────────────

    private fun handleSignal(signal: TorrentSignaling.Signal) {
        when (signal) {
            is TorrentSignaling.Signal.Offer -> handleRemoteOffer(signal)
            is TorrentSignaling.Signal.Answer -> handleRemoteAnswer(signal)
        }
    }

    private fun handleRemoteOffer(signal: TorrentSignaling.Signal.Offer) {
        val senderInfo = parsePkPayload(signal.fromPk)
        val senderPk = senderInfo?.optString("pk", "") ?: signal.fromPk

        if (senderPk == myPkB64) return // ignore our own offers

        // Dedup: skip if we already handled this exact offer
        val prevOfferId = handledOffers[senderPk]
        if (prevOfferId == signal.offerId) {
            Log.d(TAG, "Dedup: already handled offer ${signal.offerId.take(8)} from ${senderPk.take(8)}")
            return
        }
        handledOffers[senderPk] = signal.offerId
        // Cap the dedup cache
        if (handledOffers.size > HANDLED_OFFERS_CAP) {
            val oldest = handledOffers.keys().nextElement()
            handledOffers.remove(oldest)
        }

        Log.d(TAG, "Remote offer from pk=${senderPk.take(8)}")
        toast("RX offer ← ${senderPk.take(8)}")

        // Check if already bonded → auto-accept (reconnection)
        val existingContact = ContactStore.load(appContext).find { it.publicKey == senderPk }
        if (existingContact != null) {
            // Dual-offer tiebreaker: only lower PK should be the offerer
            if (myPkB64 < senderPk) {
                // I should be the offerer, not them. Ignore their offer; they'll accept mine.
                Log.d(TAG, "Dual-offer tiebreak: ignoring offer from ${senderPk.take(8)} (my PK is lower)")
                toast("Tiebreak: ignoring offer ← ${senderPk.take(8)}")
                return
            }
            handleReconnectionOffer(senderPk, signal)
            return
        }

        // New bond request — store pending and emit event for UI
        pendingApproval[senderPk] = PendingIncoming(
            fromPeerId = signal.fromPeerId,
            offerId = signal.offerId,
            infoHash = signal.infoHash,
            sdp = signal.sdp,
            fromPk = signal.fromPk
        )

        val name = senderInfo?.optString("name", "Unknown") ?: "Unknown"
        val disambig = senderInfo?.optString("disambig", "") ?: ""
        val prefs = senderInfo?.optJSONObject("prefs")?.let { parseSharingPrefs(it) }
            ?: SharingPreferences()

        _peerEventFlow.tryEmit(
            PeerEvent.IncomingBondRequest(senderPk, name, disambig, prefs)
        )
    }

    private fun handleReconnectionOffer(senderPk: String, signal: TorrentSignaling.Signal.Offer) {
        Log.d(TAG, "Reconnection from ${senderPk.take(8)}")
        toast("Reconnect ← ${senderPk.take(8)}")

        scope.launch {
            val transport = createTransport(senderPk)
            transport.handleOffer(signal.sdp) { answerSdp ->
                signaling.sendAnswer(signal.infoHash, signal.fromPeerId, signal.offerId, answerSdp)
                peerIdMap[senderPk] = signal.fromPeerId
            }
        }
    }

    private fun handleRemoteAnswer(signal: TorrentSignaling.Signal.Answer) {
        val contactPk = pendingOffers.remove(signal.offerId)
        if (contactPk == null) {
            Log.w(TAG, "Got answer for unknown offerId=${signal.offerId.take(8)}")
            return
        }

        Log.d(TAG, "Remote answer for ${contactPk.take(8)}")
        toast("RX answer ← ${contactPk.take(8)}")

        peerIdMap[contactPk] = signal.fromPeerId

        // Cancel retry loop — we got an answer
        retryJobs.remove(contactPk)?.cancel()

        transports[contactPk]?.handleAnswer(signal.sdp)
    }

    // ── Transport management ────────────────────────────────────────────

    private fun createTransport(contactPk: String): WebRtcTransport {
        // Close existing transport if any
        transports.remove(contactPk)?.close()

        val transport = WebRtcTransport(
            context = appContext,
            onMessage = { msg -> handleDataChannelMessage(contactPk, msg) },
            onOpen = { handleChannelOpened(contactPk) },
            onClose = { handleChannelClosed(contactPk) },
            onToast = { if (DebugSettings.isMessageLoggingEnabled(appContext)) toast(it) }
        )
        transports[contactPk] = transport
        return transport
    }

    private fun handleChannelOpened(contactPk: String) {
        Log.d(TAG, "DataChannel opened with ${contactPk.take(8)}")
        toast("Channel opened ← ${contactPk.take(8)}")

        // B-side: if we have a pending "acc" to send, do it now
        val accContact = pendingAccepts.remove(contactPk)
        if (accContact != null) {
            val accMsg = JSONObject().apply {
                put("t", "acc")
                put("name", ProfileManager.getUsername(appContext))
                put("disambig", ProfileManager.getDisambiguation(appContext))
            }
            sendEncrypted(transports[contactPk]!!, contactPk, accMsg.toString())
            toast("TX acc → ${contactPk.take(8)}")

            // Announce in permanent room for future reconnections
            val permRoom = RoomIds.permanentRoom(myPkB64, contactPk)
            signaling.announcePresence(permRoom)

            _peerEventFlow.tryEmit(
                PeerEvent.ChannelOpened(accContact.copy(isConnected = true), isNew = true)
            )
            return
        }

        // A-side (initiator): channel opened but we still wait for "acc" message
        // before saving the contact. Emit ChannelOpened only for reconnections.
        val existing = ContactStore.load(appContext).find { it.publicKey == contactPk }
        if (existing != null) {
            // Reconnection of existing bond
            val permRoom = RoomIds.permanentRoom(myPkB64, contactPk)
            signaling.announcePresence(permRoom)
            _peerEventFlow.tryEmit(
                PeerEvent.ChannelOpened(existing.copy(isConnected = true), isNew = false)
            )
        }
        // else: new bond, waiting for "acc" — handled in processDecryptedMessage
    }

    private fun handleChannelClosed(contactPk: String) {
        Log.d(TAG, "Channel closed with ${contactPk.take(8)}")
        toast("Channel closed ← ${contactPk.take(8)}")
        val contact = ContactStore.load(appContext).find { it.publicKey == contactPk }
            ?: Contact(name = "Peer ${contactPk.take(6)}", publicKey = contactPk)
        _peerEventFlow.tryEmit(PeerEvent.ChannelClosed(contact))
    }

    // ── Encrypted messaging over DataChannel ────────────────────────────

    private fun sendEncrypted(transport: WebRtcTransport, contactPk: String, plaintext: String) {
        try {
            val recipientPub = KeyManager.base64UrlToPublicKey(contactPk)
            val envelope = Encryption.encrypt(
                plaintext.toByteArray(Charsets.UTF_8),
                recipientPub,
                keyPair.private
            )
            val sent = transport.send(envelope)
            if (!sent) {
                Log.w(TAG, "DataChannel send failed (not open?) for ${contactPk.take(8)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encrypt/send failed: ${e.message}")
            toast("Send error: ${e.message?.take(30)}")
        }
    }

    private fun handleDataChannelMessage(contactPk: String, raw: String) {
        try {
            val plainBytes = Encryption.decrypt(raw, keyPair.private)
            val plaintext = String(plainBytes, Charsets.UTF_8)
            if (DebugSettings.isMessageLoggingEnabled(appContext)) {
                toast("RX msg ← ${contactPk.take(8)}: ${plaintext.take(50)}")
            }
            processDecryptedMessage(contactPk, plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed, trying plaintext: ${e.message}")
            processDecryptedMessage(contactPk, raw)
        }
    }

    private fun processDecryptedMessage(contactPk: String, message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("t")) {
                // ── Protocol messages ──
                "acc" -> handleAccept(contactPk, json)
                "rej" -> handleReject(contactPk)
                "bye" -> handleBye(contactPk)

                // ── Application messages ──
                "sreq" -> handleStatusRequest(contactPk)
                "srsp" -> handleStatusResponse(contactPk, json)

                // ── Legacy compatibility (old message format) ──
                else -> {
                    when (json.optString("type")) {
                        "status_request" -> handleStatusRequest(contactPk)
                        "status_response" -> handleStatusResponse(contactPk, json)
                        "bond_remove" -> handleBye(contactPk)
                        else -> Log.d(TAG, "Unknown msg type from ${contactPk.take(8)}: ${message.take(50)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
        }
    }

    // ── Protocol message handlers ────────────────────────────────────────

    /** A-side: received "acc" from B — save the contact now. */
    private fun handleAccept(contactPk: String, json: JSONObject) {
        Log.d(TAG, "RX acc ← ${contactPk.take(8)}")
        toast("Bond accepted ← ${contactPk.take(8)}")

        retryJobs.remove(contactPk)?.cancel()

        val pending = pendingContactByPk.remove(contactPk)
        val name = json.optString("name", "").ifEmpty { pending?.name ?: "Peer ${contactPk.take(6)}" }
        val disambig = json.optString("disambig", "").ifEmpty { null }

        val contact = (pending ?: Contact(name = name, publicKey = contactPk)).copy(
            name = name,
            disambiguation = disambig,
            isConnected = true
        )

        ContactStore.save(appContext, contact)

        // Announce in permanent room
        val permRoom = RoomIds.permanentRoom(myPkB64, contactPk)
        signaling.announcePresence(permRoom)

        _peerEventFlow.tryEmit(PeerEvent.ChannelOpened(contact, isNew = true))
    }

    /** A-side: received "rej" from B — stop retrying, remove pending. */
    private fun handleReject(contactPk: String) {
        Log.d(TAG, "RX rej ← ${contactPk.take(8)}")
        toast("Bond rejected ← ${contactPk.take(8)}")

        retryJobs.remove(contactPk)?.cancel()
        pendingContactByPk.remove(contactPk)
        transports.remove(contactPk)?.close()
        _peerEventFlow.tryEmit(PeerEvent.RequestRejected(contactPk))
    }

    /** Received "bye" — peer is removing the bond. */
    private fun handleBye(contactPk: String) {
        Log.d(TAG, "RX bye ← ${contactPk.take(8)}")
        toast("Bond removed ← ${contactPk.take(8)}")

        transports.remove(contactPk)?.close()
        peerIdMap.remove(contactPk)
        ContactStore.delete(appContext, contactPk)
        _peerEventFlow.tryEmit(PeerEvent.BondRemoved(contactPk))
    }

    // ── Application message handlers ─────────────────────────────────────

    private fun handleStatusRequest(contactPk: String) {
        Log.d(TAG, "RX sreq ← ${contactPk.take(8)}")
        toast("RX sreq ← ${contactPk.take(8)}")

        val contact = ContactStore.load(appContext).find { it.publicKey == contactPk }
        val prefs = contact?.ourSharingPrefs ?: SharingPreferences()

        if (!prefs.shareCurrentStatus) return

        val tests = TestsStore.load(appContext)
        val hasPositive = tests.any { record ->
            record.tests.any { it.result == TestResult.POSITIVE }
        }

        val response = JSONObject().apply {
            put("t", "srsp")
            put("pos", hasPositive)
            put("ts", System.currentTimeMillis())
        }

        val transport = transports[contactPk] ?: return
        sendEncrypted(transport, contactPk, response.toString())
        toast("TX srsp → ${contactPk.take(8)}")
    }

    private fun handleStatusResponse(contactPk: String, json: JSONObject) {
        // Support both new format ("pos") and legacy ("hasPositive")
        val hasPositive = if (json.has("pos")) json.optBoolean("pos", false)
            else json.optBoolean("hasPositive", false)
        val ts = json.optLong("ts", 0L)
        Log.d(TAG, "RX srsp ← ${contactPk.take(8)}: positive=$hasPositive")
        toast("Status ← ${contactPk.take(8)}: ${if (hasPositive) "POS" else "clear"}")

        _peerEventFlow.tryEmit(PeerEvent.StatusResponse(contactPk, hasPositive, ts))
    }

    // ── Room announcements ──────────────────────────────────────────────

    private fun announceInPersonalRoom() {
        val personalRoom = RoomIds.handshakeRoom(myPkB64)
        signaling.announcePresence(personalRoom)
        Log.d(TAG, "Announced in personal room ${personalRoom.take(8)}")
        toast("Personal room: ${personalRoom.take(8)}")
    }

    private fun announceInPermanentRooms() {
        val contacts = ContactStore.load(appContext)
        if (contacts.isEmpty()) {
            Log.d(TAG, "No saved contacts — skipping permanent room announcements")
            return
        }

        contacts.forEach { contact ->
            val permRoom = RoomIds.permanentRoom(myPkB64, contact.publicKey)
            Log.d(TAG, "Reconnecting to ${contact.name} in room ${permRoom.take(8)}")
            toast("Reconnect room → ${contact.name}")

            val transport = createTransport(contact.publicKey)
            transport.createOffer { sdp ->
                val offerId = UUID.randomUUID().toString()
                pendingOffers[offerId] = contact.publicKey

                val fromPkPayload = JSONObject().apply {
                    put("pk", myPkB64)
                }.toString()

                signaling.announceWithOffer(permRoom, offerId, sdp, fromPkPayload)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildFromPkPayload(name: String, disambig: String, prefs: SharingPreferences): String {
        return JSONObject().apply {
            put("pk", myPkB64)
            put("name", name)
            put("disambig", disambig)
            put("prefs", JSONObject().apply {
                put("status", prefs.shareCurrentStatus)
                put("history", prefs.shareHistory)
                put("counter", prefs.shareCounter)
                put("vaccines", prefs.shareVaccines)
            })
        }.toString()
    }

    private fun parsePkPayload(raw: String): JSONObject? {
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSharingPrefs(json: JSONObject) = SharingPreferences(
        shareCurrentStatus = json.optBoolean("status", true),
        shareHistory = json.optBoolean("history", true),
        shareCounter = json.optBoolean("counter", true),
        shareVaccines = json.optBoolean("vaccines", true)
    )

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
