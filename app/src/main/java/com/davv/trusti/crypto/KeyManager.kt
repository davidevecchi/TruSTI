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

    fun getOrCreateKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64 = prefs.getString(KEY_PUBLIC, null)
        if (privB64 != null && pubB64 != null) {
            return runCatching {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                KeyPair(pub, priv)
            }.getOrElse { generateAndStore(prefs) }
        }
        return generateAndStore(prefs)
    }

    fun publicKeyToBase64Url(pub: PublicKey): String =
        Base64.encodeToString(pub.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun base64UrlToPublicKey(b64: String): PublicKey? = runCatching {
        val kf = KeyFactory.getInstance("EC")
        kf.generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
    }.getOrNull()

    private fun generateAndStore(prefs: android.content.SharedPreferences): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }
}
