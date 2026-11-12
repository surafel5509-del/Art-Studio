package com.artforge.mobile.data.project

import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.AnimationConfig as AnimCfg
import com.artforge.mobile.core.model.DocumentSettings
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.ProjectKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `.artforge` package format.
 *
 * A project is a ZIP archive:
 *   manifest.json      – document metadata (layers, animation config, palettes…)
 *   frames/<id>.png    – one PNG per drawing, referenced from the manifest
 *   audio/<file>       – imported audio tracks
 *   thumbnail.png      – home-screen preview
 *
 * The manifest is plain JSON (human-readable, diffable, forward-compatible via
 * `schemaVersion`) and the pixel data stays PNG so it loads quickly with the
 * platform decoder and survives partial corruption of a single drawing.
 */
@Serializable
data class ArtforgeManifest(
    val schemaVersion: Int = ProjectDocument.SCHEMA_VERSION,
    val appVersion: String = "1.0.0",
    val id: String,
    val title: String,
    val kind: String,
    val settings: DocumentSettings,
    val layers: List<Layer>,
    val activeLayerId: String?,
    val animation: AnimationConfig,
    val pixel: com.artforge.mobile.core.model.PixelConfig,
    val pages: List<com.artforge.mobile.core.model.ComicPage>,
    val palettes: List<com.artforge.mobile.core.model.Palette>,
    val activePaletteId: String?,
    val guides: List<com.artforge.mobile.core.model.GuideLine>,
    val perspective: com.artforge.mobile.core.model.PerspectiveGuide,
    val symmetry: com.artforge.mobile.core.model.SymmetryMode,
    val createdAt: Long,
    val modifiedAt: Long,
    /** Frame ids whose PNG must exist in frames/. */
    val frameIds: List<String>,
    val audioFiles: List<String> = emptyList(),
)

object ProjectSerializer {

    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    fun toManifest(doc: ProjectDocument, frameIds: List<String>, audioFiles: List<String> = emptyList()): ArtforgeManifest =
        ArtforgeManifest(
            schemaVersion = ProjectDocument.SCHEMA_VERSION,
            id = doc.id,
            title = doc.title,
            kind = doc.kind.name,
            settings = doc.settings,
            layers = doc.layers,
            activeLayerId = doc.activeLayerId,
            animation = doc.animation,
            pixel = doc.pixel,
            pages = doc.pages,
            palettes = doc.palettes,
            activePaletteId = doc.activePaletteId,
            guides = doc.guides,
            perspective = doc.perspective,
            symmetry = doc.symmetry,
            createdAt = doc.createdAt,
            modifiedAt = doc.modifiedAt,
            frameIds = frameIds,
            audioFiles = audioFiles,
        )

    fun encodeManifest(manifest: ArtforgeManifest): String = json.encodeToString(ArtforgeManifest.serializer(), manifest)

    fun decodeManifest(text: String): ArtforgeManifest = json.decodeFromString(ArtforgeManifest.serializer(), text)

    fun toDocument(manifest: ArtforgeManifest): ProjectDocument = ProjectDocument(
        id = manifest.id,
        title = manifest.title,
        kind = runCatching { ProjectKind.valueOf(manifest.kind) }.getOrDefault(ProjectKind.Illustration),
        settings = manifest.settings,
        layers = manifest.layers,
        activeLayerId = manifest.activeLayerId,
        animation = manifest.animation,
        pixel = manifest.pixel,
        pages = manifest.pages,
        palettes = manifest.palettes,
        activePaletteId = manifest.activePaletteId,
        guides = manifest.guides,
        perspective = manifest.perspective,
        symmetry = manifest.symmetry,
        createdAt = manifest.createdAt,
        modifiedAt = manifest.modifiedAt,
    )

    /** All frame ids referenced anywhere in the document (deduplicated, ordered). */
    fun collectFrameIds(doc: ProjectDocument): List<String> {
        val ids = LinkedHashSet<String>()
        doc.layers.forEach { layer ->
            layer.activeFrameId?.let(ids::add)
            layer.maskFrameId?.let(ids::add)
            layer.frames.sortedBy { it.offset }.forEach { ids.add(it.frameId) }
        }
        doc.palettes.forEach { _ -> }
        return ids.toList()
    }

    /**
     * Migrates an older manifest to the current schema. Kept small and explicit so
     * future format changes never silently lose artwork.
     */
    fun migrate(manifest: ArtforgeManifest): ArtforgeManifest {
        if (manifest.schemaVersion >= ProjectDocument.SCHEMA_VERSION) return manifest
        var m = manifest
        // v0 → v1: animation frames list was implicit; synthesise entries.
        if (m.schemaVersion < 1) {
            val frameIds = m.animation.frames.map { it.id }.ifEmpty { m.frameIds }
            m = m.copy(
                schemaVersion = 1,
                animation = m.animation.copy(frames = m.animation.frames.ifEmpty {
                    frameIds.mapIndexed { i, id -> com.artforge.mobile.core.model.FrameMeta(id, 1, "", true) }
                }),
            )
        }
        return m
    }

    /** Serialises just the animation block (used by undo for structural edits). */
    fun encodeAnimation(config: AnimCfg): String = json.encodeToString(AnimCfg.serializer(), config)
}
