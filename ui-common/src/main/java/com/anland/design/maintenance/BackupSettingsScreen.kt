package com.anland.design.maintenance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anland.design.R
import com.anland.design.backup.*
import com.anland.design.NavigationSettingItem
import com.anland.design.SettingGroup
import com.anland.design.SwitchSettingItem
import com.anland.design.SettingTitleWithHelp
import kotlinx.coroutines.launch
import android.text.format.DateFormat
import android.provider.DocumentsContract
import android.widget.Toast

@Composable
fun BackupSettingsScreen(
    backupInputs: suspend () -> List<BackupInput>,
    onRestored: suspend (RestoredBackup) -> Unit,
    onOpenWebDav: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val manager = remember { BackupManager(context.applicationContext) }
    var settings by remember { mutableStateOf(manager.settings()) }
    var busy by remember { mutableStateOf(false) }
    var localChoices by remember { mutableStateOf<List<LocalBackup>?>(null) }
    var remoteChoices by remember { mutableStateOf<List<RemoteBackup>?>(null) }
    var pendingBackupMode by remember { mutableStateOf<BackupMode?>(null) }
    var showBackupModeDialog by remember { mutableStateOf(false) }
    var retentionTarget by remember { mutableStateOf<String?>(null) }
    var showBackupPasswordDialog by remember { mutableStateOf(false) }
    var editingBackupPassword by remember { mutableStateOf("") }
    var editingBackupPasswordVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runAction(success: String? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { action() }
                .onSuccess { if (success != null) Toast.makeText(context, success, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.maintenance_operation_failed), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    suspend fun applyRestore(restored: RestoredBackup) {
        try {
            manager.validateSettings(restored)
            onRestored(restored)
            manager.restoreSettings(restored)
            settings = manager.settings()
            com.anland.design.applySavedAppearance(context)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(context.getSharedPreferences(
                    com.anland.design.LANGUAGE_PREFERENCES, android.content.Context.MODE_PRIVATE
                ).getString(com.anland.design.LANGUAGE_TAG, "").orEmpty())
            )
        } finally { restored.directory.deleteRecursively() }
    }

    fun performBackup(mode: BackupMode) {
        manager.saveSettings(settings)
        runAction {
            val result = manager.createBackup(backupInputs(), mode)
            val text = when (mode) {
                BackupMode.LOCAL -> context.getString(R.string.maintenance_backup_local_complete)
                BackupMode.REMOTE -> if (result.remoteUploaded) context.getString(R.string.maintenance_backup_remote_complete)
                    else context.getString(R.string.maintenance_backup_remote_failed, result.remoteError.orEmpty())
                BackupMode.LOCAL_AND_REMOTE -> if (result.remoteUploaded) context.getString(R.string.maintenance_backup_complete)
                    else context.getString(R.string.maintenance_backup_local_complete_remote_failed, result.remoteError.orEmpty())
            }
            Toast.makeText(context, text, if (result.remoteError == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { manager.setLocalTree(it); settings = manager.settings() }
                .onSuccess {
                    pendingBackupMode?.let(::performBackup)
                        ?: Toast.makeText(context, R.string.maintenance_backup_folder_selected, Toast.LENGTH_SHORT).show()
                }
                .onFailure { error -> Toast.makeText(context, error.localizedMessage ?: context.getString(R.string.maintenance_operation_failed), Toast.LENGTH_LONG).show() }
        }
        pendingBackupMode = null
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runAction(context.getString(R.string.maintenance_restore_complete)) {
            applyRestore(manager.restore(it))
            settings = manager.settings()
        } }
    }
    fun requestBackup(mode: BackupMode) {
        if (mode.includesLocal && settings.localTreeUri == null) {
            pendingBackupMode = mode
            treePicker.launch(defaultDocumentsUri())
        } else performBackup(mode)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackupDashboardCard(
                localReady = settings.localTreeUri != null,
                cloudReady = settings.webDav.isConfigured,
                busy = busy,
                onClick = { requestBackup(BackupMode.LOCAL_AND_REMOTE) },
                onLongClick = { showBackupModeDialog = true },
            )
        }
        item {
            val backupPath = settings.localTreeUri?.let(::displayBackupPath) ?: RECOMMENDED_BACKUP_PATH
            BackupStoragePanel(
                path = backupPath,
                retention = retentionLabel(settings.localRetentionCount),
                onChooseFolder = { treePicker.launch(defaultDocumentsUri()) },
                onCopyFolder = {
                    clipboard.setText(AnnotatedString(backupPath))
                    Toast.makeText(context, R.string.maintenance_backup_path_copied, Toast.LENGTH_SHORT).show()
                },
                onRestore = { runAction { localChoices = manager.localBackups() } },
                onRestoreOther = { filePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                onRetention = { retentionTarget = "local" },
            )
        }
        item {
            BackupCloudPanel(
                configured = settings.webDav.isConfigured,
                preview = if (settings.webDav.isConfigured) stringResource(
                    R.string.maintenance_webdav_configuration_preview,
                    settings.webDav.url,
                    settings.webDav.directory.ifBlank { manager.defaultDirectory },
                ) else stringResource(R.string.maintenance_webdav_configuration_required),
                retention = retentionLabel(settings.remoteRetentionCount),
                onConfigure = onOpenWebDav,
                onTest = {
                    manager.saveSettings(settings)
                    runAction(context.getString(R.string.maintenance_webdav_test_success)) { manager.testWebDav() }
                },
                onRestore = {
                    manager.saveSettings(settings)
                    runAction { remoteChoices = manager.remoteBackups() }
                },
                onRetention = { retentionTarget = "remote" },
            )
        }
        item {
            SettingsCard(stringResource(R.string.maintenance_backup_security)) {
                WebDavSettingRow(
                    Icons.Outlined.Lock,
                    stringResource(R.string.maintenance_backup_encryption_password),
                    stringResource(if (settings.encryptionPassword.isEmpty()) R.string.maintenance_backup_encryption_disabled else R.string.maintenance_backup_encryption_enabled),
                    helpMarkdown = stringResource(R.string.maintenance_backup_encryption_help),
                ) {
                    editingBackupPassword = settings.encryptionPassword
                    editingBackupPasswordVisible = false
                    showBackupPasswordDialog = true
                }
                SwitchSettingRow(
                    Icons.Outlined.CloudSync,
                    stringResource(R.string.maintenance_backup_webdav_config),
                    stringResource(R.string.maintenance_backup_webdav_config_summary),
                    settings.includeWebDavConfig,
                    helpMarkdown = stringResource(R.string.maintenance_backup_webdav_config_help),
                ) { enabled ->
                    settings = settings.copy(includeWebDavConfig = enabled)
                    manager.saveSettings(settings)
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }

    localChoices?.let { choices -> BackupChoiceDialog(
        title = stringResource(R.string.maintenance_restore_local),
        choices = choices.map { backup ->
            val time = if (backup.modifiedAt > 0) DateFormat.format("yyyy-MM-dd HH:mm", backup.modifiedAt).toString() else context.getString(R.string.maintenance_unknown_time)
            val size = android.text.format.Formatter.formatShortFileSize(context, backup.size)
            BackupChoiceUi(backup.name, "$time · $size", false)
        },
        onDismiss = { localChoices = null },
    ) { index -> localChoices = null; runAction(context.getString(R.string.maintenance_restore_complete)) {
        applyRestore(manager.restore(choices[index])); settings = manager.settings()
    } } }
    remoteChoices?.let { choices -> BackupChoiceDialog(
        title = stringResource(R.string.maintenance_remote_backup_count, choices.size),
        choices = choices.map { remote ->
            val time = if (remote.modifiedAt > 0) DateFormat.format("yyyy-MM-dd HH:mm", remote.modifiedAt).toString() else context.getString(R.string.maintenance_unknown_time)
            val size = android.text.format.Formatter.formatShortFileSize(context, remote.size)
            BackupChoiceUi(remote.name, "$time · $size", true)
        }, onDismiss = { remoteChoices = null },
    ) { index -> remoteChoices = null; runAction(context.getString(R.string.maintenance_restore_complete)) {
        applyRestore(manager.restore(choices[index])); settings = manager.settings()
    } } }
    retentionTarget?.let { target ->
        val current = if (target == "local") settings.localRetentionCount else settings.remoteRetentionCount
        RetentionChoiceDialog(
            title = stringResource(if (target == "local") R.string.maintenance_backup_local_retention else R.string.maintenance_backup_remote_retention),
            selected = current,
            onDismiss = { retentionTarget = null },
        ) { value ->
            settings = if (target == "local") settings.copy(localRetentionCount = value) else settings.copy(remoteRetentionCount = value)
            manager.saveSettings(settings)
            retentionTarget = null
        }
    }
    if (showBackupPasswordDialog) AlertDialog(
        onDismissRequest = { showBackupPasswordDialog = false },
        title = { Text(stringResource(R.string.maintenance_backup_encryption_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    editingBackupPassword, { editingBackupPassword = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    label = { Text(stringResource(R.string.maintenance_backup_encryption_password)) },
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (editingBackupPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ editingBackupPasswordVisible = !editingBackupPasswordVisible }) {
                            Icon(if (editingBackupPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (editingBackupPasswordVisible) R.string.maintenance_password_hide else R.string.maintenance_password_show))
                        }
                    },
                )
                Text(
                    stringResource(R.string.maintenance_backup_encryption_password_summary),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton({ showBackupPasswordDialog = false }) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
        confirmButton = { TextButton({
            settings = settings.copy(encryptionPassword = editingBackupPassword)
            manager.saveSettings(settings)
            showBackupPasswordDialog = false
            Toast.makeText(context, R.string.maintenance_settings_saved, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.maintenance_save)) } },
    )
    if (showBackupModeDialog) AlertDialog(
        onDismissRequest = { showBackupModeDialog = false },
        icon = { Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.maintenance_backup_destination)) },
        dismissButton = { TextButton({ showBackupModeDialog = false }) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
        confirmButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.maintenance_backup_destination_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    Triple(BackupMode.LOCAL_AND_REMOTE, Icons.Outlined.CloudSync, R.string.maintenance_backup_target_both_summary),
                    Triple(BackupMode.LOCAL, Icons.Outlined.Folder, R.string.maintenance_backup_target_local_summary),
                    Triple(BackupMode.REMOTE, Icons.Outlined.CloudUpload, R.string.maintenance_backup_target_remote_summary),
                ).forEach { (mode, icon, summary) ->
                    val label = when (mode) {
                        BackupMode.LOCAL_AND_REMOTE -> stringResource(R.string.maintenance_backup_to_local_and_remote)
                        BackupMode.LOCAL -> stringResource(R.string.maintenance_backup_to_local_only)
                        BackupMode.REMOTE -> stringResource(R.string.maintenance_backup_to_remote_only)
                    }
                    Surface(
                        onClick = { showBackupModeDialog = false; requestBackup(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackupDashboardCard(
    localReady: Boolean,
    cloudReady: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !busy, onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(27.dp), strokeWidth = 2.5.dp)
                    else Icon(Icons.Outlined.Backup, null, Modifier.size(29.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    SettingTitleWithHelp(stringResource(R.string.maintenance_backup_now), stringResource(R.string.maintenance_backup_now_help), enabled = !busy)
                    Text(
                        stringResource(R.string.maintenance_backup_now_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackupReadyPill(Modifier.weight(1f), Icons.Outlined.Folder, stringResource(R.string.maintenance_backup_local), localReady)
                BackupReadyPill(Modifier.weight(1f), Icons.Outlined.Cloud, "WebDAV", cloudReady)
            }
        }
    }
}

@Composable
private fun BackupReadyPill(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ready: Boolean) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = stringResource(if (ready) R.string.maintenance_backup_target_ready else R.string.maintenance_backup_target_setup),
                modifier = Modifier.size(20.dp),
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BackupStoragePanel(
    path: String,
    retention: String,
    onChooseFolder: () -> Unit,
    onCopyFolder: () -> Unit,
    onRestore: () -> Unit,
    onRestoreOther: () -> Unit,
    onRetention: () -> Unit,
) {
    BackupPanel(title = stringResource(R.string.maintenance_backup_local), icon = Icons.Outlined.Storage) {
        @OptIn(ExperimentalFoundationApi::class)
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onChooseFolder, onLongClick = onCopyFolder).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SettingTitleWithHelp(stringResource(R.string.maintenance_backup_folder), stringResource(R.string.maintenance_backup_folder_help))
                Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupMiniAction(Modifier.weight(1f), Icons.Outlined.Restore, stringResource(R.string.maintenance_restore_local), onClick = onRestore)
            BackupMiniAction(Modifier.weight(1f), Icons.Outlined.FolderZip, stringResource(R.string.maintenance_restore_other_backup), onClick = onRestoreOther)
        }
        BackupRetentionFooter(stringResource(R.string.maintenance_backup_local_retention), retention, onRetention)
    }
}

@Composable
private fun BackupCloudPanel(
    configured: Boolean,
    preview: String,
    retention: String,
    onConfigure: () -> Unit,
    onTest: () -> Unit,
    onRestore: () -> Unit,
    onRetention: () -> Unit,
) {
    BackupPanel(title = "WebDAV", icon = Icons.Outlined.CloudQueue) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onConfigure).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.maintenance_webdav_configuration), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupMiniAction(Modifier.weight(1f), Icons.Outlined.CloudDone, stringResource(R.string.maintenance_webdav_test), configured, onTest)
            BackupMiniAction(Modifier.weight(1f), Icons.Outlined.CloudDownload, stringResource(R.string.maintenance_restore_webdav), configured, onRestore)
        }
        BackupRetentionFooter(stringResource(R.string.maintenance_backup_remote_retention), retention, onRetention)
    }
}

@Composable
private fun BackupPanel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun BackupMiniAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(17.dp)).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else .4f),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.Start) {
            Icon(icon, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else .45f))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else .45f))
        }
    }
}

