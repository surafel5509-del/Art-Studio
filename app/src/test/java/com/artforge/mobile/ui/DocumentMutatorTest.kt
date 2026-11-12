package com.artforge.mobile.ui

import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.LayerTransform
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.ui.studio.DocumentMutator
import com.artforge.mobile.ui.studio.CanvasTransform
import com.artforge.mobile.ui.studio.CanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.artforge.mobile.core.model.Pt

/** Layer-tree manipulation and the canvas coordinate transform. */
class DocumentMutatorTest {

    private fun doc(vararg layers: Layer) = ProjectDocument(
        id = "doc",
        title = "Test",
        layers = layers.toList(),
        activeLayerId = layers.firstOrNull()?.id,
    )

    private fun layer(id: String, kind: LayerKind = LayerKind.Raster) = Layer(id = id, name = id, kind = kind)

    @Test
    fun `add layer inserts above the active layer and selects it`() {
        val document = doc(layer("a"), layer("b"))
        val updated = DocumentMutator.addLayer(document, layer("new"), aboveId = "a")
        // The new layer is inserted at the active layer's index, i.e. directly above it.
        assertEquals(listOf("new", "a", "b"), updated.layers.map { it.id })
        assertEquals("new", updated.activeLayerId)
    }

    @Test
    fun `deleting a group removes its children`() {
        val group = layer("g", LayerKind.Group)
        val childA = layer("ca").copy(parentId = "g")
        val childB = layer("cb").copy(parentId = "g")
        val document = doc(group, childA, childB, layer("other"))
        val updated = DocumentMutator.deleteLayer(document, "g")
        assertEquals(listOf("other"), updated.layers.map { it.id })
    }

    @Test
    fun `grouping moves the selected layers under a new group`() {
        val document = doc(layer("a"), layer("b"), layer("c"))
        val updated = DocumentMutator.groupLayers(document, listOf("a", "b"))
        val group = updated.layers.first { it.kind == LayerKind.Group }
        assertEquals(group.id, updated.layers.first { it.id == "a" }.parentId)
        assertEquals(group.id, updated.layers.first { it.id == "b" }.parentId)
        assertNull(updated.layers.first { it.id == "c" }.parentId)
    }

    @Test
    fun `ungroup restores parents`() {
        val group = layer("g", LayerKind.Group)
        val child = layer("c").copy(parentId = "g")
        val updated = DocumentMutator.ungroup(doc(group, child), "g")
        assertNull(updated.layers.first { it.id == "c" }.parentId)
        assertTrue(updated.layers.none { it.id == "g" })
    }

    @Test
    fun `move layer reorders the stack`() {
        val document = doc(layer("a"), layer("b"), layer("c"))
        val updated = DocumentMutator.moveLayer(document, "c", 0)
        assertEquals(listOf("c", "a", "b"), updated.layers.map { it.id })
    }

    @Test
    fun `merge down keeps the lower layer`() {
        val document = doc(layer("top"), layer("bottom"))
        val merged = DocumentMutator.mergeDown(document, "top")
        assertNotNull(merged)
        assertEquals(listOf("bottom"), merged!!.layers.map { it.id })
        assertEquals(1f, merged.layers.first().opacity, 0.001f)
    }

    @Test
    fun `opacity blend visibility and lock setters clamp values`() {
        val document = doc(layer("a"))
        assertEquals(0.5f, DocumentMutator.setOpacity(document, "a", 0.5f).layers.first().opacity, 0.001f)
        assertEquals(1f, DocumentMutator.setOpacity(document, "a", 3f).layers.first().opacity, 0.001f)
        assertFalse(DocumentMutator.setVisible(document, "a", false).layers.first().visible)
        assertTrue(DocumentMutator.setLocked(document, "a", true).layers.first().locked)
        assertEquals(
            com.artforge.mobile.core.model.BlendMode.Multiply,
            DocumentMutator.setBlendMode(document, "a", com.artforge.mobile.core.model.BlendMode.Multiply).layers.first().blendMode,
        )
    }

    @Test
    fun `flatten collapses everything into one raster layer`() {
        val document = doc(layer("a"), layer("b", LayerKind.Vector))
        val flat = DocumentMutator.flatten(document, "merged_frame")
        assertEquals(1, flat.layers.size)
        assertEquals(LayerKind.Raster, flat.layers.first().kind)
        assertEquals("merged_frame", flat.layers.first().activeFrameId)
    }

    @Test
    fun `descendant detection walks the parent chain`() {
        val group = layer("g", LayerKind.Group)
        val mid = layer("m", LayerKind.Group).copy(parentId = "g")
        val leaf = layer("l").copy(parentId = "m")
        val document = doc(group, mid, leaf)
        assertTrue(DocumentMutator.isDescendant(document, "l", "g"))
        assertFalse(DocumentMutator.isDescendant(document, "g", "l"))
    }

    @Test
    fun `layer rows respect collapse state`() {
        val group = layer("g", LayerKind.Group).copy(expanded = true)
        val child = layer("c").copy(parentId = "g")
        val document = doc(group, child)
        assertEquals(2, DocumentMutator.layerRows(document).size)
        val collapsed = DocumentMutator.setExpanded(document, "g", false)
        assertEquals(1, DocumentMutator.layerRows(collapsed).size)
    }

    @Test
    fun `identity transform returns full canvas bounds`() {
        val bounds = DocumentMutator.transformBounds(layer("a"), 100, 200)
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(200f, bounds.bottom, 0.01f)
    }

    @Test
    fun `translated layer bounds shift with the transform`() {
        val shifted = layer("a").copy(transform = LayerTransform(translateX = 10f, translateY = 20f))
        val bounds = DocumentMutator.transformBounds(shifted, 100, 100)
        assertEquals(10f, bounds.left, 0.01f)
        assertEquals(20f, bounds.top, 0.01f)
    }
}

/** Canvas screen⇄document conversion at different zoom/rotation states. */
class CanvasTransformTest {

    private fun transform(view: CanvasView) = CanvasTransform(
        viewportWidth = 1000f,
        viewportHeight = 1000f,
        documentWidth = 1000f,
        documentHeight = 1000f,
        view = view,
    )

    @Test
    fun `centre maps to document centre`() {
        val t = transform(CanvasView())
        val p = t.toDocument(androidx.compose.ui.geometry.Offset(500f, 500f))
        assertEquals(500f, p.x, 0.01f)
        assertEquals(500f, p.y, 0.01f)
    }

    @Test
    fun `screen to document round trips at zoom and pan`() {
        val t = transform(CanvasView(zoom = 2.3f, panX = 40f, panY = -25f, rotation = 17f))
        val original = Pt(321f, 654f)
        val screen = t.toScreen(original)
        val back = t.toDocument(screen)
        assertEquals(original.x, back.x, 0.05f)
        assertEquals(original.y, back.y, 0.05f)
    }

    @Test
    fun `flipped canvas mirrors x`() {
        val normal = transform(CanvasView())
        val flipped = transform(CanvasView(flipHorizontal = true))
        val a = normal.toDocument(androidx.compose.ui.geometry.Offset(300f, 500f))
        val b = flipped.toDocument(androidx.compose.ui.geometry.Offset(300f, 500f))
        assertEquals(1000f - a.x, b.x, 0.5f)
        assertEquals(a.y, b.y, 0.01f)
    }

    @Test
    fun `fit scale fills the viewport with margin`() {
        val t = CanvasTransform(1000f, 800f, 2000f, 1000f, CanvasView())
        assertTrue(t.fitScale > 0.3f && t.fitScale < 0.5f)
    }
}
