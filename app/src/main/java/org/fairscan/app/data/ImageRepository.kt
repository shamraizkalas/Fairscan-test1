/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fairscan.app.domain.EncodedImage
import org.fairscan.app.domain.Jpeg
import org.fairscan.app.domain.PageMetadata
import org.fairscan.app.domain.PageViewKey
import org.fairscan.app.domain.Png
import org.fairscan.app.domain.Rotation
import org.fairscan.app.domain.ScanPage
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.OpticalMeasures
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad
import org.fairscan.imageprocessing.cameraIntrinsics
import java.io.File
import java.util.Collections.synchronizedMap

import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fairscan.app.camscanner.CleanupEffectLayer
import org.fairscan.app.camscanner.CleanupEffectType
import org.fairscan.app.camscanner.EditablePdfPageEntry
import org.fairscan.app.camscanner.EditablePdfProjectManifest
import org.fairscan.app.camscanner.EraserLayer
import org.fairscan.app.camscanner.FilterLayer
import org.fairscan.app.camscanner.ImageAdjustments
import org.fairscan.app.camscanner.ImageOverlayLayer
import org.fairscan.app.camscanner.LayerCoordinateMapper
import org.fairscan.app.camscanner.PageCompositor
import org.fairscan.app.camscanner.PageEditState
import org.fairscan.app.camscanner.ProFilterPreset
import org.fairscan.app.camscanner.SerializedAnnotation
import org.fairscan.app.camscanner.WatermarkLayer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val SOURCE_DIR_NAME = "sources"
const val PROCESSED_DIR_NAME = "scanned_pages"
const val THUMBNAIL_DIR_NAME = "thumbnails"
const val BASE_SUBDIR_NAME = "base"
const val LAYERS_SUBDIR_NAME = "layers"
const val OVERLAY_ASSETS_SUBDIR_NAME = "overlay_assets"

/**
 * Repository responsible for:
 * - page persistence (document.json)
 * - non-destructive page edit layers (layers/, overlay_assets/, base/)
 * - image files (work, source, thumbnails)
 * - page-level operations (add, rotate, move, delete, recompose)
 */
