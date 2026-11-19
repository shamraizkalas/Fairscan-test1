package org.fairscan.app.camscanner

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import kotlinx.coroutines.CancellationException

@Composable
fun QrGeneratorDialog(
    canApplyToOpenDocument: Boolean = false,
    isOnlineActive: Boolean = false,
    onAnalyzeQr: (suspend (String) -> String)? = null,
    onApplyQrToDocument: ((Bitmap, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var payloadType by remember { mutableStateOf(QrPayloadType.URL) }
    var urlOrText by remember { mutableStateOf("https://") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPass by remember { mutableStateOf("") }
    var wifiEnc by remember { mutableStateOf("WPA") }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var contactOrg by remember { mutableStateOf("") }
    var fgColorArgb by remember { mutableIntStateOf(0xFF111111.toInt()) }
    var isAnalyzingQr by remember { mutableStateOf(false) }
    var qrAiInsight by remember { mutableStateOf<String?>(null) }
    var qrAiError by remember { mutableStateOf<String?>(null) }

    val payload = remember(
        payloadType, urlOrText, wifiSsid, wifiPass, wifiEnc,
        contactName, contactPhone, contactEmail, contactOrg
    ) {
        when (payloadType) {
            QrPayloadType.URL, QrPayloadType.TEXT -> urlOrText.trim()
            QrPayloadType.WIFI -> if (wifiSsid.isBlank()) "" else QrGeneratorHelper.buildWifiPayload(wifiSsid, wifiPass, wifiEnc)
            QrPayloadType.CONTACT -> if (contactName.isBlank()) "" else QrGeneratorHelper.buildVCardPayload(
                name = contactName,
                phone = contactPhone,
                email = contactEmail,
                organization = contactOrg
            )
        }
    }

    val qrBitmap = remember(payload, fgColorArgb) {
        if (payload.isBlank() || payload == "https://") null
        else runCatching { QrGeneratorHelper.generateQrBitmap(payload, 720, fgColorArgb) }.getOrNull()
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = qrBitmap ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "QR Code saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 750.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isOnlineActive) "Smart QR Studio (Free)" else "Offline QR Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QrPayloadType.entries.forEach { t ->
                        FilterChip(
                            selected = payloadType == t,
                            onClick = {
                                payloadType = t
                                if (t == QrPayloadType.TEXT && urlOrText == "https://") urlOrText = ""
                                if (t == QrPayloadType.URL && urlOrText.isBlank()) urlOrText = "https://"
                            },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                when (payloadType) {
                    QrPayloadType.URL -> OutlinedTextField(
                        value = urlOrText,
                        onValueChange = { urlOrText = it },
                        label = { Text("Website URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    QrPayloadType.TEXT -> OutlinedTextField(
                        value = urlOrText,
                        onValueChange = { urlOrText = it },
                        label = { Text("Plain Text or Note") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    QrPayloadType.WIFI -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("Wi-Fi Name (SSID)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wifiPass,
                            onValueChange = { wifiPass = it },
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("WPA", "WEP", "NOPASS").forEach { mode ->
                                FilterChip(
                                    selected = wifiEnc == mode,
                                    onClick = { wifiEnc = mode },
                                    label = { Text(mode, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                    QrPayloadType.CONTACT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            label = { Text("Full Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = contactPhone,
                            onValueChange = { contactPhone = it },
                            label = { Text("Phone Number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = contactEmail,
                            onValueChange = { contactEmail = it },
                            label = { Text("Email (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = contactOrg,
                            onValueChange = { contactOrg = it },
                            label = { Text("Company / Office (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("QR Ink:", style = MaterialTheme.typography.labelMedium)
                    listOf(
                        0xFF111111.toInt(),
                        0xFF00695C.toInt(),
                        0xFF0D47A1.toInt(),
                        0xFF880E4F.toInt()
                    ).forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(
                                    width = if (fgColorArgb == col) 3.dp else 1.dp,
                                    color = if (fgColorArgb == col) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    shape = CircleShape
                                )
                                .clickable { fgColorArgb = col }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Generated QR Code",
                            modifier = Modifier
                                .size(196.dp)
                                .padding(6.dp)
                        )
                    } else {
                        Text(
                            text = "Enter details above to generate QR",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                if (onAnalyzeQr != null && qrBitmap != null) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = {
                            qrAiError = null
                            isAnalyzingQr = true
                            scope.launch {
                                try {
                                    qrAiInsight = onAnalyzeQr(payload)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    qrAiError = e.localizedMessage ?: "QR safety check failed."
                                } finally {
                                    isAnalyzingQr = false
                                }
                            }
                        },
                        enabled = !isAnalyzingQr,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isAnalyzingQr) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Inspecting QR safety…", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Smart Safety & Summary (Free)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    qrAiError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    qrAiInsight?.let { info ->
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = info,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { savePngLauncher.launch("ProScan_QR.png") },
                        enabled = qrBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save PNG", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            val bmp = qrBitmap ?: return@OutlinedButton
                            val f = File(context.cacheDir, "share_qr_${System.currentTimeMillis()}.png")
                            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri = uriForFile(context, f)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share QR Code"))
                        },
                        enabled = qrBitmap != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (onApplyQrToDocument != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val bmp = qrBitmap ?: return@Button
                            onApplyQrToDocument(bmp, "QR: ${payloadType.label}")
                            onDismiss()
                        },
                        enabled = qrBitmap != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qr_apply_doc_btn")
                    ) {
                        Icon(Icons.Default.Approval, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (canApplyToOpenDocument) "Place QR on Document Page"
                            else "Stamp QR onto Open Document"
                        )
                    }
                }
            }
        }
    }
}
