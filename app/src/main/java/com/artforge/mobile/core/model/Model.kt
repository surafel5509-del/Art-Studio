package com.artforge.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** ARGB colour packed as an Int, matching Android's [android.graphics.Color] layout. */
typealias Argb = Int

// ---------------------------------------------------------------------------
// Geometry primitives
// ---------------------------------------------------------------------------

@Serializable
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(f: Float) = Pt(x * f, y * f)
    fun distanceTo(o: Pt): Float = kotlin.math.hypot((x - o.x).toDouble(), (y - o.y).toDouble()).toFloat()
    fun length(): Float = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat()
    companion object {
        val Zero = Pt(0f, 0f)
    }
}

@Serializable
data class RectF2(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    fun union(o: RectF2) = RectF2(
        minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom),
    )
    fun inset(d: Float) = RectF2(left + d, top + d, right - d, bottom - d)
    fun clampTo(w: Int, h: Int): RectF2 = RectF2(
        left.coerceIn(0f, w.toFloat()), top.coerceIn(0f, h.toFloat()),
        right.coerceIn(0f, w.toFloat()), bottom.coerceIn(0f, h.toFloat()),
    )
    fun isEmpty(): Boolean = width <= 0f || height <= 0f

    companion object {
        fun ofSize(w: Int, h: Int) = RectF2(0f, 0f, w.toFloat(), h.toFloat())
    }
}

// ---------------------------------------------------------------------------
// Brush engine
// ---------------------------------------------------------------------------

enum class BrushCategory { Pencil, Ink, Marker, Paint, Watercolor, Airbrush, Dry, Texture, Pixel, Blend, Eraser, Effect }

/** How a brush builds up paint inside a single stroke. */
enum class BrushBuildUp { Normal, Multiply, Add }

/** Shape of the dab used while stamping a stroke. */
enum class DabShape { Round, Flat, Chisel, Square, Grain }

@Serializable
data class BrushSettings(
    /** Base diameter in canvas pixels. */
    val size: Float = 12f,
    /** Maximum alpha of a single dab (0..1). */
    val opacity: Float = 1f,
    /** Paint deposited per dab, used for build-up brushes (0..1). */
    val flow: Float = 1f,
    /** 0 = fully soft edge, 1 = hard edge. */
    val hardness: Float = 0.9f,
    /** Distance between dabs as a fraction of brush size. */
    val spacing: Float = 0.08f,
    val density: Float = 1f,
    val scatter: Float = 0f,
    val angleJitter: Float = 0f,
    val angle: Float = 0f,
    /** The dab aspect ratio; 1 = round. */
    val roundness: Float = 1f,
    val shape: DabShape = DabShape.Round,
    val category: BrushCategory = BrushCategory.Ink,
    val buildUp: BrushBuildUp = BrushBuildUp.Normal,
    /** 0..1: how strongly stylus pressure drives size. */
    val pressureSize: Float = 0.85f,
    /** 0..1: how strongly stylus pressure drives opacity. */
    val pressureOpacity: Float = 0.6f,
    /** 0..1: tilt enlarges the dab (airbrush / charcoal behaviour). */
    val tiltSize: Float = 0f,
    /** 0..1: stylus rotation drives dab rotation. */
    val rotationFollowsStylus: Float = 0f,
    /** 0..1 smoothing applied by the stroke stabiliser. */
    val smoothing: Float = 0.35f,
    /** 0..1 random size variation per dab. */
    val sizeJitter: Float = 0f,
    /** 0..1 random hue/alpha variation. */
    val colorJitter: Float = 0f,
    /** Grayscale texture id from the asset library, or null for none. */
    val textureId: String? = null,
    val textureScale: Float = 1f,
    /** Strength for smudge/blend brushes (0..1). */
    val smudgeStrength: Float = 0.5f,
    /** For wet brushes this pulls colour towards the sampled canvas colour. */
    val wetness: Float = 0f,
)

@Serializable
data class BrushPreset(
    val id: String,
    val name: String,
    val category: BrushCategory,
    val settings: BrushSettings,
    val favorite: Boolean = false,
    val folder: String = "General",
    val builtIn: Boolean = true,
    /** Small PNG preview encoded as base64, generated on device. */
    val previewPngBase64: String? = null,
)

// ---------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------

@Serializable
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Argb>,
    val folder: String = "My palettes",
    val builtIn: Boolean = false,
)

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

enum class ToolId {
    Brush, Pencil, Eraser, PixelEraser, Smudge, Blur, Sharpen, Dodge, Burn,
    Line, Rectangle, RoundedRectangle, Ellipse, Polygon, Star, Curve, BezierPen, Path,
    BucketFill, Gradient, Eyedropper, CloneStamp, Text,
    RectSelect, EllipseSelect, LassoSelect, PolygonSelect, MagicWand,
    Move, Transform, Crop, Hand, Zoom, RotateCanvas, Perspective,
}

