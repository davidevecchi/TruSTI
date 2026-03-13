package com.davv.trusti.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object KeyManager {

    private const val PREFS = "trusti_keys"
    private const val KEY_PRIVATE = "private_key"
    private const val KEY_PUBLIC = "public_key"
    private const val KEY_SIGNATURE = "key_signature"

    fun getOrCreateKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64 = prefs.getString(KEY_PUBLIC, null)
        val signature = prefs.getString(KEY_SIGNATURE, null)
        
        if (privB64 != null && pubB64 != null && signature != null) {
            return runCatching {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                val kp = KeyPair(pub, priv)
                
                // Verify key integrity
                val keyData = "$privB64$pubB64".toByteArray()
                if (!verify(keyData, Base64.decode(signature, Base64.NO_WRAP), pub)) {
                    throw SecurityException("Key integrity check failed")
                }
                kp
            }.getOrElse { generateAndStore(prefs) }
        }
        return generateAndStore(prefs)
    }

    fun publicKeyToBase64Url(pub: PublicKey): String =
        Base64.encodeToString(pub.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun base64UrlToPublicKey(b64: String): PublicKey? = runCatching {
        require(b64.isNotEmpty()) { "Empty public key string" }
        val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(bytes.isNotEmpty()) { "Invalid public key encoding" }
        val kf = KeyFactory.getInstance("EC")
        kf.generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    fun sign(data: ByteArray, privateKey: java.security.PrivateKey): ByteArray =
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    fun verify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean =
        runCatching {
            require(data.isNotEmpty()) { "Empty data for verification" }
            require(signature.isNotEmpty()) { "Empty signature for verification" }
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrElse { false }

    private fun generateAndStore(prefs: android.content.SharedPreferences): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val keyData = "$privB64$pubB64".toByteArray()
        val signature = sign(keyData, kp.private)
        
        prefs.edit()
            .putString(KEY_PRIVATE, privB64)
            .putString(KEY_PUBLIC, pubB64)
            .putString(KEY_SIGNATURE, Base64.encodeToString(signature, Base64.NO_WRAP))
            .apply() // async - prevents ANR
        return kp
    }
}
