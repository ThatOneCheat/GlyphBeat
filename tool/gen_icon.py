"""Generate GlyphBeat launcher icons.

Design: dot-matrix equalizer on black. Three columns of rounded square
segments (Nothing dot-matrix language = "Glyph") arranged as EQ bars
(= "Beat"), center peak column in Nothing red. Rendered at high
resolution and downscaled with LANCZOS for crisp anti-aliasing.
"""
import os
from PIL import Image, ImageDraw

SS = 4                       # supersample factor
MASTER = 1024                # master icon size (px)
C = MASTER * SS              # working canvas size

BG = (8, 8, 8, 255)          # near-black background
WHITE = (240, 240, 240, 255)
RED = (255, 45, 45, 255)     # Nothing-style red accent

# Columns as EQ "beat" bars: (cell-count, color). Bottom-aligned.
COLUMNS = [
    (3, WHITE),
    (5, RED),
    (2, WHITE),
]

def rounded(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)

def build():
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # full-bleed rounded-square background (launcher masks crop it cleanly)
    rounded(d, (0, 0, C - 1, C - 1), radius=int(C * 0.22), fill=BG)

    # --- layout the dot-matrix EQ ---
    n_cols = len(COLUMNS)
    max_cells = max(n for n, _ in COLUMNS)

    cell = int(C * 0.118)        # square segment edge
    cgap = int(cell * 0.55)      # horizontal gap between columns
    vgap = int(cell * 0.40)      # vertical gap between segments
    crad = int(cell * 0.30)      # segment corner radius

    total_w = n_cols * cell + (n_cols - 1) * cgap
    tallest_h = max_cells * cell + (max_cells - 1) * vgap

    x0 = (C - total_w) // 2
    baseline = (C + tallest_h) // 2   # bottom edge all columns sit on

    for ci, (count, color) in enumerate(COLUMNS):
        cx = x0 + ci * (cell + cgap)
        for ri in range(count):
            # ri=0 is bottom segment, grow upward
            bottom = baseline - ri * (cell + vgap)
            top = bottom - cell
            rounded(d, (cx, top, cx + cell, bottom), radius=crad, fill=color)

    # downscale to master
    return img.resize((MASTER, MASTER), Image.LANCZOS)

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    master = build()

    asset_dir = os.path.join(root, "assets", "icon")
    os.makedirs(asset_dir, exist_ok=True)
    master.save(os.path.join(asset_dir, "glyphbeat_icon.png"))

    densities = {
        "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
    }
    res = os.path.join(root, "android", "app", "src", "main", "res")
    for name, size in densities.items():
        out = os.path.join(res, f"mipmap-{name}", "ic_launcher.png")
        master.resize((size, size), Image.LANCZOS).save(out)
        print(f"wrote {out} ({size}x{size})")

    # a 512 marketing/preview copy
    master.resize((512, 512), Image.LANCZOS).save(
        os.path.join(asset_dir, "glyphbeat_icon_512.png"))
    print("done")

if __name__ == "__main__":
    main()
