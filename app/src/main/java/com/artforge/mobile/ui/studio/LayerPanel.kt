package com.artforge.mobile.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.model.BlendModeLabels
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.LayerKindLabels
import com.artforge.mobile.ui.common.PanelHeader
import com.artforge.mobile.ui.common.ValueSlider
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Layer stack with real per-layer controls: visibility, lock, opacity, blend mode,
 * clipping mask, alpha lock, masks, grouping, merge, duplicate, delete and reorder.
 */
@Composable
fun LayerPanel(
    state: StudioState,
    vm: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val doc = state.document ?: return
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val rows = remember(doc) { DocumentMutator.layerRows(doc) }

    Column(modifier.background(ForgeColors.Graphite)) {
        PanelHeader("Layers") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.addLayer() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Add, "Add layer", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { state.activeLayerId?.let(vm::duplicateLayer) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, "Duplicate", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { state.activeLayerId?.let(vm::deleteLayer) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Filled.Delete, "Delete", tint = ForgeColors.Danger, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
            items(rows, key = { it.first.id }) { (layer, depth) ->
                LayerRow(
                    depth = depth,
                    name = layer.name,
                    kind = layer.kind,
                    visible = layer.visible,
                    locked = layer.locked,
                    isActive = layer.id == state.activeLayerId,
                    colorTag = layer.colorTag,
                    clipping = layer.clippingMask,
                    hasMask = layer.maskFrameId != null,
                    expanded = layer.expanded,
                    childCount = doc.layers.count { it.parentId == layer.id },
                    onSelect = { vm.selectLayer(layer.id) },
                    onToggleVisible = { vm.setLayerVisible(layer.id, !layer.visible) },
                    onToggleLock = { vm.setLayerLocked(layer.id, !layer.locked) },
                    onToggleExpand = { vm.setLayerExpanded(layer.id, !layer.expanded) },
                    onRename = {
                        renameTarget = layer.id
                        renameDraft = layer.name
                    },
                    onDuplicate = { vm.duplicateLayer(layer.id) },
                    onDelete = { vm.deleteLayer(layer.id) },
                    onMergeDown = { vm.mergeDown(layer.id) },
                    onToggleClipping = { vm.setClipping(layer.id, !layer.clippingMask) },
                    onToggleAlphaLock = { vm.setAlphaLock(layer.id, !layer.alphaLocked) },
                    onAddMask = { vm.addLayerMask(layer.id) },
                    onRemoveMask = { vm.removeLayerMask(layer.id) },
                    onUngroup = { if (layer.kind == LayerKind.Group) vm.ungroup(layer.id) },
                    onMoveUp = {
                        val idx = doc.layers.indexOfFirst { it.id == layer.id }
                        if (idx > 0) vm.moveLayer(layer.id, idx - 1)
                    },
                    onMoveDown = {
                        val idx = doc.layers.indexOfFirst { it.id == layer.id }
                        if (idx >= 0 && idx < doc.layers.size - 1) vm.moveLayer(layer.id, idx + 1)
                    },
                )
            }
        }

        val active = doc.layers.firstOrNull { it.id == state.activeLayerId }
        if (active != null) {
            ValueSlider(
                "Opacity",
                active.opacity,
                0f..1f,
                { vm.setLayerOpacity(active.id, it) },
                formatter = { "${(it * 100).toInt()}%" },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Blend", style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(BlendModeLabels[active.blendMode] ?: active.blendMode.name, style = MaterialTheme.typography.bodySmall)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        BlendModeLabels.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.setLayerBlendMode(active.id, mode)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmallChip(if (active.alphaLocked) "Alpha Locked" else "Alpha Lock") {
                    vm.setAlphaLock(active.id, !active.alphaLocked)
                }
                SmallChip(if (active.clippingMask) "Clipped" else "Clip") {
                    vm.setClipping(active.id, !active.clippingMask)
                }
                SmallChip(if (active.maskFrameId != null) "Remove Mask" else "Add Mask") {
                    if (active.maskFrameId != null) vm.removeLayerMask(active.id) else vm.addLayerMask(active.id)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmallChip("Group") { vm.groupSelected() }
                SmallChip("Merge Down") { vm.mergeDown(active.id) }
                SmallChip("Flatten") { vm.flattenLayers() }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { vm.renameLayer(it, renameDraft) }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerRow(
    depth: Int,
    name: String,
    kind: LayerKind,
    visible: Boolean,
    locked: Boolean,
    isActive: Boolean,
    colorTag: Int,
    clipping: Boolean,
    hasMask: Boolean,
    expanded: Boolean,
    childCount: Int,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleExpand: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit,
    onToggleClipping: () -> Unit,
    onToggleAlphaLock: () -> Unit,
    onAddMask: () -> Unit,
    onRemoveMask: () -> Unit,
    onUngroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (isActive) ForgeColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(start = (6 + depth * 12).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(colorTag)),
        )
        if (childCount > 0) {
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    "Expand",
                    tint = ForgeColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (visible) ForgeColors.TextPrimary else ForgeColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onRename() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    LayerKindLabels[kind] ?: kind.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeColors.TextMuted,
                )
                if (clipping) Badge("clip")
                if (hasMask) Badge("mask")
                if (locked) Badge("lock")
            }
        }
        IconButton(onClick = onToggleVisible, modifier = Modifier.size(32.dp)) {
            Icon(
                if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                "Visibility",
                tint = if (visible) ForgeColors.TextSecondary else ForgeColors.TextMuted,
                modifier = Modifier.size(17.dp),
            )
        }
        IconButton(onClick = onToggleLock, modifier = Modifier.size(30.dp)) {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                "Lock",
                tint = if (locked) ForgeColors.AccentWarm else ForgeColors.TextMuted,
                modifier = Modifier.size(15.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, "More", tint = ForgeColors.TextMuted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun item(label: String, action: () -> Unit) {
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                }
                item("Rename") { onRename() }
                item("Duplicate") { onDuplicate() }
                item("Merge down") { onMergeDown() }
                item("Move up") { onMoveUp() }
                item("Move down") { onMoveDown() }
                item(if (clipping) "Remove clipping" else "Clipping mask") { onToggleClipping() }
                item(if (hasMask) "Remove layer mask" else "Add layer mask") {
                    if (hasMask) onRemoveMask() else onAddMask()
                }
                item("Alpha lock") { onToggleAlphaLock() }
                if (childCount > 0) item("Ungroup") { onUngroup() }
                item("Delete") { onDelete() }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(ForgeColors.GraphiteElevated)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextMuted)
    }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ForgeColors.GraphiteElevated)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextSecondary)
    }
}