@Serializable
data class ShapeSettings(
    val cornerRadius: Float = 16f,
    val polygonSides: Int = 6,
    val starPoints: Int = 5,
    val starInnerRatio: Float = 0.45f,
    val fill: Boolean = false,
    val strokeWidth: Float = 4f,
    /** 0 = freehand-ish curve, 1 = fully constrained. */
    val constrain: Boolean = false,
)

enum class GradientType { Linear, Radial, Sweep, AngleReflected }

@Serializable
data class GradientSpec(
    val type: GradientType = GradientType.Linear,
    val stops: List<GradientStop> = defaultStops(),
) {
    companion object {
        fun defaultStops() = listOf(GradientStop(0f, 0xFF6C8CFF.toInt()), GradientStop(1f, 0xFFFF8A4C.toInt()))
    }
}

@Serializable
data class GradientStop(val position: Float, val color: Argb)

// ---------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------

enum class LayerKind { Raster, Vector, Text, Group, Reference, Adjustment, Mask, Animation }

enum class BlendMode {
    Normal, Multiply, Screen, Overlay, SoftLight, HardLight, Darken, Lighten,
    ColorDodge, ColorBurn, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
}

@Serializable
data class LayerTransform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = translateX == 0f && translateY == 0f && scaleX == 1f && scaleY == 1f && rotationDeg == 0f
}

@Serializable
data class TextLayerData(
    val text: String = "",
    val fontSize: Float = 64f,
    val fontFamily: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineHeightMultiplier: Float = 1.2f,
    val align: String = "start",
    val color: Argb = 0xFF000000.toInt(),
)

@Serializable
data class VectorShape(
    val id: String,
    val kind: String,
    /** Flattened path data, sampled from the tool that created it. */
    val points: List<Pt>,
    val closed: Boolean,
    val strokeColor: Argb,
    val strokeWidth: Float,
    val fillColor: Argb?,
    /** Bezier control points matching [points] when the shape was made with the pen tool. */
    val controlsIn: List<Pt> = emptyList(),
    val controlsOut: List<Pt> = emptyList(),
)

@Serializable
data class AdjustmentSpec(
    val type: String = "BrightnessContrast",
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hueShift: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
)

@Serializable
data class FrameRef(val frameId: String, val offset: Double = 0.0)

/**
 * A single drawing layer. One layer may hold a *sequence* of animation frames;
 * a static layer simply has one frame at offset 0 and `animated == false`.
 */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    val kind: LayerKind = LayerKind.Raster,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val alphaLocked: Boolean = false,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.Normal,
    val clippingMask: Boolean = false,
    val colorTag: Argb = 0xFF6C8CFF.toInt(),
    val transform: LayerTransform = LayerTransform(),
    val parentId: String? = null,
    val expanded: Boolean = true,
    /** Frame sequence ordered by offset. Empty for non-animated layers. */
    val frames: List<FrameRef> = emptyList(),
    val activeFrameId: String? = null,
    val text: TextLayerData? = null,
    val shapes: List<VectorShape> = emptyList(),
    val adjustment: AdjustmentSpec? = null,
    val maskFrameId: String? = null,
    /** Reference layers render with a tint and reduced opacity for tracing. */
    val referenceTint: Argb? = null,
)

// ---------------------------------------------------------------------------
// Animation
// ---------------------------------------------------------------------------

@Serializable
data class AnimatableValue(
    var channel: String = "opacity",
    var keyframes: List<Keyframe> = emptyList(),
)

@Serializable
data class Keyframe(
    val frame: Int,
    val value: Float,
    val interpolation: Interpolation = Interpolation.Linear,
    /** Bezier handle offsets in (time, value) space for [Interpolation.Bezier]. */
    val outTanX: Float = 0.33f,
    val outTanY: Float = 0f,
    val inTanX: Float = 0.33f,
    val inTanY: Float = 0f,
)

enum class Interpolation { Linear, EaseIn, EaseOut, EaseInOut, Hold, Bezier }

@Serializable
data class AnimTrack(
    val id: String,
    val name: String,
    val layerId: String? = null,
    val channels: List<AnimatableValue> = emptyList(),
    val visible: Boolean = true,
)

@Serializable
data class Marker(val frame: Int, val label: String, val color: Argb = 0xFFFF8A4C.toInt())

@Serializable
data class Scene(
    val id: String,
    val name: String,
    val startFrame: Int = 0,
    val endFrame: Int = 23,
    val background: Argb = 0xFFFFFFFF.toInt(),
)

enum class AudioTrackState { Normal, Muted, Solo }

@Serializable
data class AudioSettings(
    val trackId: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val offsetFrames: Double = 0.0,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = -1L,
)

@Serializable
data class OnionSkinConfig(
    val enabled: Boolean = false,
    val previousFrames: Int = 1,
    val nextFrames: Int = 1,
    val opacity: Float = 0.35f,
    val previousTint: Argb = 0xFFFF5C7A.toInt(),
    val nextTint: Argb = 0xFF4CD9FF.toInt(),
    val tintStrength: Float = 0.75f,
)

