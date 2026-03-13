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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * Signaling client using public WebTorrent trackers.
 * Protocol: https://github.com/webtorrent/bittorrent-tracker
 */
class TorrentSignaling(
    private val trackerUrl: String = "wss://tracker.openwebtorrent.com",
    private val scope: CoroutineScope
) {
    private val client = run {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagerFactory.trustManagers, null)
        
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManagerFactory.trustManagers[0] as javax.net.ssl.X509TrustManager)
            .build()
    }

    private var ws: WebSocket? = null
    private var peerId = generatePeerId()
    // Incremented on every connect(). Callbacks from a socket whose generation != current
    // are stale (old socket fired after new one opened) and must be ignored.
    @Volatile private var generation = 0

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events

    private val rooms = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var reconnectJob: Job? = null
    private var reannounceJob: Job? = null

    @Volatile var isReady = false
        private set

    // Prevents onFailure + onClosing both firing disconnect callbacks for the same socket.
    @Volatile private var connected = false

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onTrackerError: ((String) -> Unit)? = null

    /**
     * Convert a hex room-ID (SHA-256, 64 hex chars) to the 20-byte binary
     * string the WebTorrent tracker expects as `info_hash`.
     */
    private fun hexToInfoHash(hexRoomId: String): String {
        require(hexRoomId.length >= 40) { "Room ID too short: $hexRoomId" }
        require(hexRoomId.matches(Regex("^[a-fA-F0-9]+$"))) { "Invalid hex characters in room ID" }
        val hex = hexRoomId.take(40)
        try {
            val bytes = ByteArray(20) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return String(bytes, Charsets.ISO_8859_1)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid hex room ID: $hexRoomId", e)
        }
    }

    sealed class SignalingEvent {
        data class PeerDiscovered(val peerId: String) : SignalingEvent()
        data class Offer(
            val peerId: String,
            val offerId: String,
            val sdp: String,
            val fromPk: String?,
            val sig: String?    // Base64URL ECDSA-SHA256 signature of (offerId + sdp) by fromPk's key
        ) : SignalingEvent()
        data class Answer(val peerId: String, val offerId: String, val sdp: String) : SignalingEvent()
    }

    fun connect() {
        val gen = ++generation
        peerId = generatePeerId()
        ws?.cancel()
        val request = Request.Builder().url(trackerUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                Log.d(TAG, "Connected to tracker")
                isReady = true
                connected = true
                onConnected?.invoke()
                synchronized(rooms) { rooms.toList() }.forEach { sendAnnounce(it) }
                startPeriodicReannounce()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                if (text.length > 10_000) { // Prevent oversized messages
                    Log.w(TAG, "Ignoring oversized message: ${text.length} chars")
                    return
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return

                json.optString("failure reason").takeIf { it.isNotEmpty() }?.let { reason ->
                    Log.e(TAG, "Tracker error: $reason")
                    onTrackerError?.invoke(reason)
                    return
                }

                json.optJSONArray("peers")?.let { peers ->
                    for (i in 0 until peers.length()) {
                        val p = peers.optJSONObject(i) ?: continue
                        val pid = p.optString("peer_id")
                        if (pid.isNotEmpty() && pid != peerId && isValidPeerId(pid)) {
                            scope.launch { _events.emit(SignalingEvent.PeerDiscovered(pid)) }
                        }
                    }
                }

                val fromPeerId = json.optString("peer_id")
                if (fromPeerId == peerId || fromPeerId.isEmpty() || !isValidPeerId(fromPeerId)) return

                val offer = json.optJSONObject("offer")
                val answer = json.optJSONObject("answer")

                when {
                    offer != null -> runCatching {
                        val sdp = offer.getString("sdp")
                        val pk = offer.optString("from_pk").takeIf { it.isNotEmpty() }
                        val sig = offer.optString("sig").takeIf { it.isNotEmpty() }
                        scope.launch {
                            _events.emit(SignalingEvent.Offer(
                                peerId = fromPeerId,
                                offerId = json.optString("offer_id"),
                                sdp = sdp,
                                fromPk = pk,
                                sig = sig
                            ))
                        }
                    }.onFailure { e -> Log.e(TAG, "Error parsing offer", e) }

                    answer != null -> runCatching {
                        scope.launch {
                            _events.emit(SignalingEvent.Answer(
                                fromPeerId,
                                json.optString("offer_id"),
                                answer.getString("sdp")
                            ))
                        }
                    }.onFailure { e -> Log.e(TAG, "Error parsing answer", e) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                Log.e(TAG, "Tracker failure: ${t.message}")
                isReady = false
                stopPeriodicReannounce()
                handleDisconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                Log.d(TAG, "Tracker closing: $reason")
                isReady = false
                stopPeriodicReannounce()
                webSocket.close(1000, null)
                handleDisconnect()
            }
        })
    }

    /** Called by both onFailure and onClosing — guarded so it fires at most once per connection. */
    private fun handleDisconnect() {
        if (!connected) return
        connected = false
        onDisconnected?.invoke()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(5_000)
            connect()
        }
    }

    fun removeRoom(roomId: String) {
        rooms.remove(roomId)
    }

    fun announce(roomId: String) {
        rooms.add(roomId)
        if (isReady) sendAnnounce(roomId)
    }

    private fun sendAnnounce(roomId: String) {
        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("downloaded", 0).put("left", 0).put("uploaded", 0)
            .put("numwant", 10)
        ws?.send(msg.toString())
    }

    fun announceWithOffer(roomId: String, offerId: String, sdp: String, myPk: String? = null, sig: String? = null) {
        // Do NOT add handshake rooms to the permanent rooms set — they are one-time use.
        // Re-announcing them after reconnect sends empty offers and creates signaling noise.
        val offerObj = JSONObject().put("type", "offer").put("sdp", sdp)
        myPk?.let { offerObj.put("from_pk", it) }
        sig?.let { offerObj.put("sig", it) }
        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("numwant", 1)
            .put("offers", JSONArray().put(
                JSONObject().put("offer_id", offerId).put("offer", offerObj)
            ))
        ws?.send(msg.toString())
    }

    fun sendAnswer(roomId: String, toPeerId: String, offerId: String, sdp: String) {
        val msg = JSONObject()
            .put("action", "announce")
            .put("info_hash", hexToInfoHash(roomId))
            .put("peer_id", peerId)
            .put("to_peer_id", toPeerId)
            .put("offer_id", offerId)
            .put("answer", JSONObject().put("type", "answer").put("sdp", sdp))
        ws?.send(msg.toString())
    }

    private fun startPeriodicReannounce() {
        reannounceJob?.cancel()
        reannounceJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(120_000)
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
        connected = false
        isReady = false
        ws?.cancel()
        ws = null
    }

    companion object {
        private const val TAG = "TorrentSignaling"
        
        private fun isValidPeerId(peerId: String): Boolean {
            return peerId.length == 20 && peerId.all { it.isLetterOrDigit() || it == '-' }
        }
        
        private fun generatePeerId(): String {
            val random = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            return "-TR0001-$random"
        }
    }
}
