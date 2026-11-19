package org.fairscan.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.fairscan.app.R
import org.fairscan.app.ui.components.BackButton

data class OpenSourceLibrary(
    val name: String,
    val author: String,
    val license: String,
    val description: String,
    val url: String
)

private val LIBRARIES = listOf(
    OpenSourceLibrary(
        name = "LiteRT",
        author = "Google",
        license = "Apache License 2.0",
        description = "High-performance runtime for on-device machine learning and document segmentation.",
        url = "https://ai.google.dev/edge/litert"
    ),
    OpenSourceLibrary(
        name = "OpenCV",
        author = "OpenCV Team",
        license = "Apache License 2.0",
        description = "Open Source Computer Vision Library for perspective transformation and enhancement.",
        url = "https://opencv.org"
    ),
    OpenSourceLibrary(
        name = "PdfBox-Android",
        author = "Tom Roush & Apache Software Foundation",
        license = "Apache License 2.0",
        description = "A port of Apache PDFBox library for Android to generate and edit PDF documents.",
        url = "https://github.com/TomRoush/PdfBox-Android"
    ),
    OpenSourceLibrary(
        name = "Tesseract4Android",
        author = "Adaptech s.r.o.",
        license = "Apache License 2.0",
        description = "Fork of Tesseract Tools for Android providing OCR (Optical Character Recognition) capabilities.",
        url = "https://github.com/adaptech/tesseract4android"
    ),
    OpenSourceLibrary(
        name = "AndroidX CameraX",
        author = "Google Android Jetpack",
        license = "Apache License 2.0",
        description = "Consistent and easy-to-use camera experience across Android devices.",
        url = "https://developer.android.com/training/camerax"
    ),
    OpenSourceLibrary(
        name = "Jetpack Compose & Material 3",
        author = "Google",
        license = "Apache License 2.0",
        description = "Modern Android UI toolkit and Material Design 3 components.",
        url = "https://developer.android.com/jetpack/compose"
    ),
    OpenSourceLibrary(
        name = "Zoomable",
        author = "EngawaPG",
        license = "Apache License 2.0",
        description = "Compose modifier to zoom and pan content with smooth gestures.",
        url = "https://github.com/usuiat/Zoomable"
    ),
    OpenSourceLibrary(
        name = "Reorderable",
        author = "Calvin Liang",
        license = "MIT License",
        description = "Smooth reorderable drag-and-drop lists and grids for Jetpack Compose.",
        url = "https://github.com/Calvin-LL/Reorderable"
    ),
    OpenSourceLibrary(
        name = "Kotlinx Serialization & Coroutines",
        author = "JetBrains",
        license = "Apache License 2.0",
        description = "Multiplatform serialization and asynchronous programming primitives.",
        url = "https://github.com/Kotlin/kotlinx.serialization"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.libraries_open_source)) },
                navigationIcon = { BackButton(onClick = onBack) }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(LIBRARIES) { lib ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    uriHandler.openUri(lib.url)
                                } catch (_: Exception) {}
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lib.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = lib.license,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = lib.author,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = lib.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
