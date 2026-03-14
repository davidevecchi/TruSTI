package com.davv.trusti.smp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class TorrentSignaling(private val scope: CoroutineScope) {

    sealed class Event {
        data class Offer(
            val peerId: String,
            val offerId: String,
            val sdp: String,
            val fromPk: String?,
            val sig: String?,
            val roomId: String      // which room this offer arrived on
        ) : Event()
        data class Answer(val peerId: String, val offerId: String, val sdp: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    @Volatile var isReady = false; private set

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onTrackerError: ((String) -> Unit)? = null

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    // 20-byte random peer identity for this session
    private val peerId: String = ByteArray(20)
        .also { SecureRandom().nextBytes(it) }
        .toString(Charsets.ISO_8859_1)

    // bi-directional mapping: infoHash ↔ roomId
    private val ihToRoom  = ConcurrentHashMap<String, String>()
    private val roomToIh  = ConcurrentHashMap<String, String>()

    fun connect() {
        Log.d(TAG, "connect → $TRACKER_URL")
        val req = Request.Builder().url(TRACKER_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) {
                Log.d(TAG, "tracker connected")
                isReady = true
                onConnected?.invoke()
            }
            override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                Log.e(TAG, "tracker failure: ${t.message}")
                isReady = false
                onTrackerError?.invoke(t.message ?: "tracker error")
                onDisconnected?.invoke()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "tracker closed code=$code reason=$reason")
                isReady = false
                onDisconnected?.invoke()
            }
        })
    }

    /** Listen on a room without sending an offer (answerer side / personal room). */
    fun announce(roomId: String) {
        Log.d(TAG, "announce room=${roomId.take(16)}")
        send(JSONObject().apply {
            put("action",    "announce")
            put("info_hash", infoHashFor(roomId))
            put("peer_id",   peerId)
            put("numwant",   0)
        })
    }

    /** Stop tracking a room (cleanup after disconnect). */
    fun removeRoom(roomId: String) {
        roomToIh.remove(roomId)?.let { ih -> ihToRoom.remove(ih) }
    }

    /** Send an offer into a room (initiator side). */
    fun announceWithOffer(
        roomId: String, offerId: String, sdp: String,
        myPk: String? = null, sig: String? = null
    ) {
        Log.d(TAG, "announceWithOffer room=${roomId.take(16)} offerId=${offerId.take(8)}")
        val offer = JSONObject().apply {
            put("type", "offer")
            put("sdp",  sdp)
            myPk?.let { put("from_pk", it) }
            sig?.let  { put("sig",     it) }
        }
        send(JSONObject().apply {
            put("action",    "announce")
            put("info_hash", infoHashFor(roomId))
            put("peer_id",   peerId)
            put("numwant",   10)
            put("offers", JSONArray().put(
                JSONObject().apply {
                    put("offer_id", offerId)
                    put("offer",    offer)
                }
            ))
        })
    }

    /** Send an answer back to an offeror. */
    fun sendAnswer(roomId: String, toPeerId: String, offerId: String, sdp: String) {
        Log.d(TAG, "sendAnswer room=${roomId.take(16)} offerId=${offerId.take(8)}")
        send(JSONObject().apply {
            put("action",      "announce")
            put("info_hash",   infoHashFor(roomId))
            put("peer_id",     peerId)
            put("to_peer_id",  toPeerId)
            put("offer_id",    offerId)
            put("answer", JSONObject().apply {
                put("type", "answer")
                put("sdp",  sdp)
            })
        })
    }

    fun disconnect() {
        isReady = false
        ws?.close(1000, "bye")
        ws = null
    }

    // --- private ---

    private fun infoHashFor(roomId: String): String =
        roomToIh.getOrPut(roomId) {
            // take first 20 bytes of the sha256 hex room ID
            val bytes = ByteArray(20) { i -> roomId.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val ih = bytes.toString(Charsets.ISO_8859_1)
            ihToRoom[ih] = roomId
            ih
        }

    private fun send(json: JSONObject) {
        val sent = ws?.send(json.toString()) ?: false
        if (!sent) Log.w(TAG, "send failed — ws closed?")
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: run {
            Log.w(TAG, "unparseable message: ${text.take(120)}")
            return
        }
        val action = obj.optString("action")
        if (action != "announce") {
            Log.d(TAG, "tracker msg action=$action (ignored)")
            return
        }

        val ih     = obj.optString("info_hash")
        val roomId = ihToRoom[ih] ?: ih   // fall back to ih if not registered

        val offerObj = obj.optJSONObject("offer")
        if (offerObj != null) {
            val fromPk = offerObj.optString("from_pk").takeIf { it.isNotEmpty() }
            Log.d(TAG, "← offer  room=${roomId.take(16)} offerId=${obj.optString("offer_id").take(8)} fromPk=${fromPk?.take(8)}")
            scope.launch {
                _events.emit(Event.Offer(
                    peerId  = obj.optString("peer_id"),
                    offerId = obj.optString("offer_id"),
                    sdp     = offerObj.optString("sdp"),
                    fromPk  = offerObj.optString("from_pk").takeIf { it.isNotEmpty() },
                    sig     = offerObj.optString("sig").takeIf     { it.isNotEmpty() },
                    roomId  = roomId
                ))
            }
            return
        }

        val answerObj = obj.optJSONObject("answer")
        if (answerObj != null) {
            Log.d(TAG, "← answer room=${roomId.take(16)} offerId=${obj.optString("offer_id").take(8)}")
            scope.launch {
                _events.emit(Event.Answer(
                    peerId  = obj.optString("peer_id"),
                    offerId = obj.optString("offer_id"),
                    sdp     = answerObj.optString("sdp")
                ))
            }
        }
    }

    companion object {
        private const val TAG = "TorrentSignaling"
        private const val TRACKER_URL = "wss://tracker.openwebtorrent.com"
    }
}
