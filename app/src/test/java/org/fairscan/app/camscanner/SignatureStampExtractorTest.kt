package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class SignatureStampExtractorTest {

    @Test
    fun extractTransparentAsset_removesPaperAndPreservesInk() {
        val src = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        canvas.drawColor(Color.rgb(235, 232, 224)) // Warm off-white paper
        val inkPaint = Paint().apply {
            color = Color.rgb(15, 25, 45)
            strokeWidth = 10f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(60f, 70f, 180f, 70f, inkPaint)

        val extracted = SignatureStampExtractor.extractTransparentAsset(
            source = src,
            cropLeftNorm = 0f,
            cropTopNorm = 0f,
            cropRightNorm = 1f,
            cropBottomNorm = 1f,
            inkColor = StampInkColor.BLACK,
            sensitivity = 0.05f,
        )

        assertNotNull(extracted)
        val out = extracted!!
        // Should trim tightly around the horizontal ink line rather than keeping full 240x140
        assertTrue(out.width in 110..170)
        assertTrue(out.height in 15..55)
        // Corner pixel should be transparent
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        // Center pixel on the ink line should be opaque
        assertTrue(Color.alpha(out.getPixel(out.width / 2, out.height / 2)) > 200)
    }

    @Test
    fun extractTransparentAsset_preservesColoredStampInOriginalMode() {
        val src = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        canvas.drawColor(Color.rgb(242, 242, 242))
        val stampPaint = Paint().apply {
            color = Color.rgb(195, 30, 35)
            strokeWidth = 12f
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(100f, 100f, 45f, stampPaint)

        val extracted = SignatureStampExtractor.extractTransparentAsset(
            source = src,
            inkColor = StampInkColor.ORIGINAL,
            sensitivity = 0.05f,
        )

        assertNotNull(extracted)
        val out = extracted!!
        assertEquals(0, Color.alpha(out.getPixel(out.width / 2, out.height / 2)))
        val topArcPixel = out.getPixel(out.width / 2, 10)
        assertTrue(Color.alpha(topArcPixel) > 150)
        assertTrue(Color.red(topArcPixel) > Color.blue(topArcPixel))
    }

    @Test
    fun mergeSignatureOnBitmap_supportsRotationAndOpacity() {
        val page = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)

        val stamp = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        stamp.eraseColor(Color.RED)

        val merged = SignatureHelper.mergeSignatureOnBitmap(
            pageBitmap = page,
            signatureBitmap = stamp,
            positionNormalizedX = 0.5f,
            positionNormalizedY = 0.5f,
            scaleFactor = 0.30f,
            rotationDeg = -12f,
            opacity = 0.85f,
        )

        assertEquals(400, merged.width)
        assertEquals(600, merged.height)
        val centerPixel = merged.getPixel(200, 300)
        assertTrue(Color.red(centerPixel) > Color.green(centerPixel))
    }
}
