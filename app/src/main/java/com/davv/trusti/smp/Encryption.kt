package com.davv.trusti.smp

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH ephemeral + AES-256-GCM end-to-end encryption.
 *
 * Wire format: [2-byte big-endian ephPubLen][ephPubDER][12-byte IV][ciphertext+GCM-tag]
 */
object Encryption {

    fun encrypt(plaintext: ByteArray, recipientPublicKey: PublicKey): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val ephKp = kpg.generateKeyPair()

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(ephKp.private); doPhase(recipientPublicKey, true); generateSecret()
        }
        val aesKey = MessageDigest.getInstance("SHA-256").digest(sharedSecret)

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            doFinal(plaintext)
        }

        val ephDer = ephKp.public.encoded
        val lenBytes = byteArrayOf((ephDer.size ushr 8).toByte(), (ephDer.size and 0xFF).toByte())
        return lenBytes + ephDer + iv + ciphertext
    }

    fun decrypt(data: ByteArray, privateKey: PrivateKey): ByteArray {
        var off = 0
        val ephLen = ((data[off++].toInt() and 0xFF) shl 8) or (data[off++].toInt() and 0xFF)
        val ephDer = data.copyOfRange(off, off + ephLen); off += ephLen
        val iv = data.copyOfRange(off, off + 12); off += 12
        val ciphertext = data.copyOfRange(off, data.size)

        val ephPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephDer))
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(privateKey); doPhase(ephPub, true); generateSecret()
        }
        val aesKey = MessageDigest.getInstance("SHA-256").digest(sharedSecret)

        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            doFinal(ciphertext)
        }
    }
}