class ImageRepository(
    scanRootDir: File,
    val transformations: ImageTransformations,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val sourceDir = File(scanRootDir, SOURCE_DIR_NAME).apply { mkdirs() }
    private val processedDir = File(scanRootDir, PROCESSED_DIR_NAME).apply { mkdirs() }
    private val baseDir = File(processedDir, BASE_SUBDIR_NAME).apply { mkdirs() }
    private val layersDir = File(processedDir, LAYERS_SUBDIR_NAME).apply { mkdirs() }
    private val overlayAssetsDir = File(processedDir, OVERLAY_ASSETS_SUBDIR_NAME).apply { mkdirs() }
    private val thumbnailDir = File(scanRootDir, THUMBNAIL_DIR_NAME)

    private val mutex = Mutex()
    private var lastPageTimestamp = 0L

    private val metadataFile = File(processedDir, "document.json")
    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
    private var pages: PageStore = PageStore(loadPages())

    private val _pageEditStates = MutableStateFlow<Map<String, PageEditState>>(loadAllEditStatesFromDisk())
    val pageEditStates: StateFlow<Map<String, PageEditState>> = _pageEditStates.asStateFlow()

    private val processingJobs = synchronizedMap(mutableMapOf<PageViewKey, Deferred<Unit>>())
    private val imageCache = createLruCache<PageViewKey, Deferred<EncodedImage?>>(maxEntries = 50)
    private val thumbnailCache = createLruCache<PageViewKey, Deferred<EncodedImage?>>(maxEntries = 1000)

    private fun <K, V> createLruCache(maxEntries: Int): MutableMap<K, V> =
        synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>) = size > maxEntries
        })

    // --- Metadata ---

    private fun loadPages(): MutableList<PageV2> {
        thumbnailDir.deleteRecursively() // clean up dir that was used in older versions
        normalizeLegacyFiles()
        val filesOnDisk = processedDir.listFiles()
            ?.filter { it.extension == "jpg" || it.extension == "png"}
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val metadataPages = if (metadataFile.exists()) {
            runCatching { loadMetadata() }.getOrNull()
        } else null

        return when {
            metadataPages != null ->
                metadataPages
                    .filter { processedImageFileName(it.id, it.colorMode, it.quadVersion) in filesOnDisk }
                    .toMutableList()
            else ->
                filesOnDisk
                    .sorted()
                    .map { pageFromLegacyFileName(it) }
                    .toMutableList()
        }
    }

    private fun loadMetadata(): List<PageV2> {
        val json = metadataFile.readText()

        val jsonElement = Json.parseToJsonElement(json)
        val version = jsonElement.jsonObject["version"]?.jsonPrimitive?.int ?: 1

        return when (version) {
            1 -> migrateFromV1(Json.decodeFromJsonElement<DocumentMetadataV1>(jsonElement))
            2 -> Json.decodeFromJsonElement<DocumentMetadataV2>(jsonElement).pages
            else -> error("Unsupported metadata version: $version")
        }
    }

    private fun migrateFromV1(meta: DocumentMetadataV1): MutableList<PageV2> =
        meta.pages.map { pageFromLegacyFileName(it.file) }.toMutableList()

    private fun pageFromLegacyFileName(fileName: String): PageV2 {
        val name = fileName.removeSuffix(".jpg")
        val dashIndex = name.lastIndexOf('-')
        val id = if (dashIndex >= 0) name.substring(0, dashIndex) else name
        return PageV2(id)
    }

    private fun saveMetadata() {
        val metadata = DocumentMetadataV2(pages = pages.pages())
        metadataFile.writeText(json.encodeToString<DocumentMetadataV2>(metadata))
    }

    // --- Main API ---

    suspend fun pages(): List<ScanPage> = mutex.withLock {
        pages.pages().mapNotNull {
            runCatching {
                val manualRotation = Rotation.fromDegrees(it.manualRotationDegrees)
                ScanPage(it.id, manualRotation, it.colorMode, it.quadVersion, it.toMetadata())
            }.getOrNull()
        }
    }

    suspend fun add(processed: EncodedImage, source: Jpeg, metadata: PageMetadata, colorMode: ColorMode) =
        mutex.withLock {
            var ts = System.currentTimeMillis()
            if (ts <= lastPageTimestamp) {
                ts = lastPageTimestamp + 1
            }
            lastPageTimestamp = ts
            val id = "$ts"
            val key = PageViewKey(id, Rotation.R0, colorMode, 0)
            baseDir.mkdirs()
            baseImageFile(key).writeBytes(processed.bytes)
            processedImageFile(key).writeBytes(processed.bytes)
            sourceFile(id).writeBytes(source.bytes)
            pages.addOrReplace(
                PageV2(
                    id = id,
                    quad = metadata.normalizedQuad.toSerializable(),
                    baseRotationDegrees = metadata.baseRotation.degrees,
                    manualRotationDegrees = Rotation.R0.degrees,
                    isColored = metadata.autoColorMode == ColorMode.COLOR,
                    colorMode = colorMode,
                    focalLength = metadata.opticalMeasures?.cameraIntrinsics?.focalLength,
                    sensorWidth = metadata.opticalMeasures?.cameraIntrinsics?.sensorWidth,
                    subjectDistance = metadata.opticalMeasures?.subjectDistance,
                    sourceWidth = metadata.sourceSize?.width?.toInt(),
                    sourceHeight = metadata.sourceSize?.height?.toInt(),
                )
            )
            saveMetadata()
            // Pre-populate cache for R0
            imageCache.put(key, CompletableDeferred(processed))
        }

    suspend fun addImportedBitmapPage(bitmap: Bitmap, detectedQuad: Quad? = null) = mutex.withLock {
        var ts = System.currentTimeMillis()
        if (ts <= lastPageTimestamp) {
            ts = lastPageTimestamp + 1
        }
        lastPageTimestamp = ts
        val id = "$ts"
        val key = PageViewKey(id, Rotation.R0, ColorMode.COLOR, 0)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        val bytes = stream.toByteArray()
        baseDir.mkdirs()
        baseImageFile(key).writeBytes(bytes)
        processedImageFile(key).writeBytes(bytes)
        sourceFile(id).writeBytes(bytes)
        val initialQuad = detectedQuad ?: Quad(
            Point(0.0, 0.0),
            Point(1.0, 0.0),
            Point(1.0, 1.0),
            Point(0.0, 1.0),
        )
        pages.addOrReplace(
            PageV2(
                id = id,
                quad = initialQuad.toSerializable(),
                baseRotationDegrees = 0,
                manualRotationDegrees = 0,
                isColored = true,
                colorMode = ColorMode.COLOR,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
            )
        )
        saveMetadata()
        imageCache.put(key, CompletableDeferred(Jpeg(bytes)))
    }

    suspend fun setColorMode(id: String, colorMode: ColorMode) {
        updatePage(id) { page, metadata ->
            PageUpdate(
                updatedPage = page.copy(colorMode = colorMode),
                normalizedQuad = metadata.normalizedQuad,
                colorMode = colorMode,
            )
        }
    }

    suspend fun setUserQuad(id: String, newQuad: Quad) {
        updatePage(id) { page, metadata ->
            PageUpdate(
                updatedPage = page.copy(
                    quadVersion = page.quadVersion + 1,
                    userQuad = newQuad.toSerializable(),
                ),
                normalizedQuad = newQuad,
                colorMode = page.colorMode ?: metadata.autoColorMode,
            )
        }
    }

    private data class PageUpdate(
        val updatedPage: PageV2,
        val normalizedQuad: Quad,
        val colorMode: ColorMode,
    )

    private suspend fun updatePage(
        id: String,
        buildUpdate: (PageV2, PageMetadata) -> PageUpdate
    ) {
        val page = mutex.withLock { pages.get(id) }
        val metadata = page?.toMetadata() ?: return
        val sourceFile = sourceFile(id)
        if (!sourceFile.exists())
            return

        val update = buildUpdate(page, metadata)
        val key = PageViewKey(
            pageId = id,
            rotation = Rotation.R0,
            colorMode = update.colorMode,
            quadVersion = update.updatedPage.quadVersion
        )

        val baseFile = baseImageFile(key)
        val processedFile = processedImageFile(key)
        val editState = loadEditStateFromDisk(id)
        val job = processingJobs.computeIfAbsent(key) {
            scope.async(Dispatchers.IO) {
                baseDir.mkdirs()
                if (!baseFile.exists()) {
                    val sourceJpeg = Jpeg(sourceFile.readBytes())
                    val processedJpeg =
                        transformations.process(
                            sourceJpeg,
                            metadata = metadata.copy(normalizedQuad = update.normalizedQuad),
                            colorMode = update.colorMode
                        )
                    baseFile.writeBytes(processedJpeg.bytes)
                }
                if (!editState.hasAnyEdits) {
                    if (!processedFile.exists()) {
                        baseFile.copyTo(processedFile, overwrite = true)
                    }
                } else {
                    composeBaseToProcessedFile(baseFile, processedFile, update.colorMode, editState)
                }
            }
        }
        try {
            job.await()
        } finally {
            processingJobs.remove(key, job)
        }

        mutex.withLock {
            pages.update(id) { update.updatedPage }
            saveMetadata()
            evictCachesForPage(id)
        }
    }

    suspend fun rotate(id: String, clockwise: Boolean) = mutex.withLock {
        val page = pages.get(id) ?: return@withLock
        val delta = if (clockwise) Rotation.R90 else Rotation.R270
        val newRotation = Rotation.fromDegrees(page.manualRotationDegrees).add(delta)
        pages.update(id) {
            it.copy(manualRotationDegrees = newRotation.degrees)
        }
        saveMetadata()
    }

    suspend fun image(key: PageViewKey): EncodedImage? =
        getOrCompute(imageCache, key, ::computeProcessedImage)

    suspend fun getThumbnail(key: PageViewKey): EncodedImage? =
        getOrCompute(thumbnailCache, key, ::computeThumbnail)

    // --- Cache compute functions ---

    private suspend fun getOrCompute(
        cache: MutableMap<PageViewKey, Deferred<EncodedImage?>>,
        key: PageViewKey,
        compute: suspend (PageViewKey) -> EncodedImage?
    ): EncodedImage? {
        val deferred = cache.computeIfAbsent(key) { k ->
            scope.async(Dispatchers.IO) { compute(k) }
        }
        try {
            return deferred.await()
        } catch (e: Exception) {
            cache.remove(key, deferred)
            throw e
        }
    }

    private suspend fun computeProcessedImage(key: PageViewKey): EncodedImage? =
        withContext(Dispatchers.IO) {
            val baseFile = processedImageFile(key)
            if (!baseFile.exists()) return@withContext null
            val bytes = baseFile.readBytes()
            val baseImage = if (baseFile.extension == "png") Png(bytes) else Jpeg(bytes)
            if (key.rotation == Rotation.R0) {
                baseImage
            } else {
                transformations.rotate(
                    baseImage,
                    key.rotation.degrees)
            }
        }

    private suspend fun computeThumbnail(key: PageViewKey): EncodedImage? =
        withContext(Dispatchers.IO) {
            val processed = getOrCompute(imageCache, key, ::computeProcessedImage)
                ?: return@withContext null
            try {
                transformations.resizeToThumbnail(processed)
            } catch (e: Exception) {
                val message = "Failed to compute thumbnail for ${key.pageId}"
                logger.e("ImageRepository", message, e)
                null
            }
        }

    // --- Other operations ---

    private fun processedImageFileName(id: String, colorMode: ColorMode?, quadVersion: Int) : String {
        val sb = StringBuilder(id)
        if (colorMode != null)
            sb.append(".").append(colorMode.name.lowercase())
        if (quadVersion > 0)
            sb.append(".q").append(quadVersion)

        if (colorMode == ColorMode.BLACK_AND_WHITE)
            sb.append(".png")
        else
            sb.append(".jpg")

        return sb.toString()
    }

    private fun processedImageFile(key: PageViewKey) : File =
        File(processedDir, processedImageFileName(key.pageId, key.colorMode, key.quadVersion))

    private fun baseImageFile(key: PageViewKey): File =
        File(baseDir, processedImageFileName(key.pageId, key.colorMode, key.quadVersion))

    private fun layerFile(id: String): File = File(layersDir, "$id.json")

    fun overlayAssetFile(fileName: String): File = File(overlayAssetsDir, fileName)

    private fun sourceFile(id: String): File =
        File(sourceDir, "$id.jpg")

    fun source(id: String): Jpeg? {
        val file = sourceFile(id)
        return if (file.exists()) Jpeg(file.readBytes()) else null
    }

    suspend fun movePage(id: String, newIndex: Int) = mutex.withLock {
        pages.move(id, newIndex)
        saveMetadata()
    }

    suspend fun delete(id: String) = mutex.withLock {
        pages.delete(id)
        saveMetadata()
        sourceFile(id).delete()
        processedDir.listFiles()
            ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
            ?.forEach { it.delete() }
        baseDir.listFiles()
            ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
            ?.forEach { it.delete() }
        layerFile(id).delete()
        refreshEditStatesSnapshot()
        evictCachesForPage(id)
    }

    suspend fun clear() = mutex.withLock {
        pages.clear()
        saveMetadata()
        sourceDir.listFiles()?.forEach { it.delete() }
        processedDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        baseDir.listFiles()?.forEach { it.delete() }
        layersDir.listFiles()?.forEach { it.delete() }
        overlayAssetsDir.listFiles()?.forEach { it.delete() }
        saveMetadata()
        _pageEditStates.value = emptyMap()
        synchronized(imageCache) { imageCache.clear() }
        synchronized(thumbnailCache) { thumbnailCache.clear() }
    }

    suspend fun reloadFromDisk() = mutex.withLock {
        sourceDir.mkdirs()
        processedDir.mkdirs()
        baseDir.mkdirs()
        layersDir.mkdirs()
        overlayAssetsDir.mkdirs()
        pages = PageStore(loadPages())
        _pageEditStates.value = loadAllEditStatesFromDisk()
        synchronized(imageCache) { imageCache.clear() }
        synchronized(thumbnailCache) { thumbnailCache.clear() }
    }

    suspend fun applyBitmapToPage(id: String, bitmap: Bitmap) = mutex.withLock {
        val page = pages.pages().firstOrNull { it.id == id } ?: return@withLock
        val newQuadVersion = page.quadVersion + 1
        val newKey = PageViewKey(id, Rotation.R0, page.colorMode, newQuadVersion)
        val file = processedImageFile(newKey)
        val baseFile = baseImageFile(newKey)
        val stream = ByteArrayOutputStream()
        if (page.colorMode == ColorMode.BLACK_AND_WHITE) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        val bytes = stream.toByteArray()
        baseDir.mkdirs()
        baseFile.writeBytes(bytes)
        file.writeBytes(bytes)
        layerFile(id).delete()
        val encoded = if (page.colorMode == ColorMode.BLACK_AND_WHITE) Png(bytes) else Jpeg(bytes)
        pages.update(id) {
            it.copy(
                manualRotationDegrees = Rotation.R0.degrees,
                quadVersion = newQuadVersion
            )
        }
        saveMetadata()
        refreshEditStatesSnapshot()
        evictCachesForPage(id)
        synchronized(imageCache) {
            imageCache.put(newKey, CompletableDeferred(encoded))
        }
    }

    // --- Non-Destructive Layer Management ---

    fun getEditState(pageId: String): PageEditState =
        _pageEditStates.value[pageId] ?: PageEditState()

    suspend fun addImageOverlay(
        pageId: String,
        bitmap: Bitmap,
        label: String,
        isStamp: Boolean,
        visualNormX: Float,
        visualNormY: Float,
        scaleFactor: Float,
        visualRotationDeg: Float,
        opacity: Float,
    ) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
        val basePt = LayerCoordinateMapper.visualToBasePoint(visualNormX, visualNormY, displayRot)
        val baseRotationDeg = visualRotationDeg + LayerCoordinateMapper.rotationDeltaToBaseDeg(displayRot)

        overlayAssetsDir.mkdirs()
        val assetFileName = "ovl_${UUID.randomUUID()}.png"
        FileOutputStream(overlayAssetFile(assetFileName)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val adjustedScale = if (displayRot == Rotation.R90 || displayRot == Rotation.R270) {
            // Convert scale relative to visual width into scale relative to R0 base width
            val key = PageViewKey(pageId, Rotation.R0, page.colorMode, page.quadVersion)
            val baseFile = ensureCleanBaseExistsLocked(page, key)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(baseFile.absolutePath, opts)
            val baseW = opts.outWidth.toFloat().coerceAtLeast(1f)
            val baseH = opts.outHeight.toFloat().coerceAtLeast(1f)
            (scaleFactor * (baseH / baseW)).coerceIn(0.08f, 0.90f)
        } else {
            scaleFactor
        }

        val overlay = ImageOverlayLayer(
            assetFileName = assetFileName,
            label = label,
            isStamp = isStamp,
            normX = basePt.x,
            normY = basePt.y,
            scaleFactor = adjustedScale,
            rotationDeg = baseRotationDeg,
            opacity = opacity,
        )
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(overlays = state.overlays + overlay)
        }
    }

    suspend fun updateImageOverlay(
        pageId: String,
        overlayId: String,
        visualNormX: Float,
        visualNormY: Float,
        scaleFactor: Float,
        visualRotationDeg: Float,
        opacity: Float,
    ) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
        val basePt = LayerCoordinateMapper.visualToBasePoint(visualNormX, visualNormY, displayRot)
        val baseRotationDeg = visualRotationDeg + LayerCoordinateMapper.rotationDeltaToBaseDeg(displayRot)
        val adjustedScale = if (displayRot == Rotation.R90 || displayRot == Rotation.R270) {
            val key = PageViewKey(pageId, Rotation.R0, page.colorMode, page.quadVersion)
            val baseFile = ensureCleanBaseExistsLocked(page, key)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(baseFile.absolutePath, opts)
            val baseW = opts.outWidth.toFloat().coerceAtLeast(1f)
            val baseH = opts.outHeight.toFloat().coerceAtLeast(1f)
            (scaleFactor * (baseH / baseW)).coerceIn(0.08f, 0.90f)
        } else {
            scaleFactor
        }

        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(
                overlays = state.overlays.map { ov ->
                    if (ov.id == overlayId) {
                        ov.copy(
                            normX = basePt.x,
                            normY = basePt.y,
                            scaleFactor = adjustedScale,
                            rotationDeg = baseRotationDeg,
                            opacity = opacity,
                        )
                    } else ov
                }
            )
        }
    }

    suspend fun removeImageOverlay(pageId: String, overlayId: String) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(overlays = state.overlays.filterNot { it.id == overlayId })
        }
    }

    data class VisualOverlayEditData(
        val overlayId: String,
        val overlayBitmap: Bitmap,
        val label: String,
        val isStamp: Boolean,
        val visualNormX: Float,
        val visualNormY: Float,
        val visualScaleFactor: Float,
        val visualRotationDeg: Float,
        val opacity: Float,
        val pagePreviewWithoutOverlay: Bitmap?,
    )

    suspend fun getVisualOverlayForEditing(pageId: String, overlayId: String): VisualOverlayEditData? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val page = pages.get(pageId) ?: return@withContext null
                val editState = loadEditStateFromDisk(pageId)
                val overlay = editState.overlays.firstOrNull { it.id == overlayId } ?: return@withContext null
                val assetFile = overlayAssetFile(overlay.assetFileName)
                if (!assetFile.exists()) return@withContext null
                val overlayBmp = BitmapFactory.decodeFile(assetFile.absolutePath) ?: return@withContext null

                val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
                val visualPt = LayerCoordinateMapper.baseToVisualPoint(overlay.normX, overlay.normY, displayRot)
                var visualRot = (overlay.rotationDeg - LayerCoordinateMapper.rotationDeltaToBaseDeg(displayRot)) % 360f
                if (visualRot > 180f) visualRot -= 360f
                if (visualRot < -180f) visualRot += 360f

                val keyR0 = PageViewKey(pageId, Rotation.R0, page.colorMode, page.quadVersion)
                val baseFile = ensureCleanBaseExistsLocked(page, keyR0)

                val visualScale = if (displayRot == Rotation.R90 || displayRot == Rotation.R270) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(baseFile.absolutePath, opts)
                    val baseW = opts.outWidth.toFloat().coerceAtLeast(1f)
                    val baseH = opts.outHeight.toFloat().coerceAtLeast(1f)
                    (overlay.scaleFactor * (baseW / baseH)).coerceIn(0.12f, 0.85f)
                } else {
                    overlay.scaleFactor
                }

                val previewWithoutOverlay = if (baseFile.exists()) {
                    val baseBmp = BitmapFactory.decodeFile(baseFile.absolutePath)
                    if (baseBmp != null) {
                        val strippedState = editState.copy(
                            overlays = editState.overlays.filterNot { it.id == overlayId }
                        )
                        val composed = try {
                            PageCompositor.compose(
                                cleanBase = baseBmp,
                                editState = strippedState,
                                resolveOverlayFile = ::overlayAssetFile,
                            )
                        } finally {
                            baseBmp.recycle()
                        }
                        if (displayRot != Rotation.R0) {
                            val rotated = PageCompositor.rotateBitmap(composed, displayRot)
                            composed.recycle()
                            rotated
                        } else {
                            composed
                        }
                    } else null
                } else null

                VisualOverlayEditData(
                    overlayId = overlay.id,
                    overlayBitmap = overlayBmp,
                    label = overlay.label,
                    isStamp = overlay.isStamp,
                    visualNormX = visualPt.x,
                    visualNormY = visualPt.y,
                    visualScaleFactor = visualScale,
                    visualRotationDeg = visualRot,
                    opacity = overlay.opacity,
                    pagePreviewWithoutOverlay = previewWithoutOverlay,
                )
            }
        }

    suspend fun renderPageWithoutLayers(
        pageId: String,
        excludeFilter: Boolean = false,
        excludeAnnotations: Boolean = false,
        excludeEraser: Boolean = false,
    ): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val page = pages.get(pageId) ?: return@withContext null
            val editState = loadEditStateFromDisk(pageId)
            val strippedState = editState.copy(
                filterLayer = if (excludeFilter) null else editState.filterLayer,
                annotations = if (excludeAnnotations) emptyList() else editState.annotations,
                eraserStrokes = if (excludeEraser) emptyList() else editState.eraserStrokes,
            )
            val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
            val keyR0 = PageViewKey(pageId, Rotation.R0, page.colorMode, page.quadVersion)
            val baseFile = ensureCleanBaseExistsLocked(page, keyR0)
            if (!baseFile.exists()) return@withContext null
            val baseBmp = BitmapFactory.decodeFile(baseFile.absolutePath) ?: return@withContext null
            val composed = try {
                PageCompositor.compose(
                    cleanBase = baseBmp,
                    editState = strippedState,
                    resolveOverlayFile = ::overlayAssetFile,
                )
            } finally {
                baseBmp.recycle()
            }
            if (displayRot != Rotation.R0) {
                val rotated = PageCompositor.rotateBitmap(composed, displayRot)
                composed.recycle()
                rotated
            } else {
                composed
            }
        }
    }

    suspend fun setFilterAndAdjustments(
        pageId: String,
        preset: ProFilterPreset,
        adjustments: ImageAdjustments,
    ) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        val layer = if (preset == ProFilterPreset.ORIGINAL && adjustments.isDefault) {
            null
        } else {
            FilterLayer(
                presetName = preset.name,
                brightness = adjustments.brightness,
                contrast = adjustments.contrast,
                sharpness = adjustments.sharpness,
                saturation = adjustments.saturation,
                warmth = adjustments.warmth,
                exposure = adjustments.exposure,
                fade = adjustments.fade,
            )
        }
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(filterLayer = layer)
        }
    }

    suspend fun setWatermark(pageId: String, watermark: WatermarkLayer?) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(watermark = watermark?.takeIf { it.text.isNotBlank() })
        }
    }

    suspend fun addCleanupEffect(pageId: String, type: CleanupEffectType) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            val filtered = state.cleanupEffects.filterNot { it.type == type }
            state.copy(cleanupEffects = filtered + CleanupEffectLayer(type = type))
        }
    }

    suspend fun removeCleanupEffect(pageId: String, effectId: String) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(cleanupEffects = state.cleanupEffects.filterNot { it.id == effectId })
        }
    }

    suspend fun setVisualAnnotations(pageId: String, annotationsInDisplayCoords: List<SerializedAnnotation>) =
        mutex.withLock {
            val page = pages.get(pageId) ?: return@withLock
            val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
            val converted = annotationsInDisplayCoords.map { ann ->
                when (ann) {
                    is SerializedAnnotation.PenStrokeLayer -> ann.copy(
                        points = ann.points.map { LayerCoordinateMapper.visualToBasePoint(it.x, it.y, displayRot) }
                    )
                    is SerializedAnnotation.RedactBoxLayer -> {
                        val p1 = LayerCoordinateMapper.visualToBasePoint(ann.leftNorm, ann.topNorm, displayRot)
                        val p2 = LayerCoordinateMapper.visualToBasePoint(ann.rightNorm, ann.bottomNorm, displayRot)
                        ann.copy(
                            leftNorm = minOf(p1.x, p2.x),
                            topNorm = minOf(p1.y, p2.y),
                            rightNorm = maxOf(p1.x, p2.x),
                            bottomNorm = maxOf(p1.y, p2.y),
                        )
                    }
                    is SerializedAnnotation.TextAnnotationLayer -> {
                        val pt = LayerCoordinateMapper.visualToBasePoint(ann.xNorm, ann.yNorm, displayRot)
                        ann.copy(xNorm = pt.x, yNorm = pt.y)
                    }
                    is SerializedAnnotation.BadgeStampLayer -> {
                        val pt = LayerCoordinateMapper.visualToBasePoint(ann.xNorm, ann.yNorm, displayRot)
                        ann.copy(
                            xNorm = pt.x,
                            yNorm = pt.y,
                            rotationDeg = ann.rotationDeg + LayerCoordinateMapper.rotationDeltaToBaseDeg(displayRot),
                        )
                    }
                }
            }
            mutateEditStateAndRecomposeLocked(page) { state ->
                state.copy(annotations = converted)
            }
        }

    suspend fun removeAnnotation(pageId: String, annotationId: String) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(annotations = state.annotations.filterNot { it.id == annotationId })
        }
    }

    suspend fun setVisualEraserStrokes(pageId: String, strokesInDisplayCoords: List<EraserLayer>) =
        mutex.withLock {
            val page = pages.get(pageId) ?: return@withLock
            val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
            val converted = strokesInDisplayCoords.map { stroke ->
                stroke.copy(
                    points = stroke.points.map {
                        LayerCoordinateMapper.visualToBasePoint(it.x, it.y, displayRot)
                    }
                )
            }
            mutateEditStateAndRecomposeLocked(page) { state ->
                state.copy(eraserStrokes = converted)
            }
        }

    suspend fun removeEraserStroke(pageId: String, strokeId: String) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { state ->
            state.copy(eraserStrokes = state.eraserStrokes.filterNot { it.id == strokeId })
        }
    }

    suspend fun clearAllEditsOnPage(pageId: String) = mutex.withLock {
        val page = pages.get(pageId) ?: return@withLock
        mutateEditStateAndRecomposeLocked(page) { PageEditState() }
    }

    suspend fun getCleanDisplayBitmap(pageId: String): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val page = pages.get(pageId) ?: return@withContext null
            val keyR0 = PageViewKey(page.id, Rotation.R0, page.colorMode, page.quadVersion)
            val baseFile = ensureCleanBaseExistsLocked(page, keyR0)
            if (!baseFile.exists()) return@withContext null
            val baseBmp = BitmapFactory.decodeFile(baseFile.absolutePath) ?: return@withContext null
            val displayRot = Rotation.fromDegrees(page.manualRotationDegrees)
            if (displayRot != Rotation.R0) {
                val rotated = PageCompositor.rotateBitmap(baseBmp, displayRot)
                baseBmp.recycle()
                rotated
            } else {
                baseBmp
            }
        }
    }

    suspend fun applyFilterToPages(
        pageIds: Set<String>?,
        preset: ProFilterPreset,
        adjustments: ImageAdjustments,
    ) = mutex.withLock {
        val layer = if (preset == ProFilterPreset.ORIGINAL && adjustments.isDefault) {
            null
        } else {
            FilterLayer(
                presetName = preset.name,
                brightness = adjustments.brightness,
                contrast = adjustments.contrast,
                sharpness = adjustments.sharpness,
                saturation = adjustments.saturation,
                warmth = adjustments.warmth,
                exposure = adjustments.exposure,
                fade = adjustments.fade,
            )
        }
        val targets = pages.pages().filter { pageIds == null || it.id in pageIds }
        targets.forEach { page ->
            mutateEditStateAndRecomposeLocked(page) { state ->
                state.copy(filterLayer = layer)
            }
        }
    }

    suspend fun applyWatermarkToPages(
        pageIds: Set<String>?,
        watermark: WatermarkLayer?,
    ) = mutex.withLock {
        val clean = watermark?.takeIf { it.text.isNotBlank() }
        val targets = pages.pages().filter { pageIds == null || it.id in pageIds }
        targets.forEach { page ->
            mutateEditStateAndRecomposeLocked(page) { state ->
                state.copy(watermark = clean)
            }
        }
    }

    suspend fun applyCleanupToPages(
        pageIds: Set<String>?,
        type: CleanupEffectType,
    ) = mutex.withLock {
        val targets = pages.pages().filter { pageIds == null || it.id in pageIds }
        targets.forEach { page ->
            mutateEditStateAndRecomposeLocked(page) { state ->
                val filtered = state.cleanupEffects.filterNot { it.type == type }
                state.copy(cleanupEffects = filtered + CleanupEffectLayer(type = type))
            }
        }
    }

    suspend fun rotatePages(pageIds: Collection<String>, clockwise: Boolean) = mutex.withLock {
        val idSet = pageIds.toSet()
        val delta = if (clockwise) Rotation.R90 else Rotation.R270
        pages.pages().filter { it.id in idSet }.forEach { page ->
            val newRot = Rotation.fromDegrees(page.manualRotationDegrees).add(delta)
            pages.update(page.id) { it.copy(manualRotationDegrees = newRot.degrees) }
        }
        saveMetadata()
    }

    suspend fun deletePages(pageIds: Collection<String>) = mutex.withLock {
        pageIds.distinct().forEach { id ->
            pages.delete(id)
            sourceFile(id).delete()
            processedDir.listFiles()
                ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
                ?.forEach { it.delete() }
            baseDir.listFiles()
                ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
                ?.forEach { it.delete() }
            layerFile(id).delete()
            evictCachesForPage(id)
        }
        saveMetadata()
        refreshEditStatesSnapshot()
    }

    suspend fun getProcessedPageFiles(pageIds: List<String>): List<Pair<File, Int>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val byId = pages.pages().associateBy { it.id }
            pageIds.mapNotNull { id ->
                val page = byId[id] ?: return@mapNotNull null
                val keyR0 = PageViewKey(page.id, Rotation.R0, page.colorMode, page.quadVersion)
                val baseFile = ensureCleanBaseExistsLocked(page, keyR0)
                val processedFile = processedImageFile(keyR0)
                if (!processedFile.exists() && baseFile.exists()) {
                    val state = loadEditStateFromDisk(page.id)
                    composeBaseToProcessedFile(baseFile, processedFile, page.colorMode, state)
                }
                if (processedFile.exists()) processedFile to page.manualRotationDegrees else null
            }
        }
    }

    private fun loadEditStateFromDisk(pageId: String): PageEditState {
        val file = layerFile(pageId)
        if (!file.exists()) return PageEditState()
        return runCatching { json.decodeFromString<PageEditState>(file.readText()) }
            .getOrElse { PageEditState() }
    }

    private fun saveEditStateToDisk(pageId: String, state: PageEditState) {
        layersDir.mkdirs()
        val file = layerFile(pageId)
        if (!state.hasAnyEdits) {
            file.delete()
        } else {
            file.writeText(json.encodeToString(state))
        }
    }

    private fun loadAllEditStatesFromDisk(): Map<String, PageEditState> {
        val result = LinkedHashMap<String, PageEditState>()
        pages.pages().forEach { page ->
            val state = loadEditStateFromDisk(page.id)
            if (state.hasAnyEdits) {
                result[page.id] = state
            }
        }
        return result
    }

    private fun refreshEditStatesSnapshot() {
        _pageEditStates.value = loadAllEditStatesFromDisk()
    }

    private fun ensureCleanBaseExistsLocked(page: PageV2, keyR0: PageViewKey): File {
        baseDir.mkdirs()
        val baseFile = baseImageFile(keyR0)
        if (!baseFile.exists()) {
            val processed = processedImageFile(keyR0)
            if (processed.exists()) {
                processed.copyTo(baseFile, overwrite = true)
            } else if (sourceFile(page.id).exists()) {
                val meta = page.toMetadata()
                if (meta != null) {
                    val srcJpeg = Jpeg(sourceFile(page.id).readBytes())
                    val rendered = transformations.process(srcJpeg, meta, page.colorMode ?: meta.autoColorMode)
                    baseFile.writeBytes(rendered.bytes)
                } else {
                    sourceFile(page.id).copyTo(baseFile, overwrite = true)
                }
            }
        }
        return baseFile
    }

    private fun mutateEditStateAndRecomposeLocked(
        page: PageV2,
        transform: (PageEditState) -> PageEditState,
    ) {
        val current = loadEditStateFromDisk(page.id)
        val updated = transform(current)
        saveEditStateToDisk(page.id, updated)

        val keyR0 = PageViewKey(page.id, Rotation.R0, page.colorMode, page.quadVersion)
        val baseFile = ensureCleanBaseExistsLocked(page, keyR0)
        val processedFile = processedImageFile(keyR0)
        if (baseFile.exists()) {
            composeBaseToProcessedFile(baseFile, processedFile, page.colorMode, updated)
        }
        refreshEditStatesSnapshot()
        evictCachesForPage(page.id)
    }

    private fun composeBaseToProcessedFile(
        baseFile: File,
        processedFile: File,
        colorMode: ColorMode?,
        editState: PageEditState,
    ) {
        if (!editState.hasAnyEdits) {
            baseFile.copyTo(processedFile, overwrite = true)
            return
        }
        val baseBmp = BitmapFactory.decodeFile(baseFile.absolutePath) ?: return
        val composited = try {
            PageCompositor.compose(
                cleanBase = baseBmp,
                editState = editState,
                resolveOverlayFile = ::overlayAssetFile,
            )
        } finally {
            baseBmp.recycle()
        }
        try {
            FileOutputStream(processedFile).use { out ->
                if (colorMode == ColorMode.BLACK_AND_WHITE && !editState.hasActiveFilter && editState.overlays.isEmpty() && editState.annotations.isEmpty()) {
                    composited.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    composited.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        } finally {
            composited.recycle()
        }
    }

    private fun evictCachesForPage(pageId: String) {
        synchronized(imageCache) {
            imageCache.keys.filter { it.pageId == pageId }.toList().forEach { imageCache.remove(it) }
        }
        synchronized(thumbnailCache) {
            thumbnailCache.keys.filter { it.pageId == pageId }.toList().forEach { thumbnailCache.remove(it) }
        }
    }

    // --- Editable PDF Project Archive Export / Import ---

    suspend fun exportProjectArchive(
        targetZipFile: File,
        projectTitle: String = "",
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val activePages = pages.pages()
            if (activePages.isEmpty()) return@withContext false

            val entries = mutableListOf<EditablePdfPageEntry>()
            val overlayAssetNames = linkedSetOf<String>()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZipFile))).use { zos ->
                for (page in activePages) {
                    val keyR0 = PageViewKey(page.id, Rotation.R0, page.colorMode, page.quadVersion)
                    val baseFile = ensureCleanBaseExistsLocked(page, keyR0)
                    if (!baseFile.exists()) continue

                    val baseEntryName = "base/${baseFile.name}"
                    zos.putNextEntry(ZipEntry(baseEntryName))
                    FileInputStream(baseFile).use { it.copyTo(zos) }
                    zos.closeEntry()

                    val srcFile = sourceFile(page.id)
                    val sourceEntryName = if (srcFile.exists()) {
                        val name = "sources/${srcFile.name}"
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(srcFile).use { it.copyTo(zos) }
                        zos.closeEntry()
                        srcFile.name
                    } else null

                    val editState = loadEditStateFromDisk(page.id)
                    editState.overlays.forEach { overlayAssetNames.add(it.assetFileName) }

                    entries.add(
                        EditablePdfPageEntry(
                            id = page.id,
                            baseFileName = baseFile.name,
                            sourceFileName = sourceEntryName,
                            baseRotationDegrees = page.baseRotationDegrees,
                            manualRotationDegrees = page.manualRotationDegrees,
                            quad = page.quad,
                            userQuad = page.userQuad,
                            isColored = page.isColored ?: true,
                            colorMode = page.colorMode ?: ColorMode.COLOR,
                            sourceWidth = page.sourceWidth,
                            sourceHeight = page.sourceHeight,
                            editState = editState,
                        )
                    )
                }

                for (assetName in overlayAssetNames) {
                    val assetFile = overlayAssetFile(assetName)
                    if (assetFile.exists()) {
                        zos.putNextEntry(ZipEntry("assets/$assetName"))
                        FileInputStream(assetFile).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                val manifest = EditablePdfProjectManifest(
                    formatVersion = 1,
                    title = projectTitle,
                    pages = entries,
                )
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("project.json"))
                zos.write(manifestBytes)
                zos.closeEntry()
            }
            targetZipFile.exists() && targetZipFile.length() > 0L
        }
    }

    suspend fun importProjectArchive(zipFile: File): String? = mutex.withLock {
        if (!zipFile.exists() || zipFile.length() == 0L) return@withLock null
        withContext(Dispatchers.IO) {
            val tempExtractDir = File(processedDir.parentFile, "tmp_proj_import_${System.currentTimeMillis()}").apply {
                mkdirs()
            }
            try {
                var manifestJson: String? = null
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val safeName = entry.name
                        if (!entry.isDirectory && !safeName.contains("..")) {
                            if (safeName == "project.json") {
                                manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                val outFile = File(tempExtractDir, safeName)
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val rawManifest = manifestJson ?: return@withContext null
                val manifest = json.decodeFromString<EditablePdfProjectManifest>(rawManifest)
                if (manifest.pages.isEmpty()) return@withContext null

                // Clear current session cleanly
                pages.clear()
                sourceDir.listFiles()?.forEach { it.delete() }
                processedDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                baseDir.listFiles()?.forEach { it.delete() }
                layersDir.listFiles()?.forEach { it.delete() }
                overlayAssetsDir.listFiles()?.forEach { it.delete() }

                val tempAssetsDir = File(tempExtractDir, "assets")
                if (tempAssetsDir.exists()) {
                    tempAssetsDir.listFiles()?.forEach { f ->
                        f.copyTo(overlayAssetFile(f.name), overwrite = true)
                    }
                }

                manifest.pages.forEach { entry ->
                    val extBase = File(tempExtractDir, "base/${entry.baseFileName}")
                    if (!extBase.exists()) return@forEach

                    val pageV2 = PageV2(
                        id = entry.id,
                        baseRotationDegrees = entry.baseRotationDegrees,
                        manualRotationDegrees = entry.manualRotationDegrees,
                        quad = entry.quad,
                        quadVersion = 0,
                        userQuad = entry.userQuad,
                        isColored = entry.isColored,
                        colorMode = entry.colorMode,
                        sourceWidth = entry.sourceWidth,
                        sourceHeight = entry.sourceHeight,
                    )
                    val keyR0 = PageViewKey(entry.id, Rotation.R0, entry.colorMode, 0)
                    val dstBase = baseImageFile(keyR0)
                    dstBase.parentFile?.mkdirs()
                    extBase.copyTo(dstBase, overwrite = true)

                    entry.sourceFileName?.let { srcName ->
                        val extSrc = File(tempExtractDir, "sources/$srcName")
                        if (extSrc.exists()) {
                            extSrc.copyTo(sourceFile(entry.id), overwrite = true)
                        }
                    }

                    saveEditStateToDisk(entry.id, entry.editState)
                    composeBaseToProcessedFile(dstBase, processedImageFile(keyR0), entry.colorMode, entry.editState)
                    pages.addOrReplace(pageV2)
                }

                saveMetadata()
                refreshEditStatesSnapshot()
                synchronized(imageCache) { imageCache.clear() }
                synchronized(thumbnailCache) { thumbnailCache.clear() }
                manifest.title
            } finally {
                tempExtractDir.deleteRecursively()
            }
        }
    }

    suspend fun mergeIdCardPages(
        frontId: String,
        backId: String,
        mergedBitmap: Bitmap,
        removeOriginals: Boolean
    ): Int = mutex.withLock {
        val newId = "${System.currentTimeMillis()}"
        val key = PageViewKey(newId, Rotation.R0, ColorMode.COLOR, 0)
        val stream = ByteArrayOutputStream()
        mergedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        val bytes = stream.toByteArray()
        baseDir.mkdirs()
        baseImageFile(key).writeBytes(bytes)
        processedImageFile(key).writeBytes(bytes)
        sourceFile(newId).writeBytes(bytes)

        if (removeOriginals) {
            listOf(frontId, backId).distinct().forEach { id ->
                pages.delete(id)
                sourceFile(id).delete()
                processedDir.listFiles()
                    ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
                    ?.forEach { it.delete() }
                baseDir.listFiles()
                    ?.filter { it.name.startsWith("$id.") || it.name.startsWith("$id-") }
                    ?.forEach { it.delete() }
                layerFile(id).delete()
            }
        }

        val newPage = PageV2(
            id = newId,
            quad = NormalizedQuad(
                org.fairscan.app.data.PointD(0.0, 0.0),
                org.fairscan.app.data.PointD(1.0, 0.0),
                org.fairscan.app.data.PointD(1.0, 1.0),
                org.fairscan.app.data.PointD(0.0, 1.0),
            ),
            baseRotationDegrees = 0,
            manualRotationDegrees = 0,
            isColored = true,
            colorMode = ColorMode.COLOR,
            sourceWidth = mergedBitmap.width,
            sourceHeight = mergedBitmap.height,
        )
        pages.addOrReplace(newPage)
        saveMetadata()
        refreshEditStatesSnapshot()
        val encoded = Jpeg(bytes)
        synchronized(imageCache) {
            imageCache.put(key, CompletableDeferred(encoded))
        }
        (pages.pages().size - 1).coerceAtLeast(0)
    }

    // --- Legacy migration ---

    data class DiskPageFiles(
        val base: File?,
        val rotated: List<File>
    )

    // Legacy normalization strategy:
    // If only rotated files exist, keep ONE arbitrarily as base (id.jpg)
    // and discard the others. We intentionally sacrifice exact rotation
    // fidelity to restore a coherent model.
    private fun normalizeLegacyFiles() {
        val jpgs = processedDir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()
        val byId = jpgs.groupBy { file ->
            val name = file.name.removeSuffix(".jpg")
            val dash = name.lastIndexOf('-')
            if (dash >= 0) name.substring(0, dash) else name
        }
        val pages = byId.mapValues { (_, files) ->
            val base = files.find { !it.name.contains('-') }
            val rotated = files.filter { it.name.contains('-') }
            DiskPageFiles(base, rotated)
        }
        pages.forEach { (id, files) ->
            if (files.base == null && files.rotated.isNotEmpty()) {
                val sortedRotatedFiles = files.rotated.sortedBy { it.name }
                val legacyFile = sortedRotatedFiles.first()
                val target = File(processedDir, "$id.jpg")
                if (legacyFile.renameTo(target)) {
                    sortedRotatedFiles.drop(1).forEach { it.delete() }
                }
            }
        }
    }

    fun lastAddedSourceFile(): File? {
        val sourceFiles = sourceDir.listFiles()?.filter { it.extension == "jpg" }
        if (sourceFiles.isNullOrEmpty()) {
            return null
        }
        return sourceFiles.maxByOrNull { it.lastModified() }
    }
}

fun Quad.toSerializable(): NormalizedQuad =
    NormalizedQuad(
        topLeft = PointD(topLeft.x, topLeft.y),
        topRight = PointD(topRight.x, topRight.y),
        bottomRight = PointD(bottomRight.x, bottomRight.y),
        bottomLeft = PointD(bottomLeft.x, bottomLeft.y)
    )

fun NormalizedQuad.toQuad(): Quad =
    Quad(
        Point(topLeft.x, topLeft.y),
        Point(topRight.x, topRight.y),
        Point(bottomRight.x, bottomRight.y),
        Point(bottomLeft.x, bottomLeft.y)
    )

fun PageV2.toMetadata(): PageMetadata? {
    if (quad == null || isColored == null) return null
    val cameraIntrinsics = cameraIntrinsics(focalLength, sensorWidth)
    val sourceSize =
        if (sourceWidth != null && sourceHeight != null)
            ImageSize(sourceWidth, sourceHeight)
        else
            null
    return PageMetadata(
        (userQuad ?: quad).toQuad(),
        Rotation.fromDegrees(baseRotationDegrees),
        if (isColored) ColorMode.COLOR else ColorMode.GRAYSCALE,
        sourceSize,
        cameraIntrinsics?.let { OpticalMeasures(it, subjectDistance) },
    )
}
