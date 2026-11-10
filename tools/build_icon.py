"""Klotski Box icon (design-package appIcon, background moved from walnut to maple: walnut is theremin's colour):
a maple #E6C9A0 disc, the walnut #4A3222 box seen from above with the gap in its near wall and a copper #D9A55B sill,
and the big red-lacquer #9E2B25 tile with a gold #F3D27A inlay line standing in front of the gap. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
MAPLE = (0xE6, 0xC9, 0xA0)
WALNUT = (0x4A, 0x32, 0x22)
COPPER = (0xD9, 0xA5, 0x5B)
RED = (0x9E, 0x2B, 0x25)
GOLD = (0xF3, 0xD2, 0x7A)


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# box (outer 26..82 x 22..90), walls 6 wide, the near wall open from 40 to 68
WALLS = [rect(26, 22, 82, 28), rect(26, 28, 32, 90), rect(76, 28, 82, 90), rect(32, 84, 40, 90), rect(68, 84, 76, 90)]
SILL = rect(40, 86, 68, 89)
TILE = rect(40.5, 55, 67.5, 82)
INLAY_OUT, INLAY_IN = rect(43, 57.5, 65, 79.5), rect(44.6, 59.1, 63.4, 77.9)
SMALL = []


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=MAPLE + (255,)))


def layer_box():
    def d(dr, k):
        for w in WALLS + SMALL:
            dr.polygon(sc(w, k), fill=WALNUT + (255,))
        dr.polygon(sc(SILL, k), fill=COPPER + (255,))
    return big(d)


def layer_tile():
    def d(dr, k):
        dr.polygon(sc(TILE, k), fill=RED + (255,))
        dr.polygon(sc(INLAY_OUT, k), fill=GOLD + (255,))
        dr.polygon(sc(INLAY_IN, k), fill=RED + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Klotski Box launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WALNUT), "".join(path(w) for w in WALLS + SMALL)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(COPPER), path(SILL)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(RED), path(TILE)),
        '    <path android:fillColor="%s" android:fillType="evenOdd" android:pathData="%s" />' % (hexc(GOLD), path(INLAY_OUT) + path(list(reversed(INLAY_IN)))),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_box(), layer_tile()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
