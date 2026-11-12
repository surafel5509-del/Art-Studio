package com.artforge.mobile.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "artforge_settings")

enum class ThemeMode { Dark, Light, System }

enum class GridStyle { None, Fine, Coarse, Isometric }

enum class CanvasScale { Compact, Default, Large }

/** Every persisted user preference. Immutable data class backed by DataStore. */
data class AppSettings(
    // Interface
    val theme: ThemeMode = ThemeMode.Dark,
    val uiScale: Float = 1f,
    val canvasScale: CanvasScale = CanvasScale.Default,
    val showToolLabels: Boolean = true,
    val useLeftHandLayout: Boolean = false,
    val bottomToolbar: Boolean = false,
    val animationPanelExpanded: Boolean = true,
    val hapticFeedback: Boolean = true,
    val reduceMotion: Boolean = false,
    // Stylus / input
    val pressureEnabled: Boolean = true,
    val tiltEnabled: Boolean = true,
    val palmRejection: Boolean = true,
    val palmRejectionDelayMs: Int = 250,
    val pressureCurve: Float = 1f,
    val invertStylusButtons: Boolean = false,
    val oneFingerDraw: Boolean = true,
    val gestureRotate: Boolean = true,
    // Brush
    val defaultBrushId: String = "ink_gpen",
    val defaultEraserId: String = "eraser_soft",
    val strokeStabilizer: Float = 0.3f,
    val brushCursorEnabled: Boolean = true,
    val brushCursorSize: Boolean = true,
    val velocitySmoothing: Boolean = true,
    // Canvas
    val gridStyle: GridStyle = GridStyle.None,
    val showRulers: Boolean = false,
    val showGuides: Boolean = true,
    val pixelPerfect: Boolean = true,
    val canvasAntiAlias: Boolean = true,
    val defaultCanvasWidth: Int = 2048,
    val defaultCanvasHeight: Int = 2048,
    val defaultDpi: Int = 300,
    // Animation
    val defaultFps: Int = 24,
    val defaultFrameCount: Int = 24,
    val onionSkinEnabled: Boolean = true,
    val onionPrevious: Int = 1,
    val onionNext: Int = 1,
    val onionOpacity: Float = 0.35f,
    val loopPlayback: Boolean = true,
    val playbackSpeed: Float = 1f,
    // Audio
    val audioVolume: Float = 0.8f,
    val audioSyncEnabled: Boolean = true,
    // Performance
    val maxUndoSteps: Int = 80,
    val frameCacheMb: Int = 320,
    val renderQuality: Float = 1f,
    val memoryWarningEnabled: Boolean = true,
    // Storage
    val autosaveEnabled: Boolean = true,
    val autosaveIntervalSeconds: Int = 120,
    val keepAutoBackups: Boolean = true,
    val versionHistoryEnabled: Boolean = true,
    val recentLimit: Int = 20,
    // Export
    val defaultExportFormat: String = "png",
    val exportQuality: Int = 95,
    val exportScale: Float = 1f,
    val exportBackground: Boolean = true,
    // Accessibility
    val largeTouchTargets: Boolean = false,
    val highContrast: Boolean = false,
    val onboardingComplete: Boolean = false,
    val lastProjectId: String? = null,
)

