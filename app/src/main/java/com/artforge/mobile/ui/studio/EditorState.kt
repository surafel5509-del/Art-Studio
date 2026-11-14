package com.artforge.mobile.ui.studio

import androidx.compose.ui.graphics.ImageBitmap
import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.BrushPreset
import com.artforge.mobile.core.model.Palette
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.RectF2
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.engine.draw.SelectionMask

/** Camera state for the drawing surface (zoom / pan / rotation). */
data class CanvasView(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotation: Float = 0f,
    val flipHorizontal: Boolean = false,
) {
    fun reset() = CanvasView()
}

/** Active tool configuration shown in the tool options bar. */
data class ToolState(
    val tool: ToolId = ToolId.Brush,
    val previousTool: ToolId = ToolId.Brush,
    val brush: BrushPreset? = null,
    val eraser: BrushPreset? = null,
    val primaryColor: Int = 0xFF1B1D22.toInt(),
    val secondaryColor: Int = 0xFFFFFFFF.toInt(),
    val recentColors: List<Int> = emptyList(),
    val palette: Palette? = null,
    val brushSize: Float = 12f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val hardness: Float = 0.9f,
    val spacing: Float = 0.08f,
    val smoothing: Float = 0.35f,
    val tolerance: Int = 24,
    val shapeFill: Boolean = false,
    val shapeSides: Int = 6,
    val starPoints: Int = 5,
    val cornerRadius: Float = 16f,
    val constrain: Boolean = false,
    val symmetrySegments: Int = 8,
    val selectionFeather: Int = 0,
    val selectionOp: com.artforge.mobile.engine.draw.SelectionOp = com.artforge.mobile.engine.draw.SelectionOp.Replace,
    val gradientType: com.artforge.mobile.core.model.GradientType = com.artforge.mobile.core.model.GradientType.Linear,
    val cloneSourceX: Float = 0f,
    val cloneSourceY: Float = 0f,
    val textSize: Float = 64f,
    val textBold: Boolean = false,
    val textItalic: Boolean = false,
    val isEraserActive: Boolean = false,
)

/** Everything the studio screen renders. */
data class StudioState(
    val loading: Boolean = true,
    val document: ProjectDocument? = null,
    val activeLayerId: String? = null,
    val frame: Int = 0,
    val composite: ImageBitmap? = null,
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
    val selection: SelectionMask? = null,
    val selectionBounds: RectF2? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val lastSavedAt: Long = 0L,
    val statusMessage: String? = null,
    val isPlaying: Boolean = false,
    val frameRange: IntRange = 0..0,
    /** Timeline frames selected for frame operations (blank/duplicate/delete). */
    val selectedFrames: Set<Int> = emptySet(),
    /** Layers selected in the layer panel for group/merge operations. */
    val selectedLayerIds: List<String> = emptyList(),
    val drawPreview: DrawPreview? = null,
    val droppedFrames: List<String> = emptyList(),
    val pendingRecovery: Boolean = false,
    /** Non-null when the last canvas render threw; the canvas shows a recovery hint. */
    val renderError: String? = null,
) {
    val activeLayer get() = document?.layers?.firstOrNull { it.id == activeLayerId }
    val isAnimation get() = document?.kind == com.artforge.mobile.core.model.ProjectKind.Animation
    val animation: AnimationConfig? get() = document?.animation
}

/** Live in-progress shape / path preview drawn above the composite. */
data class DrawPreview(
    val kind: PreviewKind,
    val points: List<com.artforge.mobile.core.model.Pt> = emptyList(),
    val from: com.artforge.mobile.core.model.Pt? = null,
    val to: com.artforge.mobile.core.model.Pt? = null,
    val tool: ToolId = ToolId.Line,
)

enum class PreviewKind { None, Shape, Polyline, Bezier, Lasso, Marque, Gradient }

/** Snapshot used by the layer panel rows. */
data class LayerRow(
    val id: String,
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
    val opacity: Float,
    val blendMode: com.artforge.mobile.core.model.BlendMode,
    val kind: com.artforge.mobile.core.model.LayerKind,
    val isActive: Boolean,
    val hasMask: Boolean,
    val clipped: Boolean,
    val alphaLocked: Boolean,
    val colorTag: Int,
    val childCount: Int,
    val depth: Int,
    val frameCount: Int,
    val thumbnail: ImageBitmap?,
)
