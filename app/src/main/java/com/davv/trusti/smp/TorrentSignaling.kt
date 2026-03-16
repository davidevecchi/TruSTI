package com.davv.trusti.smp

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the WebTorrent tracker protocol.
 *
 * Supports:
 *  - announce (with offers) — send SDP offer to a room
 *  - answer — reply to an offer
 *  - announcePresence — join a room without offers
 *
 * Vanilla ICE: all ICE candidates are bundled in the SDP, so there is no
 * candidate relay method.
 */
class TorrentSignaling(
    private val trackerUrl: String = "wss://tracker.openwebtorrent.com",
    private val peerId: String = RoomIds.randomPeerId(),
    private val onToast: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "TorrentSignaling"
    }

    sealed class Signal {
        /** Remote peer sent an SDP offer. */
        data class Offer(
            val infoHash: String,
            val fromPeerId: String,
            val offerId: String,
            val sdp: String,
            val fromPk: String
        ) : Signal()

        /** Remote peer answered our offer. */
        data class Answer(
            val infoHash: String,
            val fromPeerId: String,
            val offerId: String,
            val sdp: String
        ) : Signal()
    }

    private val _signals = MutableSharedFlow<Signal>(extraBufferCapacity = 64)
    val signals = _signals.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect() {
        if (ws != null) return
        Log.d(TAG, "Connecting to $trackerUrl")
        toast("Tracker: connecting…")
        val req = Request.Builder().url(trackerUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS connected to $trackerUrl")
                toast("Tracker: connected")
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closing: $code $reason")
                toast("Tracker: closing ($code)")
                _connected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                toast("Tracker: disconnected")
                _connected.value = false
                ws = null
                // Reconnect after 5s
                mainHandler.postDelayed({ connect() }, 5000)
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        _connected.value = false
    }

    /**
     * Announce to [infoHash] room with an SDP offer (vanilla ICE — SDP
     * already contains all ICE candidates).
     */
    fun announceWithOffer(infoHash: String, offerId: String, sdp: String, fromPk: String) {
        val offer = JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp)
            put("from_pk", fromPk)
        }
        val offersArr = JSONArray().put(JSONObject().apply {
            put("offer_id", offerId)
            put("offer", offer)
        })

        val msg = JSONObject().apply {
            put("action", "announce")
            put("info_hash", infoHash)
            put("peer_id", peerId)
            put("numwant", 10)
            put("offers", offersArr)
        }
        send(msg)
        Log.d(TAG, "Announced offer in room ${infoHash.take(8)}… offerId=${offerId.take(8)}")
        toast("TX offer → room ${infoHash.take(8)}")
    }

    /** Send an SDP answer back to a specific peer (vanilla ICE). */
    fun sendAnswer(infoHash: String, toPeerId: String, offerId: String, sdp: String) {
        val answer = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        }
        val msg = JSONObject().apply {
            put("action", "announce")
            put("info_hash", infoHash)
            put("peer_id", peerId)
            put("to_peer_id", toPeerId)
            put("offer_id", offerId)
            put("answer", answer)
        }
        send(msg)
        Log.d(TAG, "Sent answer to $toPeerId in room ${infoHash.take(8)}")
        toast("TX answer → peer ${toPeerId.take(8)}")
    }

    /** Re-announce to a room (no offers — just presence). */
    fun announcePresence(infoHash: String) {
        val msg = JSONObject().apply {
            put("action", "announce")
            put("info_hash", infoHash)
            put("peer_id", peerId)
            put("numwant", 0)
        }
        send(msg)
        Log.d(TAG, "Presence in room ${infoHash.take(8)}")
        toast("Presence → room ${infoHash.take(8)}")
    }

    private fun send(json: JSONObject) {
        val socket = ws
        if (socket == null) {
            Log.w(TAG, "WS not connected, dropping message")
            toast("Tracker: not connected!")
            return
        }
        socket.send(json.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Offer relayed from another peer
            if (json.has("offer")) {
                val infoHash = json.optString("info_hash", "")
                val fromPeerId = json.optString("peer_id", "")
                val offerId = json.optString("offer_id", "")
                val offerObj = json.getJSONObject("offer")
                val sdp = offerObj.optString("sdp", "")
                val fromPk = offerObj.optString("from_pk", "")
                Log.d(TAG, "RX offer from $fromPeerId in room ${infoHash.take(8)}")
                toast("RX offer ← peer ${fromPeerId.take(8)}")
                _signals.tryEmit(Signal.Offer(infoHash, fromPeerId, offerId, sdp, fromPk))
                return
            }

            // Answer relayed from another peer
            if (json.has("answer")) {
                val infoHash = json.optString("info_hash", "")
                val fromPeerId = json.optString("peer_id", "")
                val offerId = json.optString("offer_id", "")
                val answerObj = json.getJSONObject("answer")
                val sdp = answerObj.optString("sdp", "")
                Log.d(TAG, "RX answer from $fromPeerId in room ${infoHash.take(8)}")
                toast("RX answer ← peer ${fromPeerId.take(8)}")
                _signals.tryEmit(Signal.Answer(infoHash, fromPeerId, offerId, sdp))
                return
            }

            // Log any other tracker messages (peer lists, errors, etc.)
            if (json.has("info_hash")) {
                Log.d(TAG, "Tracker msg for room ${json.optString("info_hash").take(8)}: ${text.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        mainHandler.post { onToast(msg) }
    }
}
