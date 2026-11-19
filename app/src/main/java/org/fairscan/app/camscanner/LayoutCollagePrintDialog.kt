package org.fairscan.app.camscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import org.fairscan.app.library.LibraryRepository
import org.fairscan.app.library.SavedDocumentEntity
import org.fairscan.app.ui.uriForFile
import java.io.File
import java.io.FileOutputStream

@Composable
fun LayoutCollagePrintDialog(
    initialBitmaps: List<Bitmap> = emptyList(),
    savedDocuments: List<SavedDocumentEntity> = emptyList(),
    libraryRepository: LibraryRepository,
    onSavedToLibrary: (SavedDocumentEntity) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmaps by remember { mutableStateOf(initialBitmaps) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Long Stitch, 1 = Collage Grid
    var addDividers by remember { mutableStateOf(true) }
    var collageLayout by remember { mutableStateOf(CollageLayout.TWO_BY_TWO) }
    var addCutGuides by remember { mutableStateOf(true) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(false) }
    var showDocPicker by remember { mutableStateOf(false) }

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(12)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                            BitmapFactory.decodeStream(stream, null, opts)
                        }
                    }.getOrNull()
                }
            }
            if (loaded.isNotEmpty()) {
                sourceBitmaps = loaded
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
                    Toast.makeText(context, "Image saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(sourceBitmaps, selectedTab, addDividers, collageLayout, addCutGuides) {
        if (sourceBitmaps.isEmpty()) {
            previewBitmap = null
            return@LaunchedEffect
        }
        isRendering = true
        previewBitmap = withContext(Dispatchers.Default) {
            if (selectedTab == 0) {
                LayoutPrintEngine.stitchLongImage(sourceBitmaps, addDividers = addDividers)
            } else {
                LayoutPrintEngine.createCollageBitmap(
                    sourceBitmaps,
                    layout = collageLayout,
                    addCutGuides = addCutGuides
                )
            }
        }
        isRendering = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Long Stitch & Collage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Long Stitch") },
                        icon = { Icon(Icons.Default.ViewDay, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Collage Grid") },
                        icon = { Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Source Picker Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            pickPhotosLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Photos", style = MaterialTheme.typography.labelMedium)
                    }
                    if (savedDocuments.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showDocPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("From Library", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Mode Controls
                if (selectedTab == 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { addDividers = !addDividers }
                    ) {
                        Checkbox(checked = addDividers, onCheckedChange = { addDividers = it })
                        Text(
                            "Add subtle divider lines between pages (${sourceBitmaps.size} pages)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CollageLayout.entries.forEach { layout ->
                            FilterChip(
                                selected = collageLayout == layout,
                                onClick = { collageLayout = layout },
                                label = { Text(layout.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { addCutGuides = !addCutGuides }
                    ) {
                        Checkbox(checked = addCutGuides, onCheckedChange = { addCutGuides = it })
                        Text("Show printable dashed cut guides", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Preview Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 210.dp, max = 330.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previewBitmap
                    when {
                        isRendering -> CircularProgressIndicator()
                        bmp != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Stitched/Collage Preview",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = "Select photos or a Library document to stitch or arrange into a printable grid.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Export / Save / Print Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val bmp = previewBitmap ?: return@OutlinedButton
                            scope.launch {
                                val title = if (selectedTab == 0) "Long Stitch ${sourceBitmaps.size}p" else "Collage ${collageLayout.label}"
                                val saved = libraryRepository.saveBitmapsAsDocument(
                                    title = title,
                                    bitmaps = listOf(bmp),
                                    tagsCsv = if (selectedTab == 0) "Stitch" else "Collage",
                                    lastExportFormat = "PNG"
                                )
                                if (saved != null) {
                                    onSavedToLibrary(saved)
                                    Toast.makeText(context, "Saved to Library!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.BookmarkAdded, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Library", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            val name = if (selectedTab == 0) "ProScan_LongStitch.png" else "ProScan_Collage.png"
                            savePngLauncher.launch(name)
                        },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save PNG", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val bmp = previewBitmap ?: return@OutlinedButton
                            val outFile = File(context.cacheDir, "share_layout_${System.currentTimeMillis()}.png")
                            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri: Uri = uriForFile(context, outFile)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Image"))
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
                            val act = context.findActivity()
                            if (act != null) {
                                LayoutPrintEngine.printBitmaps(
                                    activity = act,
                                    jobName = if (selectedTab == 0) "ProScan Long Stitch" else "ProScan Collage",
                                    bitmaps = listOf(bmp)
                                )
                            }
                        },
                        enabled = previewBitmap != null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_print_btn")
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Print", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showDocPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDocPicker = false },
            title = { Text("Load Pages from Library") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    savedDocuments.forEach { doc ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(doc.title) },
                            supportingContent = { Text("${doc.pageCount} pages") },
                            modifier = Modifier.clickable {
                                showDocPicker = false
                                scope.launch {
                                    val files = libraryRepository.readDocumentPageFiles(doc.id)
                                    val bitmaps = withContext(Dispatchers.IO) {
                                        files.mapNotNull { (f, _) -> BitmapFactory.decodeFile(f.absolutePath) }
                                    }
                                    if (bitmaps.isNotEmpty()) sourceBitmaps = bitmaps
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDocPicker = false }) { Text("Close") } }
        )
    }
}
