package com.davv.trusti.smp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription as RtcSessionDescription
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a single WebRTC P2P connection (DataChannel only).
 *
 * Uses "vanilla ICE" — waits for ICE gathering to complete before sending the SDP,
 * so all candidates are embedded in the SDP itself.
 *
 * @param offerRoom  The signaling room to announce the offer into. Only used when
 *                   [createOffer] is called (i.e. this side initiates). Ignored for answerers.
 */
class WebRtcTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myPk: String,
    private val offerRoom: String?,   // null for answerers — only used when createOffer() is called
    private var targetPeerId: String? = null,
    private val signaling: TorrentSignaling,
    private val onMessage: (ByteArray) -> Unit,
    private val onStatusChange: (Boolean) -> Unit,
    private val onOfferSent: (offerId: String) -> Unit = {},
    /** Signs an outgoing offer. Returns a Base64URL ECDSA-SHA256 signature of (offerId + sdp). */
    private val signOffer: ((offerId: String, sdp: String) -> String)? = null,
    private val iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS
) {
    private var peerConnection: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    private val sdpSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var iceTimeoutJob: Job? = null

    private var pendingOfferId: String? = null
    private var pendingFromPeerId: String? = null
    private var connectionTimeoutJob: Job? = null

    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private val gatheredTypes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: RtcIceCandidate) {
            val typ = Regex("""typ\s+(\w+)""").find(candidate.sdp)?.groupValues?.get(1) ?: "?"
            gatheredTypes.add(typ)
        }
        override fun onDataChannel(dc: DataChannel) { setupDataChannel(dc) }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            if (p0 == PeerConnection.IceConnectionState.FAILED) {
                Log.e(TAG, "ICE FAILED — gathered types: $gatheredTypes")
                close()
                onStatusChange(false)
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (closed.get()) return
            if (state == PeerConnection.IceGatheringState.COMPLETE) maybeSendLocalSdp()
        }
        override fun onIceCandidatesRemoved(p0: Array<out RtcIceCandidate>?) {}
        override fun onAddStream(p0: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
    }

    init {
        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
    }

    fun isOpen(): Boolean = dataChannel?.state() == DataChannel.State.OPEN
    fun isClosed(): Boolean = closed.get()

    fun isConnecting(): Boolean {
        if (closed.get()) return false
        val state = peerConnection?.signalingState() ?: return false
        return state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
               state == PeerConnection.SignalingState.HAVE_REMOTE_OFFER ||
               state == PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER ||
               state == PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER
    }

    private fun maybeSendLocalSdp() {
        if (closed.get()) return
        val localSdp = peerConnection?.localDescription ?: return
        if (!sdpSent.compareAndSet(false, true)) return
        iceTimeoutJob?.cancel()

        if (localSdp.type == RtcSessionDescription.Type.OFFER) {
            val room = offerRoom ?: run { Log.e(TAG, "offerRoom is null for OFFER — cannot send"); return }
            val offerId = UUID.randomUUID().toString().take(8)
            val sig = signOffer?.invoke(offerId, localSdp.description)
            signaling.announceWithOffer(room, offerId, localSdp.description, myPk, sig)
            onOfferSent(offerId)
        } else if (localSdp.type == RtcSessionDescription.Type.ANSWER) {
            val pid = pendingFromPeerId ?: run { Log.e(TAG, "No pendingFromPeerId"); return }
            val oid = pendingOfferId   ?: run { Log.e(TAG, "No pendingOfferId"); return }
            // Answer goes to our personal room (sha256(myPk)), the room the offerer announced to.
            signaling.sendAnswer(sha256Hex(myPk), pid, oid, localSdp.description)
        }

        // If the DataChannel doesn't open within the timeout, tear down so reconnection can retry.
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (!closed.get() && !isOpen()) {
                Log.w(TAG, "Connection timeout after SDP sent — closing transport")
                close()
                onStatusChange(false)
            }
        }
    }

    private fun scheduleIceTimeout() {
        iceTimeoutJob?.cancel()
        iceTimeoutJob = scope.launch {
            delay(ICE_TIMEOUT_MS)
            Log.w(TAG, "ICE gathering timeout — sending SDP with available candidates")
            maybeSendLocalSdp()
        }
    }

    fun createOffer() {
        if (peerConnection == null || closed.get()) return
        sdpSent.set(false)
        if (dataChannel == null) {
            dataChannel = peerConnection?.createDataChannel("messaging", DataChannel.Init())
            dataChannel?.let { setupDataChannel(it) }
        }
        peerConnection?.createOffer(sdpObserver("createOffer", onCreateSuccess = { sdp ->
            peerConnection?.setLocalDescription(sdpObserver("setLocalDesc(offer)", onSetSuccess = {
                scheduleIceTimeout()
                if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    maybeSendLocalSdp()
                }
            }), sdp)
        }), MediaConstraints())
    }

    fun handleOffer(sdp: String, offerId: String, fromPeerId: String) {
        if (!isValidSdp(sdp)) {
            Log.e(TAG, "Invalid SDP content in offer")
            return
        }
        if (!isValidOfferId(offerId)) {
            Log.e(TAG, "Invalid offer ID format")
            return
        }
        pendingOfferId = offerId
        pendingFromPeerId = fromPeerId
        peerConnection?.setRemoteDescription(
            sdpObserver("setRemoteDesc(offer)", onSetSuccess = { createAnswer() }),
            RtcSessionDescription(RtcSessionDescription.Type.OFFER, sdp)
        )
    }

    private fun createAnswer() {
        sdpSent.set(false)
        peerConnection?.createAnswer(sdpObserver("createAnswer", onCreateSuccess = { sdp ->
            peerConnection?.setLocalDescription(sdpObserver("setLocalDesc(answer)", onSetSuccess = {
                scheduleIceTimeout()
                if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    maybeSendLocalSdp()
                }
            }), sdp)
        }), MediaConstraints())
    }

    fun handleAnswer(sdp: String) {
        if (!isValidSdp(sdp)) {
            Log.e(TAG, "Invalid SDP content in answer")
            return
        }
        peerConnection?.setRemoteDescription(
            sdpObserver("setRemoteDesc(answer)"),
            RtcSessionDescription(RtcSessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                if (closed.get()) return  // suppress callbacks after explicit close()
                when (dc.state()) {
                    DataChannel.State.OPEN   -> { connectionTimeoutJob?.cancel(); onStatusChange(true) }
                    DataChannel.State.CLOSED -> onStatusChange(false)
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                onMessage(data)
            }
        })
    }

    fun sendMessage(data: ByteArray): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    /**
     * Explicitly close this transport. After this call, no [onStatusChange] callbacks fire —
     * preventing re-entrant teardown if P2PMessenger closes us during a reconnect.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return  // idempotent
        iceTimeoutJob?.cancel()
        connectionTimeoutJob?.cancel()
        val dc = dataChannel
        val pc = peerConnection
        dataChannel = null
        peerConnection = null
        dc?.close()
        pc?.close()
    }

    /** Single SdpObserver factory to avoid anonymous-class boilerplate at every call site. */
    private fun sdpObserver(
        tag: String,
        onCreateSuccess: (RtcSessionDescription) -> Unit = {},
        onSetSuccess: () -> Unit = {}
    ) = object : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: RtcSessionDescription) = onCreateSuccess(sdp)
        override fun onSetSuccess() = onSetSuccess()
        override fun onCreateFailure(p0: String?) { Log.e(TAG, "$tag createFailure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e(TAG, "$tag setFailure: $p0") }
    }

    companion object {
        private const val TAG = "WebRtcTransport"
        private const val ICE_TIMEOUT_MS = 10_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L

        private fun isValidSdp(sdp: String): Boolean {
            return sdp.length in 100..100_000 && 
                   sdp.contains("v=0") && 
                   sdp.contains("m=application") &&
                   sdp.contains("a=mid:data") &&
                   !sdp.contains("<script") &&
                   !sdp.contains("javascript:") &&
                   sdp.lines().none { it.trim().startsWith("a=") && it.contains("..") }
        }

        private fun isValidOfferId(offerId: String): Boolean {
            return offerId.matches(Regex("^[a-zA-Z0-9]{8}$"))
        }

        val DEFAULT_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )

        @Volatile private var factoryInstance: PeerConnectionFactory? = null
        @Volatile private var webRtcInitialized = false

        val factory: PeerConnectionFactory
            get() {
                check(webRtcInitialized) { "Call initializeWebRtc() before creating WebRtcTransport" }
                return factoryInstance ?: synchronized(WebRtcTransport::class.java) {
                    factoryInstance ?: PeerConnectionFactory.builder()
                        .createPeerConnectionFactory()
                        .also { factoryInstance = it }
                }
            }

        fun initializeWebRtc(context: Context) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            webRtcInitialized = true
        }
    }
}
