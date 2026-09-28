package com.anland.shell

import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.anland.design.WithAnlandTheme

object LaunchUi {
    @JvmStatic fun blocked(activity: AppCompatActivity, manage: Runnable) {
        activity.setContent { WithAnlandTheme {
            AlertDialog(onDismissRequest = { activity.finish() },
                title = { Text(stringResource(R.string.desktop_close_first_title)) },
                text = { Text(stringResource(R.string.desktop_close_first)) },
                confirmButton = { TextButton(onClick = { manage.run() }) { Text(stringResource(R.string.desktop_manage_windows)) } },
                dismissButton = { TextButton(onClick = { activity.finish() }) { Text(stringResource(android.R.string.cancel)) } })
        } }
    }
}
