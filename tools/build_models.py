"""Builds every model of the Klotski box volume as GLB, in Python + Pillow (no Blender on this machine).

  python3 tools/build_models.py [--test-f]      # --test-f: an "F↑" test glyph on every tile face (profile D10)

Writes app/src/main/assets/models/:
  box.glb          walnut box: floor 0.33 x 0.015 x 0.40 (top at y = 0 = inner floor), walls 2.5 cm thick / 2.2 cm high,
                   the near wall open 14.4 cm in the middle (the gap), a copper sill across the gap; origin = inner floor centre
  wedge_<deg>.glb  the dark wood wedge for each tilt stop (0 / 30 / 45 / 60 / 90): bottom on the volume floor, top under
                   the box; origin = floor point under the box's near-bottom edge (domain/Layout.kt tiltOrigin)
  tile_<who>.glb   the ten tiles' six designs: chamfered blocks 2.8 cm thick, cell 7 cm minus 3 mm clearance per side,
                   origin at the block centre; face text in Songti (曹操 red lacquer + gold, generals maple + ink + a seal,
                   soldiers plain light wood + ink)
Numbers match domain/Layout.kt (design-package gameSpec / spatialLayout). All materials OPAQUE (profile D24 / B12).
Texture convention: image up = the far edge (-Z), image right = +X, so the text reads upright from the user.
"""
import io
import json
import math
import os
import random
import struct
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEST_F = "--test-f" in sys.argv
FONT = "/System/Library/Fonts/Supplemental/Songti.ttc"

WALNUT = (0x3E, 0x2A, 0x1C)
WALNUT_DARK = (0x2A, 0x1C, 0x13)
WEDGE = (0x30, 0x22, 0x18)
MAPLE = (0xE6, 0xC9, 0xA0)
PLAIN = (0xEF, 0xDC, 0xC0)
LACQUER = (0xC2, 0x4A, 0x35)
GOLD = (0xF3, 0xD2, 0x7A)
INK = (0x2A, 0x1E, 0x14)
SEAL = (0xB0, 0x2A, 0x22)
COPPER = (0xD9, 0xA5, 0x5B)

PITCH, CLEAR, TILE_T, BEVEL = 0.07, 0.003, 0.028, 0.003
INNER_W, INNER_D, WALL, WALL_H, FLOOR_T = 0.28, 0.35, 0.025, 0.022, 0.015
OUTER_W, OUTER_D = INNER_W + 2 * WALL, INNER_D + 2 * WALL
GAP_X = 0.072
WEDGE_NEAR, WEDGE_W, WEDGE_MIN_D, VOL_D = 0.04, 0.36, 0.05, 0.62
TILTS = (0, 30, 45, 60, 90)
PX = 3600  # texture px per metre

def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "klotski-box tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-16s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))


def mat(name, color=None, rough=0.6, tex=None, metal=0.0):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    return {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}


def box(m, mi, x0, x1, y0, y1, z0, z1, uvf=None, sides=True, bottom=True):
    """Axis-aligned box faces; uvf(x, z) gives top uvs; sides take the uv of their top edge (streaks of the edge colour)."""
    uv = uvf or (lambda x, z: (0.5, 0.5))
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), (uv(x0, z1), uv(x1, z1), uv(x1, z0), uv(x0, z0)))
    if sides:
        m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), (uv(x0, z1), uv(x1, z1), uv(x1, z1), uv(x0, z1)))
        m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), (uv(x1, z0), uv(x0, z0), uv(x0, z0), uv(x1, z0)))
        m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), (uv(x1, z1), uv(x1, z0), uv(x1, z0), uv(x1, z1)))
        m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), (uv(x0, z0), uv(x0, z1), uv(x0, z1), uv(x0, z0)))
    if bottom:
        m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (uv(x0, z0), uv(x1, z0), uv(x1, z1), uv(x0, z1)))


