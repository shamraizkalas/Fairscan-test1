package org.fairscan.app.camscanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.library.SavedAssetType
import org.fairscan.app.platform.loadBitmapFromUri
import org.fairscan.app.ui.components.rememberCameraPermissionState
import java.util.concurrent.Executors
import kotlin.math.abs

@Composable
fun ScanSignatureStampDialog(
    initialAssetType: SavedAssetType = SavedAssetType.SIGNATURE,
    initialSourceBitmap: Bitmap? = null,
    showApplyButton: Boolean = true,
    onDismiss: () -> Unit,
    onSaveAsset: (bitmap: Bitmap, label: String, assetType: SavedAssetType, applyImmediately: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember(initialSourceBitmap) { mutableStateOf(initialSourceBitmap) }
    var assetType by remember(initialAssetType) { mutableStateOf(initialAssetType) }
    var label by remember(initialAssetType) {
        mutableStateOf(
            if (initialAssetType == SavedAssetType.STAMP) "Office Stamp" else "My Signature"
        )
    }
    var inkColor by remember(initialAssetType) {
        mutableStateOf(
            if (initialAssetType == SavedAssetType.STAMP) StampInkColor.ORIGINAL else StampInkColor.BLACK
        )
    }
    var sensitivity by remember { mutableFloatStateOf(0.05f) }

    // Interactive crop rectangle in normalized coordinates [0..1]
    var cropLeft by remember { mutableFloatStateOf(0.10f) }
    var cropTop by remember { mutableFloatStateOf(0.26f) }
    var cropRight by remember { mutableFloatStateOf(0.90f) }
    var cropBottom by remember { mutableFloatStateOf(0.74f) }

    var extractedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var isExtracting by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val loaded = loadBitmapFromUri(context.contentResolver, uri, maxPixels = 2_400_000)
                    sourceBitmap = loaded
                    cropLeft = 0.08f
                    cropTop = 0.15f
                    cropRight = 0.92f
                    cropBottom = 0.85f
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(sourceBitmap, cropLeft, cropTop, cropRight, cropBottom, inkColor, sensitivity) {
        val src = sourceBitmap
        if (src == null) {
            extractedPreview = null
            return@LaunchedEffect
        }
        isExtracting = true
        val result = withContext(Dispatchers.Default) {
            SignatureStampExtractor.extractTransparentAsset(
                source = src,
                cropLeftNorm = cropLeft,
                cropTopNorm = cropTop,
                cropRightNorm = cropRight,
                cropBottomNorm = cropBottom,
                inkColor = inkColor,
                sensitivity = sensitivity,
                maxDimensionPx = 720,
            )
        }
        extractedPreview = result
        isExtracting = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            if (sourceBitmap == null) {
                CameraCaptureStep(
                    assetType = assetType,
                    onChangeType = {
                        assetType = it
                        label = if (it == SavedAssetType.STAMP) "Office Stamp" else "My Signature"
                        inkColor = if (it == SavedAssetType.STAMP) StampInkColor.ORIGINAL else StampInkColor.BLACK
                    },
                    onPhotoCaptured = { bmp ->
                        sourceBitmap = bmp
                        cropLeft = 0.12f
                        cropTop = 0.28f
                        cropRight = 0.88f
                        cropBottom = 0.72f
                    },
                    onPickGallery = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onDismiss = onDismiss
                )
            } else {
                CropAndExtractStep(
                    sourceBitmap = sourceBitmap!!,
                    extractedPreview = extractedPreview,
                    isExtracting = isExtracting,
                    assetType = assetType,
                    onAssetTypeChange = {
                        val wasDefault = label == "My Signature" || label == "Office Stamp"
                        assetType = it
                        if (wasDefault) {
                            label = if (it == SavedAssetType.STAMP) "Office Stamp" else "My Signature"
                        }
                    },
                    label = label,
                    onLabelChange = { label = it },
                    inkColor = inkColor,
                    onInkColorChange = { inkColor = it },
                    sensitivity = sensitivity,
                    onSensitivityChange = { sensitivity = it },
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropRight = cropRight,
                    cropBottom = cropBottom,
                    onCropChange = { l, t, r, b ->
                        cropLeft = l
                        cropTop = t
                        cropRight = r
                        cropBottom = b
                    },
                    showApplyButton = showApplyButton,
                    onRetake = { sourceBitmap = null },
                    onPickGallery = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onSave = { applyNow ->
                        scope.launch {
                            val finalBmp = withContext(Dispatchers.Default) {
                                SignatureStampExtractor.extractTransparentAsset(
                                    source = sourceBitmap!!,
                                    cropLeftNorm = cropLeft,
                                    cropTopNorm = cropTop,
                                    cropRightNorm = cropRight,
                                    cropBottomNorm = cropBottom,
                                    inkColor = inkColor,
                                    sensitivity = sensitivity,
                                    maxDimensionPx = 1100,
                                )
                            }
                            if (finalBmp != null) {
                                onSaveAsset(finalBmp, label, assetType, applyNow)
                                onDismiss()
                            } else {
                                Toast.makeText(
                                    context,
                                    "No ink detected — adjust crop box or Clean slider",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun CameraCaptureStep(
    assetType: SavedAssetType,
    onChangeType: (SavedAssetType) -> Unit,
    onPhotoCaptured: (Bitmap) -> Unit,
    onPickGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberCameraPermissionState()
    val scope = rememberCoroutineScope()

    var torchEnabled by remember { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        if (!cameraPermission.isGranted) {
            cameraPermission.request()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            captureExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, cameraRef) {
        cameraRef?.cameraControl?.enableTorch(torchEnabled)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scan Signature or Stamp",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = assetType == SavedAssetType.SIGNATURE,
                onClick = { onChangeType(SavedAssetType.SIGNATURE) },
                leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Signature") }
            )
            FilterChip(
                selected = assetType == SavedAssetType.STAMP,
                onClick = { onChangeType(SavedAssetType.STAMP) },
                leadingIcon = { Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Stamp") }
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!cameraPermission.isGranted) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Camera permission needed to scan directly, or pick a photo from gallery.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { cameraPermission.request() }) {
                            Text("Allow Camera")
                        }
                        OutlinedButton(onClick = onPickGallery) {
                            Text("Gallery Photo", color = Color.White)
                        }
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewViewRef = this
                        }
                    }
                )

                LaunchedEffect(previewViewRef, cameraPermission.isGranted) {
                    val view = previewViewRef ?: return@LaunchedEffect
                    val provider = withContext(Dispatchers.IO) {
                        ProcessCameraProvider.getInstance(context).get()
                    }
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = view.surfaceProvider
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                    cameraRef = camera
                    imageCaptureRef = imageCapture
                }

                // Target viewfinder box
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val boxW = size.width * 0.78f
                    val boxH = size.height * 0.44f
                    val left = (size.width - boxW) / 2f
                    val top = (size.height - boxH) / 2f
                    drawRect(
                        color = Color.Black.copy(alpha = 0.42f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, top)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.42f),
                        topLeft = Offset(0f, top + boxH),
                        size = Size(size.width, size.height - (top + boxH))
                    )
                    drawRect(
                        color = Color(0xFF1DE9B6),
                        topLeft = Offset(left, top),
                        size = Size(boxW, boxH),
                        style = Stroke(width = 4f)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = "Center signature or stamp inside green box",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                IconButton(
                    onClick = { torchEnabled = !torchEnabled },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPickGallery) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }

            Button(
                onClick = {
                    val ic = imageCaptureRef ?: return@Button
                    ic.takePicture(
                        captureExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val raw = imageProxy.toBitmap()
                                imageProxy.close()
                                val rotated = if (rot != 0) {
                                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                                        if (it !== raw) raw.recycle()
                                    }
                                } else raw
                                scope.launch(Dispatchers.Main) {
                                    onPhotoCaptured(rotated)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                scope.launch(Dispatchers.Main) {
                                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                },
                enabled = cameraPermission.isGranted && imageCaptureRef != null
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Capture")
            }
        }
    }
}

@Composable
private fun CropAndExtractStep(
    sourceBitmap: Bitmap,
    extractedPreview: Bitmap?,
    isExtracting: Boolean,
    assetType: SavedAssetType,
    onAssetTypeChange: (SavedAssetType) -> Unit,
    label: String,
    onLabelChange: (String) -> Unit,
    inkColor: StampInkColor,
    onInkColorChange: (StampInkColor) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
    onCropChange: (Float, Float, Float, Float) -> Unit,
    showApplyButton: Boolean,
    onRetake: () -> Unit,
    onPickGallery: () -> Unit,
    onSave: (applyNow: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Crop & Clean Background",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = assetType == SavedAssetType.SIGNATURE,
                onClick = { onAssetTypeChange(SavedAssetType.SIGNATURE) },
                leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Signature") }
            )
            FilterChip(
                selected = assetType == SavedAssetType.STAMP,
                onClick = { onAssetTypeChange(SavedAssetType.STAMP) },
                leadingIcon = { Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Stamp") }
            )
        }

        // Interactive Crop Frame over source image
        val ratio = (sourceBitmap.width.toFloat() / sourceBitmap.height.coerceAtLeast(1)).coerceIn(0.6f, 2.2f)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            Image(
                bitmap = sourceBitmap.asImageBitmap(),
                contentDescription = "Captured source",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            var activeHandle by remember { mutableStateOf<Int?>(null) } // 0=TL,1=TR,2=BL,3=BR,4=MOVE
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cropLeft, cropTop, cropRight, cropBottom) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val nx = start.x / size.width.coerceAtLeast(1)
                                val ny = start.y / size.height.coerceAtLeast(1)
                                val tol = 0.11f
                                activeHandle = when {
                                    abs(nx - cropLeft) < tol && abs(ny - cropTop) < tol -> 0
                                    abs(nx - cropRight) < tol && abs(ny - cropTop) < tol -> 1
                                    abs(nx - cropLeft) < tol && abs(ny - cropBottom) < tol -> 2
                                    abs(nx - cropRight) < tol && abs(ny - cropBottom) < tol -> 3
                                    nx in cropLeft..cropRight && ny in cropTop..cropBottom -> 4
                                    else -> null
                                }
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val dx = drag.x / size.width.coerceAtLeast(1)
                                val dy = drag.y / size.height.coerceAtLeast(1)
                                val minGap = 0.12f
                                when (activeHandle) {
                                    0 -> onCropChange(
                                        (cropLeft + dx).coerceIn(0f, cropRight - minGap),
                                        (cropTop + dy).coerceIn(0f, cropBottom - minGap),
                                        cropRight,
                                        cropBottom
                                    )
                                    1 -> onCropChange(
                                        cropLeft,
                                        (cropTop + dy).coerceIn(0f, cropBottom - minGap),
                                        (cropRight + dx).coerceIn(cropLeft + minGap, 1f),
                                        cropBottom
                                    )
                                    2 -> onCropChange(
                                        (cropLeft + dx).coerceIn(0f, cropRight - minGap),
                                        cropTop,
                                        cropRight,
                                        (cropBottom + dy).coerceIn(cropTop + minGap, 1f)
                                    )
                                    3 -> onCropChange(
                                        cropLeft,
                                        cropTop,
                                        (cropRight + dx).coerceIn(cropLeft + minGap, 1f),
                                        (cropBottom + dy).coerceIn(cropTop + minGap, 1f)
                                    )
                                    4 -> {
                                        val w = cropRight - cropLeft
                                        val h = cropBottom - cropTop
                                        val nl = (cropLeft + dx).coerceIn(0f, 1f - w)
                                        val nt = (cropTop + dy).coerceIn(0f, 1f - h)
                                        onCropChange(nl, nt, nl + w, nt + h)
                                    }
                                }
                            },
                            onDragEnd = { activeHandle = null },
                            onDragCancel = { activeHandle = null }
                        )
                    }
            ) {
                val l = cropLeft * size.width
                val t = cropTop * size.height
                val r = cropRight * size.width
                val b = cropBottom * size.height

                // Dim outside crop
                drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, Size(size.width, t))
                drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, b), Size(size.width, size.height - b))
                drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, t), Size(l, b - t))
                drawRect(Color.Black.copy(alpha = 0.45f), Offset(r, t), Size(size.width - r, b - t))

                drawRect(
                    color = Color(0xFF1DE9B6),
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                    style = Stroke(width = 3.5f)
                )
                listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { pt ->
                    drawCircle(Color(0xFF1DE9B6), radius = 14f, center = pt)
                    drawCircle(Color.White, radius = 8f, center = pt)
                }
            }
        }

        // Extracted Transparent Preview
        Text(
            text = "Extracted Transparent Preview:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CheckerboardBackground(modifier = Modifier.fillMaxSize())
                if (isExtracting) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else if (extractedPreview != null) {
                    Image(
                        bitmap = extractedPreview.asImageBitmap(),
                        contentDescription = "Extracted preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                } else {
                    Text(
                        text = "Move green corners around ink or adjust slider",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                }
            }
        }

        // Cleanliness / sensitivity slider
        Text(
            text = "Background Cleanliness: ${((sensitivity + 0.35f) / 0.70f * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = sensitivity,
            onValueChange = onSensitivityChange,
            valueRange = -0.35f..0.35f,
            modifier = Modifier.height(28.dp)
        )

        // Ink Color Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StampInkColor.entries.forEach { option ->
                FilterChip(
                    selected = inkColor == option,
                    onClick = { onInkColorChange(option) },
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text("Label (e.g. Company Stamp)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onRetake,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Camera", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onPickGallery,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Photo", style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showApplyButton) {
                    OutlinedButton(
                        onClick = { onSave(false) },
                        enabled = extractedPreview != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onSave(true) },
                        enabled = extractedPreview != null,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Save & Apply", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(
                        onClick = { onSave(false) },
                        enabled = extractedPreview != null,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cell = 14.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        val light = Color(0xFFF7F7F7)
        val dark = Color(0xFFE5E8EC)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
