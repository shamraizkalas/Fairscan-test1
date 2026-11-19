package org.fairscan.app.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: Long = 0xFF00897B,
    val createdAt: Long = System.currentTimeMillis(),
    val isLocked: Boolean = false,
)

@Serializable
@Entity(tableName = "saved_documents")
data class SavedDocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String? = null,
    val tagsCsv: String = "",
    val isFavorite: Boolean = false,
    val isLocked: Boolean = false,
    val pageCount: Int = 1,
    val coverPath: String? = null,
    val storageDirName: String,
    val lastExportPath: String? = null,
    val lastExportFormat: String = "PDF",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val ocrText: String = "",
)

enum class SavedAssetType {
    SIGNATURE,
    STAMP,
}

@Serializable
@Entity(tableName = "saved_signatures")
data class SavedSignatureEntity(
    @PrimaryKey val id: String,
    val label: String,
    val imagePath: String,
    val assetType: String = SavedAssetType.SIGNATURE.name,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    suspend fun getAllFoldersSync(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :folderId")
    suspend fun renameFolder(folderId: String, name: String)

    @Query("UPDATE folders SET isLocked = :locked WHERE id = :folderId")
    suspend fun setFolderLocked(folderId: String, locked: Boolean)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("UPDATE saved_documents SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromDocuments(folderId: String)

    @Query("SELECT * FROM saved_documents WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActiveDocuments(): Flow<List<SavedDocumentEntity>>

    @Query("SELECT * FROM saved_documents WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashDocuments(): Flow<List<SavedDocumentEntity>>

    @Query("SELECT * FROM saved_documents WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getAllActiveDocumentsSync(): List<SavedDocumentEntity>

    @Query("SELECT * FROM saved_documents WHERE deletedAt IS NOT NULL")
    suspend fun getAllTrashDocumentsSync(): List<SavedDocumentEntity>

    @Query("SELECT * FROM saved_documents WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun getExpiredTrashDocuments(cutoffMs: Long): List<SavedDocumentEntity>

    @Query("SELECT * FROM saved_documents WHERE id = :docId LIMIT 1")
    suspend fun getDocumentById(docId: String): SavedDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: SavedDocumentEntity)

    @Query("UPDATE saved_documents SET title = :title, updatedAt = :updatedAt WHERE id = :docId")
    suspend fun renameDocument(docId: String, title: String, updatedAt: Long)

    @Query("UPDATE saved_documents SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :docId")
    suspend fun moveDocumentToFolder(docId: String, folderId: String?, updatedAt: Long)

    @Query("UPDATE saved_documents SET tagsCsv = :tagsCsv, updatedAt = :updatedAt WHERE id = :docId")
    suspend fun updateTags(docId: String, tagsCsv: String, updatedAt: Long)

    @Query("UPDATE saved_documents SET isFavorite = :favorite WHERE id = :docId")
    suspend fun setFavorite(docId: String, favorite: Boolean)

    @Query("UPDATE saved_documents SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :docId")
    suspend fun setDeletedAt(docId: String, deletedAt: Long?, updatedAt: Long)

    @Query("UPDATE saved_documents SET ocrText = :ocrText, updatedAt = :updatedAt WHERE id = :docId")
    suspend fun updateOcrText(docId: String, ocrText: String, updatedAt: Long)

    @Query("DELETE FROM saved_documents WHERE id = :docId")
    suspend fun deleteDocument(docId: String)

    @Query("SELECT * FROM saved_signatures ORDER BY createdAt DESC")
    fun observeSignatures(): Flow<List<SavedSignatureEntity>>

    @Query("SELECT * FROM saved_signatures ORDER BY createdAt DESC")
    suspend fun getAllSignaturesSync(): List<SavedSignatureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignature(signature: SavedSignatureEntity)

    @Query("DELETE FROM saved_signatures WHERE id = :sigId")
    suspend fun deleteSignature(sigId: String)
}

@Database(
    entities = [FolderEntity::class, SavedDocumentEntity::class, SavedSignatureEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var INSTANCE: LibraryDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE saved_signatures ADD COLUMN assetType TEXT NOT NULL DEFAULT 'SIGNATURE'"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE saved_documents ADD COLUMN deletedAt INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_documents ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): LibraryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "fairscan_studio_library.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
