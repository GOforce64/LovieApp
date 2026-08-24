#!/usr/bin/env python3
"""Pixel-art icons as ASCII grids -> VectorDrawable + an SVG preview sheet.

Each icon is authored as a grid, which is the point: pixel art IS a grid, so
editing the picture means editing the picture. Grid size is inferred per icon,
so shapes that need more room (anything diagonal) can use a larger one without
forcing the others to follow.

  'X'  solid
  's'  shine — solid, but painted in the highlight colour
  'o'  hole — inside the silhouette, cut out of the filled variant and drawn
       as a mark on the outline variant, so the pair cannot drift apart
  '.'  empty

The outline variant is DERIVED (a cell touching an empty cell or the border),
never authored, for the same reason.
"""
import os, sys

# Three colours per icon. The border is a darker rose so the shape reads against
# a pale widget background; the shine is a near-white pink catching the light
# from the top right, the way a glossy object would.
BORDER = "#D1447E"
FILL   = "#FF6FA5"
SHINE  = "#FFD9E8"
IDLE   = "#C98BA8"   # the outline-only variant used for the Idle widget state

ICONS_11 = {
"heart": """
...........
..XX...XX..
.XssXXXXXX.
XXsXXXXXXXX
XXXXXXXXXXX
XXXXXXXXXXX
.XXXXXXXXX.
..XXXXXXX..
...XXXXX...
....XXX....
.....X.....
""",
"bubble": """
.XXXXXXXXX.
XXXXXXXXXXX
XXoXXXXXoXX
XXXXXXXXXXX
XXoXXXXXoXX
XXXoooooXXX
XXXXXXXXXXX
.XXXXXXXXX.
..XXXX.....
.XXX.......
XX.........
""",
# four 2-wide toes with 1-col gaps is exactly 11 columns — the full width,
# which is why an 11-grid paw cannot be tilted: there is nothing to tilt into
"paw": """
...XX.XX...
XX.XX.XX.XX
XX.XX.XX.XX
XX.......XX
...........
..XXXXXXX..
.XXXXXXXXX.
XXXXXXXXXXX
XXXXXXXXXXX
.XXXXXXXXX.
..XXXXXXX..
""",
# "CALL" on one line needs 4*3 + 3 gaps = 15 columns, so it stacks
"call": """
..XXX.XXX..
..X...X.X..
..X...XXX..
..X...X.X..
..XXX.X.X..
...........
..X...X....
..X...X....
..X...X....
..X...X....
..XXX.XXX..
""",
}

ICONS_13 = {
"heart": """
.............
..XXX...XXX..
.XXXXX.XXXXX.
XXXXXXXXXXXXX
XXXXXXXXXXXXX
XXXXXXXXXXXXX
XXXXXXXXXXXXX
.XXXXXXXXXXX.
..XXXXXXXXX..
...XXXXXXX...
....XXXXX....
.....XXX.....
......X......
""",
"bubble": """
.XXXXXXXXXXX.
XXXXXXXXXXXXX
XXXXXXXXXXXXX
XXoXXXXXXXoXX
XXoXXXXXXXoXX
XXXXXXXXXXXXX
XXoXXXXXXXoXX
XXXoooooooXXX
XXXXXXXXXXXXX
.XXXXXXXXXXX.
..XXXX.......
.XXX.........
XX...........
""",
# 13 wide leaves two spare columns, which is exactly what a tilt needs:
# toes arc up the left, pad sits down and to the right
"paw": """
......XX.....
......XX.....
..XX..XX..XX.
..XX......XX.
..XX......XX.
.............
XX...XXXXXXX.
XX..XXXXXXXXX
XX..XXXXXXXXX
....XXXXXXXXX
....XXXXXXXX.
.....XXXXXX..
.............
""",
"call": """
.............
...XXX.XXX...
...X...X.X...
...X...XXX...
...X...X.X...
...XXX.X.X...
.............
...X...X.....
...X...X.....
...X...X.....
...X...X.....
...XXX.XXX...
.............
""",
}

