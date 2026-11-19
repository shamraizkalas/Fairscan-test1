package org.fairscan.app.camscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.ZoomState
import net.engawapg.lib.zoomable.zoomable
import org.fairscan.app.R
import org.fairscan.app.cloud.CloudAiGateway
import org.fairscan.app.cloud.HybridConnectionState
import org.fairscan.app.cloud.HybridEngineBadge
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.ui.screens.document.MagicEraserCanvasOverlay
import org.fairscan.app.ui.uriForFile
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

private data class AiPromptSuggestion(
    val label: String,
    val prompt: String,
)

private val AI_PROMPT_SUGGESTIONS = listOf(
    AiPromptSuggestion("Golden Hour + Bokeh", "Warm golden hour with portrait bokeh"),
    AiPromptSuggestion("White Studio", "Auto enhance on clean white background"),
    AiPromptSuggestion("Cutout PNG", "Isolate subject transparent cutout"),
    AiPromptSuggestion("Shadow Fix + Crisp", "Fix shadows and sharpen crisp"),
    AiPromptSuggestion("Oil Painting", "Painterly oil paint style with color pop"),
    AiPromptSuggestion("Pencil Sketch", "Charcoal pencil sketch"),
    AiPromptSuggestion("Cartoon Ink", "Cartoon ink style"),
    AiPromptSuggestion("Noir B&W", "Dramatic noir b&w contrast"),
    AiPromptSuggestion("2× Super Res", "Auto enhance and 2x upscale sharp"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiPhotoEditorDialog(
    initialBitmap: Bitmap?,
    isDocumentStudioMode: Boolean,
    savedDocuments: List<SavedDocumentEntity> = emptyList(),
    libraryRepository: LibraryRepository? = null,
    hybridState: HybridConnectionState = HybridConnectionState(),
    cloudAiGateway: CloudAiGateway? = null,
    onDismiss: () -> Unit,
    onApplyToDocument: ((Bitmap) -> Unit)? = null,
    onOpenAsNewDocument: ((title: String, bitmap: Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usePersonalByokKey by remember(hybridState.isByokOnlineReady, cloudAiGateway) {
        mutableStateOf(false)
    }
    var cloudErrorMessage by remember { mutableStateOf<String?>(null) }
    var lastCloudRetry by remember { mutableStateOf<(() -> Unit)?>(null) }

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bgBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cachedMask by remember { mutableStateOf<Bitmap?>(null) }

    val undoStack = remember { mutableStateListOf<Bitmap>() }
    val redoStack = remember { mutableStateListOf<Bitmap>() }

    var activeMode by remember { mutableStateOf(AiPhotoToolMode.PROMPT) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingLabel by remember { mutableStateOf("Running AI…") }
    var isComparingOriginal by remember { mutableStateOf(false) }

    // AI Prompt state
    var promptText by remember { mutableStateOf("") }
    var lastPlan by remember { mutableStateOf<PromptEditPlan?>(null) }

    // Auto Fix state
    var enhancePreset by remember { mutableStateOf(AiEnhancePreset.BALANCED) }
    var enhanceStrength by remember { mutableFloatStateOf(0.85f) }

    // Background studio state
    var bgEffect by remember { mutableStateOf(AiBackgroundEffect.NONE) }
    var blurIntensity by remember { mutableFloatStateOf(0.68f) }

    // Heal & Upscale state
    val eraserStrokes = remember { mutableStateListOf<EraserStroke>() }
    var brushRadiusNorm by remember { mutableFloatStateOf(0.036f) }
    var sharpenStrength by remember { mutableFloatStateOf(0.55f) }

    var showSavedDocPicker by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    fun loadNewSourceBitmap(src: Bitmap) {
        scope.launch {
            isProcessing = true
            processingLabel = "Preparing image…"
            val prepared = withContext(Dispatchers.Default) {
                OfflineAiPhotoEngine.createWorkingBitmap(src)
            }
            originalBitmap = prepared
            workingBitmap = prepared
            bgBaseBitmap = prepared
            cachedMask = null
            undoStack.clear()
            redoStack.clear()
            eraserStrokes.clear()
            bgEffect = AiBackgroundEffect.NONE
            lastPlan = null
            isProcessing = false
        }
    }

    LaunchedEffect(initialBitmap) {
        if (initialBitmap != null && originalBitmap == null) {
            loadNewSourceBitmap(initialBitmap)
        }
    }

    fun pushUndoSnapshot(prev: Bitmap) {
        if (undoStack.size >= 6) {
            undoStack.removeAt(0)
        }
        undoStack.add(prev)
        redoStack.clear()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        val current = workingBitmap
        if (current != null) {
            if (redoStack.size >= 6) redoStack.removeAt(0)
            redoStack.add(current)
        }
        val restored = undoStack.removeAt(undoStack.lastIndex)
        workingBitmap = restored
        bgBaseBitmap = restored
        cachedMask = null
        bgEffect = AiBackgroundEffect.NONE
    }

    fun performRedo() {
        if (redoStack.isEmpty()) return
        val current = workingBitmap
        if (current != null) {
            if (undoStack.size >= 6) undoStack.removeAt(0)
            undoStack.add(current)
        }
        val next = redoStack.removeAt(redoStack.lastIndex)
        workingBitmap = next
        bgBaseBitmap = next
        cachedMask = null
        bgEffect = AiBackgroundEffect.NONE
    }

    fun commitProcessedBitmap(
        nextBitmap: Bitmap,
        invalidateMask: Boolean = true,
        resetBgState: Boolean = true,
    ) {
        val current = workingBitmap
        if (current != null) {
            pushUndoSnapshot(current)
        }
        workingBitmap = nextBitmap
        if (resetBgState) {
            bgBaseBitmap = nextBitmap
            bgEffect = AiBackgroundEffect.NONE
        }
        if (invalidateMask) {
            cachedMask = null
        }
    }

    fun runPromptEdit(rawPrompt: String) {
        val current = workingBitmap ?: return
        cloudErrorMessage = null
        if (usePersonalByokKey && hybridState.isByokOnlineReady && cloudAiGateway != null) {
            if (rawPrompt.isBlank()) return
            lastCloudRetry = { runPromptEdit(rawPrompt) }
            scope.launch {
                isProcessing = true
                processingLabel = "Editing with your personal BYOK key…"
                try {
                    val result = cloudAiGateway.editPhotoWithUserByokKey(
                        sourceBitmap = current,
                        instruction = rawPrompt,
                        apiKey = hybridState.personalGeminiApiKey,
                    )
                    lastPlan = PromptEditPlan(
                        rawPrompt = rawPrompt,
                        appliedBadges = listOf("Personal BYOK Key"),
                    )
                    commitProcessedBitmap(
                        nextBitmap = result,
                        invalidateMask = true,
                        resetBgState = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cloudErrorMessage = e.localizedMessage ?: "BYOK AI edit failed."
                } finally {
                    isProcessing = false
                }
            }
            return
        }

        val plan = OfflinePromptEditEngine.parse(rawPrompt)
        lastPlan = plan
        if (!plan.hasChanges) return
        scope.launch {
            isProcessing = true
            processingLabel = "Applying ML Kit + OpenCV edit…"
            var maskSeed = cachedMask
            if (maskSeed == null &&
                plan.backgroundEffect != null &&
                plan.backgroundEffect != AiBackgroundEffect.NONE &&
                hybridState.useMlKitSegmentation &&
                cloudAiGateway != null
            ) {
                maskSeed = cloudAiGateway.segmentSubjectMask(current)
            }
            val (result, maskOut) = withContext(Dispatchers.Default) {
                OfflineAiPhotoEngine.executePromptPlan(current, plan, maskSeed)
            }
            cachedMask = maskOut
            commitProcessedBitmap(
                nextBitmap = result,
                invalidateMask = plan.upscale2x,
                resetBgState = true,
            )
            isProcessing = false
        }
    }

    fun runAutoEnhance() {
        val current = workingBitmap ?: return
        cloudErrorMessage = null
        if (usePersonalByokKey && hybridState.isByokOnlineReady && cloudAiGateway != null) {
            lastCloudRetry = { runAutoEnhance() }
            val presetInstruction = when (enhancePreset) {
                AiEnhancePreset.BALANCED -> "Auto-enhance lighting, dynamic range, white balance, and crisp detail naturally"
                AiEnhancePreset.SHADOW_RESCUE -> "Brighten dark shadows cleanly, balance bright highlights, and rescue underexposed details naturally"
                AiEnhancePreset.COLOR_POP -> "Enhance natural color vibrancy, contrast, and crisp clarity without oversaturating skin tones"
                AiEnhancePreset.DEHAZE -> "Remove atmospheric haze or glare, boost clarity and micro-contrast cleanly"
            }
            val levelWord = when {
                enhanceStrength >= 0.78f -> "strong"
                enhanceStrength <= 0.42f -> "subtle"
                else -> "balanced"
            }
            scope.launch {
                isProcessing = true
                processingLabel = "Auto-fixing with your personal key…"
                try {
                    val out = cloudAiGateway.editPhotoWithUserByokKey(
                        sourceBitmap = current,
                        instruction = "$presetInstruction ($levelWord adjustment strength).",
                        apiKey = hybridState.personalGeminiApiKey,
                    )
                    commitProcessedBitmap(out, invalidateMask = false, resetBgState = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cloudErrorMessage = e.localizedMessage ?: "BYOK auto-fix failed."
                } finally {
                    isProcessing = false
                }
            }
            return
        }

        scope.launch {
            isProcessing = true
            processingLabel = "Auto-balancing light & contrast…"
            val out = withContext(Dispatchers.Default) {
                OfflineAiPhotoEngine.autoEnhanceAndFixLight(current, enhanceStrength, enhancePreset)
            }
            commitProcessedBitmap(out, invalidateMask = false, resetBgState = true)
            isProcessing = false
        }
    }

    fun applyBackgroundPreview(targetEffect: AiBackgroundEffect, blurAmount: Float) {
        val base = bgBaseBitmap ?: workingBitmap ?: return
        cloudErrorMessage = null
        if (targetEffect == AiBackgroundEffect.NONE) {
            if (workingBitmap !== base) {
                commitProcessedBitmap(base, invalidateMask = false, resetBgState = false)
            }
            bgEffect = AiBackgroundEffect.NONE
            return
        }

        if (usePersonalByokKey &&
            hybridState.isByokOnlineReady &&
            cloudAiGateway != null &&
            targetEffect != AiBackgroundEffect.TRANSPARENT_CUTOUT
        ) {
            lastCloudRetry = { applyBackgroundPreview(targetEffect, blurAmount) }
            val bgPrompt = when (targetEffect) {
                AiBackgroundEffect.PORTRAIT_BOKEH ->
                    "Keep the main foreground subject crisp and completely sharp, and blur the background with smooth natural DSLR portrait bokeh at ${(blurAmount * 100).toInt()}% blur."
                AiBackgroundEffect.STUDIO_WHITE ->
                    "Segment the main foreground subject cleanly and replace the entire background with a clean, seamless pure studio white backdrop with soft natural lighting."
                AiBackgroundEffect.GRAYSCALE_BG ->
                    "Keep the main foreground subject in full natural color and convert the entire background to clean monochrome black-and-white."
                AiBackgroundEffect.NONE, AiBackgroundEffect.TRANSPARENT_CUTOUT -> ""
            }
            scope.launch {
                isProcessing = true
                processingLabel = "Rebuilding background with BYOK key…"
                try {
                    val rendered = cloudAiGateway.editPhotoWithUserByokKey(
                        sourceBitmap = base,
                        instruction = bgPrompt,
                        apiKey = hybridState.personalGeminiApiKey,
                    )
                    if (bgBaseBitmap == null) bgBaseBitmap = base
                    val current = workingBitmap
                    if (current != null && bgEffect == AiBackgroundEffect.NONE) {
                        pushUndoSnapshot(current)
                    }
                    workingBitmap = rendered
                    bgEffect = targetEffect
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cloudErrorMessage = e.localizedMessage ?: "BYOK background studio edit failed."
                } finally {
                    isProcessing = false
                }
            }
            return
        }

        scope.launch {
            isProcessing = true
            processingLabel = if (hybridState.useMlKitSegmentation) {
                "Segmenting subject with ML Kit…"
            } else {
                "Segmenting foreground subject…"
            }
            val existingMask = cachedMask ?: if (hybridState.useMlKitSegmentation && cloudAiGateway != null) {
                cloudAiGateway.segmentSubjectMask(base)
            } else null
            val (mask, composited) = withContext(Dispatchers.Default) {
                val fgMask = existingMask ?: OfflineAiPhotoEngine.extractForegroundMask(base)
                val rendered = OfflineAiPhotoEngine.applyBackgroundEffect(
                    source = base,
                    foregroundMask = fgMask,
                    effect = targetEffect,
                    blurIntensity = blurAmount,
                )
                fgMask to rendered
            }
            cachedMask = mask
            if (bgBaseBitmap == null) bgBaseBitmap = base
            val current = workingBitmap
            if (current != null && bgEffect == AiBackgroundEffect.NONE) {
                pushUndoSnapshot(current)
            }
            workingBitmap = composited
            bgEffect = targetEffect
            isProcessing = false
        }
    }

    fun runObjectInpaint() {
        val current = workingBitmap ?: return
        val strokes = eraserStrokes.toList()
        if (strokes.isEmpty()) return
        cloudErrorMessage = null
        if (usePersonalByokKey && hybridState.isByokOnlineReady && cloudAiGateway != null) {
            lastCloudRetry = { runObjectInpaint() }
            scope.launch {
                isProcessing = true
                processingLabel = "Removing marked object with BYOK key…"
                try {
                    val out = cloudAiGateway.removeMarkedObjectsWithUserByokKey(
                        sourceBitmap = current,
                        strokes = strokes,
                        apiKey = hybridState.personalGeminiApiKey,
                    )
                    eraserStrokes.clear()
                    commitProcessedBitmap(out, invalidateMask = true, resetBgState = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cloudErrorMessage = e.localizedMessage ?: "BYOK object removal failed."
                } finally {
                    isProcessing = false
                }
            }
            return
        }

        scope.launch {
            isProcessing = true
            processingLabel = "Inpainting marked region…"
            val out = withContext(Dispatchers.Default) {
                OfflineAiPhotoEngine.removeObjectsWithInpaint(current, strokes)
            }
            eraserStrokes.clear()
            commitProcessedBitmap(out, invalidateMask = true, resetBgState = true)
            isProcessing = false
        }
    }

    fun runSharpenOrUpscale(upscale2x: Boolean) {
        val current = workingBitmap ?: return
        cloudErrorMessage = null
        if (usePersonalByokKey && hybridState.isByokOnlineReady && cloudAiGateway != null) {
            lastCloudRetry = { runSharpenOrUpscale(upscale2x) }
            val prompt = if (upscale2x) {
                "Super-resolution clarity enhancement: sharply restore fine edges, text legibility, and natural textures at crisp ${(sharpenStrength * 100).toInt()}% detail without noise or artifacts."
            } else {
                "Enhance crispness, edge definition, and micro-detail naturally at ${(sharpenStrength * 100).toInt()}% strength."
            }
            scope.launch {
                isProcessing = true
                processingLabel = if (upscale2x) "Upscaling crispness with BYOK key…" else "Sharpening with BYOK key…"
                try {
                    val out = cloudAiGateway.editPhotoWithUserByokKey(
                        sourceBitmap = current,
                        instruction = prompt,
                        apiKey = hybridState.personalGeminiApiKey,
                    )
                    commitProcessedBitmap(out, invalidateMask = upscale2x, resetBgState = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cloudErrorMessage = e.localizedMessage ?: "BYOK clarity enhancement failed."
                } finally {
                    isProcessing = false
                }
            }
            return
        }

        scope.launch {
            isProcessing = true
            processingLabel = if (upscale2x) "Upscaling 2× & sharpening…" else "Enhancing crispness…"
            val out = withContext(Dispatchers.Default) {
                OfflineAiPhotoEngine.sharpenAndUpscale(current, sharpenStrength, upscale2x)
            }
            commitProcessedBitmap(out, invalidateMask = upscale2x, resetBgState = true)
            isProcessing = false
        }
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            processingLabel = "Loading photo…"
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }.getOrNull()
            }
            isProcessing = false
            if (decoded != null) {
                loadNewSourceBitmap(decoded)
            } else {
                Toast.makeText(context, "Could not open selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = workingBitmap ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved AI photo to device", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            BackHandler { onDismiss() }

            Column(modifier = Modifier.fillMaxSize()) {
                // Top Studio Action Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_editor_close_btn"),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_photo_editor_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    usePersonalByokKey && hybridState.isByokOnlineReady -> "Personal BYOK Key"
                                    hybridState.useMlKitSegmentation -> "ML Kit + OpenCV (Free)"
                                    else -> "Offline OpenCV & ML"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }

                        IconButton(
                            onClick = ::performUndo,
                            enabled = undoStack.isNotEmpty() && !isProcessing,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_editor_undo_btn"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }

                        IconButton(
                            onClick = ::performRedo,
                            enabled = redoStack.isNotEmpty() && !isProcessing,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_editor_redo_btn"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                        }

                        if (workingBitmap != null && originalBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isComparingOriginal) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .pointerInput(originalBitmap) {
                                        detectTapGestures(
                                            onPress = {
                                                isComparingOriginal = true
                                                tryAwaitRelease()
                                                isComparingOriginal = false
                                            }
                                        )
                                    }
                                    .testTag("ai_editor_compare_btn"),
                            ) {
                                Box(
                                    modifier = Modifier.size(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Compare,
                                        contentDescription = stringResource(R.string.compare_hold_original),
                                        tint = if (isComparingOriginal) {
                                            MaterialTheme.colorScheme.onTertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        if (!isDocumentStudioMode) {
                            IconButton(
                                onClick = {
                                    pickGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("ai_editor_pick_btn"),
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Pick Photo")
                            }
                        }

                        if (isDocumentStudioMode) {
                            Button(
                                onClick = {
                                    val result = workingBitmap ?: return@Button
                                    val copy = result.copy(Bitmap.Config.ARGB_8888, false)
                                    onApplyToDocument?.invoke(copy)
                                    onDismiss()
                                },
                                enabled = workingBitmap != null && !isProcessing,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("ai_editor_apply_page_btn"),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Apply", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Box {
                                Button(
                                    onClick = { showExportMenu = true },
                                    enabled = workingBitmap != null && !isProcessing,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("ai_editor_export_btn"),
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save", style = MaterialTheme.typography.labelMedium)
                                }
                                DropdownMenu(
                                    expanded = showExportMenu,
                                    onDismissRequest = { showExportMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Save PNG to Device") },
                                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                        onClick = {
                                            showExportMenu = false
                                            savePngLauncher.launch("ProScan_AI_${System.currentTimeMillis()}.png")
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share Edited Photo") },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                        onClick = {
                                            showExportMenu = false
                                            val bmp = workingBitmap ?: return@DropdownMenuItem
                                            val outFile = File(context.cacheDir, "ai_photo_${System.currentTimeMillis()}.png")
                                            FileOutputStream(outFile).use {
                                                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                                            }
                                            val uri = uriForFile(context, outFile)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share AI Photo"))
                                        },
                                    )
                                    if (onOpenAsNewDocument != null) {
                                        DropdownMenuItem(
                                            text = { Text("Open in Document Studio") },
                                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                            onClick = {
                                                showExportMenu = false
                                                val bmp = workingBitmap ?: return@DropdownMenuItem
                                                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                                onOpenAsNewDocument("AI Photo Edit", copy)
                                                onDismiss()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Engine Switcher & Optional BYOK Error Strip
                if (cloudAiGateway != null || cloudErrorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                HybridEngineBadge(
                                    isOnlineActive = (usePersonalByokKey && hybridState.isByokOnlineReady) || hybridState.useMlKitSegmentation,
                                    onlineLabel = if (usePersonalByokKey && hybridState.isByokOnlineReady) {
                                        "Personal BYOK Key Active"
                                    } else {
                                        "ML Kit + OpenCV (100% Free)"
                                    },
                                    offlineLabel = "Offline OpenCV Active",
                                )
                                if (cloudAiGateway != null && hybridState.isByokOnlineReady) {
                                    FilterChip(
                                        selected = usePersonalByokKey,
                                        onClick = {
                                            usePersonalByokKey = !usePersonalByokKey
                                            cloudErrorMessage = null
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (usePersonalByokKey) Icons.Default.AutoAwesome else Icons.Default.Memory,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = if (usePersonalByokKey) "Use Free ML Kit" else "Use My Key",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        modifier = Modifier.testTag("ai_editor_engine_toggle"),
                                    )
                                }
                            }
                            cloudErrorMessage?.let { err ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            lastCloudRetry?.let { retryAction ->
                                                FilledTonalButton(
                                                    onClick = {
                                                        cloudErrorMessage = null
                                                        retryAction()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Retry BYOK", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    usePersonalByokKey = false
                                                    cloudErrorMessage = null
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            ) {
                                                Text("Switch to Free ML Kit + OpenCV", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(onClick = { cloudErrorMessage = null }) {
                                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Preview Canvas Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = if (isComparingOriginal) originalBitmap else workingBitmap
                    if (preview == null) {
                        EmptyAiPhotoPickerCard(
                            hasSavedDocs = savedDocuments.isNotEmpty() && libraryRepository != null,
                            onPickGallery = {
                                pickGalleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickSavedDoc = { showSavedDocPicker = true },
                        )
                    } else {
                        val showCheckerboard = !isComparingOriginal &&
                            (bgEffect == AiBackgroundEffect.TRANSPARENT_CUTOUT || preview.hasAlpha())
                        val aspect = (preview.width.toFloat() / preview.height.coerceAtLeast(1).toFloat())
                            .coerceIn(0.35f, 2.8f)

                        if (activeMode == AiPhotoToolMode.HEAL_UPSCALE && !isComparingOriginal) {
                            Box(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .aspectRatio(aspect)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                if (showCheckerboard) {
                                    AiPhotoCheckerboardBackground(Modifier.fillMaxSize())
                                }
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = "AI Photo Preview",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                MagicEraserCanvasOverlay(
                                    strokes = eraserStrokes,
                                    brushRadiusNorm = brushRadiusNorm,
                                    onAddStroke = { eraserStrokes.add(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            val zoomState = remember(preview) {
                                ZoomState(
                                    contentSize = Size(preview.width.toFloat(), preview.height.toFloat())
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(aspect)
                                        .clip(RoundedCornerShape(12.dp))
                                        .zoomable(zoomState),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (showCheckerboard) {
                                        AiPhotoCheckerboardBackground(Modifier.fillMaxSize())
                                    }
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "AI Photo Preview",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        // Dimensions & Original badge overlay
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                            ) {
                                Text(
                                    text = "${preview.width}×${preview.height} px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            if (isComparingOriginal) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.tertiary,
                                ) {
                                    Text(
                                        text = stringResource(R.string.compare_showing_original),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    Text(
                                        text = processingLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Tool Dock
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                        when (activeMode) {
                            AiPhotoToolMode.PROMPT -> AiPromptSubPanel(
                                promptText = promptText,
                                onPromptChange = { promptText = it },
                                hasImage = workingBitmap != null,
                                isProcessing = isProcessing,
                                lastPlan = lastPlan,
                                onApplyPrompt = { runPromptEdit(promptText) },
                                onSelectSuggestion = { suggestion ->
                                    promptText = suggestion.prompt
                                    runPromptEdit(suggestion.prompt)
                                },
                            )

                            AiPhotoToolMode.AUTO_ENHANCE -> AiAutoFixSubPanel(
                                selectedPreset = enhancePreset,
                                strength = enhanceStrength,
                                hasImage = workingBitmap != null,
                                isProcessing = isProcessing,
                                onSelectPreset = { enhancePreset = it },
                                onStrengthChange = { enhanceStrength = it },
                                onRunAutoEnhance = ::runAutoEnhance,
                            )

                            AiPhotoToolMode.BACKGROUND -> AiBackgroundSubPanel(
                                selectedEffect = bgEffect,
                                blurIntensity = blurIntensity,
                                hasImage = workingBitmap != null,
                                isProcessing = isProcessing,
                                onSelectEffect = { eff ->
                                    applyBackgroundPreview(eff, blurIntensity)
                                },
                                onBlurIntensityChange = { newBlur ->
                                    blurIntensity = newBlur
                                },
                                onBlurIntensityFinished = {
                                    if (bgEffect == AiBackgroundEffect.PORTRAIT_BOKEH) {
                                        applyBackgroundPreview(AiBackgroundEffect.PORTRAIT_BOKEH, blurIntensity)
                                    }
                                },
                            )

                            AiPhotoToolMode.HEAL_UPSCALE -> AiHealUpscaleSubPanel(
                                brushRadiusNorm = brushRadiusNorm,
                                strokeCount = eraserStrokes.size,
                                sharpenStrength = sharpenStrength,
                                hasImage = workingBitmap != null,
                                isProcessing = isProcessing,
                                onBrushRadiusChange = { brushRadiusNorm = it },
                                onUndoStroke = {
                                    if (eraserStrokes.isNotEmpty()) {
                                        eraserStrokes.removeAt(eraserStrokes.lastIndex)
                                    }
                                },
                                onClearStrokes = { eraserStrokes.clear() },
                                onEraseObject = ::runObjectInpaint,
                                onSharpenStrengthChange = { sharpenStrength = it },
                                onSharpenOnly = { runSharpenOrUpscale(upscale2x = false) },
                                onUpscale2x = { runSharpenOrUpscale(upscale2x = true) },
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(6.dp))

                        // 4-Mode Navigation Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            AiPhotoToolMode.entries.forEach { mode ->
                                val selected = activeMode == mode
                                val icon = when (mode) {
                                    AiPhotoToolMode.PROMPT -> Icons.Default.Psychology
                                    AiPhotoToolMode.AUTO_ENHANCE -> Icons.Default.AutoFixHigh
                                    AiPhotoToolMode.BACKGROUND -> Icons.Default.BlurOn
                                    AiPhotoToolMode.HEAL_UPSCALE -> Icons.Default.CleaningServices
                                }
                                val tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                            else Color.Transparent
                                        )
                                        .clickable { activeMode = mode }
                                        .padding(vertical = 6.dp, horizontal = 4.dp)
                                        .testTag("ai_mode_${mode.name.lowercase()}"),
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = mode.label,
                                        tint = tint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = tint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSavedDocPicker && libraryRepository != null) {
        SavedDocumentPagePickerDialog(
            documents = savedDocuments,
            onDismiss = { showSavedDocPicker = false },
            onSelectDocument = { doc ->
                showSavedDocPicker = false
                scope.launch {
                    isProcessing = true
                    processingLabel = "Opening \"${doc.title}\"…"
                    val bmp = withContext(Dispatchers.IO) {
                        val firstFile = libraryRepository.readDocumentPageFiles(doc.id).firstOrNull()?.first
                        firstFile?.let { f ->
                            val opts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            BitmapFactory.decodeFile(f.absolutePath, opts)
                        }
                    }
                    isProcessing = false
                    if (bmp != null) {
                        loadNewSourceBitmap(bmp)
                    } else {
                        Toast.makeText(context, "Could not load document page", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun EmptyAiPhotoPickerCard(
    hasSavedDocs: Boolean,
    onPickGallery: () -> Unit,
    onPickSavedDoc: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = "AI Photo Studio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Choose a gallery photo or saved scan to edit with 100% free ML Kit Subject Segmentation & OpenCV tools (plus optional personal API key).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickGallery,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("ai_empty_pick_gallery_btn"),
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick Photo from Device")
            }
            if (hasSavedDocs) {
                OutlinedButton(
                    onClick = onPickSavedDoc,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("ai_empty_pick_doc_btn"),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick from Saved Documents")
                }
            }
        }
    }
}

@Composable
private fun AiPhotoCheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cellPx = 18.dp.toPx().coerceAtLeast(8f)
        val cols = ceil(size.width / cellPx).toInt()
        val rows = ceil(size.height / cellPx).toInt()
        val light = Color(0xFFEEEEEE)
        val dark = Color(0xFFCCCCCC)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * cellPx, r * cellPx),
                    size = Size(cellPx, cellPx),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiPromptSubPanel(
    promptText: String,
    onPromptChange: (String) -> Unit,
    hasImage: Boolean,
    isProcessing: Boolean,
    lastPlan: PromptEditPlan?,
    onApplyPrompt: () -> Unit,
    onSelectSuggestion: (AiPromptSuggestion) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = promptText,
                onValueChange = onPromptChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.ai_prompt_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (hasImage && !isProcessing && promptText.isNotBlank()) onApplyPrompt() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_prompt_input"),
            )
            Button(
                onClick = onApplyPrompt,
                enabled = hasImage && !isProcessing && promptText.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ai_apply_prompt_btn"),
            ) {
                Text("Run", style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AI_PROMPT_SUGGESTIONS.forEach { suggestion ->
                FilterChip(
                    selected = false,
                    onClick = { if (hasImage && !isProcessing) onSelectSuggestion(suggestion) },
                    enabled = hasImage && !isProcessing,
                    label = {
                        Text(
                            text = suggestion.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.testTag("ai_prompt_chip_${suggestion.label.lowercase().replace(' ', '_')}"),
                )
            }
        }

        if (lastPlan != null) {
            if (lastPlan.appliedBadges.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    lastPlan.appliedBadges.forEach { badge ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "✓ $badge",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            } else if (lastPlan.unrecognizedHint != null) {
                Text(
                    text = lastPlan.unrecognizedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AiAutoFixSubPanel(
    selectedPreset: AiEnhancePreset,
    strength: Float,
    hasImage: Boolean,
    isProcessing: Boolean,
    onSelectPreset: (AiEnhancePreset) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onRunAutoEnhance: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiEnhancePreset.entries.forEach { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { onSelectPreset(preset) },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ai_enhance_preset_${preset.name.lowercase()}"),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Strength ${(strength * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(96.dp),
            )
            Slider(
                value = strength,
                onValueChange = onStrengthChange,
                valueRange = 0.1f..1.0f,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .testTag("ai_enhance_strength_slider"),
            )
        }

        Button(
            onClick = onRunAutoEnhance,
            enabled = hasImage && !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("ai_auto_enhance_btn"),
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_auto_enhance_btn))
        }
    }
}

@Composable
private fun AiBackgroundSubPanel(
    selectedEffect: AiBackgroundEffect,
    blurIntensity: Float,
    hasImage: Boolean,
    isProcessing: Boolean,
    onSelectEffect: (AiBackgroundEffect) -> Unit,
    onBlurIntensityChange: (Float) -> Unit,
    onBlurIntensityFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiBackgroundEffect.entries.forEach { effect ->
                FilterChip(
                    selected = selectedEffect == effect,
                    onClick = { if (hasImage && !isProcessing) onSelectEffect(effect) },
                    enabled = hasImage && !isProcessing,
                    leadingIcon = if (selectedEffect == effect) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    } else null,
                    label = { Text(effect.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ai_bg_${effect.name.lowercase()}"),
                )
            }
        }

        if (selectedEffect == AiBackgroundEffect.PORTRAIT_BOKEH) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bokeh ${(blurIntensity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(88.dp),
                )
                Slider(
                    value = blurIntensity,
                    onValueChange = onBlurIntensityChange,
                    onValueChangeFinished = onBlurIntensityFinished,
                    valueRange = 0.2f..1.0f,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("ai_bg_blur_slider"),
                )
            }
        } else {
            Text(
                text = "Google ML Kit Subject Segmentation + OpenCV isolates the foreground cleanly for bokeh blur, transparent PNG cutout, or studio backdrops.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiHealUpscaleSubPanel(
    brushRadiusNorm: Float,
    strokeCount: Int,
    sharpenStrength: Float,
    hasImage: Boolean,
    isProcessing: Boolean,
    onBrushRadiusChange: (Float) -> Unit,
    onUndoStroke: () -> Unit,
    onClearStrokes: () -> Unit,
    onEraseObject: () -> Unit,
    onSharpenStrengthChange: (Float) -> Unit,
    onSharpenOnly: () -> Unit,
    onUpscale2x: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
            .heightIn(max = 176.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Object Removal Brush Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Brush",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(44.dp),
            )
            Slider(
                value = brushRadiusNorm,
                onValueChange = onBrushRadiusChange,
                valueRange = 0.014f..0.075f,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .testTag("ai_heal_brush_slider"),
            )
            TextButton(
                onClick = onUndoStroke,
                enabled = strokeCount > 0 && !isProcessing,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text("Undo", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = onClearStrokes,
                enabled = strokeCount > 0 && !isProcessing,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
            FilledTonalButton(
                onClick = onEraseObject,
                enabled = hasImage && strokeCount > 0 && !isProcessing,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ai_erase_object_btn"),
            ) {
                Text("Erase ($strokeCount)", style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Sharpness + 2x Super-Resolution Upscale Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Crisp ${(sharpenStrength * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(68.dp),
            )
            Slider(
                value = sharpenStrength,
                onValueChange = onSharpenStrengthChange,
                valueRange = 0.1f..1.0f,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .testTag("ai_sharpen_slider"),
            )
            OutlinedButton(
                onClick = onSharpenOnly,
                enabled = hasImage && !isProcessing,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ai_sharpen_only_btn"),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sharpen", style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onUpscale2x,
                enabled = hasImage && !isProcessing,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ai_upscale_2x_btn"),
            ) {
                Icon(Icons.Default.Hd, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("2× Upscale", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SavedDocumentPagePickerDialog(
    documents: List<SavedDocumentEntity>,
    onDismiss: () -> Unit,
    onSelectDocument: (SavedDocumentEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick Saved Document Page") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(documents, key = { it.id }) { doc ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectDocument(doc) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = doc.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${doc.pageCount} page(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
