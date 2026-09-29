package com.anland.design.maintenance

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.anland.design.R
import com.anland.design.update.AppRelease
import com.anland.design.update.ReleaseListResult
import com.anland.design.update.UpdateClient
import com.anland.design.update.UpdateServiceConfig

private sealed interface ReleaseHistoryState {
    data object Loading : ReleaseHistoryState
    data object AuthorizationRequired : ReleaseHistoryState
    data class Ready(val releases: List<AppRelease>) : ReleaseHistoryState
    data class Error(val message: String) : ReleaseHistoryState
}

@Composable
fun ReleaseHistoryScreen() {
    val context = LocalContext.current
    val client = remember { UpdateClient(context) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ReleaseHistoryState>(ReleaseHistoryState.Loading) }

    LaunchedEffect(reload) {
        state = ReleaseHistoryState.Loading
        state = when (val result = client.releases()) {
            is ReleaseListResult.Success -> ReleaseHistoryState.Ready(result.releases)
            ReleaseListResult.AuthorizationRequired -> ReleaseHistoryState.AuthorizationRequired
            is ReleaseListResult.Failure -> {
                ReleaseHistoryState.Error(result.message)
            }
        }
    }

    when (val current = state) {
        ReleaseHistoryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        ReleaseHistoryState.AuthorizationRequired -> ReleaseHistoryMessage(
            title = stringResource(R.string.maintenance_update_private_login),
            detail = stringResource(R.string.maintenance_release_history_login_summary, "GitHub"),
            onRetry = { reload++ },
        )
        is ReleaseHistoryState.Error -> ReleaseHistoryMessage(
            title = stringResource(R.string.maintenance_operation_failed),
            detail = current.message,
            onRetry = { reload++ },
        )
        is ReleaseHistoryState.Ready -> {
            if (current.releases.isEmpty()) ReleaseHistoryMessage(
                title = stringResource(R.string.maintenance_update_no_release),
                detail = stringResource(R.string.maintenance_release_history_summary),
                onRetry = { reload++ },
            ) else LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(current.releases, key = { it.tag }) { release ->
                    ReleaseHistoryCard(release) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseHistoryMessage(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.NewReleases, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(detail, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onRetry, Modifier.padding(top = 16.dp)) {
            Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.maintenance_retry))
        }
    }
}

@Composable
private fun ReleaseHistoryCard(release: AppRelease, onOpen: () -> Unit) {
    var expanded by rememberSaveable(release.tag) { mutableStateOf(false) }
    val context = LocalContext.current
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val current = release.versionCode == androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed) && release.versionName == installed.versionName
    val expandable = release.body.length > 120
    val bodyScrollState = rememberScrollState()
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (current) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(release.name.ifBlank { release.tag }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append(release.tag)
                            release.publishedAt.take(10).takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (current) AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.maintenance_current_version)) })
                else if (release.prerelease) AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.maintenance_prerelease)) })
            }
            if (release.body.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            when {
                                !expandable -> Modifier
                                expanded -> Modifier.heightIn(max = 360.dp).verticalScroll(bodyScrollState)
                                else -> Modifier.heightIn(max = 112.dp).clipToBounds()
                            },
                        ),
                ) {
                    Markdown(
                        release.body,
                        modifier = Modifier.fillMaxWidth(),
                        typography = releaseCardMarkdownTypography(),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                if (expandable) TextButton({ expanded = !expanded }) { Text(stringResource(if (expanded) R.string.maintenance_collapse else R.string.maintenance_expand)) }
                TextButton(onOpen) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.maintenance_view_release))
                }
            }
        }
    }
}

@Composable
private fun releaseCardMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.titleMedium,
    h2 = MaterialTheme.typography.titleSmall,
    h3 = MaterialTheme.typography.labelLarge,
    h4 = MaterialTheme.typography.labelLarge,
    h5 = MaterialTheme.typography.labelLarge,
    h6 = MaterialTheme.typography.labelLarge,
    text = MaterialTheme.typography.bodySmall,
    paragraph = MaterialTheme.typography.bodySmall,
    ordered = MaterialTheme.typography.bodySmall,
    bullet = MaterialTheme.typography.bodySmall,
    list = MaterialTheme.typography.bodySmall,
)
