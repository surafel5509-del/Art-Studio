package com.artforge.mobile.ui.studio

import com.artforge.mobile.core.model.BlendMode
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.LayerTransform
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.RectF2
import java.util.UUID

/**
 * Pure document transformations used by the layer panel and canvas operations.
 * Keeping these as pure functions (document in → document out) means every layer
 * operation is testable without a device and undo is a simple snapshot of the doc.
 */
object DocumentMutator {

    fun layerIndex(doc: ProjectDocument, layerId: String?): Int =
        doc.layers.indexOfFirst { it.id == layerId }

    fun activeLayer(doc: ProjectDocument, activeId: String?): Layer? =
        doc.layers.firstOrNull { it.id == activeId }

    fun replaceLayer(doc: ProjectDocument, layer: Layer): ProjectDocument =
        doc.copy(layers = doc.layers.map { if (it.id == layer.id) layer else it })

    fun updateLayer(doc: ProjectDocument, layerId: String, block: (Layer) -> Layer): ProjectDocument =
        doc.copy(layers = doc.layers.map { if (it.id == layerId) block(it) else it })

    fun addLayer(
        doc: ProjectDocument,
        layer: Layer,
        aboveId: String? = doc.activeLayerId,
    ): ProjectDocument {
        val index = doc.layers.indexOfFirst { it.id == aboveId }
        val insertAt = if (index >= 0) index else 0
        val parentId = doc.layers.getOrNull(insertAt)?.parentId
        val positioned = if (layer.parentId == null && parentId != null) layer.copy(parentId = parentId) else layer
        val layers = doc.layers.toMutableList().apply { add(insertAt, positioned) }
        return doc.copy(layers = layers, activeLayerId = layer.id)
    }

    fun deleteLayer(doc: ProjectDocument, layerId: String): ProjectDocument {
        val target = doc.layers.firstOrNull { it.id == layerId } ?: return doc
        // Deleting a group deletes its children too.
        val ids = LinkedHashSet<String>()
        fun collect(id: String) {
            ids.add(id)
            doc.layers.filter { it.parentId == id }.forEach { collect(it.id) }
        }
        collect(target.id)
        val remaining = doc.layers.filterNot { it.id in ids }
        val nextActive = remaining.getOrNull(minOf(doc.layers.indexOf(target), remaining.size - 1))?.id
        return doc.copy(layers = remaining, activeLayerId = nextActive)
    }

    fun duplicateLayer(doc: ProjectDocument, layerId: String, newFrameId: (String) -> String = { UUID.randomUUID().toString() }): ProjectDocument {
        val target = doc.layers.firstOrNull { it.id == layerId } ?: return doc
        val copy = target.copy(
            id = UUID.randomUUID().toString(),
            name = "${target.name} copy",
            frames = target.frames.map { it.copy(frameId = newFrameId(it.frameId)) },
            activeFrameId = target.activeFrameId?.let(newFrameId),
            maskFrameId = target.maskFrameId?.let(newFrameId),
        )
        return addLayer(doc, copy, layerId)
    }

    fun moveLayer(doc: ProjectDocument, fromId: String, toIndex: Int): ProjectDocument {
        val fromIndex = doc.layers.indexOfFirst { it.id == fromId }
        if (fromIndex < 0) return doc
        val list = doc.layers.toMutableList()
        val item = list.removeAt(fromIndex)
        val target = toIndex.coerceIn(0, list.size)
        list.add(target, item)
        return doc.copy(layers = list)
    }

    fun setVisible(doc: ProjectDocument, layerId: String, visible: Boolean) =
        updateLayer(doc, layerId, { it.copy(visible = visible) })

    fun setLocked(doc: ProjectDocument, layerId: String, locked: Boolean) =
        updateLayer(doc, layerId, { it.copy(locked = locked) })

    fun setOpacity(doc: ProjectDocument, layerId: String, opacity: Float) =
        updateLayer(doc, layerId, { it.copy(opacity = opacity.coerceIn(0f, 1f)) })

    fun setBlendMode(doc: ProjectDocument, layerId: String, mode: BlendMode) =
        updateLayer(doc, layerId, { it.copy(blendMode = mode) })

    fun rename(doc: ProjectDocument, layerId: String, name: String) =
        updateLayer(doc, layerId, { it.copy(name = name.ifBlank { "Layer" }) })

    fun setAlphaLock(doc: ProjectDocument, layerId: String, locked: Boolean) =
        updateLayer(doc, layerId, { it.copy(alphaLocked = locked) })

    fun setClipping(doc: ProjectDocument, layerId: String, clipped: Boolean) =
        updateLayer(doc, layerId, { it.copy(clippingMask = clipped) })

    fun setTransform(doc: ProjectDocument, layerId: String, transform: LayerTransform) =
        updateLayer(doc, layerId, { it.copy(transform = transform) })

    fun addMask(doc: ProjectDocument, layerId: String, maskFrameId: String) =
        updateLayer(doc, layerId, { it.copy(maskFrameId = maskFrameId) })

    fun removeMask(doc: ProjectDocument, layerId: String) =
        updateLayer(doc, layerId, { it.copy(maskFrameId = null) })

