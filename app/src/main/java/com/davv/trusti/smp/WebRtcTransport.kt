package com.davv.trusti.smp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription as RtcSessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a single WebRTC P2P connection (DataChannel only).
 *
 * Uses "vanilla ICE" — waits for ICE gathering to complete before
 * delivering the SDP, so all candidates are embedded in the SDP itself.
 * This is required because the WebTorrent tracker does not relay
 * arbitrary peer-to-peer messages (like trickle ICE candidates).
 */
class WebRtcTransport(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS,
    private val onSdpGenerated: (RtcSessionDescription) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit,
    private val onChannelOpen: () -> Unit = {},
    private val onIceStateChange: ((String, Set<String>) -> Unit)? = null
) {
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val sdpSent = AtomicBoolean(false)
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val iceTimeoutRunnable = Runnable {
        Log.w(TAG, "ICE gathering timeout — sending SDP with available candidates")
        maybeSendLocalSdp()
    }

    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private fun maybeSendLocalSdp() {
        val localSdp = peerConnection?.localDescription ?: return
        if (sdpSent.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(iceTimeoutRunnable)
            Log.d(TAG, "Delivering SDP (type=${localSdp.type}, len=${localSdp.description.length})")
            onSdpGenerated(localSdp)
        }
    }

    /** Track which candidate types we gathered, for diagnostics. */
    private val gatheredTypes = mutableSetOf<String>()

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: RtcIceCandidate) {
            // Parse type from candidate line: "... typ host ...", "... typ srflx ...", "... typ relay ..."
            val typ = Regex("""typ\s+(\w+)""").find(candidate.sdp)?.groupValues?.get(1) ?: "?"
            gatheredTypes.add(typ)
            Log.d(TAG, "ICE candidate ($typ): ${candidate.sdp.take(100)}")
        }

        override fun onDataChannel(dc: DataChannel) {
            Log.d(TAG, "onDataChannel")
            setupDataChannel(dc)
        }

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "onIceConnectionChange: $p0")
            onIceStateChange?.invoke(p0?.name ?: "?", gatheredTypes)
            if (p0 == PeerConnection.IceConnectionState.FAILED) {
                Log.e(TAG, "ICE FAILED — gathered types were: $gatheredTypes")
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "onIceGatheringChange: $state  types=$gatheredTypes")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                Log.d(TAG, "ICE gathering done — types: $gatheredTypes")
                maybeSendLocalSdp()
            }
        }
        override fun onIceCandidatesRemoved(p0: Array<out RtcIceCandidate>?) {}
        override fun onAddStream(p0: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
    }

    init {
        Log.d(TAG, "Creating WebRtcTransport")
        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
        if (peerConnection == null) {
            Log.e(TAG, "createPeerConnection returned null — WebRTC init may have failed")
        } else {
            Log.d(TAG, "PeerConnection created successfully")
        }
    }

    fun isOpen(): Boolean = dataChannel?.state() == DataChannel.State.OPEN

    /** True when ICE has permanently failed or the connection was explicitly closed. */
    fun isFailed(): Boolean {
        if (peerConnection == null) return true
        val ice = peerConnection?.iceConnectionState()
        return ice == PeerConnection.IceConnectionState.FAILED ||
               ice == PeerConnection.IceConnectionState.CLOSED
    }

    /** True while an offer/answer exchange is in progress (not yet connected, not failed). */
    fun isConnecting(): Boolean {
        val state = peerConnection?.signalingState()
        return state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
               state == PeerConnection.SignalingState.HAVE_REMOTE_OFFER ||
               state == PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER ||
               state == PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER
    }

    fun createOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "createOffer() called but peerConnection is null — aborting")
            return
        }
        sdpSent.set(false)
        if (dataChannel == null) {
            dataChannel = peerConnection?.createDataChannel("messaging", DataChannel.Init())
            if (dataChannel == null) Log.e(TAG, "createDataChannel returned null")
            dataChannel?.let { setupDataChannel(it) }
        }
        Log.d(TAG, "createOffer() — peerConnection state=${peerConnection?.signalingState()}")

        peerConnection?.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: RtcSessionDescription) {
                peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local offer set — waiting for ICE gathering")
                        timeoutHandler.postDelayed(iceTimeoutRunnable, ICE_TIMEOUT_MS)
                        if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                            maybeSendLocalSdp()
                        }
                    }
                    override fun onCreateSuccess(p0: RtcSessionDescription?) {}
                    override fun onCreateFailure(p0: String?) { Log.e(TAG, "setLocalDesc createFailure: $p0") }
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "setLocalDesc setFailure: $p0") }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Log.e(TAG, "createOffer failure: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun handleOffer(sdp: String) {
        Log.d(TAG, "handleOffer() — setting remote description")
        val rtcSdp = RtcSessionDescription(RtcSessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(p0: RtcSessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set — creating answer")
                createAnswer()
            }
            override fun onCreateFailure(p0: String?) { Log.e(TAG, "setRemoteDesc (offer) createFailure: $p0") }
            override fun onSetFailure(p0: String?) { Log.e(TAG, "setRemoteDesc (offer) setFailure: $p0") }
        }, rtcSdp)
    }

    private fun createAnswer() {
        sdpSent.set(false)
        peerConnection?.createAnswer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: RtcSessionDescription) {
                peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set — waiting for ICE gathering")
                        timeoutHandler.postDelayed(iceTimeoutRunnable, ICE_TIMEOUT_MS)
                        if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                            maybeSendLocalSdp()
                        }
                    }
                    override fun onCreateSuccess(p0: RtcSessionDescription?) {}
                    override fun onCreateFailure(p0: String?) { Log.e(TAG, "setLocalDesc (answer) createFailure: $p0") }
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "setLocalDesc (answer) setFailure: $p0") }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Log.e(TAG, "createAnswer failure: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun handleAnswer(sdp: String) {
        val rtcSdp = RtcSessionDescription(RtcSessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(p0: RtcSessionDescription?) {}
            override fun onSetSuccess() { Log.d(TAG, "Remote answer set — connection should establish") }
            override fun onCreateFailure(p0: String?) { Log.e(TAG, "setRemoteDesc (answer) createFailure: $p0") }
            override fun onSetFailure(p0: String?) { Log.e(TAG, "setRemoteDesc (answer) setFailure: $p0") }
        }, rtcSdp)
    }

    fun addIceCandidate(candidate: RtcIceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${dc.state()}")
                if (dc.state() == DataChannel.State.OPEN) {
                    onChannelOpen()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                onMessageReceived(data)
            }
        })
    }

    fun sendMessage(data: ByteArray): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    fun close() {
        timeoutHandler.removeCallbacks(iceTimeoutRunnable)
        dataChannel?.close()
        peerConnection?.close()
    }

    companion object {
        private const val TAG = "WebRtcTransport"
        private const val ICE_TIMEOUT_MS = 10_000L   // longer timeout for TURN candidates

        /** STUN + TURN servers for NAT traversal across networks. */
        val DEFAULT_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            // Google STUN
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            // Open Relay TURN (free, for testing/small-scale use)
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        )

        // Double-checked locking with @Volatile for thread-safe lazy init
        @Volatile private var factoryInstance: PeerConnectionFactory? = null
        val factory: PeerConnectionFactory
            get() = factoryInstance ?: synchronized(WebRtcTransport::class.java) {
                factoryInstance ?: PeerConnectionFactory.builder()
                    .createPeerConnectionFactory()
                    .also { factoryInstance = it; Log.d(TAG, "PeerConnectionFactory created") }
            }

        fun initializeWebRtc(context: Context) {
            Log.d(TAG, "initializeWebRtc() called")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            Log.d(TAG, "initializeWebRtc() done")
        }
    }
}