def wood(base, w, h, seed, along_x=True, lines=None, contrast=1.0):
    """Wood grain: base colour, long soft streaks along the grain, a few darker rings, fine noise."""
    im = Image.new("RGB", (w, h), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    n = lines or max(30, (h if along_x else w) // 3)
    for _ in range(n):
        dark = rnd.random() < 0.7
        k = (rnd.uniform(-22, -6) if dark else rnd.uniform(4, 14)) * contrast
        col = tuple(max(0, min(255, int(c + k))) for c in base)
        width = rnd.randint(1, 4)
        if along_x:
            y = rnd.uniform(0, h); wob = rnd.uniform(-6, 6); ph = rnd.uniform(0, 6.28)
            pts = [(x, y + wob * math.sin(x / w * 3.1 + ph)) for x in range(0, w + 8, 8)]
        else:
            x = rnd.uniform(0, w); wob = rnd.uniform(-6, 6); ph = rnd.uniform(0, 6.28)
            pts = [(x + wob * math.sin(y / h * 3.1 + ph), y) for y in range(0, h + 8, 8)]
        dr.line(pts, fill=col, width=width)
    im = im.filter(ImageFilter.GaussianBlur(1.1))
    px = im.load()
    for _ in range(w * h // 6):
        x, y = rnd.randrange(w), rnd.randrange(h)
        d = rnd.randint(-7, 7)
        r, g, b = px[x, y]
        px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))
    return im


def font(size, bold=True):
    return ImageFont.truetype(FONT, size, index=1 if bold else 6)


def chamfer_block(m, mi, w, h, d, b, uv):
    """A w x h x d block centred on the origin with its four top edges chamfered by b; uv(x, z) maps the top view."""
    x0, x1, y0, y1, z0, z1 = -w / 2, w / 2, -h / 2, h / 2, -d / 2, d / 2
    yb = y1 - b
    s2 = 1 / math.sqrt(2)
    cl = lambda x, z: uv(max(x0 + 0.0008, min(x1 - 0.0008, x)), max(z0 + 0.0008, min(z1 - 0.0008, z)))
    # top
    m.quad(mi, (x0 + b, y1, z1 - b), (x1 - b, y1, z1 - b), (x1 - b, y1, z0 + b), (x0 + b, y1, z0 + b), (0, 1, 0),
           (uv(x0 + b, z1 - b), uv(x1 - b, z1 - b), uv(x1 - b, z0 + b), uv(x0 + b, z0 + b)))
    # chamfers
    m.quad(mi, (x0, yb, z1), (x1, yb, z1), (x1 - b, y1, z1 - b), (x0 + b, y1, z1 - b), (0, s2, s2), (cl(x0, z1), cl(x1, z1), cl(x1 - b, z1 - b), cl(x0 + b, z1 - b)))
    m.quad(mi, (x1, yb, z0), (x0, yb, z0), (x0 + b, y1, z0 + b), (x1 - b, y1, z0 + b), (0, s2, -s2), (cl(x1, z0), cl(x0, z0), cl(x0 + b, z0 + b), cl(x1 - b, z0 + b)))
    m.quad(mi, (x1, yb, z1), (x1, yb, z0), (x1 - b, y1, z0 + b), (x1 - b, y1, z1 - b), (s2, s2, 0), (cl(x1, z1), cl(x1, z0), cl(x1 - b, z0 + b), cl(x1 - b, z1 - b)))
    m.quad(mi, (x0, yb, z0), (x0, yb, z1), (x0 + b, y1, z1 - b), (x0 + b, y1, z0 + b), (-s2, s2, 0), (cl(x0, z0), cl(x0, z1), cl(x0 + b, z1 - b), cl(x0 + b, z0 + b)))
    # sides
    m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, yb, z1), (x0, yb, z1), (0, 0, 1), (cl(x0, z1), cl(x1, z1), cl(x1, z1), cl(x0, z1)))
    m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, yb, z0), (x1, yb, z0), (0, 0, -1), (cl(x1, z0), cl(x0, z0), cl(x0, z0), cl(x1, z0)))
    m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, yb, z0), (x1, yb, z1), (1, 0, 0), (cl(x1, z1), cl(x1, z0), cl(x1, z0), cl(x1, z1)))
    m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, yb, z1), (x0, yb, z0), (-1, 0, 0), (cl(x0, z0), cl(x0, z1), cl(x0, z1), cl(x0, z0)))
    # bottom
    m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (cl(x0, z0), cl(x1, z0), cl(x1, z1), cl(x0, z1)))


TILES = {
    # name: (cells w, cells h, face text, base, ink, seal?)
    "cao": (2, 2, "曹操", LACQUER, GOLD, False),
    "guan": (2, 1, "关羽", MAPLE, INK, True),
    "zhang": (1, 2, "张飞", MAPLE, INK, True),
    "zhao": (1, 2, "赵云", MAPLE, INK, True),
    "ma": (1, 2, "马超", MAPLE, INK, True),
    "huang": (1, 2, "黄忠", MAPLE, INK, True),
    "zu": (1, 1, "卒", PLAIN, INK, False),
}


