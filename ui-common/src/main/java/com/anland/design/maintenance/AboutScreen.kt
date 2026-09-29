package com.anland.design.maintenance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.pm.PackageInfoCompat
import com.anland.design.R
import com.anland.design.SettingGroup
import com.anland.design.SettingItem
import com.anland.design.update.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UpdateUiState {
    data object Checking : UpdateUiState
    data object Idle : UpdateUiState
    data object LoginRequired : UpdateUiState
    data class Ready(val release: AppRelease, val newer: Boolean) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

private data class PreviewDocument(val title: String, val content: String)

@Composable
fun AboutScreen(onOpenDiagnostics: () -> Unit, onOpenReleaseHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { UpdateClient(context) }
    var licenseLoading by remember { mutableStateOf(false) }
    var readmeLoading by remember { mutableStateOf(false) }
    val config = client.config
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var releaseDialog by remember { mutableStateOf<AppRelease?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var downloadTotalBytes by remember { mutableLongStateOf(-1L) }
    var previewDocument by remember { mutableStateOf<PreviewDocument?>(null) }
    val providerName = config.providerName
    val packageInfo = remember(context.packageName) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val installedVersionName = packageInfo.versionName.orEmpty()
    val installedVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, R.string.maintenance_open_link_failed, Toast.LENGTH_SHORT).show() }
    fun checkUpdate(showResult: Boolean = false) {
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = when (val result = client.latestRelease()) {
                is ReleaseResult.Success -> {
                    val newer = result.release.isNewerThan(installedVersionName, installedVersionCode)
                    if (showResult || newer) releaseDialog = result.release
                    UpdateUiState.Ready(result.release, newer)
                }
                ReleaseResult.AuthorizationRequired -> UpdateUiState.LoginRequired
                ReleaseResult.NoRelease -> UpdateUiState.Error(context.getString(R.string.maintenance_update_no_release))
                is ReleaseResult.Failure -> UpdateUiState.Error(result.message)
            }
        }
    }
    val installer = rememberApkInstaller(client) { copied, total ->
        downloadedBytes = copied
        downloadTotalBytes = total
    }
    val downloadBusy = installer.busy
    fun install(release: AppRelease) { installer.install(release) }
    fun openRepositoryDocument(license: Boolean) {
        if (if (license) licenseLoading else readmeLoading) return
        if (license) licenseLoading = true else readmeLoading = true
        val languageTag = context.resources.configuration.locales[0].toLanguageTag()
        scope.launch {
            try {
                val result = if (license) client.repositoryLicense() else client.readme(languageTag)
                result.onSuccess {
                    previewDocument = PreviewDocument(context.getString(if (license)
                        R.string.maintenance_open_source_license else R.string.maintenance_project_information), it)
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.maintenance_document_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            } finally { if (license) licenseLoading = false else readmeLoading = false }
        }
    }
    LaunchedEffect(Unit) { checkUpdate() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(remember { context.applicationInfo.loadIcon(context.packageManager).toBitmap(164,164).asImageBitmap() }, null, Modifier.size(82.dp).clip(RoundedCornerShape(22.dp)))
                    Spacer(Modifier.height(14.dp)); Text(context.applicationInfo.loadLabel(context.packageManager).toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(top = 8.dp)) {
                        Text("v$installedVersionName ($installedVersionCode)", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(stringResource(R.string.maintenance_about_description), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            val detail = when (val state = updateState) {
                UpdateUiState.Checking -> stringResource(R.string.maintenance_update_checking, providerName)
                UpdateUiState.Idle -> stringResource(R.string.maintenance_update_check_summary, providerName)
                UpdateUiState.LoginRequired -> stringResource(R.string.maintenance_update_private_login)
                is UpdateUiState.Ready -> if (state.newer) stringResource(R.string.maintenance_update_available, state.release.versionName) else stringResource(R.string.maintenance_update_latest, installedVersionName)
                is UpdateUiState.Error -> state.message
            }
            AboutUpdatePanel(
                detail = detail,
                loading = updateState is UpdateUiState.Checking,
                onCheck = { checkUpdate(true) },
                onHistory = onOpenReleaseHistory,
            )
        }
        item {
            AboutProjectPanel(
                providerName = providerName,
                informationLoading = readmeLoading,
                onInformation = { openRepositoryDocument(false) },
                upstreamRepository = config.upstreamRepositoryLabel,
                onUpstream = { open(config.upstreamUrl) },
                onMaintainer = { open(config.projectUrl.substringBeforeLast('/')) },
                onSource = { open(config.projectUrl) },
                onIssues = { open(config.issuesUrl) },
                onContributors = { open(config.contributorsUrl) },
            )
        }
        item {
            AboutGroup(stringResource(R.string.maintenance_about_legal)) {
                AboutRow(Icons.Outlined.PrivacyTip, stringResource(R.string.maintenance_privacy_policy), stringResource(R.string.maintenance_privacy_policy_summary)) {
                    previewDocument = PreviewDocument(context.getString(R.string.maintenance_privacy_policy), context.getString(R.string.maintenance_privacy_policy_content))
                }
                AboutRow(Icons.Outlined.Gavel, stringResource(R.string.maintenance_open_source_license), stringResource(R.string.maintenance_open_source_license_summary), loading = licenseLoading) {
                    openRepositoryDocument(true)
                }
                AboutRow(Icons.AutoMirrored.Outlined.Article, stringResource(R.string.maintenance_disclaimer), stringResource(R.string.maintenance_disclaimer_summary)) {
                    previewDocument = PreviewDocument(context.getString(R.string.maintenance_disclaimer), context.getString(R.string.maintenance_disclaimer_content))
                }
                AboutRow(
                    Icons.Outlined.Info,
                    stringResource(R.string.maintenance_app_details),
                    context.packageName,
                    debugBadge = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0),
                    onLongClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("package", context.packageName))
                        Toast.makeText(context, R.string.maintenance_package_copied, Toast.LENGTH_SHORT).show()
                    },
                ) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            }
        }
        item {
            AboutStandalonePanel(
                sectionTitle = stringResource(R.string.maintenance_about_support),
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.maintenance_diagnostics),
                detail = stringResource(R.string.maintenance_diagnostics_share_summary),
                onClick = onOpenDiagnostics,
            )
        }
        item {
            AboutStandalonePanel(
                sectionTitle = stringResource(R.string.maintenance_about_design),
                icon = Icons.Outlined.Palette,
                title = "Material Design 3",
                detail = stringResource(R.string.maintenance_material_design_summary),
            ) { open("https://m3.material.io/") }
        }
    }

    releaseDialog?.let { release ->
        val newer = release.isNewerThan(installedVersionName, installedVersionCode)
        val sameVersion = release.versionCode == installedVersionCode
        AlertDialog(onDismissRequest = { if (!downloadBusy) releaseDialog = null }, icon = { Icon(Icons.Outlined.SystemUpdate, null) },
            title = { Text(if (newer) stringResource(R.string.maintenance_update_found, release.versionName) else stringResource(R.string.maintenance_update_current)) },
            text = { Column {
                Text(release.name, fontWeight = FontWeight.Bold)
                if (release.body.isNotBlank()) Box(Modifier.padding(top = 10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { Markdown(release.body, typography = aboutMarkdownTypography(), modifier = Modifier.fillMaxWidth().wrapContentHeight()) }
                if (downloadBusy) {
                    val total = downloadTotalBytes
                    val fraction = if (total > 0L) (downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                    if (total > 0L) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    Text(
                        if (total > 0L) stringResource(R.string.maintenance_update_download_progress, (fraction * 100).toInt(), Formatter.formatFileSize(context, downloadedBytes), Formatter.formatFileSize(context, total))
                        else stringResource(R.string.maintenance_update_download_progress_unknown, Formatter.formatFileSize(context, downloadedBytes)),
                        Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            } },
            dismissButton = { TextButton(enabled = !downloadBusy, onClick = { releaseDialog = null }) { Text(stringResource(R.string.maintenance_dialog_cancel)) } },
            confirmButton = {
                val canInstall = release.apk != null && (newer || sameVersion)
                Button(enabled = !downloadBusy, onClick = { if (canInstall) install(release) else open(release.pageUrl) }) {
                    Text(stringResource(when { !canInstall -> R.string.maintenance_view_release; newer -> R.string.maintenance_download_and_install; else -> R.string.maintenance_redownload_and_install }))
                }
            })
    }
    previewDocument?.let { document -> DocumentPreviewSheet(document) { previewDocument = null } }
}

@Composable private fun AboutGroup(title: String, content: @Composable ColumnScope.() -> Unit) = SettingGroup(title, content = content)

@Composable private fun AboutRow(icon: ImageVector, title: String, detail: String, loading: Boolean = false, debugBadge: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) = SettingItem(
    title = title,
    description = detail,
    icon = icon,
    enabled = !loading,
    onClick = onClick,
    onLongClick = onLongClick,
    titleTrailingContent = {
        if (debugBadge) Icon(Icons.Outlined.BugReport, stringResource(R.string.maintenance_debug_build), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
    },
    trailingContent = {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    },
)

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AboutUpdatePanel(detail: String, loading: Boolean, onCheck: () -> Unit, onHistory: () -> Unit) {
    Column {
        AboutSectionTitle(stringResource(R.string.maintenance_about_updates))
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.maintenance_check_for_updates), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCheck, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.maintenance_check_for_updates))
                }
                TextButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.maintenance_release_history))
                }
            }
        }
    }
}

