# ArtForge Mobile

A native Android painting, illustration and 2D animation studio. Kotlin + Jetpack Compose,
custom `Canvas` rendering over `android.graphics`, Room for project metadata, DataStore for
preferences, WorkManager for background maintenance/export, and the platform media stack for
video and audio. **No WebView, no remote services, no login** — the app is fully usable in
airplane mode.

- **Min SDK 29** (Android 10) · target/compile SDK 34
- Phones, tablets, foldables and stylus input; portrait and landscape
- Package id `com.artforge.mobile` (debug builds install as `com.artforge.mobile.debug`)

---

## Install the APK (read this before downloading)

The finished APKs are attached to the rolling **`latest-debug`** release:

| Device | Download |
| --- | --- |
| Android 10 or newer (API 29+) — the intended target | [ArtForgeMobile-android10-debug.apk](https://github.com/surafel5509-del/Art-Studio/releases/download/latest-debug/ArtForgeMobile-android10-debug.apk) |
| Android 8.0 – 9 (API 26–28) | [ArtForgeMobile-android8plus-debug.apk](https://github.com/surafel5509-del/Art-Studio/releases/download/latest-debug/ArtForgeMobile-android8plus-debug.apk) |
| checksums | [SHA256SUMS.txt](https://github.com/surafel5509-del/Art-Studio/releases/download/latest-debug/SHA256SUMS.txt) |

### If you see "There was a problem parsing the package"

That dialog has exactly three causes, in order of likelihood:

1. **Your Android version is older than the APK's `minSdkVersion`.** The Android 10 file
   declares `minSdkVersion 29`; a phone on Android 8 or 9 rejects it with this message. Use
   the **android8plus** file (that build is produced with `-PartforgeMinSdk=26`).
2. **The file that arrived is not an APK.** GitHub always wraps *workflow artifacts* in a
   ZIP, and a ZIP (or a browser page saved as `.html`) cannot be installed. Download the
   **release asset** named `*.apk`, never the `artforge-mobile-artifacts` download.
3. **The download was truncated or modified.** Check it:
   `sha256sum ArtForgeMobile-android10-debug.apk` should match `SHA256SUMS.txt`.

Also allow *Install unknown apps* for the browser/file manager you use. The debug build
installs as a separate app id (`com.artforge.mobile.debug`), so it will not overwrite a
release install and can coexist with one.

### How the pipeline prevents shipping a broken APK

`tools/verify_apk.py` parses the ZIP central directory, checks the CRC of every entry,
parses the **binary AndroidManifest.xml** (package name, versionCode, minSdkVersion,
targetSdkVersion) and refuses an artifact that merely *wraps* an APK. After that,
`apksigner verify` proves the signature and `aapt2 dump badging` prints the final identity.
Both APK variants must pass before the release is published, and the SHA-256 of each is
emitted as a run annotation.

---

## What is implemented

### Drawing engine (`engine/draw`)
- **34 tools**, each performing a real operation: brush, pencil, eraser, pixel eraser, smudge,
  blur, sharpen, dodge, burn, clone stamp, line, rectangle, rounded rectangle, ellipse, polygon,
  star, curve, gradient, bezier pen, path, text, perspective snap, bucket fill, magic wand,
  rect/ellipse/lasso/polygon/marquee selection, eyedropper, move, transform, crop, hand, zoom,
  canvas rotate.
- Pressure, tilt and stylus-rotation response; flow vs. opacity; hardness; spacing; scatter;
  size/colour jitter; dab shapes (round, flat, chisel, square, grain); build-up modes.
- A 60+ preset brush library across 12 categories with search, favourites and JSON import/export.
- Strokes are stamped into a separate buffer and merged on lift, which is what makes live
  preview, exact undo and symmetry cheap.
- Undo: pixel patches (dirty-rect `IntArray`s) for raster edits and JSON snapshots for structural
  edits, bounded by both step count and a byte budget.
- Selection: rect, ellipse, lasso, polygon, magic wand, colour range, plus add/subtract/intersect,
  feather, expand/contract, invert, fill, move, cut/copy/paste.
- Symmetry: horizontal, vertical, both, radial and kaleidoscope, drawn through real transforms.

### Layers (`engine/draw/Compositor`)
Groups with true nesting, clipping masks, layer masks, alpha lock, 20 blend modes
(including the full `PorterDuff`/`BlendMode` set), opacity, per-layer transforms, colour tags,
reference layers with tint, merge down, flatten, duplicate.

### Animation (`engine/animation`)
Frame-by-frame exposure sheet with holds and variable exposure length, per-layer frame
sequences, onion skin (previous/next counts, opacity, tints), keyframes with linear/ease/hold/bezier
interpolation, track channels, markers, scenes, inbetweens, 2D bone rigging with two-bone IK,
pose capture/blend and phoneme→mouth-shape mapping for lip sync.

### Audio (`engine/audio`)
Waveform peak extraction through `MediaExtractor`/`MediaCodec`, timeline-synced playback with
`MediaPlayer`, voice-over recording with `MediaRecorder`, WAV trimming.

### Pixel art (`engine/pixel`)
Bresenham plotting, palette enforcement, nearest-neighbour scaling, colour quantisation,
sprite-sheet packing/unpacking.

### Vector (`engine/vector`) and comic (`engine/comic`)
Path construction, anchor editing with bezier handles, transforms, alignment/distribution,
polygon booleans, SVG export (true paths when vector layers exist, embedded raster otherwise)
and an SVG path parser (`M L H V C S Q T Z`, absolute + relative). Comic panels: 9 layout
templates, webtoon strips, bubbles with tails, screentones, speed lines, panel split/merge.

### Effects (`engine/effects`)
33 effects implemented pixel-wise: blurs (gaussian, motion, radial, directional), sharpen,
unsharp mask, noise, film grain, pixelate/mosaic, halftone, vignette, glow, bloom, drop/inner
shadow, outline, emboss, bevel, hue/saturation, brightness/contrast, colour balance, levels,
curves, threshold, posterize, gradient map, chromatic aberration, distortion, ripple, wave,
displacement.

### Storage (`data/`)
- `.artforge` project packages: `manifest.json` + `frames/*.png` + `audio/*` + `thumbnail.png`,
  written to a `.tmp` file and atomically renamed.
- Frames are streamed through a size-bounded `FrameStore` cache (sized from the device heap), so a
  200-frame document never holds every frame in RAM.
- Room for project metadata, recovery snapshots, version history, assets, brushes and palettes.
- DataStore for 70+ preferences.
- Autosave (interval configurable) + crash-recovery snapshots surfaced in a Recovery screen.
- Import/export through the Storage Access Framework; no broad storage permissions.

### Export (`data/export`)
- Images: PNG, JPEG, WebP, SVG.
- Animation: **GIF via an own GIF89a/LZW encoder** (unit-tested byte-for-byte structure),
  MP4 (H.264) and WebM (VP8) via `MediaCodec` + `MediaMuxer`, PNG-sequence ZIP, sprite sheets.
- Large exports are handed to an `ExportWorker` so they survive rotation/backgrounding, with
  progress reported back into the UI.

### UI (`ui/`)
Responsive studio shell (phone bottom sheets / tablet side inspector), canvas with pinch-zoom,
two-finger rotate, pan, flip and a real-time brush cursor; colour panel with harmonies and
built-in palettes; layer panel with nesting; animation timeline with exposure grid, drag-scrub,
markers and onion-skin controls; export dialog; home screen with templates and recent projects;
settings for every persisted preference; asset library; help screen listing the real status of
each feature.

Keyboard shortcuts: `Ctrl+Z` undo, `Ctrl+Shift+Z` redo, `Ctrl+S` save, `Ctrl+O` open, `B` brush,
`E` eraser, `I` eyedropper, `Space` hand, `←`/`→` previous/next frame, `F5` play/pause,
`F6` duplicate frame.

---

## Honest status

Everything listed above executes real code on device; there are no decorative buttons or
simulated results. Known limitations:

| Area | Status |
| --- | --- |
| Core painting, layers, selection, undo | Complete |
| Animation timeline, onion skin, keyframes, rigging | Complete |
| GIF / MP4 / WebM / PNG-sequence / sprite-sheet export | Complete (hardware encoder quality varies by device) |
| GIF frame timing | GIF stores delay in whole centiseconds, so 24/25/30 fps are written at the nearest representable rate and the export message reports the real rate |
| SVG export | Vector paths when vector layers exist; otherwise the raster composite is embedded |
| Comic panels & bubbles | Layout, bubbles, tones and lines are implemented; full page-flow pagination is basic |
| Colour management | sRGB throughout; no ICC profile conversion or CMYK soft-proofing |
| Collaboration / cloud sync | Not implemented (deliberately offline) |
| Tablet multi-window / foldable hinge-aware layout | Responsive layout only; no hinge-specific pane splitting |

The in-app **Help → Feature status** screen mirrors this table.

---

## Architecture

Single Gradle module with strict package layering (kept single-module so CI stays fast and
reproducible):

```
core/     model, colour maths, geometry/interpolation, logging
engine/   draw (FrameStore, DrawingEngine, Compositor, Undo, Selection, Brushes)
          animation, effects, vector, pixel, comic, perspective, audio
data/     db (Room), project (serializer + repository), settings (DataStore),
          asset, export (engine, GIF encoder, worker glue), imports
ui/       theme, common controls, home, studio, settings, assets, navigation
work/     WorkManager maintenance + export workers
```

MVVM + repository: Compose screens observe `StudioState`/`StateFlow` from view models; all disk and
bitmap-heavy work runs on `Dispatchers.IO`; the Application acts as the explicit dependency
container (no annotation-processor DI, which keeps build times down).

---

## Tests

- `app/src/test` — JVM + Robolectric: colour maths, interpolation/geometry, animation timeline
  and onion skin, project storage round-trips (manifest, frames, Room metadata,
  autosave/recovery, duplication), layer mutator and canvas transform maths.
- `app/src/androidTest` — device/emulator tests, because they assert on **real rasterised
  pixels** (Robolectric's `Canvas` is a no-op): drawing engine (strokes, pressure, eraser,
  symmetry, fill boundary, shapes, magic wand, frame duplication), GIF encoder output,
  perspective snapping / comic text wrap, vector / pixel / effects / rig engines, plus Compose
  tests against the real `MainActivity` (home, settings, help, create-project-into-studio).

Unit tests are the required check in CI; the emulator job is allowed to fail so a flaky
emulator boot never blocks artifact delivery, but its diagnostics are annotated on the run.

## License

No license file is included yet; add one before distributing binaries.
