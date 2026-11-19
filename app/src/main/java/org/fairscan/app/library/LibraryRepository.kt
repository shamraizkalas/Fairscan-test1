package org.fairscan.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fairscan.app.data.PROCESSED_DIR_NAME
import org.fairscan.app.data.SOURCE_DIR_NAME
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<FolderEntity> = emptyList(),
    val documents: List<SavedDocumentEntity> = emptyList(),
    val signatures: List<SavedSignatureEntity> = emptyList(),
)

data class BackupStats(
    val folderCount: Int,
    val documentCount: Int,
    val signatureCount: Int,
)

class LibraryRepository(
    context: Context,
    private val dao: LibraryDao,
) {
    private val appContext = context.applicationContext
    private val docsRoot = File(appContext.filesDir, "library_docs").apply { mkdirs() }
    private val signaturesRoot = File(appContext.filesDir, "saved_signatures").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("fairscan_library_prefs", Context.MODE_PRIVATE)
    private val backupJson = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

    var onLibraryMutatedListener: (() -> Unit)? = null
    var onDocumentPermanentlyDeletedListener: ((String) -> Unit)? = null

    private fun notifyMutated() {
        onLibraryMutatedListener?.invoke()
    }

    val folders: Flow<List<FolderEntity>> = dao.observeFolders()
    val documents: Flow<List<SavedDocumentEntity>> = dao.observeActiveDocuments()
    val trashDocuments: Flow<List<SavedDocumentEntity>> = dao.observeTrashDocuments()
    val signatures: Flow<List<SavedSignatureEntity>> = dao.observeSignatures()

    suspend fun getAllFoldersSnapshot(): List<FolderEntity> = withContext(Dispatchers.IO) {
        dao.getAllFoldersSync()
    }

    suspend fun getAllDocumentsIncludingTrashSnapshot(): List<SavedDocumentEntity> =
        withContext(Dispatchers.IO) {
            dao.getAllActiveDocumentsSync() + dao.getAllTrashDocumentsSync()
        }

    suspend fun upsertSyncedFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        dao.upsertFolder(folder)
    }

    suspend fun upsertSyncedDocument(document: SavedDocumentEntity) = withContext(Dispatchers.IO) {
        dao.upsertDocument(document)
    }

    suspend fun ensureDefaultFoldersIfFirstLaunch() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("seeded_default_folders", false)) return@withContext
        val defaults = listOf(
            FolderEntity("folder_work", "Office & Bills", 0xFF00897B),
            FolderEntity("folder_ids", "IDs & Cards", 0xFF0D47A1),
            FolderEntity("folder_notes", "Books & Notes", 0xFF6A1B9A),
        )
        defaults.forEach { dao.upsertFolder(it) }
        prefs.edit().putBoolean("seeded_default_folders", true).apply()
    }

    suspend fun createFolder(name: String, colorHex: Long = 0xFF00897B) = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext
        dao.upsertFolder(
            FolderEntity(
                id = "fld_${UUID.randomUUID().toString().take(8)}",
                name = clean,
                colorHex = colorHex,
                createdAt = System.currentTimeMillis()
            )
        )
        notifyMutated()
    }

    suspend fun renameFolder(folderId: String, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            dao.renameFolder(folderId, clean)
            notifyMutated()
        }
    }

    suspend fun setFolderLocked(folderId: String, locked: Boolean) = withContext(Dispatchers.IO) {
        dao.setFolderLocked(folderId, locked)
        notifyMutated()
    }

    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        dao.clearFolderFromDocuments(folderId)
        dao.deleteFolder(folderId)
        notifyMutated()
    }

    suspend fun saveWorkingSessionAsDocument(
        sessionRootDir: File,
        title: String,
        folderId: String? = null,
        tagsCsv: String = "",
        existingDocId: String? = null,
        isLocked: Boolean = false,
        lastExportPath: String? = null,
        lastExportFormat: String = "PDF",
        coverBitmap: Bitmap? = null,
        pageCount: Int,
    ): SavedDocumentEntity = withContext(Dispatchers.IO) {
        val docId = existingDocId ?: "doc_${System.currentTimeMillis()}"
        val existing = if (existingDocId != null) dao.getDocumentById(existingDocId) else null
        val dirName = existing?.storageDirName ?: docId
        val targetDocDir = File(docsRoot, dirName).apply { mkdirs() }

        val srcSources = File(sessionRootDir, SOURCE_DIR_NAME)
        val srcProcessed = File(sessionRootDir, PROCESSED_DIR_NAME)
        val dstSources = File(targetDocDir, SOURCE_DIR_NAME)
        val dstProcessed = File(targetDocDir, PROCESSED_DIR_NAME)

        dstSources.deleteRecursively()
        dstProcessed.deleteRecursively()
        if (srcSources.exists()) srcSources.copyRecursively(dstSources, overwrite = true)
        if (srcProcessed.exists()) srcProcessed.copyRecursively(dstProcessed, overwrite = true)

        val coverFile = File(targetDocDir, "cover.jpg")
        val resolvedCover = if (coverBitmap != null) {
            writeThumbnail(coverBitmap, coverFile)
            coverFile.absolutePath
        } else {
            val firstProcessed = dstProcessed.listFiles()
                ?.firstOrNull { it.extension == "jpg" || it.extension == "png" }
            if (firstProcessed != null) {
                BitmapFactory.decodeFile(firstProcessed.absolutePath)?.let { bmp ->
                    writeThumbnail(bmp, coverFile)
                    bmp.recycle()
                    coverFile.absolutePath
                } ?: existing?.coverPath
            } else existing?.coverPath
        }

        val now = System.currentTimeMillis()
        val entity = SavedDocumentEntity(
            id = docId,
            title = title.trim().ifEmpty { "Scan $now" },
            folderId = folderId ?: existing?.folderId,
            tagsCsv = tagsCsv.ifEmpty { existing?.tagsCsv ?: "" },
            isFavorite = existing?.isFavorite ?: false,
            isLocked = isLocked || (existing?.isLocked == true),
            pageCount = pageCount,
            coverPath = resolvedCover,
            storageDirName = dirName,
            lastExportPath = lastExportPath ?: existing?.lastExportPath,
            lastExportFormat = lastExportFormat,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = null,
            ocrText = existing?.ocrText ?: "",
        )
        dao.upsertDocument(entity)
        notifyMutated()
        entity
    }

    suspend fun loadDocumentIntoSession(docId: String, sessionRootDir: File): SavedDocumentEntity? =
        withContext(Dispatchers.IO) {
            val doc = dao.getDocumentById(docId) ?: return@withContext null
            val docDir = File(docsRoot, doc.storageDirName)
            if (!docDir.exists()) return@withContext null

            val dstSources = File(sessionRootDir, SOURCE_DIR_NAME)
            val dstProcessed = File(sessionRootDir, PROCESSED_DIR_NAME)
            dstSources.deleteRecursively()
            dstProcessed.deleteRecursively()
            dstSources.mkdirs()
            dstProcessed.mkdirs()

            val srcSources = File(docDir, SOURCE_DIR_NAME)
            val srcProcessed = File(docDir, PROCESSED_DIR_NAME)
            if (srcSources.exists()) srcSources.copyRecursively(dstSources, overwrite = true)
            if (srcProcessed.exists()) srcProcessed.copyRecursively(dstProcessed, overwrite = true)
            doc
        }

    suspend fun renameDocument(docId: String, newTitle: String) = withContext(Dispatchers.IO) {
        val clean = newTitle.trim()
        if (clean.isNotEmpty()) {
            dao.renameDocument(docId, clean, System.currentTimeMillis())
            notifyMutated()
        }
    }

    suspend fun moveDocumentToFolder(docId: String, folderId: String?) = withContext(Dispatchers.IO) {
        dao.moveDocumentToFolder(docId, folderId, System.currentTimeMillis())
        notifyMutated()
    }

    suspend fun updateTags(docId: String, tagsCsv: String) = withContext(Dispatchers.IO) {
        val normalized = tagsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        dao.updateTags(docId, normalized, System.currentTimeMillis())
        notifyMutated()
    }

    suspend fun updateDocumentOcrText(docId: String, ocrText: String) = withContext(Dispatchers.IO) {
        val normalized = ocrText.trim().take(24_000)
        if (normalized.isNotEmpty()) {
            dao.updateOcrText(docId, normalized, System.currentTimeMillis())
            notifyMutated()
        }
    }

    suspend fun toggleFavorite(doc: SavedDocumentEntity) = withContext(Dispatchers.IO) {
        dao.setFavorite(doc.id, !doc.isFavorite)
        notifyMutated()
    }

    /** Moves document to 30-day Recycle Bin instead of immediate disk destruction. */
    suspend fun moveToTrash(doc: SavedDocumentEntity, deletedAtMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            dao.setDeletedAt(doc.id, deletedAtMs, deletedAtMs)
            notifyMutated()
        }

    suspend fun deleteDocument(doc: SavedDocumentEntity) = moveToTrash(doc)

    suspend fun restoreFromTrash(doc: SavedDocumentEntity) = withContext(Dispatchers.IO) {
        dao.setDeletedAt(doc.id, null, System.currentTimeMillis())
        notifyMutated()
    }

    suspend fun permanentlyDeleteDocument(doc: SavedDocumentEntity) = withContext(Dispatchers.IO) {
        dao.deleteDocument(doc.id)
        File(docsRoot, doc.storageDirName).deleteRecursively()
        onDocumentPermanentlyDeletedListener?.invoke(doc.id)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trashed = dao.getAllTrashDocumentsSync()
        trashed.forEach { doc ->
            dao.deleteDocument(doc.id)
            File(docsRoot, doc.storageDirName).deleteRecursively()
            onDocumentPermanentlyDeletedListener?.invoke(doc.id)
        }
    }

    suspend fun purgeExpiredTrash(
        retentionDays: Int = TRASH_RETENTION_DAYS,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        val cutoffMs = nowMs - retentionDays * MILLIS_PER_DAY
        val expired = dao.getExpiredTrashDocuments(cutoffMs)
        expired.forEach { doc ->
            dao.deleteDocument(doc.id)
            File(docsRoot, doc.storageDirName).deleteRecursively()
        }
        expired.size
    }

    suspend fun saveSignature(
        bitmap: Bitmap,
        label: String,
        assetType: SavedAssetType = SavedAssetType.SIGNATURE,
    ): SavedSignatureEntity =
        withContext(Dispatchers.IO) {
            val prefix = if (assetType == SavedAssetType.STAMP) "stamp" else "sig"
            val id = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
            val file = File(signaturesRoot, "$id.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val defaultLabel = if (assetType == SavedAssetType.STAMP) "Stamp" else "Signature"
            val entity = SavedSignatureEntity(
                id = id,
                label = label.trim().ifBlank { defaultLabel },
                imagePath = file.absolutePath,
                assetType = assetType.name,
                createdAt = System.currentTimeMillis()
            )
            dao.upsertSignature(entity)
            entity
        }

    suspend fun deleteSignature(signature: SavedSignatureEntity) = withContext(Dispatchers.IO) {
        dao.deleteSignature(signature.id)
        File(signature.imagePath).delete()
    }

    suspend fun exportFullBackupZip(outputStream: OutputStream): BackupStats =
        withContext(Dispatchers.IO) {
            val allFolders = dao.getAllFoldersSync()
            val allDocs = dao.getAllActiveDocumentsSync()
            val allSigs = dao.getAllSignaturesSync()

            val normalizedSigs = allSigs.map { sig ->
                val fileName = File(sig.imagePath).name
                sig.copy(imagePath = fileName)
            }
            val normalizedDocs = allDocs.map { doc ->
                doc.copy(coverPath = if (doc.coverPath != null) "cover.jpg" else null)
            }
            val manifest = BackupManifest(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                folders = allFolders,
                documents = normalizedDocs,
                signatures = normalizedSigs,
            )

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                // 1. manifest.json
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(backupJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. Document files
                allDocs.forEach { doc ->
                    val docDir = File(docsRoot, doc.storageDirName)
                    if (docDir.exists()) {
                        docDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(docsRoot).invariantSeparatorsPath
                            zos.putNextEntry(ZipEntry("docs/$rel"))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // 3. Signature & Stamp PNGs
                allSigs.forEach { sig ->
                    val f = File(sig.imagePath)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("signatures/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            BackupStats(
                folderCount = allFolders.size,
                documentCount = allDocs.size,
                signatureCount = allSigs.size,
            )
        }

    suspend fun restoreFromBackupZip(inputStream: InputStream): BackupStats =
        withContext(Dispatchers.IO) {
            val stagingDir = File(appContext.cacheDir, "zip_restore_${System.currentTimeMillis()}").apply {
                mkdirs()
            }
            try {
                val canonicalStaging = stagingDir.canonicalPath
                var manifest: BackupManifest? = null

                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) {
                            zis.closeEntry()
                            continue
                        }
                        if (entry.name == "manifest.json") {
                            val jsonStr = zis.readBytes().toString(Charsets.UTF_8)
                            manifest = backupJson.decodeFromString(BackupManifest.serializer(), jsonStr)
                        } else if (entry.name.startsWith("docs/") || entry.name.startsWith("signatures/")) {
                            val outFile = File(stagingDir, entry.name)
                            if (!outFile.canonicalPath.startsWith(canonicalStaging)) {
                                throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
                            }
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                    }
                }

                val parsed = manifest ?: throw IllegalArgumentException("Backup archive missing manifest.json")

                // Copy staged document directories safely into docsRoot
                val stagedDocsRoot = File(stagingDir, "docs")
                if (stagedDocsRoot.exists()) {
                    stagedDocsRoot.listFiles()?.filter { it.isDirectory }?.forEach { stagedDoc ->
                        val target = File(docsRoot, stagedDoc.name)
                        stagedDoc.copyRecursively(target, overwrite = true)
                    }
                }

                // Copy staged signatures safely into signaturesRoot
                val stagedSigsRoot = File(stagingDir, "signatures")
                if (stagedSigsRoot.exists()) {
                    stagedSigsRoot.listFiles()?.filter { it.isFile }?.forEach { stagedSig ->
                        val target = File(signaturesRoot, stagedSig.name)
                        stagedSig.copyTo(target, overwrite = true)
                    }
                }

                parsed.folders.forEach { folder ->
                    dao.upsertFolder(folder)
                }
                parsed.documents.forEach { doc ->
                    val coverFile = File(File(docsRoot, doc.storageDirName), "cover.jpg")
                    dao.upsertDocument(
                        doc.copy(
                            coverPath = if (coverFile.exists()) coverFile.absolutePath else null,
                            deletedAt = null,
                        )
                    )
                }
                parsed.signatures.forEach { sig ->
                    val fileName = File(sig.imagePath).name
                    val resolvedFile = File(signaturesRoot, fileName)
                    if (resolvedFile.exists()) {
                        dao.upsertSignature(sig.copy(imagePath = resolvedFile.absolutePath))
                    }
                }

                BackupStats(
                    folderCount = parsed.folders.size,
                    documentCount = parsed.documents.size,
                    signatureCount = parsed.signatures.size,
                )
            } finally {
                stagingDir.deleteRecursively()
            }
        }

    suspend fun readDocumentPageFiles(docId: String): List<Pair<File, Int>> =
        withContext(Dispatchers.IO) {
            val doc = dao.getDocumentById(docId) ?: return@withContext emptyList()
            val processedDir = File(File(docsRoot, doc.storageDirName), PROCESSED_DIR_NAME)
            if (!processedDir.exists()) return@withContext emptyList()

            val jsonFile = File(processedDir, "document.json")
            if (jsonFile.exists()) {
                val parsed = runCatching {
                    val text = jsonFile.readText()
                    val root = Json.parseToJsonElement(text)
                    Json.decodeFromJsonElement(
                        org.fairscan.app.data.DocumentMetadataV2.serializer(),
                        root
                    )
                }.getOrNull()
                if (parsed != null && parsed.pages.isNotEmpty()) {
                    val ordered = parsed.pages.mapNotNull { page ->
                        val sb = StringBuilder(page.id)
                        if (page.colorMode != null) sb.append(".").append(page.colorMode.name.lowercase())
                        if (page.quadVersion > 0) sb.append(".q").append(page.quadVersion)
                        sb.append(if (page.colorMode == org.fairscan.imageprocessing.ColorMode.BLACK_AND_WHITE) ".png" else ".jpg")
                        val file = File(processedDir, sb.toString())
                        if (file.exists()) file to page.manualRotationDegrees else null
                    }
                    if (ordered.isNotEmpty()) return@withContext ordered
                }
            }
            processedDir.listFiles()
                ?.filter { it.extension.equals("jpg", true) || it.extension.equals("png", true) }
                ?.sortedBy { it.name }
                ?.map { it to 0 }
                ?: emptyList()
        }

    suspend fun saveBitmapsAsDocument(
        title: String,
        bitmaps: List<Bitmap>,
        folderId: String? = null,
        tagsCsv: String = "PDF",
        lastExportPath: String? = null,
        lastExportFormat: String = "PDF",
    ): SavedDocumentEntity? = withContext(Dispatchers.IO) {
        if (bitmaps.isEmpty()) return@withContext null
        val docId = "doc_${System.currentTimeMillis()}"
        val targetDocDir = File(docsRoot, docId).apply { mkdirs() }
        val dstSources = File(targetDocDir, SOURCE_DIR_NAME).apply { mkdirs() }
        val dstProcessed = File(targetDocDir, PROCESSED_DIR_NAME).apply { mkdirs() }

        val pagesMeta = mutableListOf<org.fairscan.app.data.PageV2>()
        var baseTs = System.currentTimeMillis()
        bitmaps.forEach { bmp ->
            val id = "${baseTs++}"
            val stream = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            val bytes = stream.toByteArray()
            File(dstSources, "$id.jpg").writeBytes(bytes)
            File(dstProcessed, "${id}.color.jpg").writeBytes(bytes)
            pagesMeta.add(
                org.fairscan.app.data.PageV2(
                    id = id,
                    baseRotationDegrees = 0,
                    manualRotationDegrees = 0,
                    isColored = true,
                    colorMode = org.fairscan.imageprocessing.ColorMode.COLOR,
                    sourceWidth = bmp.width,
                    sourceHeight = bmp.height,
                )
            )
        }
        val json = Json { prettyPrint = false; encodeDefaults = true }
        File(dstProcessed, "document.json").writeText(
            json.encodeToString(
                org.fairscan.app.data.DocumentMetadataV2.serializer(),
                org.fairscan.app.data.DocumentMetadataV2(pages = pagesMeta)
            )
        )

        val coverFile = File(targetDocDir, "cover.jpg")
        writeThumbnail(bitmaps.first(), coverFile)

        val now = System.currentTimeMillis()
        val entity = SavedDocumentEntity(
            id = docId,
            title = title.trim().ifEmpty { "Document" },
            folderId = folderId,
            tagsCsv = tagsCsv,
            isFavorite = false,
            isLocked = false,
            pageCount = bitmaps.size,
            coverPath = coverFile.absolutePath,
            storageDirName = docId,
            lastExportPath = lastExportPath,
            lastExportFormat = lastExportFormat,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        dao.upsertDocument(entity)
        entity
    }

    private fun writeThumbnail(bitmap: Bitmap, target: File) {
        val maxSide = 480f
        val scale = minOf(maxSide / bitmap.width, maxSide / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        FileOutputStream(target).use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        if (thumb !== bitmap) thumb.recycle()
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L

        fun remainingTrashDays(deletedAt: Long?, nowMs: Long = System.currentTimeMillis()): Int {
            if (deletedAt == null) return TRASH_RETENTION_DAYS
            val elapsedDays = ((nowMs - deletedAt).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
            return (TRASH_RETENTION_DAYS - elapsedDays).coerceIn(0, TRASH_RETENTION_DAYS)
        }

        fun extractSearchSnippet(doc: SavedDocumentEntity, query: String): String? {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty() || doc.ocrText.isBlank()) return null
            val idx = doc.ocrText.indexOf(cleanQuery, ignoreCase = true)
            if (idx < 0) return null
            val start = (idx - 24).coerceAtLeast(0)
            val end = (idx + cleanQuery.length + 40).coerceAtMost(doc.ocrText.length)
            val raw = doc.ocrText.substring(start, end)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < doc.ocrText.length) "…" else ""
            return "$prefix$raw$suffix"
        }
    }
}
