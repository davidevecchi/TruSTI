package com.davv.trusti.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

object KeyManager {
    private const val PREFS = "trusti_keys"
    private const val KEY_PUB = "ec_pub"
    private const val KEY_PRIV = "ec_priv"

    private var cached: KeyPair? = null

    fun getOrCreateKeyPair(context: Context): KeyPair {
        cached?.let { return it }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pubB64 = prefs.getString(KEY_PUB, null)
        val privB64 = prefs.getString(KEY_PRIV, null)

        if (pubB64 != null && privB64 != null) {
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
            val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
            return KeyPair(pub, priv).also { cached = it }
        }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        prefs.edit()
            .putString(KEY_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()

        return kp.also { cached = it }
    }

    fun publicKeyToBase64Url(pub: PublicKey): String =
        Base64.encodeToString(pub.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun base64UrlToPublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }
}