@Composable
private fun BackupRetentionFooter(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.History, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(9.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(value, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun WebDavSettingsScreen() {
    val context = LocalContext.current
    val manager = remember { BackupManager(context.applicationContext) }
    var settings by remember { mutableStateOf(manager.settings()) }
    var editingField by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var showAuthDialog by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf("") }
    var editingPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var connectionVerified by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            WebDavOverviewCard(
                configured = settings.webDav.isConfigured,
                verified = connectionVerified,
                identity = webDavIdentity(settings.webDav.url, settings.webDav.username),
            )
        }
        item {
            WebDavEndpointCard(
                url = settings.webDav.url.ifBlank { stringResource(R.string.maintenance_not_set) },
                directory = settings.webDav.directory.ifBlank { manager.defaultDirectory },
                onEditUrl = {
                    editingField = "url"
                    editingValue = settings.webDav.url
                },
                onEditDirectory = {
                    editingField = "directory"
                    editingValue = settings.webDav.directory
                },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WebDavIdentityTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Person,
                    label = stringResource(R.string.maintenance_webdav_account_label),
                    value = settings.webDav.username.ifBlank { stringResource(R.string.maintenance_not_set) },
                    onClick = {
                        editingAccount = settings.webDav.username
                        editingPassword = settings.webDav.password
                        passwordVisible = false
                        showAuthDialog = true
                    },
                )
                WebDavIdentityTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Smartphone,
                    label = stringResource(R.string.maintenance_webdav_device_name),
                    value = settings.webDav.deviceName.ifBlank { android.os.Build.MODEL },
                    onClick = {
                    editingField = "device"
                    editingValue = settings.webDav.deviceName
                    },
                )
            }
        }
        item {
            Button(
                onClick = {
                    if (testing || !settings.webDav.isConfigured) return@Button
                    testing = true
                    scope.launch {
                        runCatching { manager.testWebDav() }
                            .onSuccess {
                                connectionVerified = true
                                Toast.makeText(context, R.string.maintenance_webdav_test_success, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                connectionVerified = false
                                Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.maintenance_operation_failed), Toast.LENGTH_LONG).show()
                            }
                        testing = false
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                enabled = settings.webDav.isConfigured && !testing,
                shape = RoundedCornerShape(18.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Outlined.CloudSync, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (testing) R.string.maintenance_webdav_connection_testing else R.string.maintenance_webdav_test))
            }
            if (!settings.webDav.isConfigured) {
                Text(
                    stringResource(R.string.maintenance_webdav_test_config_required),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    editingField?.let { field ->
        val title = stringResource(when (field) {
            "url" -> R.string.maintenance_webdav_server_label
            "directory" -> R.string.maintenance_webdav_directory
            else -> R.string.maintenance_webdav_device_name
        })
        val supporting = when (field) {
            "url" -> stringResource(R.string.maintenance_webdav_url_summary)
            "directory" -> stringResource(R.string.maintenance_webdav_directory_summary)
            else -> stringResource(R.string.maintenance_webdav_device_name_summary)
        }
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        editingValue,
                        { editingValue = it },
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        singleLine = true,
                        label = { Text(title) },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Text(supporting, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            dismissButton = { TextButton({ editingField = null }) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
            confirmButton = { TextButton({
                val webDav = when (field) {
                    "url" -> settings.webDav.copy(url = editingValue.trim())
                    "directory" -> settings.webDav.copy(directory = editingValue.trim().trim('/'))
                    else -> settings.webDav.copy(deviceName = editingValue.trim())
                }
                settings = settings.copy(webDav = webDav)
                manager.saveSettings(settings)
                connectionVerified = null
                editingField = null
                Toast.makeText(context, R.string.maintenance_settings_saved, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.maintenance_save)) } },
        )
    }

    if (showAuthDialog) AlertDialog(
        onDismissRequest = { showAuthDialog = false },
        title = { Text(stringResource(R.string.maintenance_webdav_account_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(editingAccount, { editingAccount = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp), singleLine = true, label = { Text(stringResource(R.string.maintenance_webdav_account_label)) }, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(
                    editingPassword, { editingPassword = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    label = { Text(stringResource(R.string.maintenance_webdav_password)) },
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (passwordVisible) R.string.maintenance_password_hide else R.string.maintenance_password_show))
                        }
                    },
                )
            }
        },
        dismissButton = { TextButton({ showAuthDialog = false }) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
        confirmButton = { TextButton({
            settings = settings.copy(webDav = settings.webDav.copy(username = editingAccount.trim(), password = editingPassword))
            manager.saveSettings(settings)
            connectionVerified = null
            showAuthDialog = false
            Toast.makeText(context, R.string.maintenance_settings_saved, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.maintenance_save)) } },
    )
}

@Composable
private fun WebDavOverviewCard(configured: Boolean, verified: Boolean?, identity: String) {
    val statusText = when (verified) {
        true -> stringResource(R.string.maintenance_webdav_connection_verified)
        false -> stringResource(R.string.maintenance_webdav_connection_failed)
        null -> stringResource(if (configured) R.string.maintenance_webdav_ready_to_test else R.string.maintenance_webdav_configuration_required)
    }
    val accent = when (verified) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.primary
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when (verified) {
                            true -> Icons.Outlined.CloudDone
                            false -> Icons.Outlined.CloudOff
                            null -> Icons.Outlined.CloudQueue
                        },
                        contentDescription = null,
                        modifier = Modifier.size(29.dp),
                        tint = accent,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.maintenance_webdav_space_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        identity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(9.dp))
                    Text(statusText, style = MaterialTheme.typography.labelLarge, color = accent)
                }
            }
        }
    }
}

