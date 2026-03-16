package com.davv.trusti.smp

import android.util.Base64
import java.security.MessageDigest

object RoomIds {

    /** Handshake room: sha256(B's public key base64url) — B's personal room. */
    fun handshakeRoom(peerPkB64: String): String = sha256hex(peerPkB64)

    /** Permanent room: sha256(sorted(A_key + B_key)). */
    fun permanentRoom(myPkB64: String, peerPkB64: String): String {
        val sorted = listOf(myPkB64, peerPkB64).sorted()
        return sha256hex(sorted[0] + sorted[1])
    }

    private fun sha256hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Generate a random 20-byte peer_id hex string (BitTorrent style). */
    fun randomPeerId(): String {
        val bytes = ByteArray(20)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