@Composable
private fun AboutProjectPanel(
    providerName: String,
    informationLoading: Boolean,
    onInformation: () -> Unit,
    upstreamRepository: String,
    onUpstream: () -> Unit,
    onMaintainer: () -> Unit,
    onSource: () -> Unit,
    onIssues: () -> Unit,
    onContributors: () -> Unit,
) {
    Column {
        AboutSectionTitle(stringResource(R.string.maintenance_about_project))
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onMaintainer).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Text("Z", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("zhangyxXyz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${stringResource(R.string.maintenance_fork_maintainer)} · $providerName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onUpstream).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.Article, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(upstreamRepository, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${stringResource(R.string.maintenance_upstream_project)} · $providerName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !informationLoading, onClick = onInformation).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (informationLoading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Outlined.Article, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.maintenance_project_information), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.maintenance_project_information_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                AboutProjectAction(Modifier.weight(1f), Icons.Outlined.Code, stringResource(R.string.maintenance_source_code), onSource)
                AboutProjectAction(Modifier.weight(1f), Icons.Outlined.BugReport, stringResource(R.string.maintenance_feedback_issues), onIssues)
                AboutProjectAction(Modifier.weight(1f), Icons.Outlined.Groups, stringResource(R.string.maintenance_contributors), onContributors)
            }
        }
    }
}

@Composable
private fun AboutProjectAction(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun AboutStandalonePanel(
    sectionTitle: String,
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Column {
        AboutSectionTitle(sectionTitle)
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DocumentPreviewSheet(document: PreviewDocument, onDismiss: () -> Unit) {
    val content = remember(document) {
        val lines = document.content.lines()
        if (lines.firstOrNull()?.removePrefix("# ")?.trim() == document.title) lines.drop(1).joinToString("\n").trimStart() else document.content
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 22.dp)) {
            Text(document.title, Modifier.fillMaxWidth().padding(bottom = 18.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            SelectionContainer(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                    Markdown(content, typography = aboutMarkdownTypography(), modifier = Modifier.fillMaxWidth().wrapContentHeight())
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text(stringResource(R.string.maintenance_dialog_ok)) }
        }
    }
}

@Composable private fun aboutMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.headlineMedium,
    h2 = MaterialTheme.typography.titleLarge,
    h3 = MaterialTheme.typography.titleMedium,
    h4 = MaterialTheme.typography.titleSmall,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.titleSmall,
    text = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium,
)
