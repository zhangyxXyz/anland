package com.anland.design.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class BackupSettings(
    val webDav: WebDavConfig,
    val localRetentionCount: Int,
    val remoteRetentionCount: Int,
    val localTreeUri: String?,
    val encryptionPassword: String,
    val includeWebDavConfig: Boolean,
)

enum class BackupMode(val includesLocal: Boolean, val includesRemote: Boolean) {
    LOCAL_AND_REMOTE(true, true),
    LOCAL(true, false),
    REMOTE(false, true),
}

data class BackupResult(
    val localFile: File?,
    val remoteUploaded: Boolean,
    val remoteError: String? = null,
)

data class LocalBackup(
    val name: String,
    val modifiedAt: Long,
    val size: Long,
    internal val documentUri: Uri? = null,
    internal val internalFile: File? = null,
)

class BackupManager(private val context: Context) {
    val defaultDirectory = "Anland/" + context.packageName
    private val backupPrefix = "backup-${context.packageName}-"
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretPreferences(context, "anland.backup.secrets")
    private val backupDirectory = File(context.filesDir, "backups")

    fun settings(): BackupSettings = BackupSettings(
        webDav = WebDavConfig(
            prefs.getString(KEY_URL, "").orEmpty(),
            prefs.getString(KEY_USER, "").orEmpty(),
            secrets.read(KEY_PASSWORD),
            prefs.getString(KEY_DIRECTORY, defaultDirectory).orEmpty().ifBlank { defaultDirectory },
            prefs.getString(KEY_DEVICE_NAME, Build.MODEL).orEmpty().ifBlank { Build.MODEL },
        ),
        localRetentionCount = prefs.getInt(KEY_LOCAL_RETENTION, prefs.getInt(KEY_OLD_RETENTION, 5)).takeIf { it in RETENTION_VALUES } ?: 5,
        remoteRetentionCount = prefs.getInt(KEY_REMOTE_RETENTION, prefs.getInt(KEY_OLD_RETENTION, 5)).takeIf { it in RETENTION_VALUES } ?: 5,
        localTreeUri = prefs.getString(KEY_TREE_URI, null),
        encryptionPassword = secrets.read(KEY_ENCRYPTION_PASSWORD),
        includeWebDavConfig = prefs.getBoolean(KEY_INCLUDE_WEBDAV_CONFIG, false),
    )

    fun saveSettings(settings: BackupSettings) {
        secrets.write(KEY_PASSWORD, settings.webDav.password)
        secrets.write(KEY_ENCRYPTION_PASSWORD, settings.encryptionPassword)
        prefs.edit()
            .putString(KEY_URL, settings.webDav.url.trim())
            .putString(KEY_USER, settings.webDav.username.trim())
            .putString(KEY_DIRECTORY, settings.webDav.directory.trim().trim('/'))
            .putString(KEY_DEVICE_NAME, settings.webDav.deviceName.trim())
            .putInt(KEY_LOCAL_RETENTION, settings.localRetentionCount.takeIf { it in RETENTION_VALUES } ?: 5)
            .putInt(KEY_REMOTE_RETENTION, settings.remoteRetentionCount.takeIf { it in RETENTION_VALUES } ?: 5)
            .putString(KEY_TREE_URI, settings.localTreeUri)
            .putBoolean(KEY_INCLUDE_WEBDAV_CONFIG, settings.includeWebDavConfig)
            .apply()
    }

