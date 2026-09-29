package com.anland.shell

import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anland.design.WithAnlandTheme

object LaunchUi {
    class Progress(initial:String) {
        var message by mutableStateOf(initial); private set
        fun setText(value:String){message=value}
    }
    @JvmStatic fun prepare(activity:AppCompatActivity)=com.anland.design.applySavedAppearance(activity)
    @JvmStatic fun progress(activity:AppCompatActivity,initial:String):Progress {
        val state=Progress(initial)
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.setContent { WithAnlandTheme {
            com.anland.design.LaunchStatus(state.message) { activity.finish() }
        } }
        return state
    }
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
