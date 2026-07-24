package com.quantum.qbeam.optical

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Photon channel codec. Frame bytes are Base64-encoded and rendered as QR codes; the
 * receiver reads the Base64 string back and decodes to the original frame bytes.
 *
 * Base64 keeps us inside QR's efficient alphanumeric/byte handling and survives ML Kit's
 * String-based rawValue cleanly. Recommended optical chunk size: ~600 bytes/frame.
 */
object QrCodec {

    fun frameToPayload(frame: ByteArray): String =
        Base64.encodeToString(frame, Base64.NO_WRAP)

    fun payloadToFrame(payload: String): ByteArray? = try {
        Base64.decode(payload, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) { null }

    /** Render a frame as a square QR bitmap of the given pixel size. */
    fun encode(frame: ByteArray, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val matrix = QRCodeWriter().encode(
            frameToPayload(frame), BarcodeFormat.QR_CODE, sizePx, sizePx, hints
        )
        val w = matrix.width; val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