def tile_face(name, cw, ch, text, base, ink, seal):
    w, d = cw * PITCH - 2 * CLEAR, ch * PITCH - 2 * CLEAR
    tw, th = int(w * PX), int(d * PX)
    im = wood(base, tw, th, seed=hash(name) & 0xffff, along_x=(cw >= ch), contrast=0.55 if base == LACQUER else 1.0)
    dr = ImageDraw.Draw(im)
    bev = int(BEVEL * PX)
    # a thin darker rim where the chamfer starts (reads as a carved edge)
    edge = tuple(max(0, c - 28) for c in base)
    dr.rectangle((bev - 3, bev - 3, tw - bev + 2, th - bev + 2), outline=edge, width=3)
    if base == LACQUER:
        dr.rectangle((bev + 10, bev + 10, tw - bev - 11, th - bev - 11), outline=GOLD, width=5)
    if TEST_F:
        f = font(int(min(tw, th) * 0.55))
        dr.text((tw / 2, th / 2), "F↑", font=f, fill=(0x10, 0x10, 0x10), anchor="mm")
        return im
    # text: two characters stacked on tall tiles, side by side on wide / big ones
    if len(text) == 1:
        f = font(int(min(tw, th) * 0.62))
        dr.text((tw / 2, th / 2), text, font=f, fill=ink, anchor="mm")
    elif ch > cw:
        f = font(int(tw * 0.60))
        dr.text((tw / 2, th * 0.30), text[0], font=f, fill=ink, anchor="mm")
        dr.text((tw / 2, th * 0.62), text[1], font=f, fill=ink, anchor="mm")
    elif cw > ch:
        f = font(int(th * 0.60))
        dr.text((tw * 0.30, th / 2), text[0], font=f, fill=ink, anchor="mm")
        dr.text((tw * 0.70, th / 2), text[1], font=f, fill=ink, anchor="mm")
    else:
        f = font(int(tw * 0.36))
        dr.text((tw * 0.30, th / 2), text[0], font=f, fill=ink, anchor="mm")
        dr.text((tw * 0.70, th / 2), text[1], font=f, fill=ink, anchor="mm")
    if seal:
        # a small vermilion seal (2 cm on the design; 1.4 cm here so it never touches the name)
        s = int(0.014 * PX)
        if ch > cw:
            x1, y1 = tw - bev - 10, th - bev - 10
        else:
            x1, y1 = tw - bev - 10, th - bev - 10
        x0, y0 = x1 - s, y1 - s
        dr.rectangle((x0, y0, x1, y1), fill=SEAL)
        dr.rectangle((x0 + 4, y0 + 4, x1 - 4, y1 - 4), outline=(0xF4, 0xE4, 0xD0), width=2)
        sf = font(int(s * 0.62))
        dr.text(((x0 + x1) / 2, (y0 + y1) / 2), "印", font=sf, fill=(0xF4, 0xE4, 0xD0), anchor="mm")
    return im


def build_tile(name):
    cw, ch, text, base, ink, seal = TILES[name]
    w, d = cw * PITCH - 2 * CLEAR, ch * PITCH - 2 * CLEAR
    m = Mesh()
    chamfer_block(m, 0, w, TILE_T, d, BEVEL, lambda x, z: (x / w + 0.5, z / d + 0.5))
    rough = 0.32 if base == LACQUER else 0.55
    m.write("tile_" + name, [mat("Tile_" + name, tex=0, rough=rough)], [tile_face(name, cw, ch, text, base, ink, seal)])


