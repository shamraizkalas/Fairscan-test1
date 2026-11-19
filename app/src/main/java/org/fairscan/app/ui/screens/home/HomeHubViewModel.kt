package org.fairscan.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fairscan.app.library.FolderEntity
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.library.SavedSignatureEntity

enum class DocumentSortOrder(val label: String) {
    NEWEST("Newest"),
    TITLE("Name"),
    PAGES("Pages"),
}

enum class FolderFilter {
    ALL,
    FAVORITES,
    CUSTOM,
    TRASH,
}

data class HomeHubUiState(
    val searchQuery: String = "",
    val folderFilter: FolderFilter = FolderFilter.ALL,
    val selectedFolderId: String? = null,
    val selectedTag: String? = null,
    val sortOrder: DocumentSortOrder = DocumentSortOrder.NEWEST,
    val isGridView: Boolean = true,
    val folders: List<FolderEntity> = emptyList(),
    val allTags: List<String> = emptyList(),
    val documents: List<SavedDocumentEntity> = emptyList(),
    val totalDocumentCount: Int = 0,
    val trashCount: Int = 0,
    val unlockedFolderIds: Set<String> = emptySet(),
)

class HomeHubViewModel(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val folderFilter = MutableStateFlow(FolderFilter.ALL)
    private val selectedFolderId = MutableStateFlow<String?>(null)
    private val selectedTag = MutableStateFlow<String?>(null)
    private val sortOrder = MutableStateFlow(DocumentSortOrder.NEWEST)
    private val isGridView = MutableStateFlow(true)
    private val unlockedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    val savedSignatures: StateFlow<List<SavedSignatureEntity>> =
        libraryRepository.signatures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<HomeHubUiState> = combine(
        libraryRepository.folders,
        libraryRepository.documents,
        libraryRepository.trashDocuments,
        searchQuery,
        folderFilter,
        selectedFolderId,
        selectedTag,
        sortOrder,
        isGridView,
        unlockedFolderIds,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val folders = values[0] as List<FolderEntity>
        @Suppress("UNCHECKED_CAST")
        val docs = values[1] as List<SavedDocumentEntity>
        @Suppress("UNCHECKED_CAST")
        val trashDocs = values[2] as List<SavedDocumentEntity>
        val query = values[3] as String
        val filter = values[4] as FolderFilter
        val activeFolderId = values[5] as String?
        val activeTag = values[6] as String?
        val sort = values[7] as DocumentSortOrder
        val grid = values[8] as Boolean
        @Suppress("UNCHECKED_CAST")
        val unlockedIds = values[9] as Set<String>

        val lockedFolderIds = folders.filter { it.isLocked && it.id !in unlockedIds }.map { it.id }.toSet()

        val allTags = docs
            .filter { it.folderId == null || it.folderId !in lockedFolderIds }
            .flatMap { doc ->
                doc.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }.distinct().sorted()

        val q = query.trim().lowercase()
        val sourceList = if (filter == FolderFilter.TRASH) trashDocs else docs

        val filtered = sourceList.filter { doc ->
            val matchesFilter = when (filter) {
                FolderFilter.ALL -> doc.folderId == null || doc.folderId !in lockedFolderIds
                FolderFilter.FAVORITES -> doc.isFavorite && (doc.folderId == null || doc.folderId !in lockedFolderIds)
                FolderFilter.CUSTOM -> doc.folderId == activeFolderId && (doc.folderId !in lockedFolderIds)
                FolderFilter.TRASH -> true
            }
            val matchesTag = filter == FolderFilter.TRASH || activeTag == null || doc.tagsCsv.split(",").any {
                it.trim().equals(activeTag, ignoreCase = true)
            }
            val matchesQuery = q.isEmpty() ||
                doc.title.lowercase().contains(q) ||
                doc.tagsCsv.lowercase().contains(q) ||
                doc.ocrText.lowercase().contains(q)
            matchesFilter && matchesTag && matchesQuery
        }

        val sorted = when (filter) {
            FolderFilter.TRASH -> filtered.sortedByDescending { it.deletedAt ?: it.updatedAt }
            else -> when (sort) {
                DocumentSortOrder.NEWEST -> filtered.sortedByDescending { it.updatedAt }
                DocumentSortOrder.TITLE -> filtered.sortedBy { it.title.lowercase() }
                DocumentSortOrder.PAGES -> filtered.sortedByDescending { it.pageCount }
            }
        }

        HomeHubUiState(
            searchQuery = query,
            folderFilter = filter,
            selectedFolderId = activeFolderId,
            selectedTag = activeTag,
            sortOrder = sort,
            isGridView = grid,
            folders = folders,
            allTags = allTags,
            documents = sorted,
            totalDocumentCount = docs.size,
            trashCount = trashDocs.size,
            unlockedFolderIds = unlockedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeHubUiState())

    fun setSearchQuery(query: String) { searchQuery.value = query }
    fun selectAllDocs() {
        folderFilter.value = FolderFilter.ALL
        selectedFolderId.value = null
    }
    fun selectFavorites() {
        folderFilter.value = FolderFilter.FAVORITES
        selectedFolderId.value = null
    }
    fun selectTrash() {
        folderFilter.value = FolderFilter.TRASH
        selectedFolderId.value = null
    }
    fun selectFolder(folderId: String) {
        folderFilter.value = FolderFilter.CUSTOM
        selectedFolderId.value = folderId
    }
    fun unlockFolderAndSelect(folderId: String) {
        unlockedFolderIds.update { it + folderId }
        selectFolder(folderId)
    }
    fun toggleFolderLock(folderId: String, lock: Boolean) {
        viewModelScope.launch {
            libraryRepository.setFolderLocked(folderId, lock)
            if (lock) {
                unlockedFolderIds.update { it - folderId }
                if (selectedFolderId.value == folderId) selectAllDocs()
            } else {
                unlockedFolderIds.update { it + folderId }
            }
        }
    }
    fun toggleTag(tag: String) {
        selectedTag.value = if (selectedTag.value == tag) null else tag
    }
    fun setSortOrder(order: DocumentSortOrder) { sortOrder.value = order }
    fun toggleGridView() { isGridView.value = !isGridView.value }

    fun createFolder(name: String) {
        viewModelScope.launch { libraryRepository.createFolder(name) }
    }
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            if (selectedFolderId.value == folderId) selectAllDocs()
            libraryRepository.deleteFolder(folderId)
        }
    }
    fun renameDocument(docId: String, title: String) {
        viewModelScope.launch { libraryRepository.renameDocument(docId, title) }
    }
    fun moveDocumentToFolder(docId: String, folderId: String?) {
        viewModelScope.launch { libraryRepository.moveDocumentToFolder(docId, folderId) }
    }
    fun updateTags(docId: String, tagsCsv: String) {
        viewModelScope.launch { libraryRepository.updateTags(docId, tagsCsv) }
    }
    fun toggleFavorite(doc: SavedDocumentEntity) {
        viewModelScope.launch { libraryRepository.toggleFavorite(doc) }
    }
    fun deleteDocument(doc: SavedDocumentEntity) {
        viewModelScope.launch { libraryRepository.moveToTrash(doc) }
    }
    fun restoreFromTrash(doc: SavedDocumentEntity) {
        viewModelScope.launch { libraryRepository.restoreFromTrash(doc) }
    }
    fun permanentlyDeleteDocument(doc: SavedDocumentEntity) {
        viewModelScope.launch { libraryRepository.permanentlyDeleteDocument(doc) }
    }
    fun emptyTrash() {
        viewModelScope.launch { libraryRepository.emptyTrash() }
    }
}
