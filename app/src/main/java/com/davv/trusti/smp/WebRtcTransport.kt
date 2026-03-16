package com.davv.trusti.smp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages a single RTCPeerConnection + DataChannel for one peer.
 *
 * Uses **vanilla ICE**: all candidates are gathered before the SDP is
 * returned, so the offer/answer contains every candidate inline.
 * This is required because the WebTorrent tracker protocol only
 * understands announce (with offer) and answer messages.
 */
class WebRtcTransport(
    context: Context,
    private val onMessage: (String) -> Unit,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val onToast: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "WebRtcTransport"
        private const val DC_LABEL = "trusti"
        private const val ICE_GATHER_TIMEOUT_S = 10L

        @Volatile private var factoryInitialized = false
        private var factory: PeerConnectionFactory? = null

        fun initFactory(context: Context) {
            if (factoryInitialized) return
            synchronized(this) {
                if (factoryInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
                factoryInitialized = true
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    var isConnected = false
        private set

    init {
        initFactory(context)
        createPeerConnection()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // GATHER_ONCE = vanilla ICE: gather all candidates, then signal "complete"
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Vanilla ICE: candidates are collected into the local SDP automatically.
                // We don't need to relay them individually.
                Log.d(TAG, "ICE candidate gathered: ${candidate.sdp.take(40)}")
            }
            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "Remote DataChannel opened: ${channel.label()}")
                toast("DataChannel opened (remote)")
                attachDataChannel(channel)
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection: $s")
                toast("ICE: $s")
                when (s) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        isConnected = false
                        mainHandler.post { onClose() }
                    }
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        toast("ICE connected!")
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering: $s")
            }
            override fun onIceCandidatesRemoved(arr: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    /**
     * Create offer with vanilla ICE: sets local description, waits for ICE
     * gathering to complete, then returns the full SDP (with all candidates).
     * Callback fires on a background thread.
     */
    fun createOffer(callback: (String) -> Unit) {
        val init = DataChannel.Init().apply { ordered = true }
        dc = pc!!.createDataChannel(DC_LABEL, init)
        attachDataChannel(dc!!)

        pc!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc!!.setLocalDescription(NoOpSdpObserver, sdp)
                Log.d(TAG, "Created offer, waiting for ICE gathering…")
                toast("Gathering ICE candidates…")
                waitForIceGathering {
                    val fullSdp = pc?.localDescription?.description ?: sdp.description
                    Log.d(TAG, "Offer ready (ICE complete)")
                    toast("Offer ready")
                    callback(fullSdp)
                }
            }
            override fun onCreateFailure(s: String?) { Log.e(TAG, "Offer creation failed: $s") }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())
    }

    /**
     * Handle remote offer with vanilla ICE: sets remote description, creates
     * answer, waits for ICE gathering to complete, then returns full SDP.
     */
    fun handleOffer(remoteSdp: String, callback: (String) -> Unit) {
        val sd = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        pc!!.setRemoteDescription(NoOpSdpObserver, sd)

        pc!!.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc!!.setLocalDescription(NoOpSdpObserver, sdp)
                Log.d(TAG, "Created answer, waiting for ICE gathering…")
                toast("Gathering ICE candidates…")
                waitForIceGathering {
                    val fullSdp = pc?.localDescription?.description ?: sdp.description
                    Log.d(TAG, "Answer ready (ICE complete)")
                    toast("Answer ready")
                    callback(fullSdp)
                }
            }
            override fun onCreateFailure(s: String?) { Log.e(TAG, "Answer creation failed: $s") }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())
    }

    /** Handle remote answer (vanilla ICE: SDP already has all candidates). */
    fun handleAnswer(remoteSdp: String) {
        val sd = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        pc!!.setRemoteDescription(NoOpSdpObserver, sd)
        Log.d(TAG, "Set remote answer")
        toast("Remote answer set")
    }

    /** Send a string message over the DataChannel. */
    fun send(message: String): Boolean {
        val channel = dc ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buf = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)),
            false
        )
        return channel.send(buf)
    }

    fun close() {
        dc?.close()
        pc?.close()
        dc = null
        pc = null
        isConnected = false
    }

    /**
     * Wait for ICE gathering to reach COMPLETE (or timeout).
     * Polls every 200ms on a background thread; invokes [onComplete] when done.
     */
    private fun waitForIceGathering(onComplete: () -> Unit) {
        Thread {
            val deadline = System.currentTimeMillis() + ICE_GATHER_TIMEOUT_S * 1000
            while (System.currentTimeMillis() < deadline) {
                val state = pc?.iceGatheringState()
                if (state == PeerConnection.IceGatheringState.COMPLETE) break
                Thread.sleep(200)
            }
            val finalState = pc?.iceGatheringState()
            if (finalState != PeerConnection.IceGatheringState.COMPLETE) {
                Log.w(TAG, "ICE gathering timed out (state=$finalState), sending partial SDP")
                toast("ICE gather timeout – sending partial")
            }
            onComplete()
        }.start()
    }

    private fun attachDataChannel(channel: DataChannel) {
        dc = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${channel.state()}")
                toast("DC: ${channel.state()}")
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        isConnected = true
                        mainHandler.post { onOpen() }
                    }
                    DataChannel.State.CLOSED -> {
                        isConnected = false
                        mainHandler.post { onClose() }
                    }
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val msg = String(data, Charsets.UTF_8)
                mainHandler.post { this@WebRtcTransport.onMessage(msg) }
            }
        })
    }

    private fun toast(msg: String) {
        mainHandler.post { onToast(msg) }
    }

    private object NoOpSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