    /** Creates a group and moves the given layers into it. */
    fun groupLayers(doc: ProjectDocument, layerIds: List<String>): ProjectDocument {
        if (layerIds.isEmpty()) return doc
        val firstIndex = doc.layers.indexOfFirst { it.id in layerIds }.coerceAtLeast(0)
        val group = Layer(
            id = UUID.randomUUID().toString(),
            name = "Group",
            kind = LayerKind.Group,
        )
        val moved = doc.layers.map { if (it.id in layerIds) it.copy(parentId = group.id) else it }
        val withoutGroup = moved.filterNot { it.id == group.id }
        val insertAt = firstIndex.coerceIn(0, withoutGroup.size)
        val layers = withoutGroup.toMutableList().apply { add(insertAt, group) }
        return doc.copy(layers = layers, activeLayerId = group.id)
    }

    fun ungroup(doc: ProjectDocument, groupId: String): ProjectDocument {
        val group = doc.layers.firstOrNull { it.id == groupId } ?: return doc
        if (group.kind != LayerKind.Group) return doc
        val layers = doc.layers.filterNot { it.id == groupId }
            .map { if (it.parentId == groupId) it.copy(parentId = group.parentId) else it }
        return doc.copy(layers = layers, activeLayerId = layers.firstOrNull { it.parentId == group.parentId }?.id)
    }

    /** Merges [layerId] into the layer directly below it (raster result). */
    fun mergeDown(doc: ProjectDocument, layerId: String): ProjectDocument? {
        val index = doc.layers.indexOfFirst { it.id == layerId }
        if (index < 0 || index >= doc.layers.size - 1) return null
        val upper = doc.layers[index]
        val lower = doc.layers[index + 1]
        // Pixel merging happens in the caller (needs bitmaps); here we only describe
        // the resulting structure: keep the lower layer, drop the upper.
        val merged = lower.copy(
            opacity = 1f,
            blendMode = BlendMode.Normal,
            transform = LayerTransform(),
            name = lower.name,
        )
        val layers = doc.layers.toMutableList()
        layers.removeAt(index)
        layers[index] = merged
        return doc.copy(layers = layers, activeLayerId = merged.id)
    }

    /** Flattens all visible layers into one raster layer structure. */
    fun flatten(doc: ProjectDocument, mergedFrameId: String): ProjectDocument {
        val flat = Layer(
            id = UUID.randomUUID().toString(),
            name = "Flattened",
            kind = LayerKind.Raster,
            activeFrameId = mergedFrameId,
        )
        return doc.copy(layers = listOf(flat), activeLayerId = flat.id)
    }

    fun setParent(doc: ProjectDocument, layerId: String, parentId: String?) =
        updateLayer(doc, layerId, { it.copy(parentId = parentId) })

    fun setColorTag(doc: ProjectDocument, layerId: String, color: Int) =
        updateLayer(doc, layerId, { it.copy(colorTag = color) })

    fun setExpanded(doc: ProjectDocument, layerId: String, expanded: Boolean) =
        updateLayer(doc, layerId, { it.copy(expanded = expanded) })

    fun setReferenceTint(doc: ProjectDocument, layerId: String, tint: Int?) =
        updateLayer(doc, layerId, { it.copy(referenceTint = tint) })

    /** Children of a group in painting order. */
    fun childrenOf(doc: ProjectDocument, parentId: String): List<Layer> =
        doc.layers.filter { it.parentId == parentId }

    /** True when [layerId] is a descendant of [ancestorId]. */
    fun isDescendant(doc: ProjectDocument, layerId: String, ancestorId: String): Boolean {
        var current = doc.layers.firstOrNull { it.id == layerId } ?: return false
        var guard = 0
        while (current.parentId != null && guard++ < 64) {
            if (current.parentId == ancestorId) return true
            current = doc.layers.firstOrNull { it.id == current.parentId } ?: return false
        }
        return false
    }

    /** Builds the flattened layer rows for the panel (respects collapse state). */
    fun layerRows(doc: ProjectDocument): List<Pair<Layer, Int>> {
        val rows = ArrayList<Pair<Layer, Int>>()
        fun emit(layer: Layer, depth: Int) {
            rows.add(layer to depth)
            if (layer.kind == LayerKind.Group && layer.expanded) {
                doc.layers.filter { it.parentId == layer.id }.forEach { emit(it, depth + 1) }
            }
        }
        doc.layers.filter { it.parentId == null }.forEach { emit(it, 0) }
        return rows
    }

    fun transformBounds(layer: Layer, width: Int, height: Int): RectF2 {
        val t = layer.transform
        if (t.isIdentity) return RectF2(0f, 0f, width.toFloat(), height.toFloat())
        val px = if (t.pivotX == 0f && t.pivotY == 0f) width / 2f else t.pivotX
        val py = if (t.pivotX == 0f && t.pivotY == 0f) height / 2f else t.pivotY
        val corners = listOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(width.toFloat(), 0f),
            floatArrayOf(width.toFloat(), height.toFloat()),
            floatArrayOf(0f, height.toFloat()),
        ).map { (x, y) ->
            val dx = x - px
            val dy = y - py
            val rad = Math.toRadians(t.rotationDeg.toDouble())
            val rx = (dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
            val ry = (dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()
            Pair(px + rx * t.scaleX + t.translateX, py + ry * t.scaleY + t.translateY)
        }
        return RectF2(
            corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first }, corners.maxOf { it.second },
        )
    }
}
