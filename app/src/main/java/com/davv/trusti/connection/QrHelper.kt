package com.davv.trusti.connection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.davv.trusti.R
import android.net.Uri
import android.os.Parcelable
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.parcelize.Parcelize

/** Scanned peer info extracted from a TruSTI QR code. */
@Parcelize
data class PeerInfo(
    val publicKey: String
) : Parcelable

object QrHelper {

    private const val SCHEME = "trusti"

    /**
     * Generate a QR bitmap encoding the public key.
     * URI: trusti://peer?pk=BASE64URL
     */
    fun generateBitmap(
        context: Context,
        publicKeyB64: String,
        @Suppress("UNUSED_PARAMETER") relayUrl: String? = null,
        @Suppress("UNUSED_PARAMETER") senderId: String? = null,
        size: Int = 512
    ): Bitmap {
        val content = buildUri(publicKeyB64)
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        // Use standard Black and White for the QR code for maximum scanability and consistency.
        val qrColor = Color.BLACK
        val qrBackground = Color.WHITE
        
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) qrColor else qrBackground
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    /** Parse a scanned URI. Returns [PeerInfo] or null if not a valid TruSTI QR. */
    fun parse(raw: String): PeerInfo? = runCatching {
        val uri = Uri.parse(raw)
        if (uri.scheme != SCHEME) return null
        val pk = uri.getQueryParameter("pk") ?: return null
        PeerInfo(publicKey = pk)
    }.getOrNull()

    private fun buildUri(publicKeyB64: String): String {
        return "$SCHEME://peer?pk=$publicKeyB64"
    }
}
