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
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Encryption {

    private const val CURVE     = "secp256r1"
    private const val CIPHER    = "AES/GCM/NoPadding"
    private const val GCM_BITS  = 128
    private const val NONCE_LEN = 12

    // Layout: [2 bytes: ephemPubLen][ephemPub][12 bytes: nonce][ciphertext+tag]
    fun encrypt(plaintext: ByteArray, recipientPublicKey: PublicKey): ByteArray {
        val ephem = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(CURVE)) }
            .generateKeyPair()

        val secret = ecdh(ephem.private, recipientPublicKey)
        val aesKey = SecretKeySpec(sha256(secret), "AES")
        val nonce  = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }

        val ct = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_BITS, nonce))
            doFinal(plaintext)
        }

        val pub = ephem.public.encoded
        return ByteArray(2 + pub.size + nonce.size + ct.size).also { out ->
            out[0] = (pub.size shr 8).toByte()
            out[1] = (pub.size and 0xFF).toByte()
            System.arraycopy(pub,   0, out, 2,                    pub.size)
            System.arraycopy(nonce, 0, out, 2 + pub.size,         nonce.size)
            System.arraycopy(ct,    0, out, 2 + pub.size + nonce.size, ct.size)
        }
    }

    fun decrypt(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val pubLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val pub    = data.sliceArray(2 until 2 + pubLen)
        val nonce  = data.sliceArray(2 + pubLen until 2 + pubLen + NONCE_LEN)
        val ct     = data.sliceArray(2 + pubLen + NONCE_LEN until data.size)

        val ephemPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        val secret   = ecdh(privateKey, ephemPub)
        val aesKey   = SecretKeySpec(sha256(secret), "AES")

        return Cipher.getInstance(CIPHER).run {
            init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_BITS, nonce))
            doFinal(ct)
        }
    }

    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(pub, true)
            generateSecret()
        }

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}
