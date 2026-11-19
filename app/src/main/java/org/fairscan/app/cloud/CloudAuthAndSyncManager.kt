package org.fairscan.app.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.text.format.DateFormat
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.fairscan.app.R
import org.fairscan.app.library.LibraryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Date
import java.util.concurrent.TimeUnit

data class CloudUserSummary(
    val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String?,
)

data class CloudSyncUiState(
    val user: CloudUserSummary? = null,
    val hasDrivePermission: Boolean = false,
    val isSigningIn: Boolean = false,
    val isSyncing: Boolean = false,
    val syncedFolderCount: Int = 0,
    val syncedDocumentCount: Int = 0,
    val syncedBackupSizeBytes: Long = 0L,
    val lastSyncedAtMs: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * 100% Free Personal Google Drive Sync & Backup Manager:
 * - Authenticates user identity via Jetpack CredentialManager (Google Sign-In).
 * - Requests `drive.appdata` & `drive.file` OAuth tokens via Play Services `AuthorizationClient`.
 * - Stores full library ZIP backups (folders, metadata, OCR text, PDFs, pages & signatures)
 *   directly in the signed-in user's own 15 GB Google Drive — ZERO developer DB or server fees.
 */
class CloudAuthAndSyncManager(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val hybridModeController: HybridModeController,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "GoogleDriveSync"
        const val PREFS_NAME = "proscan_google_drive_account"
        const val KEY_UID = "uid"
        const val KEY_NAME = "display_name"
        const val KEY_EMAIL = "email"
        const val KEY_PHOTO = "photo_url"
        const val KEY_LAST_SYNC_MS = "last_sync_ms"
        const val KEY_LAST_SIZE = "last_backup_bytes"
        const val KEY_LAST_DOCS = "last_doc_count"
        const val KEY_LAST_FOLDERS = "last_folder_count"

        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val BACKUP_FILE_NAME = "proscan_library_sync.zip"
        const val VISIBLE_FOLDER_NAME = "ProScan Studio Backups"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(appContext) }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val _uiState = MutableStateFlow(loadPersistedUiState())
    val uiState: StateFlow<CloudSyncUiState> = _uiState.asStateFlow()

    private enum class PendingDriveAction {
        SYNC_APPDATA,
        RESTORE_APPDATA,
        EXPORT_VISIBLE_DRIVE,
    }

    private var pendingAction: PendingDriveAction = PendingDriveAction.SYNC_APPDATA
    private var driveConsentLauncher: ((IntentSender) -> Unit)? = null

    fun registerDriveConsentLauncher(launcher: (IntentSender) -> Unit) {
        driveConsentLauncher = launcher
    }

    fun startAfterAppCheckInitialized() {
        // App Check removed — restore cached signed-in account metadata cleanly.
        _uiState.value = loadPersistedUiState()
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun attemptAutoSignInIfOptedIn(context: Context) {
        if (_uiState.value.user != null) return
        scope.launch {
            if (!hybridModeController.isCloudSyncOptedIn()) return@launch
            val clientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrNull()
                ?: return@launch
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            try {
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                    saveSignedInUser(googleCred)
                }
            } catch (_: Exception) {
                // Silent check only; manual button remains available.
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    isSigningIn = false,
                    errorMessage = "Google OAuth client configuration missing (default_web_client_id)."
                )
            }
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            _uiState.update { it.copy(isSigningIn = true, errorMessage = null, statusMessage = null) }
            try {
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                    saveSignedInUser(googleCred)
                    hybridModeController.setCloudSyncOptedIn(true)
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            statusMessage = "Connected as ${googleCred.displayName ?: googleCred.id}. Checking Google Drive access…"
                        )
                    }
                    syncNow(activity)
                } else {
                    _uiState.update {
                        it.copy(isSigningIn = false, errorMessage = "Unexpected Google credential returned.")
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled: ${e.message}")
                _uiState.update { it.copy(isSigningIn = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                _uiState.update {
                    it.copy(
                        isSigningIn = false,
                        errorMessage = e.localizedMessage ?: "Google Sign-In failed."
                    )
                }
            }
        }
    }

    fun handleDriveAuthorizationResult(activity: Activity, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    errorMessage = "Google Drive permission was cancelled."
                )
            }
            return
        }
        scope.launch {
            try {
                val authResult = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
                val token = authResult.accessToken
                if (token.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(isSyncing = false, errorMessage = "No Drive access token received.")
                    }
                    return@launch
                }
                _uiState.update { it.copy(hasDrivePermission = true) }
                executeActionWithToken(token, pendingAction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Drive auth result failed", e)
                _uiState.update {
                    it.copy(isSyncing = false, errorMessage = e.localizedMessage ?: "Drive authorization failed.")
                }
            }
        }
    }

    fun triggerAutoSyncIfSignedIn() {
        if (_uiState.value.user == null) return
        if (!hybridModeController.state.value.isOnlineActive) return
        scope.launch {
            val token = obtainDriveAccessTokenSilently() ?: return@launch
            executeActionWithToken(token, PendingDriveAction.SYNC_APPDATA, quietMode = true)
        }
    }

    fun syncNow(activity: Activity? = null) {
        if (_uiState.value.user == null) {
            _uiState.update { it.copy(errorMessage = "Sign in with Google first to sync with Google Drive.") }
            return
        }
        runAuthorizedDriveAction(activity, PendingDriveAction.SYNC_APPDATA)
    }

    fun restoreFromDrive(activity: Activity) {
        if (_uiState.value.user == null) {
            _uiState.update { it.copy(errorMessage = "Sign in with Google first to restore from Google Drive.") }
            return
        }
        runAuthorizedDriveAction(activity, PendingDriveAction.RESTORE_APPDATA)
    }

    fun exportVisibleBackupToDrive(activity: Activity) {
        if (_uiState.value.user == null) {
            _uiState.update { it.copy(errorMessage = "Sign in with Google first to export backup to Google Drive.") }
            return
        }
        runAuthorizedDriveAction(activity, PendingDriveAction.EXPORT_VISIBLE_DRIVE)
    }

    fun signOut() {
        scope.launch {
            withContext(NonCancellable) {
                hybridModeController.setCloudSyncOptedIn(false)
                prefs.edit().clear().apply()
                runCatching {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                }
                _uiState.value = CloudSyncUiState()
            }
        }
    }

    private fun runAuthorizedDriveAction(activity: Activity?, action: PendingDriveAction) {
        pendingAction = action
        scope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null, statusMessage = "Authorizing Google Drive…") }
            try {
                val request = buildDriveAuthRequest()
                val result = Identity.getAuthorizationClient(activity ?: appContext)
                    .authorize(request)
                    .await()
                if (result.hasResolution()) {
                    val intentSender = result.pendingIntent?.intentSender
                    val launcher = driveConsentLauncher
                    if (intentSender != null && launcher != null) {
                        launcher(intentSender)
                    } else {
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                errorMessage = "Tap Sync Now again to grant Google Drive permission."
                            )
                        }
                    }
                    return@launch
                }
                val token = result.accessToken
                if (token.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(isSyncing = false, errorMessage = "Unable to obtain Google Drive access token.")
                    }
                    return@launch
                }
                _uiState.update { it.copy(hasDrivePermission = true) }
                executeActionWithToken(token, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Drive authorization error", e)
                _uiState.update {
                    it.copy(isSyncing = false, errorMessage = e.localizedMessage ?: "Google Drive authorization failed.")
                }
            }
        }
    }

    private suspend fun obtainDriveAccessTokenSilently(): String? {
        return try {
            val result = Identity.getAuthorizationClient(appContext)
                .authorize(buildDriveAuthRequest())
                .await()
            if (result.hasResolution()) null else result.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDriveAuthRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder()
            .setRequestedScopes(
                listOf(
                    Scope(SCOPE_DRIVE_APPDATA),
                    Scope(SCOPE_DRIVE_FILE),
                )
            )
            .build()
    }

    private suspend fun executeActionWithToken(
        accessToken: String,
        action: PendingDriveAction,
        quietMode: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        try {
            when (action) {
                PendingDriveAction.SYNC_APPDATA -> performAppDataSync(accessToken, quietMode)
                PendingDriveAction.RESTORE_APPDATA -> performAppDataRestore(accessToken)
                PendingDriveAction.EXPORT_VISIBLE_DRIVE -> performVisibleFolderBackup(accessToken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Drive action $action failed", e)
            if (!quietMode) {
                _uiState.update {
                    it.copy(isSyncing = false, errorMessage = e.localizedMessage ?: "Google Drive sync failed.")
                }
            } else {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    private suspend fun performAppDataSync(accessToken: String, quietMode: Boolean) {
        val localDocs = libraryRepository.getAllDocumentsIncludingTrashSnapshot().filter { it.deletedAt == null }
        val localFolders = libraryRepository.getAllFoldersSnapshot()

        val existingFileId = findFileInAppData(accessToken, BACKUP_FILE_NAME)
        if (localDocs.isEmpty() && existingFileId != null && !quietMode) {
            // Fresh phone with existing cloud backup -> auto-restore instead of overwriting with empty!
            performAppDataRestore(accessToken)
            return
        }

        if (!quietMode) {
            _uiState.update { it.copy(statusMessage = "Uploading library backup to your Google Drive…") }
        }
        val zipFile = File(appContext.cacheDir, "drive_appdata_backup.zip")
        try {
            val stats = FileOutputStream(zipFile).use { out ->
                libraryRepository.exportFullBackupZip(out)
            }
            val sizeBytes = zipFile.length()
            if (existingFileId != null) {
                updateFileMultipart(accessToken, existingFileId, zipFile)
            } else {
                createFileMultipart(
                    accessToken = accessToken,
                    fileName = BACKUP_FILE_NAME,
                    parentId = "appDataFolder",
                    zipFile = zipFile,
                )
            }
            val now = System.currentTimeMillis()
            persistSyncStats(
                syncedAtMs = now,
                sizeBytes = sizeBytes,
                docCount = stats.documentCount,
                folderCount = stats.folderCount,
            )
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    hasDrivePermission = true,
                    syncedFolderCount = stats.folderCount,
                    syncedDocumentCount = stats.documentCount,
                    syncedBackupSizeBytes = sizeBytes,
                    lastSyncedAtMs = now,
                    statusMessage = "Synced ${stats.documentCount} docs & ${stats.folderCount} folders to Google Drive",
                    errorMessage = null,
                )
            }
        } finally {
            zipFile.delete()
        }
    }

    private suspend fun performAppDataRestore(accessToken: String) {
        _uiState.update { it.copy(statusMessage = "Checking Google Drive for backup…") }
        val fileId = findFileInAppData(accessToken, BACKUP_FILE_NAME)
        if (fileId == null) {
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = null,
                    errorMessage = "No ProScan backup found in your Google Drive yet. Tap Sync Now first."
                )
            }
            return
        }

        val zipFile = File(appContext.cacheDir, "drive_appdata_restore.zip")
        try {
            downloadDriveFile(accessToken, fileId, zipFile)
            val stats = FileInputStream(zipFile).use { input ->
                libraryRepository.restoreFromBackupZip(input)
            }
            val now = System.currentTimeMillis()
            persistSyncStats(
                syncedAtMs = now,
                sizeBytes = zipFile.length(),
                docCount = stats.documentCount,
                folderCount = stats.folderCount,
            )
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    hasDrivePermission = true,
                    syncedFolderCount = stats.folderCount,
                    syncedDocumentCount = stats.documentCount,
                    syncedBackupSizeBytes = zipFile.length(),
                    lastSyncedAtMs = now,
                    statusMessage = "Restored ${stats.documentCount} docs & ${stats.folderCount} folders from Google Drive",
                    errorMessage = null,
                )
            }
        } finally {
            zipFile.delete()
        }
    }

    private suspend fun performVisibleFolderBackup(accessToken: String) {
        _uiState.update { it.copy(statusMessage = "Saving visible backup ZIP to Google Drive…") }
        val folderId = findOrCreateVisibleBackupFolder(accessToken)
        val stamp = DateFormat.format("yyyyMMdd-HHmm", Date()).toString()
        val exportName = "ProScan-Backup-$stamp.zip"
        val zipFile = File(appContext.cacheDir, exportName)
        try {
            val stats = FileOutputStream(zipFile).use { out ->
                libraryRepository.exportFullBackupZip(out)
            }
            createFileMultipart(
                accessToken = accessToken,
                fileName = exportName,
                parentId = folderId,
                zipFile = zipFile,
            )
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    hasDrivePermission = true,
                    statusMessage = "Saved \"$exportName\" (${stats.documentCount} docs) in Drive/$VISIBLE_FOLDER_NAME",
                    errorMessage = null,
                )
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun findFileInAppData(accessToken: String, name: String): String? {
        val query = URLEncoder.encode("name='$name' and trashed=false", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$query&fields=files(id,name,size)"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("Google Drive lookup failed (HTTP ${res.code})")
            }
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            return if (files.length() > 0) files.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } else null
        }
    }

    private fun findOrCreateVisibleBackupFolder(accessToken: String): String {
        val query = URLEncoder.encode(
            "name='$VISIBLE_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            "UTF-8"
        )
        val lookupUrl = "https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,name)"
        val lookupReq = Request.Builder()
            .url(lookupUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(lookupReq).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.isSuccessful) {
                val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
                val existing = arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                if (existing != null) return existing
            }
        }

        val folderMeta = JSONObject().apply {
            put("name", VISIBLE_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(folderMeta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(createReq).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("Unable to create Drive folder (HTTP ${res.code})")
            }
            return JSONObject(body).getString("id")
        }
    }

    private fun createFileMultipart(
        accessToken: String,
        fileName: String,
        parentId: String,
        zipFile: File,
    ) {
        val metaJson = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put(parentId))
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(zipFile.asRequestBody("application/zip".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,size")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("Google Drive upload failed (HTTP ${res.code})")
            }
        }
    }

    private fun updateFileMultipart(
        accessToken: String,
        fileId: String,
        zipFile: File,
    ) {
        val metaJson = JSONObject().apply {
            put("mimeType", "application/zip")
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(zipFile.asRequestBody("application/zip".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id,size")
            .addHeader("Authorization", "Bearer $accessToken")
            .patch(body)
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("Google Drive update failed (HTTP ${res.code})")
            }
        }
    }

    private fun downloadDriveFile(accessToken: String, fileId: String, outFile: File) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("Google Drive download failed (HTTP ${res.code})")
            }
            val stream = res.body?.byteStream() ?: throw IllegalStateException("Empty Drive backup stream.")
            outFile.outputStream().use { output -> stream.copyTo(output) }
        }
    }

    private fun saveSignedInUser(googleCred: GoogleIdTokenCredential) {
        val email = googleCred.id
        val name = googleCred.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
        val photo = googleCred.profilePictureUri?.toString()
        prefs.edit()
            .putString(KEY_UID, email)
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PHOTO, photo)
            .apply()

        _uiState.update {
            it.copy(
                user = CloudUserSummary(
                    uid = email,
                    displayName = name,
                    email = email,
                    photoUrl = photo,
                ),
                errorMessage = null,
            )
        }
    }

    private fun persistSyncStats(
        syncedAtMs: Long,
        sizeBytes: Long,
        docCount: Int,
        folderCount: Int,
    ) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_MS, syncedAtMs)
            .putLong(KEY_LAST_SIZE, sizeBytes)
            .putInt(KEY_LAST_DOCS, docCount)
            .putInt(KEY_LAST_FOLDERS, folderCount)
            .apply()
    }

    private fun loadPersistedUiState(): CloudSyncUiState {
        val uid = prefs.getString(KEY_UID, null)
        if (uid.isNullOrBlank()) return CloudSyncUiState()
        val lastMs = prefs.getLong(KEY_LAST_SYNC_MS, 0L).takeIf { it > 0L }
        return CloudSyncUiState(
            user = CloudUserSummary(
                uid = uid,
                displayName = prefs.getString(KEY_NAME, "Google Account") ?: "Google Account",
                email = prefs.getString(KEY_EMAIL, uid) ?: uid,
                photoUrl = prefs.getString(KEY_PHOTO, null),
            ),
            hasDrivePermission = lastMs != null,
            syncedFolderCount = prefs.getInt(KEY_LAST_FOLDERS, 0),
            syncedDocumentCount = prefs.getInt(KEY_LAST_DOCS, 0),
            syncedBackupSizeBytes = prefs.getLong(KEY_LAST_SIZE, 0L),
            lastSyncedAtMs = lastMs,
        )
    }
}