/**
 * DataStore-backed settings repository. All settings are local (requirement #25)
 * and survive process death.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val uiScale = floatPreferencesKey("ui_scale")
        val canvasScale = stringPreferencesKey("canvas_scale")
        val showToolLabels = booleanPreferencesKey("show_tool_labels")
        val leftHand = booleanPreferencesKey("left_hand")
        val bottomToolbar = booleanPreferencesKey("bottom_toolbar")
        val animPanel = booleanPreferencesKey("anim_panel")
        val haptics = booleanPreferencesKey("haptics")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val pressure = booleanPreferencesKey("pressure")
        val tilt = booleanPreferencesKey("tilt")
        val palmRejection = booleanPreferencesKey("palm_rejection")
        val palmDelay = intPreferencesKey("palm_delay")
        val pressureCurve = floatPreferencesKey("pressure_curve")
        val invertStylus = booleanPreferencesKey("invert_stylus")
        val oneFingerDraw = booleanPreferencesKey("one_finger_draw")
        val gestureRotate = booleanPreferencesKey("gesture_rotate")
        val defaultBrush = stringPreferencesKey("default_brush")
        val defaultEraser = stringPreferencesKey("default_eraser")
        val stabilizer = floatPreferencesKey("stabilizer")
        val brushCursor = booleanPreferencesKey("brush_cursor")
        val cursorSize = booleanPreferencesKey("cursor_size")
        val velocitySmoothing = booleanPreferencesKey("velocity_smoothing")
        val gridStyle = stringPreferencesKey("grid_style")
        val rulers = booleanPreferencesKey("rulers")
        val guides = booleanPreferencesKey("guides")
        val pixelPerfect = booleanPreferencesKey("pixel_perfect")
        val antiAlias = booleanPreferencesKey("anti_alias")
        val canvasW = intPreferencesKey("canvas_w")
        val canvasH = intPreferencesKey("canvas_h")
        val dpi = intPreferencesKey("dpi")
        val fps = intPreferencesKey("fps")
        val frameCount = intPreferencesKey("frame_count")
        val onionEnabled = booleanPreferencesKey("onion_enabled")
        val onionPrev = intPreferencesKey("onion_prev")
        val onionNext = intPreferencesKey("onion_next")
        val onionOpacity = floatPreferencesKey("onion_opacity")
        val loop = booleanPreferencesKey("loop")
        val speed = floatPreferencesKey("speed")
        val audioVolume = floatPreferencesKey("audio_volume")
        val audioSync = booleanPreferencesKey("audio_sync")
        val undoSteps = intPreferencesKey("undo_steps")
        val cacheMb = intPreferencesKey("cache_mb")
        val quality = floatPreferencesKey("render_quality")
        val memoryWarning = booleanPreferencesKey("memory_warning")
        val autosave = booleanPreferencesKey("autosave")
        val autosaveInterval = intPreferencesKey("autosave_interval")
        val backups = booleanPreferencesKey("backups")
        val versions = booleanPreferencesKey("versions")
        val recentLimit = intPreferencesKey("recent_limit")
        val exportFormat = stringPreferencesKey("export_format")
        val exportQuality = intPreferencesKey("export_quality")
        val exportScale = floatPreferencesKey("export_scale")
        val exportBackground = booleanPreferencesKey("export_background")
        val largeTargets = booleanPreferencesKey("large_targets")
        val highContrast = booleanPreferencesKey("high_contrast")
        val onboarding = booleanPreferencesKey("onboarding")
        val lastProject = stringPreferencesKey("last_project")
        val lastOpened = longPreferencesKey("last_opened")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            uiScale = this[Keys.uiScale] ?: d.uiScale,
            canvasScale = this[Keys.canvasScale]?.let { runCatching { CanvasScale.valueOf(it) }.getOrNull() } ?: d.canvasScale,
            showToolLabels = this[Keys.showToolLabels] ?: d.showToolLabels,
            useLeftHandLayout = this[Keys.leftHand] ?: d.useLeftHandLayout,
            bottomToolbar = this[Keys.bottomToolbar] ?: d.bottomToolbar,
            animationPanelExpanded = this[Keys.animPanel] ?: d.animationPanelExpanded,
            hapticFeedback = this[Keys.haptics] ?: d.hapticFeedback,
            reduceMotion = this[Keys.reduceMotion] ?: d.reduceMotion,
            pressureEnabled = this[Keys.pressure] ?: d.pressureEnabled,
            tiltEnabled = this[Keys.tilt] ?: d.tiltEnabled,
            palmRejection = this[Keys.palmRejection] ?: d.palmRejection,
            palmRejectionDelayMs = this[Keys.palmDelay] ?: d.palmRejectionDelayMs,
            pressureCurve = this[Keys.pressureCurve] ?: d.pressureCurve,
            invertStylusButtons = this[Keys.invertStylus] ?: d.invertStylusButtons,
            oneFingerDraw = this[Keys.oneFingerDraw] ?: d.oneFingerDraw,
            gestureRotate = this[Keys.gestureRotate] ?: d.gestureRotate,
            defaultBrushId = this[Keys.defaultBrush] ?: d.defaultBrushId,
            defaultEraserId = this[Keys.defaultEraser] ?: d.defaultEraserId,
            strokeStabilizer = this[Keys.stabilizer] ?: d.strokeStabilizer,
            brushCursorEnabled = this[Keys.brushCursor] ?: d.brushCursorEnabled,
            brushCursorSize = this[Keys.cursorSize] ?: d.brushCursorSize,
            velocitySmoothing = this[Keys.velocitySmoothing] ?: d.velocitySmoothing,
            gridStyle = this[Keys.gridStyle]?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.gridStyle,
            showRulers = this[Keys.rulers] ?: d.showRulers,
            showGuides = this[Keys.guides] ?: d.showGuides,
            pixelPerfect = this[Keys.pixelPerfect] ?: d.pixelPerfect,
            canvasAntiAlias = this[Keys.antiAlias] ?: d.canvasAntiAlias,
            defaultCanvasWidth = this[Keys.canvasW] ?: d.defaultCanvasWidth,
            defaultCanvasHeight = this[Keys.canvasH] ?: d.defaultCanvasHeight,
            defaultDpi = this[Keys.dpi] ?: d.defaultDpi,
            defaultFps = this[Keys.fps] ?: d.defaultFps,
            defaultFrameCount = this[Keys.frameCount] ?: d.defaultFrameCount,
            onionSkinEnabled = this[Keys.onionEnabled] ?: d.onionSkinEnabled,
            onionPrevious = this[Keys.onionPrev] ?: d.onionPrevious,
            onionNext = this[Keys.onionNext] ?: d.onionNext,
            onionOpacity = this[Keys.onionOpacity] ?: d.onionOpacity,
            loopPlayback = this[Keys.loop] ?: d.loopPlayback,
            playbackSpeed = this[Keys.speed] ?: d.playbackSpeed,
            audioVolume = this[Keys.audioVolume] ?: d.audioVolume,
            audioSyncEnabled = this[Keys.audioSync] ?: d.audioSyncEnabled,
            maxUndoSteps = this[Keys.undoSteps] ?: d.maxUndoSteps,
            frameCacheMb = this[Keys.cacheMb] ?: d.frameCacheMb,
            renderQuality = this[Keys.quality] ?: d.renderQuality,
            memoryWarningEnabled = this[Keys.memoryWarning] ?: d.memoryWarningEnabled,
            autosaveEnabled = this[Keys.autosave] ?: d.autosaveEnabled,
            autosaveIntervalSeconds = this[Keys.autosaveInterval] ?: d.autosaveIntervalSeconds,
            keepAutoBackups = this[Keys.backups] ?: d.keepAutoBackups,
            versionHistoryEnabled = this[Keys.versions] ?: d.versionHistoryEnabled,
            recentLimit = this[Keys.recentLimit] ?: d.recentLimit,
            defaultExportFormat = this[Keys.exportFormat] ?: d.defaultExportFormat,
            exportQuality = this[Keys.exportQuality] ?: d.exportQuality,
            exportScale = this[Keys.exportScale] ?: d.exportScale,
            exportBackground = this[Keys.exportBackground] ?: d.exportBackground,
            largeTouchTargets = this[Keys.largeTargets] ?: d.largeTouchTargets,
            highContrast = this[Keys.highContrast] ?: d.highContrast,
            onboardingComplete = this[Keys.onboarding] ?: d.onboardingComplete,
            lastProjectId = this[Keys.lastProject],
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = transform(current)
            if (next.theme != current.theme) prefs[Keys.theme] = next.theme.name
            if (next.uiScale != current.uiScale) prefs[Keys.uiScale] = next.uiScale
            if (next.canvasScale != current.canvasScale) prefs[Keys.canvasScale] = next.canvasScale.name
            if (next.showToolLabels != current.showToolLabels) prefs[Keys.showToolLabels] = next.showToolLabels
            if (next.useLeftHandLayout != current.useLeftHandLayout) prefs[Keys.leftHand] = next.useLeftHandLayout
            if (next.bottomToolbar != current.bottomToolbar) prefs[Keys.bottomToolbar] = next.bottomToolbar
            if (next.animationPanelExpanded != current.animationPanelExpanded) prefs[Keys.animPanel] = next.animationPanelExpanded
            if (next.hapticFeedback != current.hapticFeedback) prefs[Keys.haptics] = next.hapticFeedback
            if (next.reduceMotion != current.reduceMotion) prefs[Keys.reduceMotion] = next.reduceMotion
            if (next.pressureEnabled != current.pressureEnabled) prefs[Keys.pressure] = next.pressureEnabled
            if (next.tiltEnabled != current.tiltEnabled) prefs[Keys.tilt] = next.tiltEnabled
            if (next.palmRejection != current.palmRejection) prefs[Keys.palmRejection] = next.palmRejection
            if (next.palmRejectionDelayMs != current.palmRejectionDelayMs) prefs[Keys.palmDelay] = next.palmRejectionDelayMs
            if (next.pressureCurve != current.pressureCurve) prefs[Keys.pressureCurve] = next.pressureCurve
            if (next.invertStylusButtons != current.invertStylusButtons) prefs[Keys.invertStylus] = next.invertStylusButtons
            if (next.oneFingerDraw != current.oneFingerDraw) prefs[Keys.oneFingerDraw] = next.oneFingerDraw
            if (next.gestureRotate != current.gestureRotate) prefs[Keys.gestureRotate] = next.gestureRotate
            if (next.defaultBrushId != current.defaultBrushId) prefs[Keys.defaultBrush] = next.defaultBrushId
            if (next.defaultEraserId != current.defaultEraserId) prefs[Keys.defaultEraser] = next.defaultEraserId
            if (next.strokeStabilizer != current.strokeStabilizer) prefs[Keys.stabilizer] = next.strokeStabilizer
            if (next.brushCursorEnabled != current.brushCursorEnabled) prefs[Keys.brushCursor] = next.brushCursorEnabled
            if (next.brushCursorSize != current.brushCursorSize) prefs[Keys.cursorSize] = next.brushCursorSize
            if (next.velocitySmoothing != current.velocitySmoothing) prefs[Keys.velocitySmoothing] = next.velocitySmoothing
            if (next.gridStyle != current.gridStyle) prefs[Keys.gridStyle] = next.gridStyle.name
            if (next.showRulers != current.showRulers) prefs[Keys.rulers] = next.showRulers
            if (next.showGuides != current.showGuides) prefs[Keys.guides] = next.showGuides
            if (next.pixelPerfect != current.pixelPerfect) prefs[Keys.pixelPerfect] = next.pixelPerfect
            if (next.canvasAntiAlias != current.canvasAntiAlias) prefs[Keys.antiAlias] = next.canvasAntiAlias
            if (next.defaultCanvasWidth != current.defaultCanvasWidth) prefs[Keys.canvasW] = next.defaultCanvasWidth
            if (next.defaultCanvasHeight != current.defaultCanvasHeight) prefs[Keys.canvasH] = next.defaultCanvasHeight
            if (next.defaultDpi != current.defaultDpi) prefs[Keys.dpi] = next.defaultDpi
            if (next.defaultFps != current.defaultFps) prefs[Keys.fps] = next.defaultFps
            if (next.defaultFrameCount != current.defaultFrameCount) prefs[Keys.frameCount] = next.defaultFrameCount
            if (next.onionSkinEnabled != current.onionSkinEnabled) prefs[Keys.onionEnabled] = next.onionSkinEnabled
            if (next.onionPrevious != current.onionPrevious) prefs[Keys.onionPrev] = next.onionPrevious
            if (next.onionNext != current.onionNext) prefs[Keys.onionNext] = next.onionNext
            if (next.onionOpacity != current.onionOpacity) prefs[Keys.onionOpacity] = next.onionOpacity
            if (next.loopPlayback != current.loopPlayback) prefs[Keys.loop] = next.loopPlayback
            if (next.playbackSpeed != current.playbackSpeed) prefs[Keys.speed] = next.playbackSpeed
            if (next.audioVolume != current.audioVolume) prefs[Keys.audioVolume] = next.audioVolume
            if (next.audioSyncEnabled != current.audioSyncEnabled) prefs[Keys.audioSync] = next.audioSyncEnabled
            if (next.maxUndoSteps != current.maxUndoSteps) prefs[Keys.undoSteps] = next.maxUndoSteps
            if (next.frameCacheMb != current.frameCacheMb) prefs[Keys.cacheMb] = next.frameCacheMb
            if (next.renderQuality != current.renderQuality) prefs[Keys.quality] = next.renderQuality
            if (next.memoryWarningEnabled != current.memoryWarningEnabled) prefs[Keys.memoryWarning] = next.memoryWarningEnabled
            if (next.autosaveEnabled != current.autosaveEnabled) prefs[Keys.autosave] = next.autosaveEnabled
            if (next.autosaveIntervalSeconds != current.autosaveIntervalSeconds) prefs[Keys.autosaveInterval] = next.autosaveIntervalSeconds
            if (next.keepAutoBackups != current.keepAutoBackups) prefs[Keys.backups] = next.keepAutoBackups
            if (next.versionHistoryEnabled != current.versionHistoryEnabled) prefs[Keys.versions] = next.versionHistoryEnabled
            if (next.recentLimit != current.recentLimit) prefs[Keys.recentLimit] = next.recentLimit
            if (next.defaultExportFormat != current.defaultExportFormat) prefs[Keys.exportFormat] = next.defaultExportFormat
            if (next.exportQuality != current.exportQuality) prefs[Keys.exportQuality] = next.exportQuality
            if (next.exportScale != current.exportScale) prefs[Keys.exportScale] = next.exportScale
            if (next.exportBackground != current.exportBackground) prefs[Keys.exportBackground] = next.exportBackground
            if (next.largeTouchTargets != current.largeTouchTargets) prefs[Keys.largeTargets] = next.largeTouchTargets
            if (next.highContrast != current.highContrast) prefs[Keys.highContrast] = next.highContrast
            if (next.onboardingComplete != current.onboardingComplete) prefs[Keys.onboarding] = next.onboardingComplete
            if (next.lastProjectId != current.lastProjectId) {
                next.lastProjectId?.let { prefs[Keys.lastProject] = it }
                prefs[Keys.lastOpened] = System.currentTimeMillis()
            }
        }
    }
}
