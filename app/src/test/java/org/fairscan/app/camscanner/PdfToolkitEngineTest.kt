package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.fairscan.app.domain.PdfCompression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PdfToolkitEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    private fun createBlankPdf(name: String, pageCount: Int, password: String? = null): File {
        val file = File(tempFolder.root, name)
        PDDocument().use { doc ->
            repeat(pageCount) {
                doc.addPage(PDPage(PDRectangle.A4))
            }
            if (!password.isNullOrEmpty()) {
                val spp = StandardProtectionPolicy(password, password, AccessPermission())
                spp.encryptionKeyLength = 128
                doc.protect(spp)
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun parsePageRangeSpec_parsesRangesSinglesAndReversedOrder() {
        assertEquals(listOf(0, 1, 2, 4), PdfToolkitEngine.parsePageRangeSpec("1-3, 5", 8))
        assertEquals(listOf(3, 2, 1), PdfToolkitEngine.parsePageRangeSpec("4-2", 5))
        assertEquals(listOf(0, 4), PdfToolkitEngine.parsePageRangeSpec("1, 99, 5, 1", 5))
        assertEquals(emptyList<Int>(), PdfToolkitEngine.parsePageRangeSpec("", 5))
    }

    @Test
    fun mergePdfSources_combinesMultiplePdfsInOrder() {
        val pdfA = createBlankPdf("a.pdf", 2)
        val pdfB = createBlankPdf("b.pdf", 3)
        val out = File(tempFolder.root, "merged.pdf")
        val scratch = tempFolder.newFolder("scratch_merge")

        val res = PdfToolkitEngine.mergePdfSources(
            sources = listOf(
                MergeSourceSpec.ExternalPdf(pdfA, null, "A"),
                MergeSourceSpec.ExternalPdf(pdfB, null, "B"),
            ),
            outputPdf = out,
            tempCacheDir = scratch,
        )

        assertEquals(5, res.pageCount)
        assertTrue(out.exists() && res.sizeBytes > 0)
        val inspected = PdfToolkitEngine.inspectPdf(out)
        assertTrue(inspected is PdfInspectResult.Ready)
        assertEquals(5, (inspected as PdfInspectResult.Ready).pageCount)
    }

    @Test
    fun extractOrOrganizePdf_reordersAndRotatesPages() {
        val src = createBlankPdf("src.pdf", 4)
        val out = File(tempFolder.root, "organized.pdf")

        val res = PdfToolkitEngine.extractOrOrganizePdf(
            sourceFile = src,
            password = null,
            pageEntries = listOf(
                PageTransformEntry(zeroBasedIndex = 3, extraRotationDegrees = 90),
                PageTransformEntry(zeroBasedIndex = 0, extraRotationDegrees = 180),
            ),
            outputPdf = out,
        )

        assertEquals(2, res.pageCount)
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals(180, doc.getPage(1).rotation)
        }
    }

    @Test
    fun splitPdfToSinglePages_createsSeparateFiles() {
        val src = createBlankPdf("split_src.pdf", 3)
        val splitDir = tempFolder.newFolder("split_out")

        val files = PdfToolkitEngine.splitPdfToSinglePages(
            sourceFile = src,
            password = null,
            selectedZeroBasedIndices = listOf(0, 2),
            outputDir = splitDir,
            baseName = "Report",
        )

        assertEquals(2, files.size)
        assertEquals(1, files[0].pageCount)
        assertEquals(1, files[1].pageCount)
        assertTrue(files[0].file.name.contains("Report_page_1"))
        assertTrue(files[1].file.name.contains("Report_page_3"))
    }

    @Test
    fun unlockPdf_verifiesWrongPasswordAndStripsSecurityOnSuccess() {
        val locked = createBlankPdf("locked.pdf", 2, password = "secret123")
        val unlocked = File(tempFolder.root, "unlocked.pdf")

        assertTrue(PdfToolkitEngine.inspectPdf(locked) is PdfInspectResult.PasswordRequired)
        assertEquals(
            PdfUnlockResult.WrongPassword,
            PdfToolkitEngine.unlockPdf(locked, "wrong", unlocked)
        )

        val success = PdfToolkitEngine.unlockPdf(locked, "secret123", unlocked)
        assertTrue(success is PdfUnlockResult.Success)
        assertEquals(2, (success as PdfUnlockResult.Success).pageCount)

        val checkUnlocked = PdfToolkitEngine.inspectPdf(unlocked)
        assertTrue(checkUnlocked is PdfInspectResult.Ready)
        assertFalse((checkUnlocked as PdfInspectResult.Ready).isEncrypted)
        assertEquals(2, checkUnlocked.pageCount)
    }

    @Test
    fun buildPdfFromImageFiles_writesValidPdfFromBitmaps() {
        val imgFile = File(tempFolder.root, "page1.jpg")
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.LTGRAY)
        FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val out = File(tempFolder.root, "from_images.pdf")
        val res = PdfToolkitEngine.buildPdfFromImageFiles(
            imageFiles = listOf(imgFile to 90),
            outputPdf = out,
            compression = PdfCompression.COMPACT,
        )

        assertEquals(1, res.pageCount)
        assertTrue(out.length() > 0L)
        val inspected = PdfToolkitEngine.inspectPdf(out)
        assertTrue(inspected is PdfInspectResult.Ready)
    }
}
