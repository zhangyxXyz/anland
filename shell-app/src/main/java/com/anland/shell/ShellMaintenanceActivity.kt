package com.anland.shell

import com.anland.design.LANGUAGE_PREFERENCES
import com.anland.design.backup.*
import com.anland.design.maintenance.MaintenanceActivity
import com.anland.shell.connections.CredentialStore
import com.anland.shell.connections.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ShellMaintenanceActivity: MaintenanceActivity() {
    private fun preferences() = AppPreferencesBackup(this,setOf("shell","anland_appearance",LANGUAGE_PREFERENCES))
    override suspend fun backupInputs(): List<BackupInput> = withContext(Dispatchers.IO) {
        val profiles=CredentialStore(this@ShellMaintenanceActivity).list()
        require(profiles.isEmpty() || BackupManager(this@ShellMaintenanceActivity).settings().encryptionPassword.isNotEmpty()) {
            getString(com.anland.design.R.string.maintenance_secrets_require_password)
        }
        listOf(preferences().export(),BackupInput.Serialized("app/connections.json",JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()))
    }
    override suspend fun restoreBackup(backup: RestoredBackup) = withContext(Dispatchers.IO) {
        val preferences=preferences()
        val values=preferences.decode(backup)
        val raw=backup.text("app/connections.json") ?: error("Missing connection backup")
        val array=JSONArray(raw)
        val profiles=(0 until array.length()).map { ConnectionProfile.fromJson(array.getJSONObject(it)) }
        require(profiles.all { it.valid() } && profiles.map { it.id }.distinct().size==profiles.size) { "Invalid connection backup" }
        // Validate every entry before replacing the authenticated vault in one atomic write.
        val store=CredentialStore(this@ShellMaintenanceActivity)
        store.replaceAll(profiles)
        preferences.restore(values)
    }
}
