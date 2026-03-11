package com.davv.trusti.smp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Signaling client using public WebTorrent trackers.
 * Protocol: https://github.com/webtorrent/bittorrent-tracker
 *
 * Offer flow:
 *   Offerer  → Tracker : announce with "offers" array [{offer_id, offer:{type,sdp,...}}]
 *   Tracker  → Answerer: {peer_id, offer_id, offer:{type,sdp,...}}
 *   Answerer → Tracker : announce with to_peer_id, offer_id, answer:{type,sdp}
 *   Tracker  → Offerer : {peer_id, offer_id, answer:{type,sdp}}
 */
class TorrentSignaling(
    private val trackerUrl: String = "wss://tracker.openwebtorrent.com",
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val peerId = "-TR0001-" + UUID.randomUUID().toString().replace("-", "").take(12)

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events

    private val rooms = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var reconnectJob: Job? = null
    private var reannounceJob: Job? = null

    @Volatile var isReady = false
        private set

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onTrackerError: ((String) -> Unit)? = null

    /**
     * Convert a hex room-ID (SHA-256, 64 hex chars) to the 20-byte binary
     * string the WebTorrent tracker expects as `info_hash`.
     *
     * The tracker validates `info_hash.length === 20` (JS string length)
     * and then calls `Buffer.from(str, 'binary')` to extract bytes.
     * We use ISO-8859-1 so each Java char maps 1-to-1 to one byte.
     */
    private fun hexToInfoHash(hexRoomId: String): String {
        val hex = hexRoomId.take(40) // first 20 bytes = 40 hex chars
        val bytes = ByteArray(20) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    sealed class SignalingEvent {
        data class PeerDiscovered(val peerId: String) : SignalingEvent()
        data class Offer(
            val peerId: String,
            val offerId: String,
            val sdp: String,
            val fromPk: String?,
            val fromName: String?,
            val fromDisambiguation: String?
        ) : SignalingEvent()
        data class Answer(val peerId: String, val offerId: String, val sdp: String) : SignalingEvent()
        data class IceCandidate(val peerId: String, val candidate: JSONObject) : SignalingEvent()
    }

    fun connect() {
        val request = Request.Builder().url(trackerUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to tracker as $peerId")
                isReady = true
                onConnected?.invoke()
                synchronized(rooms) { rooms.toList() }.forEach { sendAnnounce(it) }
                startPeriodicReannounce()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Tracker msg: ${text.take(300)}")
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return

                json.optString("failure reason").takeIf { it.isNotEmpty() }?.let { reason ->
                    Log.e(TAG, "Tracker error: $reason")
                    onTrackerError?.invoke(reason)
                    return
                }

                // Peer discovery from announce response
                json.optJSONArray("peers")?.let { peers ->
                    for (i in 0 until peers.length()) {
                        val p = peers.optJSONObject(i) ?: continue
                        val pid = p.optString("peer_id")
                        if (pid.isNotEmpty() && pid != peerId) {
                            scope.launch { _events.emit(SignalingEvent.PeerDiscovered(pid)) }
                        }
                    }
                }

                val fromPeerId = json.optString("peer_id")
                if (fromPeerId == peerId || fromPeerId.isEmpty()) return

                val offerId = json.optString("offer_id")
                val offer = json.optJSONObject("offer")
                val answer = json.optJSONObject("answer")
                val candidate = json.optJSONObject("candidate")

                when {
                    offer != null -> runCatching {
                        val sdp = offer.getString("sdp")
                        // Try JSON fields first, then fall back to SDP attributes
                        // (tracker may strip custom JSON fields but SDP is always opaque)
                        val pk = offer.optString("from_pk").takeIf { it.isNotEmpty() }
                            ?: extractSdpAttr(sdp, "x-trusti-pk")
                        val name = offer.optString("from_name").takeIf { it.isNotEmpty() }
                            ?: extractSdpAttr(sdp, "x-trusti-name")
                        val disambig = offer.optString("from_disambig").takeIf { it.isNotEmpty() }
                            ?: extractSdpAttr(sdp, "x-trusti-disambig")
                        scope.launch {
                            _events.emit(SignalingEvent.Offer(
                                peerId = fromPeerId,
                                offerId = offerId,
                                sdp = sdp,
                                fromPk = pk,
                                fromName = name,
                                fromDisambiguation = disambig
                            ))
                        }
                    }.onFailure { e -> Log.e(TAG, "Error parsing offer: $e") }

                    answer != null -> runCatching {
                        scope.launch {
                            _events.emit(SignalingEvent.Answer(fromPeerId, offerId, answer.getString("sdp")))
                        }
                    }.onFailure { e -> Log.e(TAG, "Error parsing answer: $e") }

                    candidate != null -> scope.launch {
                        _events.emit(SignalingEvent.IceCandidate(fromPeerId, candidate))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Tracker failure: ${t.message}")
                isReady = false
                stopPeriodicReannounce()
                onDisconnected?.invoke()
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Tracker closing: $reason")
                isReady = false
                stopPeriodicReannounce()
                onDisconnected?.invoke()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(5000)
            connect()
        }
    }

    /** Stop announcing a room (e.g. when a contact is deleted). */
    fun removeRoom(roomId: String) {
        rooms.remove(roomId)
    }

    /** Announce presence in a room (passive — no offer). */
    fun announce(roomId: String) {
        rooms.add(roomId)
        sendAnnounce(roomId)
    }

    private fun sendAnnounce(roomId: String) {
        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("downloaded", 0).put("left", 0).put("uploaded", 0)
            .put("numwant", 10)
        val sent = ws?.send(msg.toString()) ?: false
        Log.d(TAG, "sendAnnounce room=${roomId.take(8)}… sent=$sent")
    }

    /**
     * Announce to a room with a WebRTC offer attached.
     * The tracker delivers the offer to a peer already present in the room.
     */
    fun announceWithOffer(
        roomId: String,
        offerId: String,
        sdp: String,
        myPk: String? = null,
        myName: String? = null,
        myDisambiguation: String? = null
    ) {
        rooms.add(roomId)
        val offerObj = JSONObject().put("type", "offer").put("sdp", sdp)
        myPk?.let { offerObj.put("from_pk", it) }
        myName?.let { offerObj.put("from_name", it) }
        myDisambiguation?.let { offerObj.put("from_disambig", it) }

        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("numwant", 1)
            .put("offers", JSONArray().put(
                JSONObject().put("offer_id", offerId).put("offer", offerObj)
            ))
        val sent = ws?.send(msg.toString()) ?: false
        Log.d(TAG, "announceWithOffer room=${roomId.take(8)}… offerId=${offerId.take(8)} sent=$sent")
    }

    /** Send answer back to the offerer. */
    fun sendAnswer(roomId: String, toPeerId: String, offerId: String, sdp: String) {
        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("to_peer_id", toPeerId)
            .put("offer_id", offerId)
            .put("answer", JSONObject().put("type", "answer").put("sdp", sdp))
        val sent = ws?.send(msg.toString()) ?: false
        Log.d(TAG, "sendAnswer room=${roomId.take(8)}… to=${toPeerId.take(8)} offerId=${offerId.take(8)} sent=$sent")
    }

    private fun startPeriodicReannounce() {
        reannounceJob?.cancel()
        reannounceJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(120_000) // re-announce every 2 min (tracker default interval)
                Log.d(TAG, "Periodic re-announce for ${rooms.size} rooms")
                synchronized(rooms) { rooms.toList() }.forEach { sendAnnounce(it) }
            }
        }
    }

    private fun stopPeriodicReannounce() {
        reannounceJob?.cancel()
        reannounceJob = null
    }

    fun disconnect() {
        reconnectJob?.cancel()
        stopPeriodicReannounce()
        ws?.close(1000, "Normal closure")
        ws = null
        isReady = false
    }

    /** Extract a custom `a=<attr>:<value>` line from an SDP string. */
    private fun extractSdpAttr(sdp: String, attr: String): String? {
        val prefix = "a=$attr:"
        return sdp.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "TorrentSignaling"
    }
}
