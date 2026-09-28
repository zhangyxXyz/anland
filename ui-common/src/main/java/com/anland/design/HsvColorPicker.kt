package com.anland.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.anland.design.R

@Composable
fun HsvColorDialog(initial: String, dismiss: () -> Unit, preview: ((String) -> Unit)? = null, select: (String) -> Unit) {
    val parsed = runCatching { android.graphics.Color.parseColor(initial) }.getOrDefault(android.graphics.Color.GRAY)
    val initialHsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(parsed, it) } }
    var hue by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable(initial) { mutableFloatStateOf(initialHsv[2]) }
    val selectedArgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    val hex = String.format("#%06X", 0xFFFFFF and selectedArgb)
    var hexInput by rememberSaveable { mutableStateOf(hex) }
    val hueColors = remember { (0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) } }
    LaunchedEffect(hex) {
        hexInput = hex
        preview?.invoke(hex)
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.color_picker_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(220.dp).pointerInput(hue) { detectTapGestures { point -> saturation = (point.x / size.width).coerceIn(0f, 1f); brightness = (1f - point.y / size.height).coerceIn(0f, 1f) } }) {
                val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor))); drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val marker = Offset(size.width * saturation, size.height * (1f - brightness))
                drawCircle(Color.White, 10.dp.toPx(), marker); drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
            }
            Canvas(Modifier.fillMaxWidth().height(34.dp).pointerInput(Unit) { detectTapGestures { point -> hue = (point.x / size.width * 360f).coerceIn(0f, 359.9f) } }) {
                drawRoundRect(Brush.horizontalGradient(hueColors), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                val x = size.width * hue / 360f; drawCircle(Color.White, 9.dp.toPx(), Offset(x, size.height / 2)); drawCircle(Color.Black, 7.dp.toPx(), Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = Color(selectedArgb)) {}
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input.uppercase().take(7)
                        if (hexInput.matches(Regex("#[0-9A-F]{6}"))) {
                            val parsedInput = android.graphics.Color.parseColor(hexInput)
                            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(parsedInput, it) }
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    modifier = Modifier.padding(start = 12.dp).width(132.dp).heightIn(min = 56.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                )
                Text(
                    "H ${hue.toInt()}°\nS ${(saturation * 100).toInt()}% · B ${(brightness * 100).toInt()}%",
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.dialog_cancel)) } }, confirmButton = { Button({ select(hex) }) { Text(stringResource(R.string.dialog_ok)) } })
}
