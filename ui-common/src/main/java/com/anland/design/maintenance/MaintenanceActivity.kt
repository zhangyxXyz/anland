package com.anland.design.maintenance

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anland.design.*
import com.anland.design.R
import com.anland.design.backup.*

abstract class MaintenanceActivity: AppCompatActivity() {
    protected abstract suspend fun backupInputs(): List<BackupInput>
    protected abstract suspend fun restoreBackup(backup: RestoredBackup)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme {
            val initial=intent.getStringExtra("page")?.takeIf { it in listOf("backup","about") } ?: "backup"
            var page by rememberSaveable { mutableStateOf(initial) }
            fun back() { if(page=="webdav")page="backup" else if(page=="history")page="about" else finish() }
            BackHandler { back() }
            val title=when(page) { "webdav" -> "WebDAV"; "history" -> stringResource(R.string.maintenance_release_history)
                "about" -> stringResource(R.string.maintenance_settings_about); else -> stringResource(R.string.maintenance_backup_and_restore) }
            Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={
                TopAppBar(title={Text(title)},navigationIcon={IconButton(onClick={back()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.design_back)) }},
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
                Box(Modifier.widthIn(max=920.dp).fillMaxSize()) {
                    when(page) {
                        "webdav" -> WebDavSettingsScreen()
                        "history" -> ReleaseHistoryScreen()
                        "about" -> AboutScreen(onOpenDiagnostics={shareDiagnostics()},onOpenReleaseHistory={page="history"})
                        else -> BackupSettingsScreen(backupInputs={backupInputs()},onRestored={restoreBackup(it)},onOpenWebDav={page="webdav"})
                    }
                }
            } }
        } }
    }
    private fun shareDiagnostics() {
        val info=packageManager.getPackageInfo(packageName,0)
        val text="Anland diagnostics\nPackage: $packageName\nVersion: ${info.versionName} (${info.longVersionCode})\nAndroid: ${android.os.Build.VERSION.RELEASE}\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),getString(R.string.maintenance_diagnostics)))
    }
}
