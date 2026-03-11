package com.davv.trusti.smp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.davv.trusti.crypto.KeyManager
import com.davv.trusti.model.Contact
import com.davv.trusti.model.Message
import com.davv.trusti.utils.ContactStore
import com.davv.trusti.utils.MessageStore
import com.davv.trusti.utils.ProfileManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import com.davv.trusti.model.DiseaseTest
import com.davv.trusti.model.TestResult
import com.davv.trusti.model.TestsRecord

/**
 * P2P Messenger using WebRTC data channels and WebTorrent tracker signaling.
 *
 * Uses "vanilla ICE" — the SDP offer/answer includes all ICE candidates
 * (gathered before sending), because the WebTorrent tracker does not relay
 * separate trickle-ICE candidate messages.
 *
 * Handshake flow (A scans B's QR):
 *  1. A calls startHandshake(B) → createOffer() → ICE gathers → announceWithOffer(sha256(B.key))
 *  2. Tracker delivers offer to B (B announced sha256(B.key) in initialize())
 *  3. B handleOffer → createAnswer → ICE gathers → sendAnswer(sha256(B.key), A.trackerId, offerId)
 *  4. Tracker delivers answer to A → handleAnswer → WebRTC connected
 */
class P2PMessenger private constructor(private val context: Context) {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception in scope", e) }
    )
    private val signaling = TorrentSignaling(scope = scope)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(msg: String) {
        Log.d(TAG, "TOAST: $msg")
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    @Volatile private var initialized = false
    private lateinit var cachedMyPk: String

    /** contactPubKey → WebRtcTransport */
    private val transports = mutableMapOf<String, WebRtcTransport>()
    /** tracker peerId → contactPubKey */
    private val peerToContact = mutableMapOf<String, String>()
    /** our outgoing offerId → contactPubKey */
    private val offerIdToContact = mutableMapOf<String, String>()
    /** contactPubKey → incoming offerId */
    private val pendingOfferIds = mutableMapOf<String, String>()
    /** contactPubKey → tracker room used for answer routing */
    private val contactRoom = mutableMapOf<String, String>()
    /** contactPubKey → encrypted messages queued while DataChannel is not yet open */
    private val pendingMessages = mutableMapOf<String, MutableList<ByteArray>>()

    sealed class PeerEvent {
        data class ChannelOpened(val contact: Contact) : PeerEvent()
        data class StatusRequest(val fromPublicKey: String) : PeerEvent()
        data class StatusResponse(val fromPublicKey: String, val records: List<TestsRecord>) : PeerEvent()
    }

    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 128)
    val messageFlow: SharedFlow<Message> = _messageFlow

    private val _peerEventFlow = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 16)
    val peerEventFlow: SharedFlow<PeerEvent> = _peerEventFlow

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun initialize() {
        if (initialized) {
            Log.w(TAG, "initialize() called again — skipping to avoid double WebRTC init")
            return
        }
        initialized = true
        Log.d(TAG, "initialize() start")

        runCatching {
            WebRtcTransport.initializeWebRtc(context)
            Log.d(TAG, "WebRTC native library initialized")
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize WebRTC — P2P will not work", e)
            initialized = false
            return
        }

        cachedMyPk = KeyManager.publicKeyToBase64Url(KeyManager.getOrCreateKeyPair(context).public)

        signaling.onConnected = {
            Log.d(TAG, "Tracker WebSocket connected")
            toast("Tracker connected")
            mainHandler.post { _isConnected.value = true }
        }
        signaling.onDisconnected = {
            Log.d(TAG, "Tracker WebSocket disconnected")
            toast("Tracker disconnected")
            mainHandler.post { _isConnected.value = false }
        }
        signaling.onTrackerError = { reason ->
            toast("Tracker error: $reason")
        }
        signaling.connect()
        Log.d(TAG, "Signaling connect() called")

        scope.launch {
            signaling.events.collect { event ->
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Signaling event: ${event::class.simpleName}")
                    runCatching { handleSignalingEvent(event) }
                        .onFailure { e -> Log.e(TAG, "Error handling signaling event", e) }
                }
            }
        }

        scope.launch {
            val handshakeRoom = sha256(cachedMyPk)
            Log.d(TAG, "Announcing my handshake room: ${handshakeRoom.take(8)}…")
            signaling.announce(handshakeRoom)

            val contacts = ContactStore.load(context)
            Log.d(TAG, "Announcing ${contacts.size} permanent contact rooms")
            contacts.forEach { contact ->
                signaling.announce(deriveRoomId(contact.publicKey))
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun deriveRoomId(theirPubKey: String): String {
        val sorted = listOf(cachedMyPk, theirPubKey).sorted()
        return sha256(sorted.joinToString(""))
    }

    /** Append our identity as custom SDP session-level attributes. */
    private fun injectIdentityIntoSdp(sdp: String, pk: String, name: String?, disambig: String?): String {
        val extra = buildString {
            append("a=x-trusti-pk:$pk\r\n")
            if (!name.isNullOrEmpty()) append("a=x-trusti-name:$name\r\n")
            if (!disambig.isNullOrEmpty()) append("a=x-trusti-disambig:$disambig\r\n")
        }
        // Insert before the first m= line (session-level attributes)
        val mIndex = sdp.indexOf("\r\nm=")
        return if (mIndex >= 0) {
            sdp.substring(0, mIndex) + "\r\n" + extra.trimEnd() + sdp.substring(mIndex)
        } else {
            sdp.trimEnd() + "\r\n" + extra.trimEnd() + "\r\n"
        }
    }

    private fun handleSignalingEvent(event: TorrentSignaling.SignalingEvent) {
        when (event) {
            is TorrentSignaling.SignalingEvent.PeerDiscovered ->
                Log.d(TAG, "Peer discovered: ${event.peerId}")

            is TorrentSignaling.SignalingEvent.Offer -> {
                Log.d(TAG, "Offer from ${event.peerId} (pk=${event.fromPk?.take(8)}) offerId=${event.offerId.take(8)}")
                toast("Offer received from ${event.fromPk?.take(8) ?: "unknown"}")
                val contactPk = event.fromPk ?: peerToContact[event.peerId] ?: run {
                    Log.w(TAG, "Ignoring offer: no fromPk and unknown peerId=${event.peerId}")
                    toast("Ignoring offer: unknown sender")
                    return
                }

                if (ContactStore.load(context).none { it.publicKey == contactPk }) {
                    val name = event.fromName ?: "Peer ${contactPk.take(8)}"
                    ContactStore.save(context, Contact(name, contactPk, System.currentTimeMillis(), event.fromDisambiguation))
                    Log.d(TAG, "Auto-saved new contact from incoming handshake: ${contactPk.take(8)}")
                }
                // Announce permanent room so both sides can reconnect without QR
                scope.launch { signaling.announce(deriveRoomId(contactPk)) }

                // Incoming offer means remote has a fresh PeerConnection — close our old one
                transports[contactPk]?.let { old ->
                    Log.d(TAG, "Closing old transport for incoming offer from ${contactPk.take(8)}")
                    old.close()
                    transports.remove(contactPk)
                }

                peerToContact[event.peerId] = contactPk
                pendingOfferIds[contactPk] = event.offerId
                contactRoom[contactPk] = sha256(cachedMyPk)
                Log.d(TAG, "Handling offer → createAnswer for ${contactPk.take(8)}")

                val transport = getOrCreateTransport(Contact("Peer", contactPk, 0), event.peerId)
                transport.handleOffer(event.sdp)
            }

            is TorrentSignaling.SignalingEvent.Answer -> {
                Log.d(TAG, "Answer from ${event.peerId} for offerId=${event.offerId.take(8)}")
                val contactPk = offerIdToContact[event.offerId] ?: run {
                    Log.w(TAG, "Ignoring answer: unknown offerId=${event.offerId.take(8)} — known offers: ${offerIdToContact.keys.map{it.take(8)}}")
                    toast("Ignoring answer: unknown offerId")
                    return
                }
                Log.d(TAG, "Answer matched to contact ${contactPk.take(8)}")
                toast("Answer received for ${contactPk.take(8)}")
                peerToContact[event.peerId] = contactPk
                transports[contactPk]?.handleAnswer(event.sdp)
            }

            is TorrentSignaling.SignalingEvent.IceCandidate -> {
                // Tracker doesn't actually relay these, but handle just in case
                val contactPk = peerToContact[event.peerId] ?: return
                runCatching {
                    val candidate = org.webrtc.IceCandidate(
                        event.candidate.getString("sdpMid"),
                        event.candidate.getInt("sdpMLineIndex"),
                        event.candidate.getString("candidate")
                    )
                    transports[contactPk]?.addIceCandidate(candidate)
                }.onFailure { e -> Log.e(TAG, "Failed to add ICE candidate", e) }
            }
        }
    }

    fun startHandshake(contact: Contact) {
        if (!initialized) {
            Log.e(TAG, "startHandshake() called before initialize() completed — ignoring")
            return
        }
        Log.d(TAG, "startHandshake() called for ${contact.publicKey.take(8)}")
        toast("Handshake → ${contact.publicKey.take(8)}")
        scope.launch(Dispatchers.Main) {
            runCatching {
                // If already open, nothing to do
                val existing = transports[contact.publicKey]
                if (existing != null && existing.isOpen()) {
                    Log.d(TAG, "Transport already open for ${contact.publicKey.take(8)}")
                    toast("Already connected to ${contact.publicKey.take(8)}")
                    return@runCatching
                }
                // Close stale transport — PeerConnection can't be reused for a new handshake
                if (existing != null) {
                    Log.d(TAG, "Closing stale transport for ${contact.publicKey.take(8)}")
                    closeContact(contact.publicKey)
                }
                contactRoom[contact.publicKey] = sha256(contact.publicKey)
                // Announce permanent room so peer can reconnect to us later without QR
                scope.launch { signaling.announce(deriveRoomId(contact.publicKey)) }
                val transport = getOrCreateTransport(contact)
                Log.d(TAG, "Creating offer for ${contact.publicKey.take(8)}")
                toast("Creating offer…")
                transport.createOffer()
            }.onFailure { e ->
                Log.e(TAG, "startHandshake failed for ${contact.publicKey.take(8)}", e)
                toast("Handshake failed: ${e.message}")
            }
        }
    }

    suspend fun sendMessage(contact: Contact, content: String) {
        val payload = JSONObject()
            .put("type", "text")
            .put("from", cachedMyPk)
            .put("content", content)
            .put("ts", System.currentTimeMillis())
            .toString().toByteArray()

        val encrypted = Encryption.encrypt(payload, KeyManager.base64UrlToPublicKey(contact.publicKey)!!)

        val msg = Message(
            id = UUID.randomUUID().toString(),
            contactPublicKey = contact.publicKey,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutbound = true
        )

        var transport = withContext(Dispatchers.Main) { getOrCreateTransport(contact) }

        if (transport.isOpen()) {
            if (transport.sendMessage(encrypted)) {
                MessageStore.save(context, msg)
                _messageFlow.emit(msg)
            }
        } else {
            // Queue the encrypted payload and start (or await) the handshake.
            // Save and emit optimistically so the UI shows the message immediately.
            withContext(Dispatchers.Main) {
                // Close stale transport — can't reuse a completed PeerConnection
                if (!transport.isConnecting()) {
                    closeContact(contact.publicKey)
                    contactRoom[contact.publicKey] = sha256(contact.publicKey)
                    transport = getOrCreateTransport(contact)
                }
                pendingMessages.getOrPut(contact.publicKey) { mutableListOf() }.add(encrypted)
                if (!transport.isConnecting()) {
                    transport.createOffer()
                }
            }
            MessageStore.save(context, msg)
            _messageFlow.emit(msg)
        }
    }

    /** Tear down all state for a contact so the next handshake starts fresh. */
    fun closeContact(publicKey: String) {
        Log.d(TAG, "closeContact ${publicKey.take(8)}")
        transports.remove(publicKey)?.close()
        pendingMessages.remove(publicKey)
        pendingOfferIds.remove(publicKey)
        contactRoom.remove(publicKey)
        offerIdToContact.entries.removeAll { it.value == publicKey }
        peerToContact.entries.removeAll { it.value == publicKey }
        if (::cachedMyPk.isInitialized) {
            signaling.removeRoom(deriveRoomId(publicKey))
        }
    }

    /** Flush queued messages once the DataChannel opens. Runs on Main thread. */
    private fun drainPending(contactPk: String) {
        val pending = pendingMessages.remove(contactPk) ?: return
        val transport = transports[contactPk] ?: return
        pending.forEach { encrypted ->
            if (!transport.sendMessage(encrypted)) {
                Log.w(TAG, "Failed to drain queued message for ${contactPk.take(8)}")
            }
        }
    }

    private fun getOrCreateTransport(contact: Contact, incomingPeerId: String? = null): WebRtcTransport {
        incomingPeerId?.let { peerToContact[it] = contact.publicKey }

        // If the existing transport has permanently failed, close it and create a fresh one.
        val existing = transports[contact.publicKey]
        if (existing != null && existing.isFailed()) {
            Log.d(TAG, "Replacing failed transport for ${contact.publicKey.take(8)}")
            existing.close()
            transports.remove(contact.publicKey)
        }

        return transports.getOrPut(contact.publicKey) {
            val myPk = cachedMyPk
            val myName = ProfileManager.getUsername(context)
            val myDisambiguation = ProfileManager.getDisambiguation(context)

            WebRtcTransport(
                context = context,
                onSdpGenerated = { sdp ->
                    if (sdp.type == org.webrtc.SessionDescription.Type.OFFER) {
                        val offerId = UUID.randomUUID().toString()
                        val roomId = sha256(contact.publicKey)
                        val taggedSdp = injectIdentityIntoSdp(sdp.description, myPk, myName, myDisambiguation)
                        mainHandler.post {
                            offerIdToContact[offerId] = contact.publicKey
                            Log.d(TAG, "Sending offer offerId=${offerId.take(8)} to room ${roomId.take(8)}")
                            signaling.announceWithOffer(
                                roomId = roomId,
                                offerId = offerId,
                                sdp = taggedSdp,
                                myPk = myPk,
                                myName = myName,
                                myDisambiguation = myDisambiguation
                            )
                        }
                    } else {
                        mainHandler.post {
                            val offerId = pendingOfferIds[contact.publicKey] ?: run {
                                Log.w(TAG, "No pending offerId for ${contact.publicKey.take(8)}")
                                return@post
                            }
                            val remotePeerId = peerToContact.entries
                                .firstOrNull { it.value == contact.publicKey }?.key ?: run {
                                Log.w(TAG, "No tracker peerId for ${contact.publicKey.take(8)}")
                                return@post
                            }
                            val room = contactRoom[contact.publicKey] ?: sha256(myPk)
                            Log.d(TAG, "Sending answer offerId=${offerId.take(8)} to $remotePeerId room ${room.take(8)}")
                            signaling.sendAnswer(room, remotePeerId, offerId, sdp.description)
                        }
                    }
                },
                onMessageReceived = { data -> handleIncomingData(data) },
                onChannelOpen = {
                    mainHandler.post {
                        toast("Connected to ${contact.publicKey.take(8)}!")
                        drainPending(contact.publicKey)
                    }
                    // Announce permanent room (ensure both sides can reconnect)
                    scope.launch { signaling.announce(deriveRoomId(contact.publicKey)) }
                    // Notify UI to show connection dialog
                    scope.launch {
                        val known = ContactStore.load(context)
                            .firstOrNull { it.publicKey == contact.publicKey } ?: contact
                        _peerEventFlow.emit(PeerEvent.ChannelOpened(known))
                    }
                },
                onIceStateChange = { state, types ->
                    toast("ICE: $state (${types.joinToString()})")
                }
            )
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        scope.launch {
            runCatching {
                val kp = KeyManager.getOrCreateKeyPair(context)
                val plaintext = Encryption.decrypt(data, kp.private)
                val json = JSONObject(String(plaintext))
                val from = json.getString("from")

                when (json.optString("type")) {
                    "text" -> {
                        val msg = Message(
                            id = UUID.randomUUID().toString(),
                            contactPublicKey = from,
                            content = json.getString("content"),
                            timestamp = json.getLong("ts"),
                            isOutbound = false
                        )
                        MessageStore.save(context, msg)
                        _messageFlow.emit(msg)
                    }
                    "status_request" -> {
                        _peerEventFlow.emit(PeerEvent.StatusRequest(from))
                    }
                    "status_response" -> {
                        val records = parseStatusRecords(json.getJSONArray("records"))
                        _peerEventFlow.emit(PeerEvent.StatusResponse(from, records))
                    }
                }
            }.onFailure { e -> Log.e(TAG, "Failed to process incoming message", e) }
        }
    }

    private fun parseStatusRecords(arr: JSONArray): List<TestsRecord> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { obj ->
                val tests = obj.optJSONArray("tests")?.let { ta ->
                    (0 until ta.length()).mapNotNull { j ->
                        ta.optJSONObject(j)?.let { t ->
                            val result = TestResult.entries.firstOrNull { it.name == t.optString("result") }
                                ?: TestResult.NOT_TESTED
                            DiseaseTest(t.getString("disease"), result)
                        }
                    }
                } ?: emptyList()
                TestsRecord(obj.getString("id"), obj.getLong("date"), tests)
            }
        }

    fun sendStatusRequest(contact: Contact) {
        scope.launch {
            runCatching {
                val payload = JSONObject().put("type", "status_request").put("from", cachedMyPk)
                    .toString().toByteArray()
                val encrypted = Encryption.encrypt(payload, KeyManager.base64UrlToPublicKey(contact.publicKey)!!)
                withContext(Dispatchers.Main) { transports[contact.publicKey]?.sendMessage(encrypted) }
            }.onFailure { e -> Log.e(TAG, "sendStatusRequest failed", e) }
        }
    }

    fun sendStatusResponse(contact: Contact, records: List<TestsRecord>) {
        scope.launch {
            runCatching {
                val arr = JSONArray().apply {
                    records.forEach { r ->
                        put(JSONObject()
                            .put("id", r.id)
                            .put("date", r.date)
                            .put("tests", JSONArray().apply {
                                r.tests.forEach { t ->
                                    put(JSONObject().put("disease", t.disease).put("result", t.result.name))
                                }
                            }))
                    }
                }
                val payload = JSONObject()
                    .put("type", "status_response").put("from", cachedMyPk).put("records", arr)
                    .toString().toByteArray()
                val encrypted = Encryption.encrypt(payload, KeyManager.base64UrlToPublicKey(contact.publicKey)!!)
                withContext(Dispatchers.Main) { transports[contact.publicKey]?.sendMessage(encrypted) }
            }.onFailure { e -> Log.e(TAG, "sendStatusResponse failed", e) }
        }
    }

    companion object {
        private const val TAG = "P2PMessenger"

        @Volatile private var instance: P2PMessenger? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: P2PMessenger(context.applicationContext).also { instance = it }
        }
    }
}
