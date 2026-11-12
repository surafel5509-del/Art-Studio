package com.artforge.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Full colour picker with HSV wheel, RGB / HSL / CMYK / HEX numeric entry, alpha,
 * harmony suggestions and the recent-colour strip. Every tab edits the same state.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    recentColors: List<Int> = emptyList(),
) {
    val hsv0 = remember(initialColor) { ColorUtils.toHsv(initialColor) }
    var hue by remember { mutableStateOf(hsv0[0]) }
    var saturation by remember { mutableStateOf(hsv0[1]) }
    var value by remember { mutableStateOf(hsv0[2]) }
    var alpha by remember { mutableStateOf(ColorUtils.alpha(initialColor) / 255f) }
    var tab by remember { mutableStateOf(0) }
    var hexField by remember { mutableStateOf(ColorUtils.toHex(initialColor)) }

    val color = ColorUtils.fromHsv(hue, saturation, value, (alpha * 255).toInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour studio", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(color)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(ColorUtils.toHex(color, includeAlpha = true), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "HSV ${hue.toInt()} / ${(saturation * 100).toInt()}% / ${(value * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = ForgeColors.TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ColorWheel(hue, saturation, value, onChange = { h, s, v ->
                    hue = h
                    saturation = s
                    value = v
                    hexField = ColorUtils.toHex(ColorUtils.fromHsv(h, s, v, (alpha * 255).toInt()))
                })
                ValueSlider("Value", value, 0f..1f, {
                    value = it
                    hexField = ColorUtils.toHex(ColorUtils.fromHsv(hue, saturation, it, (alpha * 255).toInt()))
                }, formatter = { "${(it * 100).toInt()}%" })
                ValueSlider("Alpha", alpha, 0f..1f, { alpha = it }, formatter = { "${(it * 100).toInt()}%" })

                TabRow(selectedTabIndex = tab, containerColor = ForgeColors.GraphiteElevated) {
                    listOf("HEX", "RGB", "HSL", "CMYK", "Harmony").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }) {
                            Text(label, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                when (tab) {
                    0 -> OutlinedTextField(
                        value = hexField,
                        onValueChange = { input ->
                            hexField = input
                            ColorUtils.parseHex(input)?.let { parsed ->
                                val hsv = ColorUtils.toHsv(parsed)
                                hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                alpha = ColorUtils.alpha(parsed) / 255f
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    1 -> {
                        val r = ColorUtils.red(color)
                        val g = ColorUtils.green(color)
                        val b = ColorUtils.blue(color)
                        ValueSlider("Red", r / 255f, 0f..1f, {
                            val c = ColorUtils.argb(255, (it * 255).toInt(), g, b)
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 255).toInt()}" })
                        ValueSlider("Green", g / 255f, 0f..1f, {
                            val c = ColorUtils.argb(255, r, (it * 255).toInt(), b)
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 255).toInt()}" })
                        ValueSlider("Blue", b / 255f, 0f..1f, {
                            val c = ColorUtils.argb(255, r, g, (it * 255).toInt())
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 255).toInt()}" })
                    }

                    2 -> {
                        val hsl = ColorUtils.toHsl(color)
                        ValueSlider("Hue", hsl[0], 0f..360f, {
                            hue = it
                            hexField = ColorUtils.toHex(ColorUtils.fromHsv(it, saturation, value))
                        }, formatter = { "${it.toInt()}°" })
                        ValueSlider("Saturation", hsl[1], 0f..1f, {
                            saturation = it
                            hexField = ColorUtils.toHex(ColorUtils.fromHsv(hue, it, value))
                        }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Lightness", hsl[2], 0f..1f, {
                            val c = ColorUtils.fromHsl(hue, saturation, it)
                            val hsv = ColorUtils.toHsv(c); value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 100).toInt()}%" })
                    }

                    3 -> {
                        val cmyk = ColorUtils.toCmyk(color)
                        Text(
                            "C ${(cmyk[0] * 100).toInt()}%  M ${(cmyk[1] * 100).toInt()}%  " +
                                "Y ${(cmyk[2] * 100).toInt()}%  K ${(cmyk[3] * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ValueSlider("Cyan", cmyk[0], 0f..1f, {
                            val c = ColorUtils.fromCmyk(it, cmyk[1], cmyk[2], cmyk[3])
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Magenta", cmyk[1], 0f..1f, {
                            val c = ColorUtils.fromCmyk(cmyk[0], it, cmyk[2], cmyk[3])
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Yellow", cmyk[2], 0f..1f, {
                            val c = ColorUtils.fromCmyk(cmyk[0], cmyk[1], it, cmyk[3])
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Black", cmyk[3], 0f..1f, {
                            val c = ColorUtils.fromCmyk(cmyk[0], cmyk[1], cmyk[2], it)
                            val hsv = ColorUtils.toHsv(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            hexField = ColorUtils.toHex(c)
                        }, formatter = { "${(it * 100).toInt()}%" })
                    }

                    else -> {
                        val harmonies = listOf(
                            "Complementary" to ColorUtils.complementary(color),
                            "Analogous" to ColorUtils.analogous(color),
                            "Triadic" to ColorUtils.triadic(color),
                            "Split" to ColorUtils.splitComplementary(color),
                            "Tetradic" to ColorUtils.tetradic(color),
                        )
                        Column {
                            harmonies.forEach { (label, colors) ->
                                Text(label, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextMuted)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    colors.forEach { swatch ->
                                        ColorSwatch(swatch, size = 32) {
                                            val hsv = ColorUtils.toHsv(swatch)
                                            hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                            hexField = ColorUtils.toHex(swatch)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (recentColors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recent", style = MaterialTheme.typography.labelMedium, color = ForgeColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        recentColors.take(10).forEach { c ->
                            ColorSwatch(c, size = 26) {
                                val hsv = ColorUtils.toHsv(c)
                                hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                                alpha = ColorUtils.alpha(c) / 255f
                                hexField = ColorUtils.toHex(c)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Reusable numeric text field for dimensions and counts. */
@Composable
fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.all { it.isDigit() } || input.isEmpty()) onValueChange(input) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
