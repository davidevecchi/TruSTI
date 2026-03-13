package com.davv.trusti.smp

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * ECDH ephemeral + AES-256-GCM end-to-end encryption.
 *
 * Key derivation: HKDF-SHA256 with info = "trusti-v1" + ephPubDER + recipientPubDER,
 * binding both parties into the key material and preventing cross-context key reuse.
 *
 * Wire format: [2-byte big-endian ephPubLen][ephPubDER][12-byte IV][ciphertext+GCM-tag]
 */
object Encryption {

    private val rng = SecureRandom()

    /** Constant-time comparison to prevent timing attacks */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i] xor b[i]).toInt()
        }
        return result == 0
    }

    /** HKDF-SHA256: extract then expand, binding ephemeral and recipient keys into info. */
    private fun hkdf(sharedSecret: ByteArray, ephPub: ByteArray, recipientPub: ByteArray): ByteArray {
        val info = "trusti-v1".toByteArray() + ephPub + recipientPub
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC-SHA256(salt=zeros, IKM=sharedSecret)
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(sharedSecret)
        // Expand: OKM = HMAC-SHA256(PRK, info || 0x01) — one block = 32 bytes = AES-256 key
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        return mac.doFinal(byteArrayOf(0x01))
    }

    fun encrypt(plaintext: ByteArray, recipientPublicKey: PublicKey): ByteArray {
        require(recipientPublicKey.encoded.isNotEmpty()) { "Invalid recipient public key" }
        
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val ephKp = kpg.generateKeyPair()
        val ephDer = ephKp.public.encoded

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(ephKp.private); doPhase(recipientPublicKey, true); generateSecret()
        }
        val aesKey = hkdf(sharedSecret, ephDer, recipientPublicKey.encoded)

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            doFinal(plaintext)
        }

        val lenBytes = byteArrayOf((ephDer.size ushr 8).toByte(), (ephDer.size and 0xFF).toByte())
        return lenBytes + ephDer + iv + ciphertext
    }

    fun decrypt(data: ByteArray, privateKey: PrivateKey, recipientPublicKey: PublicKey): ByteArray {
        require(data.size >= 2) { "Ciphertext too short" }
        require(recipientPublicKey.encoded.isNotEmpty()) { "Invalid recipient public key" }
        
        var off = 0
        val ephLen = ((data[off++].toInt() and 0xFF) shl 8) or (data[off++].toInt() and 0xFF)
        require(ephLen > 0 && ephLen <= 200) { "Invalid ephemeral key length: $ephLen" }
        require(data.size >= 2 + ephLen + 12 + 16) { "Ciphertext truncated (need ephem+IV+tag)" }
        require(2 + ephLen + 12L + 16L <= Integer.MAX_VALUE) { "Ciphertext size overflow" }
        val ephDer = data.copyOfRange(off, off + ephLen); off += ephLen
        val iv = data.copyOfRange(off, off + 12); off += 12
        val ciphertext = data.copyOfRange(off, data.size)

        val ephPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephDer))
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(privateKey); doPhase(ephPub, true); generateSecret()
        }
        val aesKey = hkdf(sharedSecret, ephDer, ephDer)

        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        }.getOrElse { e -> throw IllegalArgumentException("Decryption failed: ${e.message}", e) }
    }
}
