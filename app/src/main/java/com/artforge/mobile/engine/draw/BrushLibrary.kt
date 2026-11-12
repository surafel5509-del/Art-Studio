package com.artforge.mobile.engine.draw

import com.artforge.mobile.core.model.BrushBuildUp
import com.artforge.mobile.core.model.BrushCategory
import com.artforge.mobile.core.model.BrushPreset
import com.artforge.mobile.core.model.BrushSettings
import com.artforge.mobile.core.model.DabShape
import kotlin.math.max
import kotlin.math.min

/**
 * The built-in brush catalogue.
 *
 * Every preset is a real parameter set for [DrawingEngine]; none of these are
 * decorative. The catalogue is intentionally broad (pencil → oil → pixel → smudge)
 * because the brush engine is parameter driven rather than hard-coded per brush.
 */
object BrushLibrary {

    private fun preset(
        id: String,
        name: String,
        category: BrushCategory,
        settings: BrushSettings,
        folder: String = category.name,
    ) = BrushPreset(
        id = id,
        name = name,
        category = category,
        settings = settings.copy(category = category),
        folder = folder,
        builtIn = true,
    )

    val presets: List<BrushPreset> = listOf(
        // ---------------- Pencil / graphite -------------------------------------
        preset(
            "pencil_hb", "HB Pencil", BrushCategory.Pencil,
            BrushSettings(
                size = 6f, opacity = 0.82f, flow = 0.55f, hardness = 0.55f, spacing = 0.09f,
                pressureSize = 0.55f, pressureOpacity = 0.85f, smoothing = 0.2f, sizeJitter = 0.12f,
                shape = DabShape.Round,
            ),
        ),
        preset(
            "pencil_mech", "Mechanical Pencil", BrushCategory.Pencil,
            BrushSettings(
                size = 3f, opacity = 0.95f, flow = 0.7f, hardness = 0.8f, spacing = 0.06f,
                pressureSize = 0.25f, pressureOpacity = 0.7f, smoothing = 0.15f,
            ),
        ),
        preset(
            "graphite_soft", "Soft Graphite", BrushCategory.Pencil,
            BrushSettings(
                size = 26f, opacity = 0.5f, flow = 0.25f, hardness = 0.15f, spacing = 0.12f,
                scatter = 0.18f, sizeJitter = 0.35f, pressureSize = 0.5f, pressureOpacity = 0.9f,
                smoothing = 0.3f, tiltSize = 0.6f,
            ),
        ),
        preset(
            "graphite_stick", "Graphite Stick", BrushCategory.Pencil,
            BrushSettings(
                size = 40f, opacity = 0.65f, flow = 0.35f, hardness = 0.35f, spacing = 0.1f,
                roundness = 0.45f, angle = 35f, scatter = 0.08f, pressureOpacity = 0.8f, tiltSize = 0.5f,
            ),
        ),

        // ---------------- Ink ---------------------------------------------------
        preset(
            "ink_gpen", "G-Pen", BrushCategory.Ink,
            BrushSettings(
                size = 10f, opacity = 1f, flow = 1f, hardness = 0.95f, spacing = 0.035f,
                pressureSize = 0.9f, pressureOpacity = 0.15f, smoothing = 0.45f,
            ),
        ),
        preset(
            "ink_technical", "Technical Pen", BrushCategory.Ink,
            BrushSettings(
                size = 4f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.03f,
                pressureSize = 0f, pressureOpacity = 0f, smoothing = 0.6f,
            ),
        ),
        preset(
            "ink_ballpoint", "Ballpoint", BrushCategory.Ink,
            BrushSettings(
                size = 3.5f, opacity = 0.9f, flow = 0.85f, hardness = 0.85f, spacing = 0.05f,
                pressureSize = 0.35f, pressureOpacity = 0.55f, smoothing = 0.25f,
            ),
        ),
        preset(
            "ink_fountain", "Fountain Pen", BrushCategory.Ink,
            BrushSettings(
                size = 14f, opacity = 1f, flow = 0.9f, hardness = 0.9f, spacing = 0.04f,
                roundness = 0.35f, angle = 45f, rotationFollowsStylus = 0.5f,
                pressureSize = 0.75f, pressureOpacity = 0.3f, smoothing = 0.4f,
            ),
        ),
        preset(
            "ink_calligraphy", "Calligraphy", BrushCategory.Ink,
            BrushSettings(
                size = 24f, opacity = 1f, flow = 1f, hardness = 0.9f, spacing = 0.05f,
                shape = DabShape.Flat, roundness = 0.22f, angle = 40f, rotationFollowsStylus = 0.8f,
                pressureSize = 0.4f, pressureOpacity = 0.2f, smoothing = 0.3f,
            ),
        ),
        preset(
            "ink_liner", "Liner", BrushCategory.Ink,
            BrushSettings(
                size = 2f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.05f,
                pressureSize = 0.2f, smoothing = 0.7f,
            ),
        ),

        // ---------------- Marker ------------------------------------------------
        preset(
            "marker_chisel", "Chisel Marker", BrushCategory.Marker,
            BrushSettings(
                size = 34f, opacity = 0.6f, flow = 0.5f, hardness = 0.75f, spacing = 0.05f,
                shape = DabShape.Chisel, roundness = 0.3f, angle = 35f, rotationFollowsStylus = 0.7f,
                pressureSize = 0.2f, pressureOpacity = 0.45f, smoothing = 0.35f,
                buildUp = BrushBuildUp.Multiply,
            ),
        ),
        preset(
            "marker_alcohol", "Alcohol Marker", BrushCategory.Marker,
            BrushSettings(
                size = 28f, opacity = 0.42f, flow = 0.35f, hardness = 0.6f, spacing = 0.06f,
                buildUp = BrushBuildUp.Multiply, pressureOpacity = 0.5f, smoothing = 0.3f, wetness = 0.25f,
            ),
        ),
        preset(
            "marker_highlighter", "Highlighter", BrushCategory.Marker,
            BrushSettings(
                size = 40f, opacity = 0.3f, flow = 0.3f, hardness = 0.5f, spacing = 0.05f,
                shape = DabShape.Square, roundness = 0.5f, buildUp = BrushBuildUp.Multiply,
                pressureSize = 0f, pressureOpacity = 0.2f,
            ),
        ),

        // ---------------- Paint --------------------------------------------------
        preset(
            "paint_oil", "Oil Paint", BrushCategory.Paint,
            BrushSettings(
                size = 48f, opacity = 0.95f, flow = 0.55f, hardness = 0.35f, spacing = 0.06f,
                roundness = 0.7f, angleJitter = 0.3f, scatter = 0.05f, sizeJitter = 0.15f,
                pressureSize = 0.4f, pressureOpacity = 0.7f, tiltSize = 0.5f, smudgeStrength = 0.6f,
                wetness = 0.35f, smoothing = 0.2f,
            ),
        ),
        preset(
            "paint_acrylic", "Acrylic", BrushCategory.Paint,
            BrushSettings(
                size = 36f, opacity = 0.98f, flow = 0.7f, hardness = 0.55f, spacing = 0.07f,
                roundness = 0.8f, pressureSize = 0.45f, pressureOpacity = 0.6f, wetness = 0.15f,
            ),
        ),
        preset(
            "paint_gouache", "Gouache", BrushCategory.Paint,
            BrushSettings(
                size = 30f, opacity = 0.9f, flow = 0.5f, hardness = 0.45f, spacing = 0.08f,
                scatter = 0.04f, pressureSize = 0.5f, pressureOpacity = 0.75f, wetness = 0.2f, tiltSize = 0.3f,
            ),
        ),
        preset(
            "paint_watercolor", "Watercolor", BrushCategory.Watercolor,
            BrushSettings(
                size = 56f, opacity = 0.35f, flow = 0.18f, hardness = 0.12f, spacing = 0.1f,
                scatter = 0.12f, sizeJitter = 0.25f, pressureSize = 0.35f, pressureOpacity = 0.85f,
                wetness = 0.75f, buildUp = BrushBuildUp.Multiply, smoothing = 0.25f,
            ),
        ),
        preset(
            "paint_wetwash", "Wet Wash", BrushCategory.Watercolor,
            BrushSettings(
                size = 90f, opacity = 0.22f, flow = 0.1f, hardness = 0.05f, spacing = 0.12f,
                scatter = 0.2f, pressureOpacity = 0.9f, wetness = 0.9f, buildUp = BrushBuildUp.Multiply,
            ),
        ),
        preset(
            "airbrush_soft", "Soft Airbrush", BrushCategory.Airbrush,
            BrushSettings(
                size = 120f, opacity = 0.25f, flow = 0.12f, hardness = 0f, spacing = 0.04f,
                pressureSize = 0.2f, pressureOpacity = 0.9f, smoothing = 0.4f,
            ),
        ),
        preset(
            "airbrush_hard", "Hard Airbrush", BrushCategory.Airbrush,
            BrushSettings(
                size = 60f, opacity = 0.6f, flow = 0.3f, hardness = 0.55f, spacing = 0.05f,
                pressureSize = 0.3f, pressureOpacity = 0.8f,
            ),
        ),
        preset(
            "spray_paint", "Spray", BrushCategory.Airbrush,
            BrushSettings(
                size = 70f, opacity = 0.4f, flow = 0.15f, hardness = 0.2f, spacing = 0.25f,
                scatter = 1.4f, sizeJitter = 0.8f, colorJitter = 0.15f, pressureOpacity = 0.85f,
            ),
        ),

        // ---------------- Dry media ---------------------------------------------
        preset(
            "charcoal", "Charcoal", BrushCategory.Dry,
            BrushSettings(
                size = 44f, opacity = 0.7f, flow = 0.3f, hardness = 0.1f, spacing = 0.14f,
                scatter = 0.3f, sizeJitter = 0.5f, roundness = 0.6f, tiltSize = 0.8f,
                pressureSize = 0.4f, pressureOpacity = 0.85f,
            ),
        ),
        preset(
            "pastel", "Pastel", BrushCategory.Dry,
            BrushSettings(
                size = 38f, opacity = 0.75f, flow = 0.35f, hardness = 0.25f, spacing = 0.12f,
                scatter = 0.22f, colorJitter = 0.1f, tiltSize = 0.6f, pressureOpacity = 0.8f,
            ),
        ),
        preset(
            "crayon", "Crayon", BrushCategory.Dry,
            BrushSettings(
                size = 22f, opacity = 0.85f, flow = 0.4f, hardness = 0.35f, spacing = 0.16f,
                scatter = 0.28f, sizeJitter = 0.3f, angleJitter = 0.4f, pressureOpacity = 0.7f,
            ),
        ),
        preset(
            "chalk", "Chalk", BrushCategory.Dry,
            BrushSettings(
                size = 30f, opacity = 0.6f, flow = 0.25f, hardness = 0.15f, spacing = 0.18f,
                scatter = 0.35f, sizeJitter = 0.55f, textureScale = 1.4f,
            ),
        ),

        // ---------------- Texture ------------------------------------------------
        preset(
            "texture_grain", "Grain Brush", BrushCategory.Texture,
            BrushSettings(
                size = 50f, opacity = 0.8f, flow = 0.4f, hardness = 0.5f, spacing = 0.2f,
                shape = DabShape.Grain, scatter = 0.4f, sizeJitter = 0.7f, textureScale = 2f,
            ),
        ),
        preset(
            "texture_stipple", "Stipple", BrushCategory.Texture,
            BrushSettings(
                size = 26f, opacity = 0.9f, flow = 0.6f, hardness = 0.9f, spacing = 0.6f,
                scatter = 1.1f, sizeJitter = 0.6f,
            ),
        ),
        preset(
            "texture_pattern", "Pattern Brush", BrushCategory.Texture,
            BrushSettings(
                size = 64f, opacity = 0.85f, flow = 0.5f, hardness = 0.7f, spacing = 0.5f,
                angle = 45f, angleJitter = 0.1f, textureScale = 1f,
            ),
        ),

        // ---------------- Pixel --------------------------------------------------
        preset(
            "pixel_1px", "Pixel 1px", BrushCategory.Pixel,
            BrushSettings(
                size = 1f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 1f,
                shape = DabShape.Square, roundness = 1f, pressureSize = 0f, pressureOpacity = 0f,
                smoothing = 0f,
            ),
        ),
        preset(
            "pixel_3px", "Pixel 3px", BrushCategory.Pixel,
            BrushSettings(
                size = 3f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 1f,
                shape = DabShape.Square, pressureSize = 0f, pressureOpacity = 0f, smoothing = 0f,
            ),
        ),
        preset(
            "pixel_dither", "Pixel Dither", BrushCategory.Pixel,
            BrushSettings(
                size = 2f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 1.6f,
                shape = DabShape.Square, scatter = 1.2f, pressureSize = 0f, smoothing = 0f,
            ),
        ),

        // ---------------- Blend / smudge ----------------------------------------
        preset(
            "blend_smudge", "Smudge", BrushCategory.Blend,
            BrushSettings(size = 60f, opacity = 0.7f, smudgeStrength = 0.65f, hardness = 0.3f, spacing = 0.08f),
        ),
        preset(
            "blend_blur", "Soft Blur", BrushCategory.Blend,
            BrushSettings(size = 80f, opacity = 0.5f, smudgeStrength = 0.55f, hardness = 0.2f, spacing = 0.1f),
        ),
        preset(
            "blend_sharpen", "Sharpen", BrushCategory.Blend,
            BrushSettings(size = 40f, opacity = 0.6f, smudgeStrength = 0.35f, hardness = 0.3f, spacing = 0.1f),
        ),

        // ---------------- Erasers ------------------------------------------------
        preset(
            "eraser_soft", "Soft Eraser", BrushCategory.Eraser,
            BrushSettings(size = 50f, opacity = 0.85f, flow = 0.6f, hardness = 0.25f, spacing = 0.07f,
                pressureSize = 0.5f, pressureOpacity = 0.8f),
        ),
        preset(
            "eraser_hard", "Hard Eraser", BrushCategory.Eraser,
            BrushSettings(size = 24f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.04f),
        ),
        preset(
            "eraser_pixel", "Pixel Eraser", BrushCategory.Eraser,
            BrushSettings(size = 4f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 1f,
                shape = DabShape.Square, pressureSize = 0f, smoothing = 0f),
        ),

        // ---------------- Effects ------------------------------------------------
        preset(
            "fx_sparkle", "Sparkle", BrushCategory.Effect,
            BrushSettings(
                size = 18f, opacity = 0.95f, flow = 0.8f, hardness = 0.6f, spacing = 0.7f,
                scatter = 1.6f, sizeJitter = 0.9f, colorJitter = 0.2f, buildUp = BrushBuildUp.Add,
            ),
        ),
        preset(
            "fx_neon", "Neon Glow", BrushCategory.Effect,
            BrushSettings(
                size = 16f, opacity = 0.8f, flow = 0.4f, hardness = 0.1f, spacing = 0.05f,
                buildUp = BrushBuildUp.Add, pressureSize = 0.6f,
            ),
        ),
        preset(
            "fx_rain", "Rain Streaks", BrushCategory.Effect,
            BrushSettings(
                size = 8f, opacity = 0.5f, flow = 0.3f, hardness = 0.3f, spacing = 0.4f,
                roundness = 0.15f, angle = 80f, scatter = 0.8f, sizeJitter = 0.5f,
            ),
        ),
    )

