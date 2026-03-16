package com.davv.trusti.connection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrHelper {

    private const val SCHEME = "trusti://peer"

    fun generateBitmap(context: Context, publicKeyB64Url: String, size: Int = 512): Bitmap {
        val uri = "$SCHEME?pk=$publicKeyB64Url"
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** Parse a trusti:// URI and return the public key, or null if invalid. */
    fun parse(contents: String): String? {
        if (!contents.startsWith("$SCHEME?")) return null
        val params = contents.substringAfter("?").split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        return params["pk"]
    }
}