@Composable
private fun WebDavEndpointCard(url: String, directory: String, onEditUrl: () -> Unit, onEditDirectory: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.maintenance_webdav_endpoint), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        WebDavEndpointRow(
            icon = Icons.Outlined.Dns,
            label = stringResource(R.string.maintenance_webdav_server_label),
            value = url,
            helpMarkdown = stringResource(R.string.maintenance_webdav_url_help),
            onClick = onEditUrl,
        )
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
        WebDavEndpointRow(
            icon = Icons.Outlined.FolderOpen,
            label = stringResource(R.string.maintenance_webdav_directory),
            value = directory,
            helpMarkdown = stringResource(R.string.maintenance_webdav_directory_help),
            onClick = onEditDirectory,
        )
    }
}

@Composable
private fun WebDavEndpointRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, helpMarkdown: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SettingTitleWithHelp(label, helpMarkdown)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WebDavIdentityTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun webDavIdentity(url: String, username: String): String {
    if (url.isBlank() && username.isBlank()) return "WebDAV"
    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url }
    return listOf(username, host).filter { it.isNotBlank() }.joinToString(" · ")
}

private const val RECOMMENDED_BACKUP_PATH = "Documents/Anland"

private fun defaultDocumentsUri() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

private fun displayBackupPath(uriString: String): String = runCatching {
    DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uriString)).substringAfter(':').trim('/').ifBlank { "storage/emulated/0" }
}.getOrDefault(uriString.substringAfterLast('/'))

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) = SettingGroup(title, content = content)

