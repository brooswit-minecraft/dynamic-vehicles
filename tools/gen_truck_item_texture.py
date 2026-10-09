#!/usr/bin/env python3
"""Generates the truck item icon (16x16 PNG) to match the truck's new
block-built render: an orange body, white trim/roof/bumper, dark cab glass
and black wheels, viewed as a simple 3/4 side silhouette.

Not run at build/runtime -- regenerate by hand with `python3
tools/gen_truck_item_texture.py` whenever the truck's paint scheme changes.
Output: src/main/resources/assets/dynamicvehicles/textures/item/truck.png
"""
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/dynamicvehicles/textures/item/truck.png"

TRANSPARENT = (0, 0, 0, 0)
OUTLINE = (40, 25, 10, 255)
BODY = (216, 115, 33, 255)
BODY_SHADE = (178, 92, 23, 255)
TRIM = (235, 235, 230, 255)
TRIM_SHADE = (196, 196, 190, 255)
GLASS = (120, 170, 200, 255)
GLASS_SHADE = (90, 130, 155, 255)
WHEEL = (25, 25, 25, 255)
BUMPER = (60, 60, 60, 255)

W = H = 16


def px(img, x, y, color):
    if 0 <= x < W and 0 <= y < H:
        img.putpixel((x, y), color)


def row(img, y, x0, x1, color):
    for x in range(x0, x1):
        px(img, x, y, color)


def main():
    img = Image.new("RGBA", (W, H), TRANSPARENT)

    # Cargo bed (rear, left side of icon), low walls.
    row(img, 6, 1, 6, TRIM)
    row(img, 7, 1, 6, BODY_SHADE)
    row(img, 8, 1, 6, BODY_SHADE)
    row(img, 9, 1, 6, BODY)

    # Cab (centre), taller, with a glass window band.
    row(img, 3, 6, 11, OUTLINE)
    row(img, 4, 6, 11, GLASS)
    row(img, 5, 6, 11, GLASS_SHADE)
    row(img, 6, 6, 11, TRIM)
    row(img, 7, 6, 11, BODY)
    row(img, 8, 6, 11, BODY)
    row(img, 9, 6, 11, BODY)

    # Hood (front, right side of icon), lower than the cab.
    row(img, 7, 11, 15, TRIM_SHADE)
    row(img, 8, 11, 15, BODY)
    row(img, 9, 11, 15, BODY)

    # Front bumper / grille.
    row(img, 9, 13, 16, BUMPER)

    # Outline the whole silhouette top edge.
    row(img, 2, 6, 11, OUTLINE)
    for x in range(1, 16):
        px(img, x, 10, OUTLINE)

    # Wheels.
    for cx in (3, 12):
        for y in (10, 11):
            row(img, y, cx - 1, cx + 2, WHEEL)

    img.save(OUT)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
