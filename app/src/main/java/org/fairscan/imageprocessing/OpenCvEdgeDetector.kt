package org.fairscan.imageprocessing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class OpenCvQuadResult(
    val normalizedQuad: Quad,
    val confidenceScore: Double,
)

/**
 * Pure OpenCV contour-based document edge detector.
 * Identifies rectangular paper/card boundaries directly from RGB/BGR image contours
 * and returns a normalized 0.0..1.0 Quad ordered as TopLeft -> TopRight -> BottomRight -> BottomLeft.
 */
object OpenCvEdgeDetector {

    private const val WORKING_MAX_DIM = 720.0

    fun detectDocumentQuad(bgrMat: Mat): OpenCvQuadResult? {
        if (bgrMat.empty() || bgrMat.cols() < 24 || bgrMat.rows() < 24) return null
        return try {
            detectInternal(bgrMat)
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectInternal(bgrMat: Mat): OpenCvQuadResult? {
        val origW = bgrMat.cols().toDouble()
        val origH = bgrMat.rows().toDouble()
        val scale = min(WORKING_MAX_DIM / max(origW, origH), 1.0)
        val workW = (origW * scale).toInt().coerceAtLeast(32)
        val workH = (origH * scale).toInt().coerceAtLeast(32)

        val resized = Mat()
        if (scale < 1.0) {
            Imgproc.resize(bgrMat, resized, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            bgrMat.copyTo(resized)
        }

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)

        // Suppress interior text/lines with morphological closing so outer paper edges dominate
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val textSuppressed = Mat()
        Imgproc.morphologyEx(gray, textSuppressed, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()

        val blurred = Mat()
        Imgproc.GaussianBlur(textSuppressed, blurred, Size(5.0, 5.0), 0.0)

        val candidates = mutableListOf<OpenCvQuadResult>()
        val imageArea = workW.toDouble() * workH.toDouble()

        // Strategy 1 & 2: Multi-threshold Canny on morphologically smoothed luminance
        val thresholdPairs = listOf(
            45.0 to 145.0,
            25.0 to 95.0,
            75.0 to 200.0,
        )
        val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        for ((t1, t2) in thresholdPairs) {
            val edges = Mat()
            Imgproc.Canny(blurred, edges, t1, t2)
            Imgproc.dilate(edges, edges, dilateKernel)
            collectQuadCandidates(edges, workW, workH, imageArea, candidates)
            edges.release()
        }

        // Strategy 3: Adaptive brightness/Otsu foreground contrast against desk/table
        val lab = Mat()
        Imgproc.cvtColor(resized, lab, Imgproc.COLOR_BGR2Lab)
        val labChannels = ArrayList<Mat>(3)
        Core.split(lab, labChannels)
        val lChannel = labChannels[0]
        val lSmooth = Mat()
        Imgproc.GaussianBlur(lChannel, lSmooth, Size(7.0, 7.0), 0.0)
        val otsu = Mat()
        Imgproc.threshold(lSmooth, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val morphGrad = Mat()
        Imgproc.morphologyEx(otsu, morphGrad, Imgproc.MORPH_GRADIENT, dilateKernel)
        collectQuadCandidates(morphGrad, workW, workH, imageArea, candidates)

        morphGrad.release()
        otsu.release()
        lSmooth.release()
        labChannels.forEach { it.release() }
        lab.release()
        dilateKernel.release()
        blurred.release()
        textSuppressed.release()
        gray.release()
        resized.release()

        return candidates.maxByOrNull { it.confidenceScore }
    }

    private fun collectQuadCandidates(
        edgeMap: Mat,
        workW: Int,
        workH: Int,
        imageArea: Double,
        outCandidates: MutableList<OpenCvQuadResult>,
    ) {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edgeMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val sorted = contours
            .asSequence()
            .map { contour -> contour to abs(Imgproc.contourArea(contour)) }
            .filter { (_, area) -> area >= imageArea * 0.08 && area <= imageArea * 0.98 }
            .sortedByDescending { it.second }
            .take(8)
            .toList()

        for ((contour, area) in sorted) {
            val c2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(c2f, true)
            if (perimeter <= 0.0) {
                c2f.release()
                continue
            }

            var foundApproxQuad = false
            for (epsFactor in listOf(0.02, 0.03, 0.04, 0.05)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, epsFactor * perimeter, true)
                val pts = approx.toArray()
                approx.release()

                if (pts.size == 4) {
                    val matInt = MatOfPoint(*pts)
                    val isConvex = Imgproc.isContourConvex(matInt)
                    matInt.release()
                    if (isConvex) {
                        scoreAndAdd(pts, area, workW, workH, imageArea, bonus = 0.12, outCandidates)
                        foundApproxQuad = true
                        break
                    }
                }
            }

            // Fallback: fit minimum rotated rectangle if contour is strongly rectangular
            if (!foundApproxQuad && area >= imageArea * 0.14) {
                val rotRect = Imgproc.minAreaRect(c2f)
                val rectArea = rotRect.size.width * rotRect.size.height
                if (rectArea > 0.0 && (area / rectArea) >= 0.72) {
                    val boxPts = Array(4) { org.opencv.core.Point() }
                    rotRect.points(boxPts)
                    scoreAndAdd(boxPts, area, workW, workH, imageArea, bonus = 0.0, outCandidates)
                }
            }
            c2f.release()
        }
        contours.forEach { it.release() }
    }

    private fun scoreAndAdd(
        rawPts: Array<org.opencv.core.Point>,
        area: Double,
        workW: Int,
        workH: Int,
        imageArea: Double,
        bonus: Double,
        outCandidates: MutableList<OpenCvQuadResult>,
    ) {
        val normPts = rawPts.map { pt ->
            Point(
                (pt.x / workW.toDouble()).coerceIn(0.0, 1.0),
                (pt.y / workH.toDouble()).coerceIn(0.0, 1.0),
            )
        }
        val quad = orderQuadClockwise(normPts) ?: return

        // Validate interior corner angles (reject collapsed/needle quads)
        val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var anglePenalty = 0.0
        for (i in 0 until 4) {
            val prev = corners[(i + 3) % 4]
            val curr = corners[i]
            val next = corners[(i + 1) % 4]
            val angleDeg = interiorAngleDeg(prev, curr, next)
            if (angleDeg < 38.0 || angleDeg > 142.0) return
            anglePenalty += abs(angleDeg - 90.0) / 90.0
        }
        val angleScore = (1.0 - (anglePenalty / 4.0)).coerceIn(0.0, 1.0)
        val areaRatio = (area / imageArea).coerceIn(0.0, 1.0)
        val score = (areaRatio * 0.55) + (angleScore * 0.33) + bonus
        if (score >= 0.30) {
            outCandidates.add(OpenCvQuadResult(quad, score))
        }
    }

    private fun orderQuadClockwise(pts: List<Point>): Quad? {
        if (pts.size != 4) return null
        val sums = pts.sortedBy { it.x + it.y }
        val tl = sums.first()
        val br = sums.last()
        val remaining = pts.filter { it !== tl && it !== br }
        if (remaining.size != 2) return null
        val (p1, p2) = remaining
        val (tr, bl) = if ((p1.x - p1.y) >= (p2.x - p2.y)) p1 to p2 else p2 to p1
        return Quad(tl, tr, br, bl)
    }

    private fun interiorAngleDeg(a: Point, b: Point, c: Point): Double {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 < 1e-6 || mag2 < 1e-6) return 0.0
        val cos = ((v1x * v2x + v1y * v2y) / (mag1 * mag2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
