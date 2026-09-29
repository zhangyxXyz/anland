package com.anland.shell

import androidx.compose.runtime.Composable
import com.anland.design.Appearance
import com.anland.design.AppearanceSettings
import com.anland.design.Page

@Composable
internal fun ShellSettings(appearance:Appearance) {
    Page {
        AppearanceSettings(appearance)
        com.anland.design.maintenance.MaintenanceEntries(ShellMaintenanceActivity::class.java)
    }
}
