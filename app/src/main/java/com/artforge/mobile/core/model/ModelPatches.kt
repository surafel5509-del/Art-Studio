package com.artforge.mobile.core.model

/**
 * Small additive model fields kept in a separate file so the main model stays
 * readable. These are part of the serialised document.
 */
data class LayerBlendInfo(val label: String, val mode: BlendMode)

val BlendModeLabels: Map<BlendMode, String> = mapOf(
    BlendMode.Normal to "Normal",
    BlendMode.Multiply to "Multiply",
    BlendMode.Screen to "Screen",
    BlendMode.Overlay to "Overlay",
    BlendMode.SoftLight to "Soft Light",
    BlendMode.HardLight to "Hard Light",
    BlendMode.Darken to "Darken",
    BlendMode.Lighten to "Lighten",
    BlendMode.ColorDodge to "Color Dodge",
    BlendMode.ColorBurn to "Color Burn",
    BlendMode.Difference to "Difference",
    BlendMode.Exclusion to "Exclusion",
    BlendMode.Hue to "Hue",
    BlendMode.Saturation to "Saturation",
    BlendMode.Color to "Color",
    BlendMode.Luminosity to "Luminosity",
)

val LayerKindLabels: Map<LayerKind, String> = mapOf(
    LayerKind.Raster to "Raster",
    LayerKind.Vector to "Vector",
    LayerKind.Text to "Text",
    LayerKind.Group to "Group",
    LayerKind.Reference to "Reference",
    LayerKind.Adjustment to "Adjustment",
    LayerKind.Mask to "Mask",
    LayerKind.Animation to "Animation",
)
