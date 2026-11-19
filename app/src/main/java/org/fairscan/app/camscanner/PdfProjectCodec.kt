package org.fairscan.app.camscanner

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.GregorianCalendar

/**
 * Embeds and extracts the non-destructive layered ProScan project archive inside standard PDF files.
 * External PDF viewers render pages normally and ignore the embedded attachment, while ProScan Studio
 * detects it upon re-import to restore every stamp, signature, annotation, filter, watermark, and eraser edit.
 */
object PdfProjectCodec {

    const val EMBEDDED_ARCHIVE_NAME = "proscan_layers.zip"

    fun embedProjectArchive(
        pdfFile: File,
        projectZipFile: File,
        password: String? = null,
    ): Boolean {
        if (!pdfFile.exists() || !projectZipFile.exists() || projectZipFile.length() == 0L) return false
        val zipBytes = projectZipFile.readBytes()
        val tempOut = File(pdfFile.parentFile, "tmp_embed_${System.currentTimeMillis()}.pdf")
        return try {
            val doc = if (password.isNullOrEmpty()) {
                PDDocument.load(pdfFile)
            } else {
                PDDocument.load(pdfFile, password)
            }
            doc.use { pdDoc ->
                val efTree = pdDoc.documentCatalog.names?.embeddedFiles ?: PDEmbeddedFilesNameTreeNode()
                val existing = efTree.names?.toMutableMap() ?: LinkedHashMap()

                val fileSpec = PDComplexFileSpecification().apply {
                    file = EMBEDDED_ARCHIVE_NAME
                }
                val embeddedFile = PDEmbeddedFile(pdDoc, ByteArrayInputStream(zipBytes)).apply {
                    subtype = "application/zip"
                    size = zipBytes.size
                    creationDate = GregorianCalendar()
                    modDate = GregorianCalendar()
                }
                fileSpec.embeddedFile = embeddedFile
                existing[EMBEDDED_ARCHIVE_NAME] = fileSpec
                efTree.names = existing

                val namesDict = pdDoc.documentCatalog.names ?: PDDocumentNameDictionary(pdDoc.documentCatalog)
                namesDict.embeddedFiles = efTree
                pdDoc.documentCatalog.names = namesDict

                if (!password.isNullOrEmpty()) {
                    val ap = AccessPermission()
                    val policy = StandardProtectionPolicy(password, password, ap).apply {
                        encryptionKeyLength = 128
                    }
                    pdDoc.protect(policy)
                }

                pdDoc.save(tempOut)
            }
            if (tempOut.exists() && tempOut.length() > 0L) {
                tempOut.copyTo(pdfFile, overwrite = true)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            tempOut.delete()
        }
    }

    fun extractProjectArchive(
        pdfFile: File,
        password: String? = null,
        outZipFile: File,
    ): Boolean {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return false
        return try {
            val doc = if (password.isNullOrEmpty()) {
                PDDocument.load(pdfFile)
            } else {
                PDDocument.load(pdfFile, password)
            }
            doc.use { pdDoc ->
                val efTree = pdDoc.documentCatalog.names?.embeddedFiles ?: return false
                val spec = findSpec(efTree) ?: return false
                val embedded = spec.embeddedFile ?: return false
                outZipFile.parentFile?.mkdirs()
                embedded.createInputStream().use { input ->
                    FileOutputStream(outZipFile).use { output ->
                        input.copyTo(output)
                    }
                }
                outZipFile.exists() && outZipFile.length() > 0L
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun findSpec(node: PDNameTreeNode<PDComplexFileSpecification>): PDComplexFileSpecification? {
        val map = node.names
        if (map != null && map.containsKey(EMBEDDED_ARCHIVE_NAME)) {
            return map[EMBEDDED_ARCHIVE_NAME]
        }
        val kids = node.kids ?: return null
        for (kid in kids) {
            val found = findSpec(kid)
            if (found != null) return found
        }
        return null
    }
}
