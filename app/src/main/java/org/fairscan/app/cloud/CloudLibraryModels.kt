package org.fairscan.app.cloud

import kotlinx.serialization.Serializable
import org.fairscan.app.library.FolderEntity
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.library.SavedSignatureEntity

@Serializable
data class DriveBackupRecord(
    val fileId: String = "",
    val fileName: String = "proscan_library_sync.zip",
    val modifiedTime: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
data class DriveSyncMetadata(
    val version: Int = 1,
    val syncedAtMs: Long = System.currentTimeMillis(),
    val folderCount: Int = 0,
    val documentCount: Int = 0,
    val signatureCount: Int = 0,
)

object DriveIdHelper {
    private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9._\\-]")

    fun sanitizeFileName(raw: String, fallback: String = "ProScanBackup"): String {
        val cleaned = raw.trim().replace(SAFE_NAME_REGEX, "_").take(80)
        return cleaned.ifBlank { fallback }
    }

    fun countSummary(
        folders: List<FolderEntity>,
        docs: List<SavedDocumentEntity>,
        signatures: List<SavedSignatureEntity>,
    ): DriveSyncMetadata = DriveSyncMetadata(
        syncedAtMs = System.currentTimeMillis(),
        folderCount = folders.size,
        documentCount = docs.size,
        signatureCount = signatures.size,
    )
}
