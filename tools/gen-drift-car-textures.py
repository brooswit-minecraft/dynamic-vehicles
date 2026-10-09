#!/usr/bin/env python3
"""Reproducibly regenerate the drift_car textures (MINECRAFT-188).

Pixel-art only, no external assets: every pixel is drawn by this script, so
re-running it always produces byte-identical PNGs. Run from the repo root:

    python3 tools/gen-drift-car-textures.py

It (re)writes:
  - src/main/resources/assets/dynamicvehicles/textures/entity/drift_car_body.png
    (32x32, opaque): the livery CarRenderer's texturedBox helper maps onto
    every face of the DRIFT nose/tub boxes alike -- bright racing yellow with
    a black/white racing stripe and a simple numbered badge.
  - src/main/resources/assets/dynamicvehicles/textures/entity/drift_car_trim.png
    (16x16, opaque): a dark carbon-look trim used for the skirts, roof cap and
    spoiler stands/wing.
  - src/main/resources/assets/dynamicvehicles/textures/item/drift_car.png
    (16x16, RGBA): the item icon, a flattened side-view silhouette in the same
    livery, replacing the borrowed furnace_minecart icon.

Requires Pillow (``pip install pillow``); not a build-time dependency.
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/dynamicvehicles/textures"

YELLOW = (255, 204, 0, 255)
YELLOW_DARK = (219, 174, 0, 255)
BLACK = (18, 18, 20, 255)
WHITE = (240, 240, 240, 255)
TRIM_DARK = (32, 32, 36, 255)
TRIM_LIGHT = (46, 46, 52, 255)
GLASS = (60, 70, 80, 255)
CLEAR = (0, 0, 0, 0)


def gen_body(size=32):
    img = Image.new("RGBA", (size, size), YELLOW)
    px = img.load()
    # Racing stripe band across the middle, bordered by thin white pinstripes.
    for y in range(12, 20):
        for x in range(size):
            px[x, y] = BLACK
    for y in (11, 20):
        for x in range(size):
            px[x, y] = WHITE
    # A few darker panel lines to break up the flat fill (subtle, not another band).
    for x in range(size):
        px[x, 4] = YELLOW_DARK
        px[x, 27] = YELLOW_DARK
    # Numbered badge (blocky "7") in the lower-right corner, on the yellow field.
    badge_x, badge_y = size - 9, size - 9
    for x in range(badge_x, badge_x + 7):
        px[x, badge_y] = BLACK
    for y in range(badge_y, badge_y + 7):
        px[badge_x + 6, y] = BLACK
        if y >= badge_y + 3:
            px[badge_x + 4, y] = BLACK
    return img


def gen_trim(size=16):
    img = Image.new("RGBA", (size, size), TRIM_DARK)
    px = img.load()
    # Faint pinstripe weave so the trim doesn't read as a single flat color.
    for y in range(size):
        for x in range(size):
            if (x + y) % 4 == 0:
                px[x, y] = TRIM_LIGHT
    return img


def gen_item_icon(size=16):
    img = Image.new("RGBA", (size, size), CLEAR)
    px = img.load()

    def fill(x0, y0, x1, y1, color):
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[x, y] = color

    # Low main body.
    fill(1, 10, 15, 13, YELLOW)
    # Tapered nose (front, right) and a small rear deck (left).
    fill(13, 10, 15, 11, YELLOW_DARK)
    fill(1, 9, 3, 10, YELLOW_DARK)
    # Racing stripe.
    fill(1, 11, 15, 12, BLACK)
    # Cabin / windows, set back toward the rear.
    fill(4, 6, 11, 10, YELLOW)
    fill(5, 7, 10, 10, GLASS)
    # Roof cap.
    fill(5, 6, 10, 7, TRIM_DARK)
    # Rear spoiler on a small stand.
    fill(2, 8, 3, 10, TRIM_DARK)
    fill(1, 7, 4, 8, TRIM_DARK)
    # Wheels.
    fill(2, 13, 5, 15, BLACK)
    fill(10, 13, 13, 15, BLACK)
    return img


def main():
    entity_dir = ASSETS / "entity"
    item_dir = ASSETS / "item"
    entity_dir.mkdir(parents=True, exist_ok=True)
    item_dir.mkdir(parents=True, exist_ok=True)

    gen_body().save(entity_dir / "drift_car_body.png")
    gen_trim().save(entity_dir / "drift_car_trim.png")
    gen_item_icon().save(item_dir / "drift_car.png")
    print("wrote drift_car_body.png, drift_car_trim.png, item/drift_car.png")


if __name__ == "__main__":
    main()