def parse(g):
    rows = g.strip("\n").split("\n")
    n = len(rows)
    for r in rows:
        assert len(r) == n, f"grid must be square: {n} rows, row width {len(r)}: {r!r}"
    solid = [[c in "Xs" for c in r] for r in rows]
    holes = [[c == "o" for c in r] for r in rows]
    shine = [[c == "s" for c in r] for r in rows]
    sil   = [[c in "Xso" for c in r] for r in rows]
    return solid, sil, holes, shine, n

def outline(cells, n):
    out = [[False]*n for _ in range(n)]
    for y in range(n):
        for x in range(n):
            if not cells[y][x]:
                continue
            for dy, dx in ((-1,0),(1,0),(0,-1),(0,1)):
                ny, nx = y+dy, x+dx
                if not (0 <= ny < n and 0 <= nx < n) or not cells[ny][nx]:
                    out[y][x] = True
                    break
    return out

def union(a, b, n):
    return [[a[y][x] or b[y][x] for x in range(n)] for y in range(n)]

def layers(solid, sil, holes, shine, n):
    """Split into border / interior / shine.

    A shape only one pixel thick has no interior at all — every cell is a border
    cell — so it would render entirely in the dark border colour and look wrong
    beside the chunky shapes. When that happens the shape is painted as fill
    instead, with no border.
    """
    border = [[outline(sil, n)[y][x] and solid[y][x] for x in range(n)] for y in range(n)]
    interior = [[solid[y][x] and not border[y][x] and not shine[y][x]
                 for x in range(n)] for y in range(n)]
    if not any(any(r) for r in interior):
        # an empty grid, not [] — every consumer indexes cells[y][x] by position
        return [[False]*n for _ in range(n)], solid, shine
    border = [[border[y][x] and not shine[y][x] for x in range(n)] for y in range(n)]
    return border, interior, shine

def path_of(cells, n):
    unit = 22.0 / n
    return "".join(
        f"M{x*unit:.4f},{y*unit:.4f}h{unit:.4f}v{unit:.4f}h-{unit:.4f}z"
        for y in range(n) for x in range(n) if cells[y][x])

def vector_drawable(paths, n):
    """22 units of art inside a 24 viewport, whatever the grid size, so icons of
    different grids line up optically next to each other. `paths` is a list of
    (cells, colour) drawn back to front."""
    body = "\n        ".join(
        f'<path android:fillColor="#FF{colour.lstrip("#")}" '
        f'android:pathData="{path_of(cells, n)}" />'
        for cells, colour in paths if any(any(r) for r in cells))
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group android:translateX="1" android:translateY="1">
        {body}
    </group>
