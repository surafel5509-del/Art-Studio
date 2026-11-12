package com.artforge.mobile.ui.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LineAxis
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.artforge.mobile.core.model.ToolId

/**
 * Icon + label + grouping for every tool. The toolbar and shortcut handler both
 * read from here so a tool can never exist in one place and not the other.
 */
data class ToolSpec(
    val id: ToolId,
    val label: String,
    val icon: ImageVector,
    val group: String,
    val shortcut: String? = null,
)

object ToolCatalog {

    val tools: List<ToolSpec> = listOf(
        // Draw group
        ToolSpec(ToolId.Brush, "Brush", Icons.Filled.Edit, "Draw", "B"),
        ToolSpec(ToolId.Pencil, "Pencil", Icons.Filled.Gesture, "Draw", "P"),
        ToolSpec(ToolId.Eraser, "Eraser", Icons.Filled.AutoFixHigh, "Draw", "E"),
        ToolSpec(ToolId.PixelEraser, "Pixel Eraser", Icons.Filled.GridOn, "Draw"),
        ToolSpec(ToolId.Smudge, "Smudge", Icons.Filled.WaterDrop, "Draw", "S"),
        ToolSpec(ToolId.Blur, "Blur", Icons.Filled.BlurOn, "Draw"),
        ToolSpec(ToolId.Sharpen, "Sharpen", Icons.Filled.Tune, "Draw"),
        ToolSpec(ToolId.Dodge, "Dodge", Icons.Filled.LightMode, "Draw"),
        ToolSpec(ToolId.Burn, "Burn", Icons.Filled.DarkMode, "Draw"),
        ToolSpec(ToolId.CloneStamp, "Clone Stamp", Icons.Filled.ContentCut, "Draw"),

        // Shapes
        ToolSpec(ToolId.Line, "Line", Icons.Filled.LineAxis, "Shape", "L"),
        ToolSpec(ToolId.Rectangle, "Rectangle", Icons.Filled.Rectangle, "Shape", "R"),
        ToolSpec(ToolId.RoundedRectangle, "Rounded Rect", Icons.Filled.Square, "Shape"),
        ToolSpec(ToolId.Ellipse, "Ellipse", Icons.Filled.Circle, "Shape", "O"),
        ToolSpec(ToolId.Polygon, "Polygon", Icons.Filled.Hexagon, "Shape"),
        ToolSpec(ToolId.Star, "Star", Icons.Filled.Star, "Shape"),
        ToolSpec(ToolId.Curve, "Curve", Icons.Filled.Polyline, "Shape"),
        ToolSpec(ToolId.BezierPen, "Bézier Pen", Icons.Filled.Timeline, "Shape"),
        ToolSpec(ToolId.Path, "Path", Icons.Filled.AltRoute, "Shape"),
        ToolSpec(ToolId.BucketFill, "Fill", Icons.Filled.FormatColorFill, "Shape", "G"),
        ToolSpec(ToolId.Gradient, "Gradient", Icons.Filled.ColorLens, "Shape"),
        ToolSpec(ToolId.Text, "Text", Icons.Filled.TextFields, "Shape", "T"),

        // Colour
        ToolSpec(ToolId.Eyedropper, "Eyedropper", Icons.Filled.ColorLens, "Color", "I"),

        // Selection
        ToolSpec(ToolId.RectSelect, "Rect Select", Icons.Filled.CropSquare, "Select", "M"),
        ToolSpec(ToolId.EllipseSelect, "Ellipse Select", Icons.Filled.RadioButtonUnchecked, "Select"),
        ToolSpec(ToolId.LassoSelect, "Lasso", Icons.Filled.Highlight, "Select", "Q"),
        ToolSpec(ToolId.PolygonSelect, "Poly Select", Icons.Filled.Pentagon, "Select"),
        ToolSpec(ToolId.MagicWand, "Magic Wand", Icons.Filled.GridOn, "Select", "W"),

        // Transform
        ToolSpec(ToolId.Move, "Move", Icons.Filled.OpenWith, "Transform", "V"),
        ToolSpec(ToolId.Transform, "Transform", Icons.Filled.Flip, "Transform", "F"),
        ToolSpec(ToolId.Crop, "Crop", Icons.Filled.Crop, "Transform", "C"),

        // Navigation
        ToolSpec(ToolId.Hand, "Hand", Icons.Filled.PanTool, "Navigation", "Space"),
        ToolSpec(ToolId.Zoom, "Zoom", Icons.Filled.ZoomIn, "Navigation", "Z"),
        ToolSpec(ToolId.RotateCanvas, "Rotate", Icons.Filled.RotateRight, "Navigation"),
        ToolSpec(ToolId.Perspective, "Perspective", Icons.Filled.Grain, "Navigation"),
    )

    val groups: List<String> = tools.map { it.group }.distinct()

    fun spec(id: ToolId): ToolSpec = tools.first { it.id == id }

    fun byShortcut(key: String): ToolId? = tools.firstOrNull { it.shortcut?.equals(key, ignoreCase = true) == true }?.id
}
