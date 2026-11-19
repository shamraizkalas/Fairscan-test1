package org.fairscan.app.camscanner

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.data.ImageTransformations
import org.fairscan.app.data.Logger
import org.fairscan.app.domain.EncodedImage
import org.fairscan.app.domain.Jpeg
import org.fairscan.app.domain.PageMetadata
import org.fairscan.app.domain.PdfPageSize
import org.fairscan.app.domain.Rotation
import org.fairscan.app.library.LibraryDatabase
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedAssetType
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ImageRect
import org.fairscan.imageprocessing.OcrTextBox
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ProScanOfflineSuitesTest {

    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(context, db.libraryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun securityVaultManager_saltsHashesAndLocksCorrectly() {
        val context = RuntimeEnvironment.getApplication()
        val vault = SecurityVaultManager(context)
        vault.clearPin()
        assertFalse(vault.hasPin())
        assertFalse(vault.isAppLockEnabled())

        vault.setPin("2580")
        assertTrue(vault.hasPin())
        assertTrue(vault.verifyPin("2580"))
        assertFalse(vault.verifyPin("0000"))

        vault.setAppLockEnabled(true)
        assertTrue(vault.isAppLockEnabled())

        val hash1 = SecurityVaultManager.hashPin("2580", "saltA")
        val hash2 = SecurityVaultManager.hashPin("2580", "saltB")
        assertNotEquals(hash1, hash2)

        vault.clearPin()
        assertFalse(vault.hasPin())
        assertFalse(vault.isAppLockEnabled())
    }

    @Test
    fun trashLifecycle_softDeletesRestoresAndPurgesExpired30Days() = runTest {
        val bmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val doc1 = repository.saveBitmapsAsDocument("Recent Trash", listOf(bmp))!!
        val doc2 = repository.saveBitmapsAsDocument("Old Expired Trash", listOf(bmp))!!
        bmp.recycle()

        val now = 1_750_000_000_000L
        val thirtyTwoDaysAgo = now - 32 * LibraryRepository.MILLIS_PER_DAY
        val fiveDaysAgo = now - 5 * LibraryRepository.MILLIS_PER_DAY

        repository.moveToTrash(doc1, deletedAtMs = fiveDaysAgo)
        repository.moveToTrash(doc2, deletedAtMs = thirtyTwoDaysAgo)

        assertEquals(0, repository.documents.first().size)
        assertEquals(2, repository.trashDocuments.first().size)
        assertEquals(25, LibraryRepository.remainingTrashDays(fiveDaysAgo, now))

        val purged = repository.purgeExpiredTrash(retentionDays = 30, nowMs = now)
        assertEquals(1, purged)
        val remainingTrash = repository.trashDocuments.first()
        assertEquals(1, remainingTrash.size)
        assertEquals(doc1.id, remainingTrash.first().id)

        repository.restoreFromTrash(remainingTrash.first())
        assertEquals(0, repository.trashDocuments.first().size)
        assertEquals(1, repository.documents.first().size)
    }

    @Test
    fun offlineZipBackupAndRestore_roundTripsFoldersDocsAndSignatures() = runTest {
        val context = RuntimeEnvironment.getApplication()
        repository.createFolder("Bank Statements")
        val folder = repository.folders.first().first()
        repository.setFolderLocked(folder.id, true)

        val pageBmp = Bitmap.createBitmap(140, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val savedDoc = repository.saveBitmapsAsDocument(
            title = "Electricity Bill",
            bitmaps = listOf(pageBmp),
            folderId = folder.id,
            tagsCsv = "Bill,2026"
        )!!
        pageBmp.recycle()

        val sigBmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val savedSig = repository.saveSignature(sigBmp, "Office Stamp", SavedAssetType.STAMP)
        sigBmp.recycle()

        val baos = ByteArrayOutputStream()
        val exported = repository.exportFullBackupZip(baos)
        assertEquals(1, exported.folderCount)
        assertEquals(1, exported.documentCount)
        assertEquals(1, exported.signatureCount)

        // Wipe DB and disk files before restore
        repository.permanentlyDeleteDocument(savedDoc)
        repository.deleteSignature(savedSig)
        repository.deleteFolder(folder.id)
        assertEquals(0, repository.documents.first().size)
        assertEquals(0, repository.folders.first().size)
        assertEquals(0, repository.signatures.first().size)

        val freshRepo = LibraryRepository(context, db.libraryDao())
        val restored = freshRepo.restoreFromBackupZip(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, restored.folderCount)
        assertEquals(1, restored.documentCount)
        assertEquals(1, restored.signatureCount)

        val restoredFolders = freshRepo.folders.first()
        assertTrue(restoredFolders.single().isLocked)

        val restoredDocs = freshRepo.documents.first()
        assertEquals("Electricity Bill", restoredDocs.single().title)
        assertTrue(freshRepo.readDocumentPageFiles(restoredDocs.single().id).isNotEmpty())

        val restoredSigs = freshRepo.signatures.first()
        assertEquals("Office Stamp", restoredSigs.single().label)
        assertTrue(File(restoredSigs.single().imagePath).exists())
    }

    @Test
    fun qrGeneratorHelper_buildsValidPayloadsAndBitmap() {
        val wifi = QrGeneratorHelper.buildWifiPayload("Office;Net", "Pass:123", "WPA")
        assertEquals("WIFI:T:WPA;S:Office\\;Net;P:Pass\\:123;;", wifi)

        val vcard = QrGeneratorHelper.buildVCardPayload("Ali Khan", "+923001234567", "ali@example.com")
        assertTrue(vcard.contains("BEGIN:VCARD"))
        assertTrue(vcard.contains("FN:Ali Khan"))
        assertTrue(vcard.contains("TEL:+923001234567"))

        val qr = QrGeneratorHelper.generateQrBitmap(wifi, sizePx = 256)
        assertNotNull(qr)
        assertEquals(256, qr!!.width)
        assertEquals(256, qr.height)
        // Verify dark modules exist inside the QR matrix
        var hasDark = false
        for (x in 0 until 256) {
            if (qr.getPixel(x, 128) == Color.BLACK) {
                hasDark = true
                break
            }
        }
        assertTrue(hasDark)
        qr.recycle()
    }

    @Test
    fun layoutPrintEngine_stitchesLongImageAndBuildsCollageSheet() {
        val p1 = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val p2 = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val stitched = LayoutPrintEngine.stitchLongImage(listOf(p1, p2), addDividers = true, targetWidthPx = 600)
        assertEquals(600, stitched.width)
        assertEquals(900 + 900 + 12, stitched.height)
        stitched.recycle()

        val collage = LayoutPrintEngine.createCollageBitmap(listOf(p1, p2), CollageLayout.TWO_BY_TWO)
        assertEquals(1654, collage.width)
        assertEquals(2339, collage.height)
        collage.recycle()

        p1.recycle()
        p2.recycle()
    }

    @Test
    fun idPhotoEngine_generatesExactPresetDimensionsAndPrintableSheet() {
        val portrait = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val idPhoto = IdPhotoEngine.generateIdPhoto(
            source = portrait,
            preset = IdPhotoSizePreset.PASSPORT_35X45,
            background = IdPhotoBackground.ORIGINAL,
        )
        assertEquals(IdPhotoSizePreset.PASSPORT_35X45.outputWidthPx, idPhoto.width)
        assertEquals(IdPhotoSizePreset.PASSPORT_35X45.outputHeightPx, idPhoto.height)

        val sheet = IdPhotoEngine.createPrintableIdSheet(idPhoto, IdPrintSheetType.SHEET_A4, copies = 8)
        assertEquals(1654, sheet.width)
        assertEquals(2339, sheet.height)

        portrait.recycle()
        idPhoto.recycle()
        sheet.recycle()
    }

    @Test
    fun scanCleanupEngine_magicEraserAppliesWithoutCrashOnUnitTestFallback() {
        val page = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val cleaned = ScanCleanupEngine.applyMagicEraserBrush(
            source = page,
            strokes = listOf(EraserStroke(listOf(0.2f to 0.2f, 0.8f to 0.8f), 0.04f))
        )
        assertEquals(200, cleaned.width)
        assertEquals(200, cleaned.height)
        page.recycle()
        cleaned.recycle()
    }

    @Test
    fun pageCompositor_nonDestructiveLayersApplyAndRemoveCleanly() {
        val context = RuntimeEnvironment.getApplication()
        val cacheDir = File(context.cacheDir, "compositor_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val base = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val stamp = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val stampFile = File(cacheDir, "stamp.png")
            stampFile.outputStream().use { stamp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            stamp.recycle()

            val fullState = PageEditState(
                filterLayer = FilterLayer(
                    presetName = ProFilterPreset.ORIGINAL.name,
                    brightness = -30f,
                    contrast = 1.25f,
                ),
                overlays = listOf(
                    ImageOverlayLayer(
                        id = "ov1",
                        assetFileName = "stamp.png",
                        label = "Official Stamp",
                        isStamp = true,
                        normX = 0.5f,
                        normY = 0.5f,
                        scaleFactor = 0.4f,
                    )
                ),
                annotations = listOf(
                    SerializedAnnotation.BadgeStampLayer(
                        id = "ann1",
                        label = "APPROVED",
                        xNorm = 0.5f,
                        yNorm = 0.25f,
                        colorArgb = Color.BLUE,
                    )
                ),
                watermark = WatermarkLayer(text = "CONFIDENTIAL", opacity = 0.4f),
            )

            val edited = PageCompositor.compose(base, fullState) { File(cacheDir, it) }
            assertEquals(200, edited.width)
            assertEquals(300, edited.height)
            // Center pixel must be altered by the red stamp overlay
            assertNotEquals(Color.WHITE, edited.getPixel(100, 150))

            // Remove stamp & watermark & filter & annotations -> pixel-exact base restoration
            val restored = PageCompositor.compose(base, PageEditState()) { File(cacheDir, it) }
            assertEquals(Color.WHITE, restored.getPixel(100, 150))
            assertEquals(Color.WHITE, restored.getPixel(100, 75))

            base.recycle()
            edited.recycle()
            restored.recycle()
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun layerCoordinateMapper_roundTripsAcrossAllRotations() {
        val x = 0.23f
        val y = 0.71f
        Rotation.entries.forEach { rot ->
            val basePt = LayerCoordinateMapper.visualToBasePoint(x, y, rot)
            val visualPt = LayerCoordinateMapper.baseToVisualPoint(basePt.x, basePt.y, rot)
            assertEquals("Rotation $rot X mismatch", x, visualPt.x, 1e-4f)
            assertEquals("Rotation $rot Y mismatch", y, visualPt.y, 1e-4f)
        }
    }

    @Test
    fun pdfProjectCodec_embedsAndExtractsArchiveInPlainAndEncryptedPdfs() {
        val context = RuntimeEnvironment.getApplication()
        val workDir = File(context.cacheDir, "codec_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val manifest = EditablePdfProjectManifest(
                title = "Contract 2026",
                pages = listOf(
                    EditablePdfPageEntry(
                        id = "p1",
                        baseFileName = "p1.jpg",
                        editState = PageEditState(
                            watermark = WatermarkLayer("DRAFT"),
                            overlays = listOf(
                                ImageOverlayLayer(
                                    id = "sig1",
                                    assetFileName = "sig.png",
                                    label = "Signature",
                                    isStamp = false,
                                    normX = 0.7f,
                                    normY = 0.8f,
                                    scaleFactor = 0.3f,
                                )
                            ),
                        ),
                    )
                ),
            )
            val payloadBytes = Json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val srcZip = File(workDir, "project.zip").apply { writeBytes(payloadBytes) }

            // Plain PDF round-trip
            val plainPdf = File(workDir, "plain.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(plainPdf)
            }
            assertTrue(PdfProjectCodec.embedProjectArchive(plainPdf, srcZip, password = null))
            val extractedPlain = File(workDir, "extracted_plain.zip")
            assertTrue(PdfProjectCodec.extractProjectArchive(plainPdf, password = null, outZipFile = extractedPlain))
            assertTrue(payloadBytes.contentEquals(extractedPlain.readBytes()))

            // Encrypted PDF round-trip
            val protectedPdf = File(workDir, "protected.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(protectedPdf)
            }
            assertTrue(PdfProjectCodec.embedProjectArchive(protectedPdf, srcZip, password = "secret123"))
            val wrongPwOut = File(workDir, "wrong.zip")
            assertFalse(PdfProjectCodec.extractProjectArchive(protectedPdf, password = "badpw", outZipFile = wrongPwOut))
            val extractedEnc = File(workDir, "extracted_enc.zip")
            assertTrue(PdfProjectCodec.extractProjectArchive(protectedPdf, password = "secret123", outZipFile = extractedEnc))
            assertTrue(payloadBytes.contentEquals(extractedEnc.readBytes()))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun expandedFilters_coverAllCategoriesAnd7AdjustmentSlidersWorkNonDestructively() {
        // Verify 29 presets across all 4 specialized categories
        assertTrue(ProFilterPreset.entries.size >= 29)
        assertTrue(ProFilterPreset.forCategory(FilterCategory.DOCUMENT).size >= 7)
        assertTrue(ProFilterPreset.forCategory(FilterCategory.BLACK_AND_WHITE).size >= 7)
        assertTrue(ProFilterPreset.forCategory(FilterCategory.COLOR).size >= 6)
        assertTrue(ProFilterPreset.forCategory(FilterCategory.ARTISTIC).size >= 5)

        val src = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 120, 200))
        }

        // Verify all pure-Android tonal presets run without native JNI and visibly alter pixels
        val jvmPresets = listOf(
            ProFilterPreset.VIVID,
            ProFilterPreset.WARM_TONE,
            ProFilterPreset.COOL_BLUE,
            ProFilterPreset.EMERALD,
            ProFilterPreset.ROSE_PRINT,
            ProFilterPreset.SUNLIGHT,
            ProFilterPreset.DEEP_CONTRAST,
            ProFilterPreset.SEPIA,
            ProFilterPreset.VINTAGE,
            ProFilterPreset.NOIR,
            ProFilterPreset.DUOTONE_TEAL,
            ProFilterPreset.INVERT,
            ProFilterPreset.SILVER_GRAY,
            ProFilterPreset.NEWSPAPER,
            ProFilterPreset.WARM_PAPER,
        )
        val origPixel = src.getPixel(40, 40)
        jvmPresets.forEach { preset ->
            val out = ProFilterEngine.applyPreset(src, preset)
            assertEquals(80, out.width)
            assertEquals(80, out.height)
            assertNotEquals("Preset $preset should modify color", origPixel, out.getPixel(40, 40))
            out.recycle()
        }

        // Verify 7 adjustment sliders (Brightness, Contrast, Saturation, Warmth, Exposure, Fade)
        val adj = ImageAdjustments(
            brightness = 15f,
            contrast = 1.2f,
            sharpness = 0f,
            saturation = 0.4f,
            warmth = 30f,
            exposure = 0.5f,
            fade = 0.35f,
        )
        assertFalse(adj.isDefault)
        val tuned = ProFilterEngine.applyFilterAndAdjustments(src, ProFilterPreset.SEPIA, adj)
        assertNotEquals(origPixel, tuned.getPixel(40, 40))
        tuned.recycle()

        // Verify JSON serialization round-trip of expanded FilterLayer
        val layer = FilterLayer(
            presetName = ProFilterPreset.VINTAGE.name,
            brightness = 15f,
            contrast = 1.2f,
            sharpness = 0.3f,
            saturation = 0.4f,
            warmth = 30f,
            exposure = 0.5f,
            fade = 0.35f,
        )
        val decoded = Json.decodeFromString<FilterLayer>(Json.encodeToString(layer))
        assertEquals(layer, decoded)
        assertEquals(ProFilterPreset.VINTAGE, decoded.toPreset())
        assertFalse(decoded.isDefault)
        src.recycle()
    }

    @Test
    fun documentDataExtractor_parsesReceiptTotalsAndTableCsv() {
        val sampleReceipt = """
            KALAS OFFICE MART
            Invoice No: INV-2026-884
            Date: 2026-11-17
            A4 Copy Paper Ream    $12.50
            Black Ink Cartridge   $34.00
            Subtotal              $46.50
            Tax (8%)              $3.72
            Grand Total           $50.22
        """.trimIndent()

        val receipt = DocumentDataExtractor.extractReceiptData(sampleReceipt)
        assertTrue(receipt.hasAnyField)
        assertEquals("KALAS OFFICE MART", receipt.merchant)
        assertEquals("2026-11-17", receipt.date)
        assertEquals("INV-2026-884", receipt.invoiceNumber)
        assertEquals("$46.50", receipt.subtotal)
        assertEquals("$3.72", receipt.tax)
        assertEquals("$50.22", receipt.total)
        assertEquals(50.22, receipt.totalValue!!, 0.001)
        assertEquals(2, receipt.lineItems.size)
        assertEquals("A4 Copy Paper Ream", receipt.lineItems[0].description)

        val boxes = listOf(
            OcrTextBox("Item", ImageRect(20, 20, 90, 46), 26, 46),
            OcrTextBox("Qty", ImageRect(260, 20, 310, 46), 26, 46),
            OcrTextBox("Price", ImageRect(420, 20, 500, 46), 26, 46),
            OcrTextBox("Pen, Blue", ImageRect(20, 70, 150, 96), 26, 96),
            OcrTextBox("2", ImageRect(265, 70, 285, 96), 26, 96),
            OcrTextBox("$4.00", ImageRect(422, 70, 495, 96), 26, 96),
        )
        val table = DocumentDataExtractor.extractTableToCsv(boxes)
        assertEquals(3, table.columnCount)
        assertEquals(2, table.rows.size)
        assertEquals("Item,Qty,Price\n\"Pen, Blue\",2,$4.00", table.csv)
    }

    @Test
    fun libraryDatabase_migration3To4PreservesDocsAndSupportsOcrSearchSnippet() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = File(context.cacheDir, "migration_v3_v4_${System.currentTimeMillis()}.db")
        try {
            // Create a v3 schema database manually without ocrText column
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { rawDb ->
                rawDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        colorHex INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isLocked INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                rawDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_documents (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        folderId TEXT,
                        tagsCsv TEXT NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        isLocked INTEGER NOT NULL,
                        pageCount INTEGER NOT NULL,
                        coverPath TEXT,
                        storageDirName TEXT NOT NULL,
                        lastExportPath TEXT,
                        lastExportFormat TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deletedAt INTEGER
                    )
                    """.trimIndent()
                )
                rawDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_signatures (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        imagePath TEXT NOT NULL,
                        assetType TEXT NOT NULL DEFAULT 'SIGNATURE',
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                rawDb.execSQL(
                    """
                    INSERT INTO saved_documents
                    (id, title, folderId, tagsCsv, isFavorite, isLocked, pageCount, coverPath, storageDirName, lastExportPath, lastExportFormat, createdAt, updatedAt, deletedAt)
                    VALUES ('doc_v3', 'Legacy Contract', NULL, 'legal', 0, 0, 2, NULL, 'legacy_dir', NULL, 'PDF', 1000, 2000, NULL)
                    """.trimIndent()
                )
                rawDb.version = 3
            }

            val migratedDb = Room.databaseBuilder(context, LibraryDatabase::class.java, dbFile.absolutePath)
                .addMigrations(
                    LibraryDatabase.MIGRATION_1_2,
                    LibraryDatabase.MIGRATION_2_3,
                    LibraryDatabase.MIGRATION_3_4
                )
                .allowMainThreadQueries()
                .build()
            try {
                val repo = LibraryRepository(context, migratedDb.libraryDao())
                val docBefore = migratedDb.libraryDao().getDocumentById("doc_v3")
                assertNotNull(docBefore)
                assertEquals("", docBefore!!.ocrText)

                repo.updateDocumentOcrText("doc_v3", "Rental Agreement for Suite 402 signed November 2026")
                val docAfter = migratedDb.libraryDao().getDocumentById("doc_v3")!!
                assertTrue(docAfter.ocrText.contains("Suite 402"))

                val snippet = LibraryRepository.extractSearchSnippet(docAfter, "Suite 402")
                assertNotNull(snippet)
                assertTrue(snippet!!.contains("Suite 402"))
                assertEquals(null, LibraryRepository.extractSearchSnippet(docAfter, "NonexistentTerm"))
            } finally {
                migratedDb.close()
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun batchLayerOperations_applyFiltersWatermarksAndCleanupAcrossMultiplePages() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val scanRoot = File(context.cacheDir, "batch_repo_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val stubTransforms = object : ImageTransformations {
                override fun rotate(input: EncodedImage, rotationDegrees: Int): EncodedImage = input
                override fun resizeToThumbnail(input: EncodedImage): EncodedImage = input
                override fun process(source: Jpeg, metadata: PageMetadata, colorMode: ColorMode): EncodedImage = source
            }
            val logger = Logger { _, _, _ -> }
            val repo = ImageRepository(scanRoot, stubTransforms, scope, logger)

            val blueBmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val jpegBaos = ByteArrayOutputStream()
            blueBmp.compress(Bitmap.CompressFormat.JPEG, 95, jpegBaos)
            val jpegBytes = jpegBaos.toByteArray()
            blueBmp.recycle()

            val meta = PageMetadata(
                normalizedQuad = Quad(
                    Point(0.0, 0.0),
                    Point(1.0, 0.0),
                    Point(1.0, 1.0),
                    Point(0.0, 1.0),
                ),
                baseRotation = Rotation.R0,
                autoColorMode = ColorMode.COLOR,
                sourceSize = null,
                opticalMeasures = null,
            )
            repo.add(Jpeg(jpegBytes), Jpeg(jpegBytes), meta, ColorMode.COLOR)
            repo.add(Jpeg(jpegBytes), Jpeg(jpegBytes), meta, ColorMode.COLOR)

            val pages = repo.pages()
            assertEquals(2, pages.size)

            repo.applyFilterToPages(null, ProFilterPreset.SEPIA, ImageAdjustments(brightness = 10f))
            repo.applyWatermarkToPages(null, WatermarkLayer(text = "CONFIDENTIAL"))
            repo.applyCleanupToPages(null, CleanupEffectType.SHADOW_FLATTEN)

            val states = repo.pageEditStates.value
            pages.forEach { p ->
                val state = states[p.id]
                assertNotNull(state)
                assertEquals(ProFilterPreset.SEPIA.name, state!!.filterLayer?.presetName)
                assertEquals("CONFIDENTIAL", state.watermark?.text)
                assertTrue(state.cleanupEffects.any { it.type == CleanupEffectType.SHADOW_FLATTEN })

                val cleanBmp = repo.getCleanDisplayBitmap(p.id)
                assertNotNull(cleanBmp)
                assertEquals(80, cleanBmp!!.width)
                cleanBmp.recycle()
            }
        } finally {
            scanRoot.deleteRecursively()
        }
    }

    @Test
    fun offlinePromptEditEngine_parsesMultiClausePromptsAndModifiers() {
        val emptyPlan = OfflinePromptEditEngine.parse("   ")
        assertFalse(emptyPlan.hasChanges)
        assertNotNull(emptyPlan.unrecognizedHint)

        val richPlan = OfflinePromptEditEngine.parse(
            "Strong warm golden hour with dramatic portrait bokeh, color pop, and 2x upscale"
        )
        assertTrue(richPlan.hasChanges)
        assertEquals(AiArtisticStyle.WARM_GOLDEN, richPlan.artisticStyle)
        assertEquals(AiBackgroundEffect.PORTRAIT_BOKEH, richPlan.backgroundEffect)
        assertTrue(richPlan.upscale2x)
        assertTrue(richPlan.saturationMultiplier > 1.2f)
        assertTrue(richPlan.blurIntensity >= 0.85f)
        assertTrue(richPlan.appliedBadges.containsAll(listOf("Portrait Bokeh", "Golden Hour", "Color Pop", "2x Upscale")))

        val cutoutPlan = OfflinePromptEditEngine.parse("Remove background cutout png dark fix shadow")
        assertEquals(AiBackgroundEffect.TRANSPARENT_CUTOUT, cutoutPlan.backgroundEffect)
        assertEquals(AiEnhancePreset.SHADOW_RESCUE, cutoutPlan.autoEnhancePreset)
        assertTrue(cutoutPlan.appliedBadges.contains("Subject Cutout"))
        assertTrue(cutoutPlan.appliedBadges.contains("Shadow Rescue"))

        val unknownPlan = OfflinePromptEditEngine.parse("xyzzy completely unrelated words")
        assertFalse(unknownPlan.hasChanges)
        assertNotNull(unknownPlan.unrecognizedHint)
    }

    @Test
    fun offlineAiPhotoEngine_autoEnhanceCutoutInpaintAndUpscaleWorkOffline() {
        val src = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 110, 150))
        }

        // 1. Working bitmap normalization & resizing cap
        val work = OfflineAiPhotoEngine.createWorkingBitmap(src, maxSidePx = 80)
        assertEquals(80, work.width)
        assertEquals(67, work.height)

        // 2. Auto-Enhance & Lighting Fix
        val enhanced = OfflineAiPhotoEngine.autoEnhanceAndFixLight(
            source = src,
            strength = 0.85f,
            preset = AiEnhancePreset.SHADOW_RESCUE,
        )
        assertEquals(120, enhanced.width)
        assertEquals(100, enhanced.height)
        assertNotEquals(src.getPixel(60, 50), enhanced.getPixel(60, 50))

        // 3. Foreground Mask + Transparent Cutout + Bokeh + Studio White
        val mask = OfflineAiPhotoEngine.extractForegroundMask(src)
        assertEquals(120, mask.width)
        assertEquals(100, mask.height)

        val cutout = OfflineAiPhotoEngine.applyBackgroundEffect(src, mask, AiBackgroundEffect.TRANSPARENT_CUTOUT)
        assertTrue(Color.alpha(cutout.getPixel(60, 50)) > 200)
        assertEquals(0, Color.alpha(cutout.getPixel(0, 0)))

        val whiteStudio = OfflineAiPhotoEngine.applyBackgroundEffect(src, mask, AiBackgroundEffect.STUDIO_WHITE)
        assertEquals(Color.WHITE, whiteStudio.getPixel(0, 0))

        val bokeh = OfflineAiPhotoEngine.applyBackgroundEffect(src, mask, AiBackgroundEffect.PORTRAIT_BOKEH, 0.8f)
        assertEquals(120, bokeh.width)
        assertEquals(100, bokeh.height)

        val bwBg = OfflineAiPhotoEngine.applyBackgroundEffect(src, mask, AiBackgroundEffect.GRAYSCALE_BG)
        assertEquals(120, bwBg.width)
        bwBg.recycle()

        // 4. Object removal inpainting
        val inpainted = OfflineAiPhotoEngine.removeObjectsWithInpaint(
            src,
            listOf(EraserStroke(listOf(0.2f to 0.2f, 0.7f to 0.7f), 0.04f)),
        )
        assertEquals(120, inpainted.width)
        assertEquals(100, inpainted.height)

        // 5. Sharpness + 2x Super-Resolution Upscale
        val upscaled = OfflineAiPhotoEngine.sharpenAndUpscale(src, sharpenStrength = 0.6f, upscale2x = true)
        assertEquals(240, upscaled.width)
        assertEquals(200, upscaled.height)

        // 6. Full prompt pipeline
        val plan = OfflinePromptEditEngine.parse("Vintage film portrait bokeh crisp")
        val (promptOut, returnedMask) = OfflineAiPhotoEngine.executePromptPlan(src, plan, cachedMask = null)
        assertEquals(120, promptOut.width)
        assertEquals(100, promptOut.height)
        assertNotNull(returnedMask)

        src.recycle()
        work.recycle()
        enhanced.recycle()
        mask.recycle()
        cutout.recycle()
        whiteStudio.recycle()
        bokeh.recycle()
        inpainted.recycle()
        upscaled.recycle()
        promptOut.recycle()
        returnedMask?.recycle()
    }

    @Test
    fun docxOoxmlCodec_roundTripsRealDocxAndLegacyDoc() {
        val context = RuntimeEnvironment.getApplication()
        val workDir = File(context.cacheDir, "conv_docx_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val previewBmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val jpegBaos = ByteArrayOutputStream()
            previewBmp.compress(Bitmap.CompressFormat.JPEG, 85, jpegBaos)
            previewBmp.recycle()

            val pages = listOf(
                DocxPageContent(
                    pageNumber = 1,
                    text = "Quarterly Financial Report\nRevenue rose 24% offline.",
                    previewJpegBytes = jpegBaos.toByteArray(),
                    previewWidthPx = 120,
                    previewHeightPx = 90,
                ),
                DocxPageContent(
                    pageNumber = 2,
                    text = "Appendix Notes\nVerified in ProScan Studio.",
                ),
            )
            val docxFile = File(workDir, "quarterly.docx")
            DocxOoxmlCodec.createDocxFromPages(
                title = "Quarterly Report",
                pages = pages,
                outFile = docxFile,
                includePagePreviews = true,
            )
            assertTrue(docxFile.exists() && docxFile.length() > 500)

            val entries = mutableSetOf<String>()
            ZipInputStream(docxFile.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    entries.add(e.name)
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertTrue(entries.contains("[Content_Types].xml"))
            assertTrue(entries.contains("_rels/.rels"))
            assertTrue(entries.contains("word/document.xml"))
            assertTrue(entries.contains("word/_rels/document.xml.rels"))
            assertTrue(entries.contains("word/media/page_1.jpg"))

            val parsed = DocxOoxmlCodec.parseWordFile(docxFile, "Quarterly Report")
            val combinedText = parsed.blocks.joinToString("\n") { block ->
                when (block) {
                    is ParsedDocBlock.Heading -> block.text
                    is ParsedDocBlock.Paragraph -> block.plainText
                    else -> ""
                }
            }
            assertTrue(combinedText.contains("Quarterly Financial Report"))
            assertTrue(combinedText.contains("Appendix Notes"))
            assertTrue(parsed.blocks.any { it is ParsedDocBlock.EmbeddedImage })

            val pdfOutDir = File(workDir, "pdf_out")
            val pdfSummary = UniversalConverterEngine.convertWordToPdf(
                wordFile = docxFile,
                baseName = "quarterly_export",
                outputDir = pdfOutDir,
                options = ConverterOptions(pdfPageSize = PdfPageSize.A4),
            )
            assertEquals(1, pdfSummary.files.size)
            val outPdf = pdfSummary.files.first()
            assertTrue(outPdf.exists())
            PDDocument.load(outPdf).use { doc ->
                assertTrue(doc.numberOfPages >= 2)
                val extracted = PDFTextStripper().getText(doc)
                assertTrue(extracted.contains("Quarterly Financial Report"))
                assertTrue(extracted.contains("Verified in ProScan Studio"))
            }

            // Legacy HTML .doc round-trip
            val legacyDoc = File(workDir, "legacy_notes.doc")
            DocxOoxmlCodec.createLegacyDocHtml("Legacy Notes", pages, legacyDoc)
            assertTrue(legacyDoc.exists())
            val legacyPdfSummary = UniversalConverterEngine.convertWordToPdf(
                wordFile = legacyDoc,
                baseName = "legacy_notes",
                outputDir = pdfOutDir,
                options = ConverterOptions(),
            )
            assertTrue(legacyPdfSummary.files.first().exists())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun universalConverterEngine_convertsMarkdownCsvJsonCodeImagesAndTxtOffline() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val workDir = File(context.cacheDir, "conv_suite_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1. Markdown -> PDF -> TXT & HTML
            val mdFile = File(workDir, "notes.md").apply {
                writeText("# Project Plan\n## Milestones\n- Offline DOCX ready\n- Offline OCR enabled", Charsets.UTF_8)
            }
            val mdPdf = UniversalConverterEngine.convertStructuredTextOrCodeToPdf(
                inputFile = mdFile,
                baseName = "project_plan",
                outputDir = File(workDir, "md_pdf"),
                tool = ConverterTool.TEXT_DOC_TO_PDF,
                options = ConverterOptions(),
            ).files.first()
            assertTrue(mdPdf.exists())

            val txtSummary = UniversalConverterEngine.convertPdfToTextOrHtml(
                pdfFile = mdPdf,
                baseName = "project_plan",
                outputDir = File(workDir, "pdf_txt"),
                asHtml = false,
                options = ConverterOptions(useOcrFallback = false),
                ocrService = null,
            )
            val txtContent = txtSummary.files.first().readText(Charsets.UTF_8)
            assertTrue(txtContent.contains("Project Plan"))
            assertTrue(txtContent.contains("Offline DOCX ready"))

            val htmlSummary = UniversalConverterEngine.convertPdfToTextOrHtml(
                pdfFile = mdPdf,
                baseName = "project_plan",
                outputDir = File(workDir, "pdf_html"),
                asHtml = true,
                options = ConverterOptions(useOcrFallback = false),
                ocrService = null,
            )
            val htmlContent = htmlSummary.files.first().readText(Charsets.UTF_8)
            assertTrue(htmlContent.contains("<html"))
            assertTrue(htmlContent.contains("Offline OCR enabled"))

            // 2. CSV -> PDF
            val csvFile = File(workDir, "expenses.csv").apply {
                writeText("Item,Qty,Price\nScanner,1,\"120.00\"\nPaper,5,25.50", Charsets.UTF_8)
            }
            val csvPdf = UniversalConverterEngine.convertStructuredTextOrCodeToPdf(
                inputFile = csvFile,
                baseName = "expenses",
                outputDir = File(workDir, "csv_pdf"),
                tool = ConverterTool.TEXT_DOC_TO_PDF,
                options = ConverterOptions(),
            ).files.first()
            PDDocument.load(csvPdf).use { doc ->
                val text = PDFTextStripper().getText(doc)
                assertTrue(text.contains("Scanner"))
                assertTrue(text.contains("120.00"))
            }

            // 3. JSON Code -> PDF with line numbers
            val jsonFile = File(workDir, "config.json").apply {
                writeText("""{"app":"ProScan","offline":true}""", Charsets.UTF_8)
            }
            val jsonPdf = UniversalConverterEngine.convertStructuredTextOrCodeToPdf(
                inputFile = jsonFile,
                baseName = "config",
                outputDir = File(workDir, "json_pdf"),
                tool = ConverterTool.CODE_TO_PDF,
                options = ConverterOptions(includeLineNumbers = true),
            ).files.first()
            PDDocument.load(jsonPdf).use { doc ->
                val text = PDFTextStripper().getText(doc)
                assertTrue(text.contains("ProScan"))
                assertTrue(text.contains("1 |"))
            }

            // 4. Image -> PDF & Batch Image Format Conversion
            val img1 = File(workDir, "sample1.png")
            val img2 = File(workDir, "sample2.png")
            listOf(img1 to Color.RED, img2 to Color.BLUE).forEach { (f, color) ->
                val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }

            val imgPdfSummary = UniversalConverterEngine.convertImagesToPdf(
                imageFiles = listOf(img1, img2),
                baseName = "photos_bundle",
                outputDir = File(workDir, "img_pdf"),
                options = ConverterOptions(pdfPageSize = PdfPageSize.AUTO_FIT),
            )
            PDDocument.load(imgPdfSummary.files.first()).use { doc ->
                assertEquals(2, doc.numberOfPages)
            }

            val batchSummary = UniversalConverterEngine.convertBatchImagesFormat(
                imageFiles = listOf(img1, img2),
                outputDir = File(workDir, "batch_jpg"),
                options = ConverterOptions(
                    imageFormat = TargetImageFormat.JPEG,
                    imageQuality = 85,
                    resizeCap = ImageResizeCap.PX_1280,
                ),
            )
            assertEquals(2, batchSummary.files.size)
            assertTrue(batchSummary.files.all { it.extension == "jpg" && it.exists() })
            assertNotNull(batchSummary.zipArchive)
            assertTrue(batchSummary.zipArchive!!.exists())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