</vector>
'''

def svg_tile(cells, n, ox, oy, colour, span):
    unit = span / n
    return "".join(
        f'<rect x="{ox+x*unit:.2f}" y="{oy+y*unit:.2f}" width="{unit:.2f}" '
        f'height="{unit:.2f}" fill="{colour}"/>'
        for y in range(n) for x in range(n) if cells[y][x])

# How each icon's mid-fill frame grows. The direction is per icon because what
# reads as "filling" depends on the shape: a heart blooms from its centre, a
# speech bubble closes in from its edge.
FILL_MODE = {
    "heart": "centre",
    "bubble": "inward",
    "paw": "up",
    "call": "up",
}

def half(solid, sil, holes, n, mode="up"):
    """A mid-fill frame: outlined all round, interior partly filled.

    RemoteViews cannot animate, but Android 12+ tweens a widget's content change,
    so an intermediate frame between outline and filled reads as the icon filling
    rather than as two unrelated pictures.
    """
    border = outline(sil, n)
    inner = [[solid[y][x] and not border[y][x] and not holes[y][x]
              for x in range(n)] for y in range(n)]

    cx = cy = (n - 1) / 2.0
    # Radius covering half the interior cells, so every mode fills a comparable
    # amount and the three frames read as one even sequence.
    cells = [(y, x) for y in range(n) for x in range(n) if inner[y][x]]
    target = max(1, len(cells) // 2)

    if mode == "centre":
        ranked = sorted(cells, key=lambda c: (c[0]-cy)**2 + (c[1]-cx)**2)
    elif mode == "inward":
        # distance to the nearest empty cell: the rim of the shape fills first
        def depth(c):
            y, x = c
            return min((abs(y-yy) + abs(x-xx))
                       for yy in range(n) for xx in range(n)
                       if not sil[yy][xx]) if any(
                           not sil[yy][xx] for yy in range(n) for xx in range(n)) else 0
        ranked = sorted(cells, key=depth)
    else:  # "up" — from the bottom
        ranked = sorted(cells, key=lambda c: -c[0])

    chosen = set(ranked[:target])
    interior = [[(y, x) in chosen for x in range(n)] for y in range(n)]
    return border, interior

def build(icons, outdir, title):
    os.makedirs(outdir, exist_ok=True)
    span, pad, labelh = 132, 28, 26
    w = pad + len(icons)*(span+pad)
    h = labelh + 3*(span+pad+labelh) + 10
    names = ["I love you", "Thinking of you", "Miss you", "Call me"]

    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
           f'viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="#141216"/>']
    for row, variant in enumerate(["outline (idle)", "half (sending)", "filled (sent)"]):
        oy = labelh + row*(span+pad+labelh)
        svg.append(f'<text x="{pad}" y="{oy-8}" fill="#8a8090" font-family="monospace" '
                   f'font-size="13">{title} · {variant}</text>')
        for col, (key, grid) in enumerate(icons.items()):
            solid, sil, holes, shine, n = parse(grid)
            ox = pad + col*(span+pad)
            svg.append(f'<rect x="{ox-6}" y="{oy-6}" width="{span+12}" height="{span+12}" '
                       f'rx="10" fill="#1e1b21"/>')
            if variant.startswith("filled"):
                b, i, sh = layers(solid, sil, holes, shine, n)
                for cells, colour in ((i, FILL), (b, BORDER), (sh, SHINE)):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            elif variant.startswith("half"):
                hb, hi = half(solid, sil, holes, n, FILL_MODE.get(key, "up"))
                for cells, colour in ((hi, FILL), (hb, BORDER)):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            else:
                svg.append(svg_tile(union(outline(sil, n), holes, n), n, ox, oy, IDLE, span))
            if row == 2:
                svg.append(f'<text x="{ox+span/2}" y="{oy+span+18}" fill="#6f6878" '
                           f'font-family="sans-serif" font-size="11" '
                           f'text-anchor="middle">{names[col]}</text>')
    svg.append("</svg>")
    open(os.path.join(outdir, "preview.svg"), "w").write("".join(svg))

    for key, grid in icons.items():
        solid, sil, holes, shine, n = parse(grid)
        b, i, sh = layers(solid, sil, holes, shine, n)
        open(os.path.join(outdir, f"ic_{key}_filled.xml"), "w").write(
            vector_drawable([(i, FILL), (b, BORDER), (sh, SHINE)], n))
        open(os.path.join(outdir, f"ic_{key}_outline.xml"), "w").write(
            vector_drawable([(union(outline(sil, n), holes, n), IDLE)], n))
        hb, hi = half(solid, sil, holes, n, FILL_MODE.get(key, "up"))
        open(os.path.join(outdir, f"ic_{key}_half.xml"), "w").write(
            vector_drawable([(hi, FILL), (hb, BORDER)], n))
    print(f"{title}: {len(icons)*2} drawables + preview.svg -> {outdir}")

if __name__ == "__main__":
    base = sys.argv[1]
    build(ICONS_11, os.path.join(base, "grid-11"), "11x11")
    build(ICONS_13, os.path.join(base, "grid-13"), "13x13")