@Composable private fun BackupRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, enabled: Boolean = true, helpMarkdown: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) = NavigationSettingItem(
    title = title,
    description = detail,
    icon = icon,
    enabled = enabled,
    helpMarkdown = helpMarkdown,
    onLongClick = onLongClick,
    onClick = onClick,
)

@Composable private fun WebDavSettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, preview: String, helpMarkdown: String? = null, onClick: () -> Unit) = NavigationSettingItem(
    title = title,
    value = preview,
    icon = icon,
    helpMarkdown = helpMarkdown,
    onClick = onClick,
)

@Composable private fun RetentionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, valueLabel: String, onClick: () -> Unit) = NavigationSettingItem(
    title = title,
    value = valueLabel,
    icon = icon,
    onClick = onClick,
)

@Composable private fun SwitchSettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, checked: Boolean, helpMarkdown: String? = null, onCheckedChange: (Boolean) -> Unit) = SwitchSettingItem(
    title = title,
    description = detail,
    icon = icon,
    checked = checked,
    helpMarkdown = helpMarkdown,
    onCheckedChange = onCheckedChange,
)

@Composable private fun retentionLabel(value: Int) = if (value == 0) stringResource(R.string.maintenance_backup_keep_unlimited) else stringResource(R.string.maintenance_backup_keep_count, value)

@Composable private fun RetentionChoiceDialog(title: String, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
        confirmButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.maintenance_backup_retention_dialog_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackupManager.RETENTION_VALUES.filter { it > 0 }.forEach { value ->
                        val isSelected = value == selected
                        Surface(
                            onClick = { onSelect(value) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.maintenance_backup_count_unit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val unlimitedSelected = selected == 0
                Surface(
                    onClick = { onSelect(0) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (unlimitedSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, if (unlimitedSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AllInclusive, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.maintenance_backup_keep_unlimited), Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (unlimitedSelected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    )
}

private data class BackupChoiceUi(val name: String, val detail: String, val remote: Boolean)

@Composable private fun BackupChoiceDialog(title: String, choices: List<BackupChoiceUi>, onDismiss: () -> Unit, onExtra: (() -> Unit)? = null, extraLabel: String = "", onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
        text = {
        LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(choices.size) { index ->
                val choice = choices[index]
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(if (choice.remote) Icons.Outlined.CloudQueue else Icons.Outlined.FolderZip, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                            Text(choice.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(choice.detail, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (choices.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Inventory2, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.maintenance_no_backups), Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onExtra != null) item {
                OutlinedButton(onClick = onExtra, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(extraLabel)
                }
            }
        }
    })
}
