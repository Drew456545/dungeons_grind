#!/usr/bin/env python3
"""Synthetic EnchantedMC map captchas for training the recogniser (0.9.32).

What the real captures show (tools/captcha-fixtures, 128 px map grid): background
(8, 11, 19); 3-4 large characters in a bold sans (DejaVu/Arial family, the Java
default) drawn one colour each from a saturated palette, so big they fill the map,
sheared/rotated per character and often overlapping; 100-200 confetti specks of
1-3 px in random colours, some over the letters; everything quantised to the
Minecraft map palette (hard-edged pixels). This generator reproduces that with
random parameters wide enough to cover the real thing, and is the only training
data the recogniser sees.

    python tools/captcha_synth.py --sheet sheet.png      # 24 samples with labels, for eyeballing
    python tools/captcha_synth.py --dump out/ --n 200    # PNGs named <label>-<i>.png
"""
import argparse, colorsys, os, random, string, sys

from PIL import Image, ImageDraw, ImageFont

CHARS = string.ascii_letters + string.digits
BG = (8, 11, 19)
FONT_DIRS = [
    "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/truetype/liberation",
    "C:/Windows/Fonts", os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts"),
]
FONT_NAMES = [
    "DejaVuSans-Bold.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans.ttf",
    "LiberationSans-Bold.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Regular.ttf",
    "arialbd.ttf", "arialbd.ttf", "arial.ttf", "verdanab.ttf", "tahomabd.ttf",
]


def find_fonts():
    out = []
    for d in FONT_DIRS:
        for n in FONT_NAMES:
            p = os.path.join(d, n)
            if os.path.exists(p):
                out.append(p)
    if not out:
        sys.exit("no fonts found; install fonts-dejavu or put TTFs under tools/fonts/")
    return out


_font_cache = {}


def font(path, size):
    k = (path, size)
    if k not in _font_cache:
        _font_cache[k] = ImageFont.truetype(path, size)
    return _font_cache[k]


def rand_colour(rng, v_lo=0.55, v_hi=0.9):
    h = rng.random()
    s = rng.uniform(0.45, 0.95)
    v = rng.uniform(v_lo, v_hi)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return (int(r * 255), int(g * 255), int(b * 255))


def quantise(img, levels=5):
    """Minecraft map colours are coarse: snap each channel to a few levels so edges are hard."""
    step = 255 // levels
    return img.point(lambda p: min(255, (p // step) * step + step // 2))


def draw_char(rng, ch, fpath, size, colour):
    """One character on its own transparent tile, sheared and rotated."""
    f = font(fpath, size)
    w, h = int(size * 1.6), int(size * 1.6)
    tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    # Heavy strokes: the real glyphs are bold at a large size.
    d.text((w * 0.25, h * 0.1), ch, font=f, fill=colour + (255,), stroke_width=rng.choice([0, 1, 1, 2]), stroke_fill=colour + (255,))
    shear = rng.uniform(-0.4, 0.4)
    tile = tile.transform(tile.size, Image.AFFINE, (1, shear, -shear * h / 2, 0, 1, 0), resample=Image.NEAREST)
    tile = tile.rotate(rng.uniform(-10, 10), resample=Image.NEAREST, expand=False)
    return tile


def make(rng, fonts, n_chars=None, size=128):
    n = n_chars or rng.choices([3, 4, 5], weights=[25, 65, 10])[0]
    label = "".join(rng.choice(CHARS) for _ in range(n))
    img = Image.new("RGB", (size, size), BG)
    fpath = rng.choice(fonts)
    # Big: the string spans the map. Per-character size jitter and vertical wobble.
    # Real captures: glyphs 45-90 % of the map height, the string spanning nearly the whole width.
    base = rng.uniform(size * 0.42, size * 0.7) * 4.0 / n
    x = rng.uniform(-4, 6)
    for ch in label:
        fs = int(base * rng.uniform(0.85, 1.2))
        tile = draw_char(rng, ch, fpath, fs, rand_colour(rng))
        bbox = tile.getbbox()
        if bbox is None:
            continue
        glyph = tile.crop(bbox)
        y = int(size / 2 - glyph.height / 2 + rng.uniform(-size * 0.12, size * 0.12))
        img.paste(glyph, (int(x), y), glyph)
        x += glyph.width + rng.uniform(-glyph.width * 0.45, size * 0.03)
    # Confetti: small specks in random colours, some over the letters.
    d = ImageDraw.Draw(img)
    for _ in range(rng.randint(60, 220)):
        cx, cy = rng.randrange(size), rng.randrange(size)
        s = rng.choices([1, 2, 3], weights=[55, 35, 10])[0]
        d.rectangle([cx, cy, cx + s - 1, cy + s - 1], fill=rand_colour(rng, 0.4, 1.0))
    if rng.random() < 0.25:
        # a few captures are slightly blurred by the map renderer
        img = img.resize((size * 2, size * 2), Image.BILINEAR).resize((size, size), Image.BOX)
    img = quantise(img, rng.choice([4, 5, 6, 8]))
    return img, label


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", help="write a labelled contact sheet")
    ap.add_argument("--dump", help="directory to write PNGs into")
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--seed", type=int, default=None)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    fonts = find_fonts()
    if a.sheet:
        cols = 6
        rows = (a.n + cols - 1) // cols
        sheet = Image.new("RGB", (cols * 132, rows * 146), (0, 0, 0))
        d = ImageDraw.Draw(sheet)
        for i in range(a.n):
            img, label = make(rng, fonts)
            sheet.paste(img, ((i % cols) * 132 + 2, (i // cols) * 146 + 16))
            d.text(((i % cols) * 132 + 4, (i // cols) * 146 + 2), label, fill=(255, 255, 255))
        sheet.save(a.sheet)
        print("wrote", a.sheet, "fonts:", [os.path.basename(f) for f in fonts])
    if a.dump:
        os.makedirs(a.dump, exist_ok=True)
        for i in range(a.n):
            img, label = make(rng, fonts)
            img.save(os.path.join(a.dump, f"{label}-{i}.png"))
        print("wrote", a.n, "to", a.dump)


if __name__ == "__main__":
    main()
