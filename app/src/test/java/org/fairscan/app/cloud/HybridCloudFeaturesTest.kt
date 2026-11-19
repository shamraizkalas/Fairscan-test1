package org.fairscan.app.cloud

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.fairscan.app.camscanner.DocxBlock
import org.fairscan.app.camscanner.DocxOoxmlCodec
import org.fairscan.app.camscanner.DocxParagraphStyle
import org.fairscan.app.camscanner.ParsedDocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.FloatBuffer

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class HybridCloudFeaturesTest {

    @Test
    fun hybridModeController_transitionsAcrossFreeMlKitDriveAndByokStates() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dsFile = File(context.cacheDir, "hybrid_test_${System.currentTimeMillis()}.preferences_pb")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { dsFile }
            )
            val controller = HybridModeController(
                context = context,
                dataStore = dataStore,
                scope = scope,
                enableSystemNetworkCallback = false,
            )

            // Default AUTO_HYBRID with no network -> Free • Offline
            controller.setNetworkAvailableForTest(false)
            val s1 = controller.state.first()
            assertEquals(HybridModePreference.AUTO_HYBRID, s1.preference)
            assertFalse(s1.isOnlineActive)
            assertEquals("Free • Offline", s1.shortBadgeLabel)

            // Network comes online without BYOK -> ML Kit • Free
            controller.setNetworkAvailableForTest(true)
            val s2 = controller.state.first { it.hasNetwork }
            assertTrue(s2.isOnlineActive)
            assertFalse(s2.isByokOnlineReady)
            assertEquals("ML Kit • Free", s2.shortBadgeLabel)

            // Adding optional personal BYOK key when online -> ML Kit + BYOK
            controller.setPersonalGeminiApiKey("AIzaTestPersonalKey123")
            val sByok = controller.state.first { it.hasPersonalByokKey }
            assertTrue(sByok.isByokOnlineReady)
            assertEquals("ML Kit + BYOK", sByok.shortBadgeLabel)

            // Explicit OFFLINE_ONLY -> stays offline even with network and BYOK key
            controller.setPreference(HybridModePreference.OFFLINE_ONLY)
            val s3 = controller.state.first { it.preference == HybridModePreference.OFFLINE_ONLY }
            assertFalse(s3.isOnlineActive)
            assertFalse(s3.isByokOnlineReady)
            assertEquals("Offline", s3.shortBadgeLabel)

            // Explicit ONLINE_PREFERRED without network -> shows No Network badge
            controller.setPreference(HybridModePreference.ONLINE_PREFERRED)
            controller.setNetworkAvailableForTest(false)
            val s4 = controller.state.first {
                it.preference == HybridModePreference.ONLINE_PREFERRED && !it.hasNetwork
            }
            assertFalse(s4.isOnlineActive)
            assertTrue(s4.isOnlineRequestedButDisconnected)
            assertEquals("No Network", s4.shortBadgeLabel)

            // ML Kit segmentation & smart Word conversion toggles
            controller.setAiStructuredConversion(false)
            controller.setUseMlKitSegmentation(false)
            val s5 = controller.state.first { !it.aiStructuredConversion && !it.useMlKitSegmentation }
            assertFalse(s5.aiStructuredConversion)
            assertFalse(s5.useMlKitSegmentation)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun freeMlKitEngine_summarizesInspectsQrAndMapsLanguagesLocally() {
        val sampleText = """
            Al-Noor Book Depot Invoice #INV-902
            Date: 2026-11-18
            Subtotal is PKR 2,300 for notebooks and stationery supplies.
            Total Amount Paid: PKR 2,450
            Thank you for shopping with us today.
        """.trimIndent()
        val summary = FreeMlKitEngine.summarizeTextLocally(sampleText)
        assertTrue(summary.contains("INV-902") || summary.contains("2,450"))

        val httpsReport = FreeMlKitEngine.analyzeQrPayloadLocally("https://example.com/verify?id=42")
        assertTrue(httpsReport.contains("Encrypted HTTPS"))
        assertTrue(httpsReport.contains("example.com"))

        val wifiReport = FreeMlKitEngine.analyzeQrPayloadLocally("WIFI:T:WPA;S:OfficeNet;P:secret;;")
        assertTrue(wifiReport.contains("OfficeNet"))

        assertEquals("ur", FreeMlKitEngine.mapDisplayLanguageToCode("Urdu"))
        assertEquals("hi", FreeMlKitEngine.mapDisplayLanguageToCode("Hindi"))
        assertEquals("de", FreeMlKitEngine.mapDisplayLanguageToCode("German"))

        val buf = FloatBuffer.wrap(floatArrayOf(0.05f, 0.90f, 0.45f, 0.80f))
        val mask = FreeMlKitEngine.confidenceBufferToAlphaMask(buf, 2, 2)
        assertNotNull(mask)
        assertEquals(0, android.graphics.Color.alpha(mask!!.getPixel(0, 0)))
        assertEquals(255, android.graphics.Color.alpha(mask.getPixel(1, 0)))
        mask.recycle()
        assertNull(FreeMlKitEngine.confidenceBufferToAlphaMask(FloatBuffer.wrap(floatArrayOf(0.5f)), 2, 2))
    }

    @Test
    fun cloudAiGateway_classifiesPlainTextAndPolishesLineWrapsOffline() {
        val gateway = CloudAiGateway()
        val messy = "INVOICE SUMMARY\n\nThis is a wrap-\nped paragraph from\nscanned OCR text.\n\n- Item A\n- Item B\n\nRegion   Qty   Revenue\nLahore   120   4800"
        val polished = gateway.polishDocumentTextLocally(messy)
        assertTrue(polished.contains("wrapped paragraph from scanned OCR text."))
        assertTrue(polished.contains("• Item A"))

        val blocks = gateway.classifyPlainTextToDocxBlocks(polished)
        assertTrue(blocks.any { it is DocxBlock.Paragraph && it.style == DocxParagraphStyle.HEADING_2 })
        assertTrue(blocks.any { it is DocxBlock.Paragraph && it.style == DocxParagraphStyle.BULLET })
        assertTrue(blocks.any { it is DocxBlock.Table && it.rows.size >= 2 })

        assertEquals("doc_abc-123", DriveIdHelper.sanitizeFileName("doc_abc-123"))
        assertEquals("folder_invalid_id_", DriveIdHelper.sanitizeFileName("folder/invalid id!"))
        assertEquals("ProScanBackup", DriveIdHelper.sanitizeFileName("   "))
    }

    @Test
    fun docxOoxmlCodec_createDocxFromBlocksBuildsHeadingsBulletsAndTable() {
        val context = RuntimeEnvironment.getApplication()
        val workDir = File(context.cacheDir, "ai_blocks_docx_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val blocks = listOf(
                DocxBlock.Paragraph("Quarterly Sales Brief", DocxParagraphStyle.HEADING_1),
                DocxBlock.Paragraph("Regional Highlights", DocxParagraphStyle.HEADING_2),
                DocxBlock.Paragraph("Revenue grew 18% YoY", DocxParagraphStyle.BULLET),
                DocxBlock.Paragraph("Offline fallback remained active", DocxParagraphStyle.NORMAL),
                DocxBlock.Table(
                    listOf(
                        listOf("Region", "Units", "Revenue"),
                        listOf("Lahore", "120", "$4,800"),
                    )
                ),
            )
            val docxFile = File(workDir, "ai_structured.docx")
            DocxOoxmlCodec.createDocxFromBlocks("Quarterly Sales Brief", blocks, docxFile)
            assertTrue(docxFile.exists() && docxFile.length() > 500)

            val parsed = DocxOoxmlCodec.parseWordFile(docxFile, "Quarterly Sales Brief")
            assertTrue(parsed.blocks.any { it is ParsedDocBlock.Heading && it.text.contains("Quarterly Sales Brief") })
            assertTrue(parsed.blocks.any { it is ParsedDocBlock.Table && it.rows.size >= 2 })

            val plain = DocxOoxmlCodec.renderDocxBlocksToPlainText(blocks)
            assertTrue(plain.contains("QUARTERLY SALES BRIEF"))
            assertTrue(plain.contains("• Revenue grew 18% YoY"))
            assertTrue(plain.contains("Lahore | 120 | $4,800"))

            val html = DocxOoxmlCodec.renderDocxBlocksToHtml("Quarterly Sales Brief", blocks)
            assertTrue(html.contains("<table>"))
            assertTrue(html.contains("<li>Revenue grew 18% YoY</li>"))
        } finally {
            workDir.deleteRecursively()
        }
    }
}
