package com.davv.trusti.smp

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH ephemeral P-256 + AES-256-GCM envelope.
 *
 * Wire format (all base64url):  EPHEMERAL_PUB.IV.CIPHERTEXT
 *
 * Key derivation: raw ECDH shared secret → SHA-256 → 32-byte AES key.
 * Ephemeral key per message → forward secrecy.
 */
object Encryption {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Encrypt [plaintext] for [recipientPub]. Returns base64url envelope. */
    fun encrypt(plaintext: ByteArray, recipientPub: PublicKey, senderPriv: java.security.PrivateKey): String {
        val ephKpg = KeyPairGenerator.getInstance("EC")
        ephKpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephKp = ephKpg.generateKeyPair()

        val aesKey = deriveKey(ephKp.private, recipientPub)

        val iv = ByteArray(IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)

        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        return "${b64(ephKp.public.encoded)}.${b64(iv)}.${b64(ct)}"
    }

    /** Decrypt an envelope produced by [encrypt]. */
    fun decrypt(envelope: String, recipientPriv: java.security.PrivateKey): ByteArray {
        val parts = envelope.split(".")
        require(parts.size == 3) { "Bad envelope" }
        val dec = { s: String -> Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

        val ephPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(dec(parts[0])))
        val aesKey = deriveKey(recipientPriv, ephPub)

        val iv = dec(parts[1])
        val ct = dec(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** ECDH raw shared secret → SHA-256 → 32-byte AES-256 key. */
    private fun deriveKey(myPriv: java.security.PrivateKey, theirPub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPriv)
        ka.doPhase(theirPub, true)
        val rawSecret = ka.generateSecret()                 // raw bytes
        return MessageDigest.getInstance("SHA-256").digest(rawSecret)  // 32 bytes
    }
}
