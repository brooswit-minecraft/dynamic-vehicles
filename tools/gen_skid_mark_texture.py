#!/usr/bin/env python3
"""Reproducibly regenerate the skid mark decal texture (MINECRAFT-225).

Pixel-art only, no external assets: every pixel is drawn by this script, so re-running it always
produces a byte-identical PNG. Run from the repo root:

    python3 tools/gen_skid_mark_texture.py

It (re)writes:
  - src/main/resources/assets/dynamicvehicles/textures/misc/skid_mark.png (16x16, RGBA): a dark,
    semi-transparent smudge with soft edges (alpha falls off toward the border) so {@link
    SkidMarkRenderer}'s quads blend into the ground instead of showing a hard-edged rectangle. The
    per-mark fade (age -> alpha, see SkidMarkMath#alpha) is applied on top of this at render time via
    the quad's vertex color alpha, so this texture's own alpha is just the smudge shape, not the fade.

Requires Pillow (``pip install pillow``); not a build-time dependency.
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/dynamicvehicles/textures/misc/skid_mark.png"

SIZE = 16
RUBBER = (24, 22, 20)
MAX_ALPHA = 235


def gen_skid_mark(size=SIZE):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    cx = (size - 1) / 2.0
    cz = (size - 1) / 2.0
    half_x = size / 2.0
    half_z = size / 2.0
    for y in range(size):
        for x in range(size):
            # Normalized distance from centre on each axis, 0 at centre, 1 at the edge.
            nx = abs(x - cx) / half_x
            nz = abs(y - cz) / half_z
            edge = max(nx, nz)
            if edge >= 1.0:
                px[x, y] = (0, 0, 0, 0)
                continue
            # Soft falloff: solid through the middle, fading out over the outer third.
            fade = 1.0 if edge < 0.55 else max(0.0, 1.0 - (edge - 0.55) / 0.45)
            px[x, y] = (*RUBBER, int(MAX_ALPHA * fade))
    return img


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    gen_skid_mark().save(OUT)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
