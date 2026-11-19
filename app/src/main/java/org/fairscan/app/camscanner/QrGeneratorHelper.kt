package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

enum class QrPayloadType(val label: String) {
    URL("Website URL"),
    TEXT("Text / Note"),
    WIFI("Wi-Fi Share"),
    CONTACT("Contact vCard"),
}

object QrGeneratorHelper {

    fun buildWifiPayload(ssid: String, password: String, encryption: String = "WPA"): String {
        val cleanSsid = escapeWifiField(ssid.trim())
        val cleanPass = escapeWifiField(password)
        val enc = encryption.uppercase().takeIf { it in listOf("WPA", "WEP", "NOPASS") } ?: "WPA"
        return if (enc == "NOPASS" || cleanPass.isEmpty()) {
            "WIFI:T:nopass;S:$cleanSsid;;"
        } else {
            "WIFI:T:$enc;S:$cleanSsid;P:$cleanPass;;"
        }
    }

    fun buildVCardPayload(
        name: String,
        phone: String = "",
        email: String = "",
        organization: String = "",
    ): String {
        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:${name.trim()}")
            if (organization.isNotBlank()) appendLine("ORG:${organization.trim()}")
            if (phone.isNotBlank()) appendLine("TEL:${phone.trim()}")
            if (email.isNotBlank()) appendLine("EMAIL:${email.trim()}")
            append("END:VCARD")
        }
    }

    fun generateQrBitmap(
        content: String,
        sizePx: Int = 720,
        foregroundArgb: Int = Color.BLACK,
        backgroundArgb: Int = Color.WHITE,
    ): Bitmap? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val side = sizePx.coerceIn(128, 2048)
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(trimmed, BarcodeFormat.QR_CODE, side, side, hints)
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val rowOffset = y * side
            for (x in 0 until side) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) foregroundArgb else backgroundArgb
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun escapeWifiField(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
    }
}
