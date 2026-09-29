package com.anland.design.maintenance

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.anland.design.NavigationSettingItem
import com.anland.design.SettingGroup
import com.anland.design.R

@Composable
fun MaintenanceEntries(activityClass: Class<out MaintenanceActivity>) {
    val context=LocalContext.current
    SettingGroup(stringResource(R.string.maintenance_backup_and_restore)) {
        NavigationSettingItem(stringResource(R.string.maintenance_backup_and_restore),
            description=stringResource(R.string.maintenance_backup_and_restore_summary), icon=Icons.Outlined.Backup,
            onClick={context.startActivity(Intent(context,activityClass).putExtra("page","backup"))})
    }
    SettingGroup(stringResource(R.string.maintenance_settings_about)) {
        NavigationSettingItem(stringResource(R.string.maintenance_settings_about),
            description=stringResource(R.string.maintenance_about_summary),icon=Icons.Outlined.Info,
            onClick={context.startActivity(Intent(context,activityClass).putExtra("page","about"))})
    }
}
