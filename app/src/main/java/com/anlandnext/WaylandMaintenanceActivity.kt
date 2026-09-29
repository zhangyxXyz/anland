package com.anlandnext

import com.anland.design.LANGUAGE_PREFERENCES
import com.anland.design.backup.*
import com.anland.design.maintenance.MaintenanceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WaylandMaintenanceActivity: MaintenanceActivity() {
    private fun preferences() = AppPreferencesBackup(this,setOf("awl","anland_appearance",LANGUAGE_PREFERENCES))
    override suspend fun backupInputs(): List<BackupInput> = withContext(Dispatchers.IO) { listOf(preferences().export()) }
    override suspend fun restoreBackup(backup: RestoredBackup) = withContext(Dispatchers.IO) {
        val preferences=preferences()
        preferences.restore(preferences.decode(backup))
        com.anlandnext.awl.WindowTaskService.sync(this@WaylandMaintenanceActivity)
    }
}
