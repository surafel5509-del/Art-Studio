@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.artforge.mobile.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.Palette
import com.artforge.mobile.data.asset.AssetLibrary
import com.artforge.mobile.ui.common.ColorSwatch
import com.artforge.mobile.ui.common.GestureIconButton
import com.artforge.mobile.ui.common.MicroLabel
import com.artforge.mobile.ui.common.PanelHeader
import com.artforge.mobile.ui.theme.ForgeColors
import java.util.UUID

/**
 * Colour studio panel: foreground / background swatches, palettes, recent colours
 * and harmony shortcuts. The full picker opens from the swatch.
 */
@Composable
fun ColorPanel(
    tools: ToolState,
    vm: StudioViewModel,
    onPickColor: (ColorTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPaletteDialog by remember { mutableStateOf(false) }
    var paletteName by remember { mutableStateOf("") }

    Column(modifier.background(ForgeColors.Graphite)) {
        PanelHeader("Colour") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GestureIconButton(Icons.Filled.SwapHoriz, "Swap colours", { vm.swapColors() }, size = 30)
                GestureIconButton(
                    Icons.Filled.BookmarkAdd,
                    "New palette",
                    { showPaletteDialog = true },
                    size = 30,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Primary swatch sits on top of the secondary one, with a swap affordance.
            Box(Modifier.padding(end = 12.dp).size(58.dp)) {
                ColorSwatch(tools.secondaryColor, size = 30) { onPickColor(ColorTarget.Secondary) }
                Box(Modifier.align(Alignment.BottomEnd)) {
                    ColorSwatch(tools.primaryColor, size = 40, selected = true) {
                        onPickColor(ColorTarget.Primary)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    ColorUtils.toHex(tools.primaryColor).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeColors.TextPrimary,
                )
                Text(
                    tools.brush?.name ?: "Custom brush",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                )
            }
        }

        if (tools.recentColors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MicroLabel(
                "Recent",
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                tools.recentColors.take(18).forEach { color ->
                    ColorSwatch(color, size = 22) { vm.setPrimaryColor(color) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        MicroLabel(
            "Harmony",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )
        val harmonies = remember(tools.primaryColor) {
            listOf(
                ColorUtils.analogous(tools.primaryColor),
                ColorUtils.triadic(tools.primaryColor),
                ColorUtils.complementary(tools.primaryColor),
            ).flatten().distinct()
        }
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            harmonies.forEach { color -> ColorSwatch(color, size = 24) { vm.setPrimaryColor(color) } }
        }

        Spacer(Modifier.height(6.dp))
        MicroLabel("Palettes", modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        BuiltInPalettes.forEach { (name, colors) ->
            PaletteRow(name, colors) { color -> vm.setPrimaryColor(color) }
        }
    }

    if (showPaletteDialog) {
        AlertDialog(
            onDismissRequest = { showPaletteDialog = false },
            title = { Text("Save palette") },
            text = {
                Column {
                    OutlinedTextField(
                        value = paletteName,
                        onValueChange = { paletteName = it },
                        label = { Text("Palette name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saves your recent colours (${tools.recentColors.size}) as a reusable palette.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeColors.TextMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val colors = if (tools.recentColors.isEmpty()) listOf(tools.primaryColor) else tools.recentColors
                    vm.addPalette(
                        Palette(
                            id = UUID.randomUUID().toString(),
                            name = paletteName.ifBlank { "My palette" },
                            colors = colors,
                            builtIn = false,
                        ),
                    )
                    showPaletteDialog = false
                    paletteName = ""
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showPaletteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaletteRow(name: String, colors: List<Int>, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            colors.forEach { color -> ColorSwatch(color, size = 22, onClick = { onPick(color) }) }
        }
    }
}

/** Built-in palettes mirrored from the asset library so the panel is available offline. */
val BuiltInPalettes: List<Pair<String, List<Int>>> = AssetLibrary.builtInPalettes()