def build_box():
    m = Mesh()
    hw, hd = OUTER_W / 2, OUTER_D / 2
    uv = lambda x, z: ((x + hw) / OUTER_W, (z + hd) / OUTER_D)
    clamp = lambda x, z: uv(max(-hw + 0.001, min(hw - 0.001, x)), max(-hd + 0.001, min(hd - 0.001, z)))
    # floor slab (top at y = 0)
    box(m, 0, -hw, hw, -FLOOR_T, 0.0, -hd, hd, uvf=clamp)
    # walls (material 1: the same walnut, a separate texture so the wall tops are not the floor grain)
    gapz = INNER_D / 2
    walls = [
        (-hw, hw, -hd, -INNER_D / 2),
        (-hw, -INNER_W / 2, -INNER_D / 2, gapz), (INNER_W / 2, hw, -INNER_D / 2, gapz),
        (-hw, -GAP_X, gapz, hd), (GAP_X, hw, gapz, hd),
    ]
    for x0, x1, z0, z1 in walls:
        box(m, 1, x0, x1, 0.0, WALL_H, z0, z1, uvf=clamp, bottom=False)
    # copper sill across the gap, flush with the floor (a tile slides over it)
    box(m, 2, -GAP_X, GAP_X, -0.0035, 0.0006, gapz + 0.004, gapz + 0.022, uvf=lambda x, z: (0.5, 0.5), bottom=False)
    # floor texture: walnut grain along Z, faint cell grooves so the 4 x 5 grid reads in the empty cells
    fw, fd = int(OUTER_W * PX / 2), int(OUTER_D * PX / 2)
    floor = wood(WALNUT, fw, fd, seed=11, along_x=False, contrast=0.8)
    dr = ImageDraw.Draw(floor)
    k = PX / 2
    groove = tuple(max(0, c - 14) for c in WALNUT)
    for c in range(1, 4):
        x = (WALL + c * PITCH) * k
        dr.line((x, WALL * k, x, (WALL + INNER_D) * k), fill=groove, width=2)
    for r in range(1, 5):
        y = (WALL + r * PITCH) * k
        dr.line((WALL * k, y, (WALL + INNER_W) * k, y), fill=groove, width=2)
    wallt = wood(WALNUT_DARK, fw, fd, seed=12, along_x=True, contrast=0.9)
    m.write("box", [mat("BoxFloor", tex=0, rough=0.7), mat("BoxWall", tex=1, rough=0.62),
                    {"name": "CopperSill", "pbrMetallicRoughness": {"baseColorFactor": [srgb_lin(c) for c in COPPER] + [1.0], "metallicFactor": 0.85, "roughnessFactor": 0.32},
                     "emissiveFactor": [0.10, 0.06, 0.02], "alphaMode": "OPAQUE", "doubleSided": False}],
            [floor, wallt])


def build_wedge(deg):
    """Right trapezoid prism: near face at z = 0 (height WEDGE_NEAR), back to z = -depth; its top follows the box underside."""
    t = math.radians(deg)
    depth = max(OUTER_D * math.cos(t), WEDGE_MIN_D)
    far_h = WEDGE_NEAR + OUTER_D * math.sin(t)
    hw = WEDGE_W / 2
    m = Mesh()
    # the top runs from (z=0, y=near) along the box underside; at 90° the box stands up, the wedge is a back block
    top_far = (-depth, far_h if depth > WEDGE_MIN_D + 1e-6 or deg < 89 else far_h)
    a, b = (0.0, WEDGE_NEAR), top_far
    uv = lambda x, z: ((x + hw) / WEDGE_W, (-z) / max(depth, 0.001))
    # top slope
    n = (0.0, depth, -(far_h - WEDGE_NEAR)) if deg < 89 else (0.0, 1.0, 0.0)
    ln = math.sqrt(sum(c * c for c in n)); n = tuple(c / ln for c in n)
    if deg >= 89:
        # back block: flat top
        box(m, 0, -hw, hw, 0.0, far_h, -depth, 0.0, uvf=uv, bottom=False)
    else:
        m.quad(0, (-hw, a[1], a[0]), (hw, a[1], a[0]), (hw, b[1], b[0]), (-hw, b[1], b[0]), n, (uv(-hw, 0), uv(hw, 0), uv(hw, -depth), uv(-hw, -depth)))
        # near face
        m.quad(0, (-hw, 0.0, 0.0), (hw, 0.0, 0.0), (hw, a[1], 0.0), (-hw, a[1], 0.0), (0, 0, 1), (uv(-hw, 0), uv(hw, 0), uv(hw, 0), uv(-hw, 0)))
        # back face
        m.quad(0, (hw, 0.0, -depth), (-hw, 0.0, -depth), (-hw, b[1], -depth), (hw, b[1], -depth), (0, 0, -1), (uv(hw, -depth), uv(-hw, -depth), uv(-hw, -depth), uv(hw, -depth)))
        # sides (quads: bottom near, bottom far, top far, top near)
        m.quad(0, (hw, 0.0, 0.0), (hw, 0.0, -depth), (hw, b[1], -depth), (hw, a[1], 0.0), (1, 0, 0), (uv(hw, 0), uv(hw, -depth), uv(hw, -depth), uv(hw, 0)))
        m.quad(0, (-hw, 0.0, -depth), (-hw, 0.0, 0.0), (-hw, a[1], 0.0), (-hw, b[1], -depth), (-1, 0, 0), (uv(-hw, -depth), uv(-hw, 0), uv(-hw, 0), uv(-hw, -depth)))
    tex = wood(WEDGE, 512, 512, seed=20 + deg, along_x=False, contrast=0.8)
    m.write("wedge_%d" % deg, [mat("Wedge", tex=0, rough=0.72)], [tex])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_box()
    for d in TILTS:
        build_wedge(d)
    for name in TILES:
        build_tile(name)
