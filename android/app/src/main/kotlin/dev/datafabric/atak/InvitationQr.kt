package dev.arachne.atak

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal fun invitationQr(link: String, size: Int): Bitmap {
    require(size in 256..2048)
    WorkspaceInvitation.decode(link)
    val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.LTGRAY
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