    fun setLocalTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        saveSettings(settings().copy(localTreeUri = uri.toString()))
    }

    suspend fun createBackup(inputs: List<BackupInput>, mode: BackupMode = BackupMode.LOCAL_AND_REMOTE): BackupResult = withContext(Dispatchers.IO) {
        val config = settings()
        val deviceName = config.webDav.deviceName.trim().ifBlank { Build.MODEL }
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-').ifBlank { "Android" }
        val name = "$backupPrefix$deviceName-${LocalDateTime.now().format(FILE_TIME)}.zip"
        if (mode.includesLocal) require(!config.localTreeUri.isNullOrBlank()) { "请先选择本地备份目录" }
        require(!config.includeWebDavConfig || config.encryptionPassword.isNotEmpty()) { context.getString(com.anland.design.R.string.maintenance_secrets_require_password) }
        val archiveInputs = if (config.includeWebDavConfig) inputs + webDavConfigInput(config.webDav) else inputs
        val archive = BackupArchive.pack(
            archiveInputs,
            File(if (mode.includesLocal) backupDirectory else context.cacheDir, name),
            config.encryptionPassword,
        )
        var localFile: File? = null
        if (mode.includesLocal) {
            copyToTree(archive, Uri.parse(config.localTreeUri), config.localRetentionCount)
            pruneLocal(config.localRetentionCount)
            localFile = archive
        }
        var remoteUploaded = false
        var remoteError: String? = null
        if (mode.includesRemote) {
            if (!config.webDav.isConfigured) {
                remoteError = "WebDAV 配置不完整"
            } else {
                runCatching {
                    WebDavClient(config.webDav, backupPrefix = backupPrefix).apply {
                        upload(archive)
                        prune(config.remoteRetentionCount)
                    }
                }.onSuccess { remoteUploaded = true }
                    .onFailure { remoteError = it.localizedMessage ?: "WebDAV 备份失败" }
            }
        }
        if (!mode.includesLocal) archive.delete()
        BackupResult(localFile, remoteUploaded, remoteError)
    }

    suspend fun localBackups(): List<LocalBackup> = withContext(Dispatchers.IO) {
        val external = settings().localTreeUri?.let { treeUri ->
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: error("本地备份目录不可用，请重新选择")
            tree.listFiles().mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                if (!document.isFile || !isBackupName(name)) return@mapNotNull null
                LocalBackup(name, document.lastModified(), document.length(), documentUri = document.uri)
            }
        }.orEmpty()
        val externalNames = external.mapTo(hashSetOf()) { it.name }
        val internal = backupDirectory.listFiles { file -> file.isFile && isBackupName(file.name) }
            ?.filterNot { it.name in externalNames }
            ?.map { LocalBackup(it.name, it.lastModified(), it.length(), internalFile = it) }
            .orEmpty()
        (external + internal).sortedByDescending(LocalBackup::modifiedAt)
    }

    suspend fun remoteBackups(): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val config = settings().webDav
        require(config.isConfigured) { "请先配置 WebDAV" }
        WebDavClient(config, backupPrefix = backupPrefix).listBackups()
    }

    suspend fun testWebDav(): Unit = withContext(Dispatchers.IO) {
        val config = settings().webDav
        require(config.isConfigured) { "WebDAV 配置不完整" }
        WebDavClient(config, backupPrefix = backupPrefix).test()
    }

    suspend fun restore(file: File): RestoredBackup = withContext(Dispatchers.IO) {
        unpackAndRestoreSettings(file)
    }

    suspend fun restore(backup: LocalBackup): RestoredBackup = when {
        backup.documentUri != null -> restore(backup.documentUri)
        backup.internalFile != null -> restore(backup.internalFile)
        else -> error("本地备份文件不可用")
    }

    suspend fun restore(uri: Uri): RestoredBackup = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-input.zip")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) }
            ?: error("无法读取备份文件")
        try { unpackAndRestoreSettings(temp) } finally { temp.delete() }
    }

    suspend fun restore(remote: RemoteBackup): RestoredBackup = withContext(Dispatchers.IO) {
        val current = settings()
        val temp = WebDavClient(current.webDav, backupPrefix = backupPrefix).download(remote.name, File(context.cacheDir, remote.name))
        try { unpackAndRestoreSettings(temp) } finally { temp.delete() }
    }

    private fun unpackAndRestoreSettings(file: File): RestoredBackup {
        return try { BackupArchive.unpack(file, freshRestoreDirectory(), settings().encryptionPassword) }
        catch (error: Exception) { freshRestoreDirectory().deleteRecursively(); throw error }
    }

    fun validateSettings(restored: RestoredBackup) {
        restored.text(WEBDAV_CONFIG_ENTRY)?.let { raw ->
            val json = JSONObject(raw)
            listOf("url", "username", "password", "directory", "deviceName").forEach { require(json.has(it) && json.get(it) is String) }
            val url = json.getString("url")
            if (url.isNotBlank()) WebDavClient(WebDavConfig(url, json.getString("username"), json.getString("password"), json.getString("directory")))
        }
    }

    fun restoreSettings(restored: RestoredBackup) {
        restored.text(WEBDAV_CONFIG_ENTRY)?.let(::restoreWebDavConfig)
    }

    private fun webDavConfigInput(config: WebDavConfig) = BackupInput.Serialized(
        WEBDAV_CONFIG_ENTRY,
        JSONObject().apply {
            put("url", config.url)
            put("username", config.username)
            put("password", config.password)
            put("directory", config.directory)
            put("deviceName", config.deviceName)
        }.toString(),
    )

    private fun restoreWebDavConfig(raw: String) {
        val json = JSONObject(raw)
        val current = settings()
        saveSettings(current.copy(webDav = WebDavConfig(
            url = json.optString("url"),
            username = json.optString("username"),
            password = json.optString("password"),
            directory = json.optString("directory", defaultDirectory).ifBlank { defaultDirectory },
            deviceName = json.optString("deviceName", Build.MODEL).ifBlank { Build.MODEL },
        )))
    }

    private fun freshRestoreDirectory() = File(context.cacheDir, "restored-backup")

    private fun isBackupName(name: String) = name.startsWith(backupPrefix) && name.endsWith(".zip", ignoreCase = true)

    private fun pruneLocal(keep: Int) {
        if (keep == 0) return
        backupDirectory.listFiles { file -> file.isFile && file.name.startsWith(backupPrefix) && file.extension == "zip" }
            ?.sortedByDescending(File::lastModified)?.drop(keep.coerceAtLeast(1))?.forEach(File::delete)
    }

    private fun copyToTree(source: File, treeUri: Uri, keep: Int) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: error("本地备份目录不可用")
        tree.findFile(source.name)?.delete()
        val target = tree.createFile("application/zip", source.name) ?: error("无法在所选目录创建备份")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: error("无法写入本地备份")
        if (keep > 0) tree.listFiles().filter { it.isFile && it.name?.startsWith(backupPrefix) == true && it.name?.endsWith(".zip") == true }
            .sortedByDescending(DocumentFile::lastModified).drop(keep).forEach(DocumentFile::delete)
    }

    companion object {
        private const val PREFS = "backup_settings"
        private const val KEY_URL = "webdav_url"
        private const val KEY_USER = "webdav_user"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_DIRECTORY = "webdav_directory"
        private const val KEY_DEVICE_NAME = "webdav_device_name"
        private const val KEY_OLD_RETENTION = "retention_count"
        private const val KEY_LOCAL_RETENTION = "local_retention_count"
        private const val KEY_REMOTE_RETENTION = "remote_retention_count"
        private const val KEY_TREE_URI = "local_tree_uri"
        private const val KEY_ENCRYPTION_PASSWORD = "encryption_password"
        private const val KEY_INCLUDE_WEBDAV_CONFIG = "include_webdav_config"
        private const val WEBDAV_CONFIG_ENTRY = "backup/webdav.json"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        val RETENTION_VALUES = listOf(1, 3, 5, 10, 0)
    }
}
