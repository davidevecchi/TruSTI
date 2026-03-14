package com.davv.trusti.smp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One WebRTC PeerConnection + DataChannel for a single contact.
 *
 * Uses "vanilla ICE": waits for full ICE gathering before sending SDP,
 * so no trickle-ICE relay is needed.
 */
class WebRtcTransport(
    private val scope: CoroutineScope,
    private val myPk: String,
    private val offerRoom: String?,
    private val signaling: TorrentSignaling,
    private val onMessage: (ByteArray) -> Unit,
    private val onStatusChange: (Boolean) -> Unit,
    private val onOfferSent: (offerId: String) -> Unit = {},
    private val signOffer: ((offerId: String, sdp: String) -> String)? = null,
    private val onConnectionTimeout: (() -> Unit)? = null,
    iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS
) {
    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
    }

    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private val closed = AtomicBoolean(false)

    fun isOpen(): Boolean  = dc?.state() == DataChannel.State.OPEN
    fun isClosed(): Boolean = closed.get()

    /** Initiator path: create offer and send it via signaling once ICE is complete. */
    fun createOffer() {
        val offerId = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        Log.d(TAG, "createOffer offerId=${offerId.take(8)} room=${offerRoom?.take(16)}")
        pc = factory!!.createPeerConnection(rtcConfig, pcObserver(
            onGatheringComplete = {
                val sdp  = pc?.localDescription?.description ?: return@pcObserver
                val room = offerRoom ?: return@pcObserver
                Log.d(TAG, "ICE gathered → sending offer offerId=${offerId.take(8)}")
                val sig  = signOffer?.invoke(offerId, sdp)
                // Embed identity in SDP — trackers strip unknown fields from the offer object
                val sdpWithId = "$sdp\r\na=x-trusti-pk:$myPk"
                signaling.announceWithOffer(room, offerId, sdpWithId, myPk, sig)
                onOfferSent(offerId)
            }
        )) ?: run { Log.e(TAG, "createPeerConnection returned null"); return }

        dc = pc!!.createDataChannel("trusti", DataChannel.Init())
            .also { it.registerObserver(dcObserver()) }

        pc!!.createOffer(sdpObserver { sdp ->
            Log.d(TAG, "offer SDP created, setting local description")
            pc?.setLocalDescription(NoopSdpObserver, sdp)
        }, MediaConstraints())
    }

    /** Answerer path: set remote offer, create answer, send when ICE is complete. */
    fun handleOffer(sdp: String, offerId: String, fromPeerId: String) {
        Log.d(TAG, "handleOffer offerId=${offerId.take(8)} room=${offerRoom?.take(16)}")
        pc = factory!!.createPeerConnection(rtcConfig, pcObserver(
            onGatheringComplete = {
                val answerSdp = pc?.localDescription?.description ?: return@pcObserver
                val room      = offerRoom ?: return@pcObserver
                Log.d(TAG, "ICE gathered → sending answer offerId=${offerId.take(8)}")
                signaling.sendAnswer(room, fromPeerId, offerId, answerSdp)
            },
            onDataChannel = { ch -> attachDc(ch) }
        )) ?: run { Log.e(TAG, "createPeerConnection returned null (answerer)"); return }

        pc!!.setRemoteDescription(sdpObserver {
            Log.d(TAG, "remote offer set, creating answer")
            pc?.createAnswer(sdpObserver { answer ->
                Log.d(TAG, "answer SDP created, setting local description")
                pc?.setLocalDescription(NoopSdpObserver, answer)
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    /** Called when the tracker delivers the answer to our offer. */
    fun handleAnswer(sdp: String) {
        Log.d(TAG, "handleAnswer → setting remote description")
        pc?.setRemoteDescription(
            NoopSdpObserver,
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun sendMessage(data: ByteArray): Boolean {
        if (!isOpen()) {
            Log.w(TAG, "sendMessage: channel not open")
            return false
        }
        return dc?.send(DataChannel.Buffer(ByteBuffer.wrap(data), true)) ?: false
    }

    fun close() {
        if (closed.getAndSet(true)) return
        Log.d(TAG, "close")
        dc?.unregisterObserver()
        dc?.close()
        dc?.dispose()
        pc?.close()
        pc?.dispose()
        dc = null
        pc = null
    }

    // --- private ---

    private fun attachDc(channel: DataChannel) {
        dc = channel
        channel.registerObserver(dcObserver())
    }

    private fun dcObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}
        override fun onStateChange() {
            val state = dc?.state()
            Log.d(TAG, "DataChannel state → $state")
            val open = state == DataChannel.State.OPEN
            onStatusChange(open)
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            Log.d(TAG, "← message ${bytes.size}B")
            scope.launch { onMessage(bytes) }
        }
    }

    private fun pcObserver(
        onGatheringComplete: () -> Unit,
        onDataChannel: ((DataChannel) -> Unit)? = null
    ) = object : SimplePCObserver() {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering → $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) onGatheringComplete()
        }
        override fun onDataChannel(channel: DataChannel) {
            Log.d(TAG, "onDataChannel (answerer received DC)")
            onDataChannel?.invoke(channel)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "PeerConnection state → $state")
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                onStatusChange(false)
                onConnectionTimeout?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "WebRtcTransport"

        val DEFAULT_ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        @Volatile private var factory: PeerConnectionFactory? = null

        fun initializeWebRtc(context: Context) {
            if (factory != null) return
            synchronized(this) {
                if (factory != null) return
                Log.d(TAG, "initializing PeerConnectionFactory")
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                Log.d(TAG, "PeerConnectionFactory ready")
            }
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

// Boilerplate-free helpers

private fun sdpObserver(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("WebRtcTransport", "SDP create failure: $error") }
    override fun onSetFailure(error: String)    { Log.e("WebRtcTransport", "SDP set failure: $error")    }
}

private object NoopSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("WebRtcTransport", "SDP create failure: $error") }
    override fun onSetFailure(error: String)    { Log.e("WebRtcTransport", "SDP set failure: $error")    }
}

private open class SimplePCObserver : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
    override fun onAddStream(s: MediaStream) {}
    override fun onRemoveStream(s: MediaStream) {}
    override fun onDataChannel(ch: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(r: RtpReceiver, s: Array<MediaStream>) {}
    override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {}
}
