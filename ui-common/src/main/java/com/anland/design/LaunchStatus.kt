package com.anland.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A slow launch needs feedback, but a fast one should not flash a loading page. */
@Composable
fun LaunchStatus(message: String, cancel: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(700); visible = true }
    if (visible) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.BottomCenter) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
            shadowElevation = 4.dp, modifier = Modifier.widthIn(max = 520.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = cancel) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
