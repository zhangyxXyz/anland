package com.anland.design.maintenance

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.anland.design.R
import com.anland.design.update.AppRelease
import com.anland.design.update.UpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ApkInstaller(val busy: Boolean, val install: (AppRelease) -> Unit)

@Composable
internal fun rememberApkInstaller(client: UpdateClient, progress: (Long,Long) -> Unit): ApkInstaller {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("") }
    var pendingCode by rememberSaveable { mutableLongStateOf(0) }
    var pendingHash by rememberSaveable { mutableStateOf("") }
    fun notifyFailure(error: Throwable) { Toast.makeText(context,context.getString(R.string.maintenance_update_download_failed,error.message.orEmpty()),Toast.LENGTH_LONG).show() }
    fun launchPending(): kotlinx.coroutines.Job? {
        val path=pendingPath ?: return null
        busy=true
        return scope.launch {
            try {
                val file=File(path)
                withContext(Dispatchers.IO) {
                    require(file.canonicalFile.parentFile == File(context.cacheDir,"updates").canonicalFile) { "Invalid update file" }
                    val hash=file.inputStream().use { stream ->
                        val digest=java.security.MessageDigest.getInstance("SHA-256")
                        val buffer=ByteArray(65536)
                        while(true) { val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
                        UpdateClient.hex(digest.digest())
                    }
                    require(hash==pendingHash) { "Downloaded APK changed" }
                    val pm=context.packageManager
                    val candidate=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
                    val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
                    require(candidate.packageName==context.packageName) { "APK belongs to another app" }
                    require(PackageInfoCompat.getLongVersionCode(candidate)==pendingCode && candidate.versionName==pendingName) { "APK version differs from release manifest" }
                    require(pendingCode>=PackageInfoCompat.getLongVersionCode(installed)) { "APK downgrade refused" }
                    val current=installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
                    val signing=candidate.signingInfo ?: error("Unsigned APK")
                    val accepted=if(signing.hasMultipleSigners()) signing.apkContentsSigners.toSet()==current
                        else current.size==1 && signing.signingCertificateHistory.toSet().containsAll(current)
                    require(current.isNotEmpty() && accepted) { "APK signing certificate does not match the installed app" }
                }
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                pendingPath=null
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
              catch(e: Exception) { notifyFailure(e) }
              finally { busy=false }
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) launchPending()
        else Toast.makeText(context,R.string.maintenance_update_allow_install,Toast.LENGTH_LONG).show()
    }
    return ApkInstaller(busy) { release ->
        if(!busy) {
            val asset=release.apk
            if(asset!=null) {
                busy=true
                scope.launch {
                    try {
                        val target=File(context.cacheDir,"updates/${asset.name}")
                        val file=client.download(asset,target) { copied,total -> withContext(Dispatchers.Main) { progress(copied,total) } }.getOrThrow()
                        pendingPath=file.path;pendingName=release.versionName;pendingCode=release.versionCode;pendingHash=asset.sha256
                        if(context.packageManager.canRequestPackageInstalls()) launchPending()?.join()
                        else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
                    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                      catch(e: Exception) { notifyFailure(e) }
                      finally { busy=false }
                }
            }
        }
    }
}
