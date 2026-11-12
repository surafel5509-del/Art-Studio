#!/usr/bin/env python3
"""Generates the raster launcher icons (mipmap-*/ic_launcher.png + round variant).

Why this exists: adaptive icons (`mipmap-anydpi-v26`) only cover API 26+. API 26-28
launchers and some third-party launchers fall back to `mipmap/ic_launcher.png`, so
the app needs raster icons at every density. The build sandbox has no PIL, so the
PNGs are written directly with zlib, in pure Python. Run with:

    python3 tools/generate_launcher_icons.py

The artwork matches `res/drawable/ic_launcher_foreground.xml`: a warm brush stroke
crossing a cool arc on a deep indigo field.
"""

import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

SUPERSAMPLE = 4

BACKGROUND_TOP = (0x1B, 0x1B, 0x3A)
BACKGROUND_BOTTOM = (0x2E, 0x1D, 0x4F)
BRUSH_WARM = (0xFF, 0x8A, 0x3D)
BRUSH_HOT = (0xFF, 0xC4, 0x6B)
ACCENT = (0x4F, 0xD1, 0xE8)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def segment_distance(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    length_sq = dx * dx + dy * dy
    if length_sq == 0:
        return math.hypot(px - x1, py - y1)
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / length_sq))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def arc_distance(px, py, cx, cy, radius):
    return abs(math.hypot(px - cx, py - cy) - radius)


def sample(u, v):
    """Colour of the icon at (u, v) in 0..1 icon space, or None for transparent."""
    # Background: rounded square is applied by the caller; here we only shade.
    colour = mix(BACKGROUND_TOP, BACKGROUND_BOTTOM, v)

    # Cool arc: a thick ring segment on the right, hinting at the animation timeline.
    if arc_distance(u, v, 0.52, 0.48, 0.34) < 0.038 and u > 0.52:
        colour = mix(colour, ACCENT, 0.75)

    # Warm brush stroke: tapering diagonal from bottom-left tail to the top-right tip.
    d = segment_distance(u, v, 0.20, 0.84, 0.72, 0.26)
    width = 0.170 - 0.070 * ((u - 0.20) / 0.52)
    if d < width:
        edge = 1.0 - (d / max(width, 1e-6))
        colour = mix(colour, BRUSH_WARM, min(1.0, edge * 2.2))
        if edge > 0.5:
            colour = mix(colour, BRUSH_HOT, (edge - 0.5) / 0.5)

    # Ferrule: a short bright band across the stroke, aligned to it.
    band = ((u - 0.20) * 0.8 + (0.84 - v) * 0.6) / math.hypot(0.8, 0.6)
    if abs(band - 0.62) < 0.055 and d < width + 0.012:
        colour = mix(colour, (0xEE, 0xF2, 0xF8), 0.8)

    return colour


def inside_rounded(u, v, radius=0.22):
    """Rounded-square coverage test in 0..1 space."""
    x = min(max(u, radius), 1.0 - radius)
    y = min(max(v, radius), 1.0 - radius)
    return math.hypot(u - x, v - y) <= radius + 1e-9


def inside_circle(u, v, radius=0.5):
    return math.hypot(u - 0.5, v - 0.5) <= radius


def render(size, mask):
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)  # PNG filter type 0
        for x in range(size):
            r = g = b = a = 0.0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    u = (x + (sx + 0.5) / SUPERSAMPLE) / size
                    v = (y + (sy + 0.5) / SUPERSAMPLE) / size
                    if not mask(u, v):
                        continue
                    colour = sample(u, v)
                    if colour is None:
                        continue
                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 1.0
            total = SUPERSAMPLE * SUPERSAMPLE
            coverage = a / total
            if coverage <= 0.0:
                pixels.extend((0, 0, 0, 0))
            else:
                pixels.extend((
                    int(round(r / a)),
                    int(round(g / a)),
                    int(round(b / a)),
                    int(round(coverage * 255)),
                ))
    return bytes(pixels)


def write_png(path, size, pixels):
    raw = zlib.compress(pixels, 9)

    def chunk(tag, data):
        payload = tag + data
        return (
            struct.pack(">I", len(data))
            + payload
            + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", header))
        handle.write(chunk(b"IDAT", raw))
        handle.write(chunk(b"IEND", b""))


def main():
    for density, size in DENSITIES.items():
        directory = os.path.join(ROOT, f"mipmap-{density}")
        os.makedirs(directory, exist_ok=True)
        for name, mask in (("ic_launcher", inside_rounded), ("ic_launcher_round", inside_circle)):
            path = os.path.join(directory, f"{name}.png")
            write_png(path, size, render(size, mask))
            print(f"wrote {os.path.relpath(path, ROOT)} ({size}x{size})")


if __name__ == "__main__":
    main()