@Serializable
data class FrameMeta(
    val id: String,
    val duration: Int = 1,
    val label: String = "",
    val exposed: Boolean = true,
)

/** Bone used by the rigging tool for cut-out character animation. */
@Serializable
data class Bone(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val restX: Float,
    val restY: Float,
    val restAngle: Float = 0f,
    val length: Float = 80f,
    val layerId: String? = null,
    val rotation: Float = 0f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scale: Float = 1f,
)

@Serializable
data class Pose(val id: String, val name: String, val boneRotations: Map<String, Float>)

// ---------------------------------------------------------------------------
// Guides and perspective
// ---------------------------------------------------------------------------

@Serializable
data class VanishingPoint(val id: String, val x: Float, val y: Float, val color: Argb = 0xFF6C8CFF.toInt())

@Serializable
data class GuideLine(
    val id: String,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Argb = 0x80FF8A4C.toInt(),
    val symmetry: Boolean = false,
)

enum class SymmetryMode { None, Horizontal, Vertical, Both, Radial, Kaleidoscope }

@Serializable
data class PerspectiveGuide(
    val mode: Int = 0, // number of vanishing points: 1, 2 or 3
    val points: List<VanishingPoint> = emptyList(),
    val horizonY: Float = 0f,
    val enabled: Boolean = false,
)

// ---------------------------------------------------------------------------
// Comic
// ---------------------------------------------------------------------------

@Serializable
data class ComicPanel(
    val id: String,
    val x: Float, val y: Float, val width: Float, val height: Float,
    val borderWidth: Float = 6f,
    val borderColor: Argb = 0xFF111111.toInt(),
    val cornerRadius: Float = 0f,
    val rotation: Float = 0f,
)

@Serializable
data class ComicBubble(
    val id: String,
    val kind: String = "speech", // speech | thought | caption | shout
    val x: Float, val y: Float, val width: Float, val height: Float,
    val text: String = "",
    val tailTargetX: Float? = null,
    val tailTargetY: Float? = null,
    val fontSize: Float = 32f,
    val fillColor: Argb = 0xFFFFFFFF.toInt(),
    val strokeColor: Argb = 0xFF111111.toInt(),
    val strokeWidth: Float = 4f,
)

@Serializable
data class ComicPage(
    val index: Int,
    val panels: List<ComicPanel> = emptyList(),
    val bubbles: List<ComicBubble> = emptyList(),
    val screentone: Boolean = false,
    val safeMargin: Float = 48f,
)

// ---------------------------------------------------------------------------
// Project
// ---------------------------------------------------------------------------

enum class ProjectKind { Illustration, Animation, Comic, PixelArt, Design }

@Serializable
data class AnimationConfig(
    val fps: Int = 24,
    val frameCount: Int = 24,
    val loop: Boolean = true,
    val onionSkin: OnionSkinConfig = OnionSkinConfig(),
    val scenes: List<Scene> = emptyList(),
    val tracks: List<AnimTrack> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val frames: List<FrameMeta> = emptyList(),
    val bones: List<Bone> = emptyList(),
    val poses: List<Pose> = emptyList(),
    val currentSceneIndex: Int = 0,
)

@Serializable
data class PixelConfig(
    val tileWidth: Int = 32,
    val tileHeight: Int = 32,
    val showGrid: Boolean = true,
    val pixelScale: Int = 12,
    val restrictedPalette: List<Argb> = emptyList(),
)

@Serializable
data class DocumentSettings(
    val width: Int = 2048,
    val height: Int = 2048,
    val dpi: Int = 300,
    val backgroundColor: Argb = 0xFFFFFFFF.toInt(),
    val transparentBackground: Boolean = true,
    val ditherGradient: Boolean = false,
)

/**
 * In-memory representation of an open project. Binary frame pixels are *not*
 * held here — they live in the frame store on disk and are loaded on demand.
 */
@Serializable
data class ProjectDocument(
    val id: String,
    val title: String,
    val kind: ProjectKind = ProjectKind.Illustration,
    val settings: DocumentSettings = DocumentSettings(),
    val layers: List<Layer> = emptyList(),
    val activeLayerId: String? = null,
    val animation: AnimationConfig = AnimationConfig(),
    val pixel: PixelConfig = PixelConfig(),
    val pages: List<ComicPage> = emptyList(),
    val palettes: List<Palette> = emptyList(),
    val activePaletteId: String? = null,
    val guides: List<GuideLine> = emptyList(),
    val perspective: PerspectiveGuide = PerspectiveGuide(),
    val symmetry: SymmetryMode = SymmetryMode.None,
    val selectionFrameId: String? = null,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val thumbnailBase64: String? = null,
    @Transient val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ProjectSummary(
    val id: String,
    val title: String,
    val kind: ProjectKind,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val layerCount: Int,
    val modifiedAt: Long,
    val createdAt: Long,
    val thumbnailPath: String? = null,
    val filePath: String? = null,
)
