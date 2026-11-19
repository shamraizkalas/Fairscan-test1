package org.fairscan.app.camscanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.fairscan.app.library.BackupStats
import org.fairscan.app.library.LibraryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppVaultLockGate(
    vaultManager: SecurityVaultManager,
    title: String = "ProScan Vault Locked",
    subtitle: String = "Unlock with Fingerprint or PIN to view your documents",
    onUnlocked: () -> Unit,
    onDismissOptional: (() -> Unit)? = null,
) {
    BackHandler(enabled = true) {
        if (onDismissOptional != null) {
            onDismissOptional()
        }
        // When used as full-screen App Lock Gate, prevent back bypass
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val canBiometric = remember(context) {
        vaultManager.isBiometricEnabled() && vaultManager.canUseBiometrics(context) && activity != null
    }
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val launchBiometric = {
        if (activity != null && vaultManager.isBiometricEnabled()) {
            vaultManager.authenticateBiometric(
                activity = activity,
                title = title,
                subtitle = subtitle,
                negativeText = "Use PIN",
                onSuccess = onUnlocked,
                onUsePin = {},
                onError = { msg -> errorMessage = msg }
            )
        }
    }

    LaunchedEffect(canBiometric) {
        if (canBiometric) {
            launchBiometric()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag("vault_lock_gate")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = pinInput,
                onValueChange = { next ->
                    if (next.length <= 8 && next.all { it.isDigit() }) {
                        pinInput = next
                        errorMessage = null
                    }
                },
                label = { Text("Enter Vault PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .testTag("vault_pin_input")
            )
            Spacer(Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDismissOptional != null) {
                    OutlinedButton(onClick = onDismissOptional) {
                        Text("Cancel")
                    }
                }
                Button(
                    onClick = {
                        if (vaultManager.verifyPin(pinInput)) {
                            onUnlocked()
                        } else {
                            errorMessage = "Incorrect PIN. Try again."
                            pinInput = ""
                        }
                    },
                    enabled = pinInput.length >= 4,
                    modifier = Modifier.testTag("vault_pin_unlock_btn")
                ) {
                    Text("Unlock")
                }
            }

            if (canBiometric) {
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = launchBiometric,
                    modifier = Modifier.testTag("vault_biometric_btn")
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use Fingerprint / Biometric")
                }
            }
        }
    }
}

@Composable
fun SetVaultPinDialog(
    title: String = "Set 4–8 Digit Vault PIN",
    onDismiss: () -> Unit,
    onPinConfirmed: (String) -> Unit,
) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = pin1,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin1 = it; error = null } },
                    label = { Text("New PIN (4–8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin2 = it; error = null } },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        pin1.length < 4 -> error = "PIN must be at least 4 digits"
                        pin1 != pin2 -> error = "PINs do not match"
                        else -> onPinConfirmed(pin1)
                    }
                }
            ) { Text("Save PIN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun VaultAndBackupDialog(
    vaultManager: SecurityVaultManager,
    libraryRepository: LibraryRepository,
    docCount: Int,
    folderCount: Int,
    assetCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPin by remember { mutableStateOf(vaultManager.hasPin()) }
    var appLockEnabled by remember { mutableStateOf(vaultManager.isAppLockEnabled()) }
    var biometricEnabled by remember { mutableStateOf(vaultManager.isBiometricEnabled()) }
    val canBiometric = remember(context) { vaultManager.canUseBiometrics(context) }

    var showSetPinDialog by remember { mutableStateOf(false) }
    var pendingEnableLockAfterPin by remember { mutableStateOf(false) }
    var isWorkingBackup by remember { mutableStateOf(false) }
    var backupResultMsg by remember { mutableStateOf<String?>(null) }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorkingBackup = true
            backupResultMsg = null
            try {
                val stats: BackupStats? = context.contentResolver.openOutputStream(uri)?.use { out ->
                    libraryRepository.exportFullBackupZip(out)
                }
                backupResultMsg = if (stats != null) {
                    "Backup saved: ${stats.documentCount} docs, ${stats.folderCount} folders, ${stats.signatureCount} signs/stamps"
                } else {
                    "Could not open backup destination"
                }
            } catch (e: Exception) {
                backupResultMsg = "Backup failed: ${e.message}"
            } finally {
                isWorkingBackup = false
            }
        }
    }

    val restoreZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorkingBackup = true
            backupResultMsg = null
            try {
                val stats: BackupStats? = context.contentResolver.openInputStream(uri)?.use { input ->
                    libraryRepository.restoreFromBackupZip(input)
                }
                backupResultMsg = if (stats != null) {
                    "Restored ${stats.documentCount} docs, ${stats.folderCount} folders, ${stats.signatureCount} signs/stamps!"
                } else {
                    "Could not read selected backup archive"
                }
            } catch (e: Exception) {
                backupResultMsg = "Restore failed: ${e.message}"
            } finally {
                isWorkingBackup = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Vault Lock & Offline Backup")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Security Lock Section
                Text(
                    text = "Privacy & App Lock",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!appLockEnabled && !hasPin) {
                                pendingEnableLockAfterPin = true
                                showSetPinDialog = true
                            } else {
                                appLockEnabled = !appLockEnabled
                                vaultManager.setAppLockEnabled(appLockEnabled)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lock App on Launch", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = "Require PIN or fingerprint when opening ProScan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appLockEnabled,
                        onCheckedChange = { checked ->
                            if (checked && !hasPin) {
                                pendingEnableLockAfterPin = true
                                showSetPinDialog = true
                            } else {
                                appLockEnabled = checked
                                vaultManager.setAppLockEnabled(checked)
                            }
                        }
                    )
                }

                if (canBiometric) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fingerprint / Biometric", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "Also unlocks private locked folders",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = {
                                biometricEnabled = it
                                vaultManager.setBiometricEnabled(it)
                            }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pendingEnableLockAfterPin = false
                            showSetPinDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (hasPin) "Change PIN" else "Set Vault PIN")
                    }
                    if (hasPin) {
                        OutlinedButton(
                            onClick = {
                                vaultManager.clearPin()
                                hasPin = false
                                appLockEnabled = false
                                Toast.makeText(context, "Vault PIN removed", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Remove PIN", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Offline ZIP Backup & Restore Section
                Text(
                    text = "Offline ZIP Backup & Restore",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Back up $docCount docs, $folderCount folders & $assetCount signs/stamps into a single offline .zip file — move between phones anytime without cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isWorkingBackup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Processing offline archive…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                backupResultMsg?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        exportZipLauncher.launch("ProScan_Backup_$stamp.zip")
                    },
                    enabled = !isWorkingBackup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_export_zip_btn")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export Full Backup (.zip)")
                }

                OutlinedButton(
                    onClick = {
                        restoreZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    enabled = !isWorkingBackup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_restore_zip_btn")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from Backup (.zip)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showSetPinDialog) {
        SetVaultPinDialog(
            onDismiss = {
                showSetPinDialog = false
                pendingEnableLockAfterPin = false
            },
            onPinConfirmed = { newPin ->
                vaultManager.setPin(newPin)
                hasPin = true
                if (pendingEnableLockAfterPin) {
                    vaultManager.setAppLockEnabled(true)
                    appLockEnabled = true
                }
                pendingEnableLockAfterPin = false
                showSetPinDialog = false
                Toast.makeText(context, "Vault PIN saved", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
