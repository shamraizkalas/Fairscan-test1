package org.fairscan.app.camscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector

object QrScannerHelper {
    fun detectQrFromBitmap(bitmap: Bitmap): String? {
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val detector = QRCodeDetector()
            val decoded = detector.detectAndDecode(gray)
            decoded?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            rgba.release()
            gray.release()
        }
    }
}