    private val byId = presets.associateBy { it.id }

    fun get(id: String): BrushPreset? = byId[id]

    fun byCategory(category: BrushCategory): List<BrushPreset> = presets.filter { it.category == category }

    fun search(query: String): List<BrushPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return presets
        return presets.filter { it.name.lowercase().contains(q) || it.category.name.lowercase().contains(q) }
    }

    fun categories(): List<BrushCategory> = BrushCategory.entries

    fun folders(): List<String> = presets.map { it.folder }.distinct().sorted()

    /**
     * Builds a brush pack container (shareable JSON) from the given presets.
     * The pack format is plain JSON so users can hand-edit or version-control packs.
     */
    fun packJson(packName: String, presets: List<BrushPreset>): String {
        val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        return json.encodeToString(
            BrushPack.serializer(),
            BrushPack(name = packName, version = 1, brushes = presets),
        )
    }

    fun parsePack(json: String): List<BrushPreset> {
        val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        return parser.decodeFromString(BrushPack.serializer(), json).brushes
    }

    /** Applies a parameter delta while keeping the value in its legal range. */
    fun sanitize(settings: BrushSettings): BrushSettings = settings.copy(
        size = settings.size.coerceIn(0.5f, 2000f),
        opacity = settings.opacity.coerceIn(0f, 1f),
        flow = settings.flow.coerceIn(0f, 1f),
        hardness = settings.hardness.coerceIn(0f, 1f),
        spacing = settings.spacing.coerceIn(0.01f, 2f),
        density = settings.density.coerceIn(0f, 1f),
        scatter = max(0f, settings.scatter),
        roundness = settings.roundness.coerceIn(0.05f, 1f),
        smoothing = settings.smoothing.coerceIn(0f, 0.98f),
        pressureSize = settings.pressureSize.coerceIn(0f, 1f),
        pressureOpacity = settings.pressureOpacity.coerceIn(0f, 1f),
        tiltSize = settings.tiltSize.coerceIn(0f, 1f),
        sizeJitter = settings.sizeJitter.coerceIn(0f, 1f),
        colorJitter = settings.colorJitter.coerceIn(0f, 1f),
        textureScale = min(max(settings.textureScale, 0.1f), 8f),
        wetness = settings.wetness.coerceIn(0f, 1f),
        smudgeStrength = settings.smudgeStrength.coerceIn(0f, 1f),
    )
}

@kotlinx.serialization.Serializable
data class BrushPack(
    val name: String,
    val version: Int = 1,
    val brushes: List<BrushPreset> = emptyList(),
)
