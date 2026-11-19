package org.fairscan.app.camscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.ui.uriForFile
import java.io.File
import java.io.FileOutputStream

@Composable
fun IdPhotoMakerDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourcePortrait by remember { mutableStateOf<Bitmap?>(null) }
    var preset by remember { mutableStateOf(IdPhotoSizePreset.PASSPORT_35X45) }
    var background by remember { mutableStateOf(IdPhotoBackground.PURE_WHITE) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var verticalPan by remember { mutableFloatStateOf(0f) }
    var printableSheet by remember { mutableStateOf(true) }
    var sheetType by remember { mutableStateOf(IdPrintSheetType.SHEET_A4) }
    var copies by remember { mutableIntStateOf(8) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }.getOrNull()
            }
            if (loaded != null) {
                sourcePortrait = loaded
            }
        }
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = previewBitmap ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "ID Photo saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(sourcePortrait, preset, background, zoom, verticalPan, printableSheet, sheetType, copies) {
        val src = sourcePortrait ?: run {
            previewBitmap = null
            return@LaunchedEffect
        }
        isProcessing = true
        previewBitmap = withContext(Dispatchers.Default) {
            val single = IdPhotoEngine.generateIdPhoto(
                source = src,
                preset = preset,
                background = background,
                zoom = zoom,
                offsetYNorm = verticalPan,
            )
            if (printableSheet) {
                val sheet = IdPhotoEngine.createPrintableIdSheet(
                    photo = single,
                    sheetType = sheetType,
                    copies = copies
                )
                if (sheet !== single) single.recycle()
                sheet
            } else {
                single
            }
        }
        isProcessing = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 780.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Passport & ID Photo Maker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        pickPhotoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("id_photo_pick_btn")
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sourcePortrait == null) "Select Portrait Photo" else "Change Portrait Photo")
                }

                Spacer(Modifier.height(10.dp))
                Text("Official Photo Size", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IdPhotoSizePreset.entries.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Background Color", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IdPhotoBackground.entries.forEach { bg ->
                        FilterChip(
                            selected = background == bg,
                            onClick = { background = bg },
                            label = { Text(bg.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Zoom ${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(74.dp))
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 0.85f..2.0f,
                        modifier = Modifier.weight(1f).height(26.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Vertical", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(74.dp))
                    Slider(
                        value = verticalPan,
                        onValueChange = { verticalPan = it },
                        valueRange = -0.6f..0.6f,
                        modifier = Modifier.weight(1f).height(26.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Create Printable Multi-Copy Sheet", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Switch(checked = printableSheet, onCheckedChange = { printableSheet = it })
                }

                if (printableSheet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IdPrintSheetType.entries.forEach { st ->
                            FilterChip(
                                selected = sheetType == st,
                                onClick = { sheetType = st },
                                label = { Text(st.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        listOf(4, 8, 12).forEach { cnt ->
                            FilterChip(
                                selected = copies == cnt,
                                onClick = { copies = cnt },
                                label = { Text("${cnt}×", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previewBitmap
                    when {
                        isProcessing -> CircularProgressIndicator()
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "ID Photo Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(8.dp)
                        )
                        else -> Text(
                            text = "Pick any portrait photo to crop & swap background offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { savePngLauncher.launch("ProScan_IDPhoto.png") },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            val bmp = previewBitmap ?: return@OutlinedButton
                            val f = File(context.cacheDir, "id_photo_${System.currentTimeMillis()}.png")
                            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri = uriForFile(context, f)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share ID Photo"))
                        },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = {
                            val bmp = previewBitmap ?: return@Button
                            context.findActivity()?.let { act ->
                                LayoutPrintEngine.printBitmaps(act, "ProScan ID Photo", listOf(bmp))
                            }
                        },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Print", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
