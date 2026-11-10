"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio) and the 3D colour pairs (CIE L*)."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
def L(h): y = Y(h); return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16
GLASS = "#B4B3B1"  # darkest measured window glass (royal-ur / factory measure)
pairs = [
    ("正文 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("状态卡 Ink / TagFace", "#1B1F23", "#FBF6EC", 4.5), ("卡内次要字 / TagFace", "#3A3F44", "#FBF6EC", 4.5),
    ("主按钮字 白 / Primary", "#FFFFFF", "#7A2E22", 4.5), ("退出按钮字 白 / Error", "#FFFFFF", "#B3261E", 4.5),
    ("新纪录 白 / Primary", "#FFFFFF", "#7A2E22", 4.5), ("规矩页序号 白 / Primary", "#FFFFFF", "#7A2E22", 4.5),
    ("步数小牌 墨 / 牌面", "#2A1E14", "#F6ECD8", 4.5), ("牌面 墨字 / 枫木", "#2A1E14", "#E6C9A0", 4.5),
    ("牌面 墨字 / 素木", "#2A1E14", "#EFDCC0", 4.5), ("曹操 金字 / 红漆", "#F3D27A", "#C24A35", 3.0),
    ("规矩图 金字 / 红漆", "#F3D27A", "#C24A35", 3.0), ("规矩图 墨字 / 枫木", "#1B1F23", "#E6C9A0", 4.5),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
print()
lp = [("枫木牌 vs 胡桃盒底", "#E6C9A0", "#3E2A1C", 25), ("素木卒 vs 胡桃盒底", "#EFDCC0", "#3E2A1C", 25),
      ("红漆曹操 vs 胡桃盒底", "#C24A35", "#3E2A1C", 25), ("红漆曹操 vs 枫木牌", "#C24A35", "#E6C9A0", 25),
      ("铜门槛 vs 胡桃盒壁", "#D9A55B", "#2A1C13", 25), ("胡桃盒 vs 木楔", "#3E2A1C", "#302218", 0),
      ("落点标记 vs 盒底（要求只深半档）", "#33231A", "#3E2A1C", 0)]
print("| 3D 对比 | A | B | ΔL* | 门槛 |\n|---|---|---|---|---|")
for n, a, b, t in lp:
    print(f"| {n} | {a} | {b} | {abs(L(a) - L(b)):.1f} | {'≥ %d' % t if t else '—'} |")
