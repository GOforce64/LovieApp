#!/usr/bin/env python3
"""Pixel-art icons as ASCII grids -> VectorDrawable + an SVG preview sheet.

Each icon is authored as a grid, which is the point: pixel art IS a grid, so
editing the picture means editing the picture. Grid size is inferred per icon,
so shapes that need more room (anything diagonal) can use a larger one without
forcing the others to follow.

  'X'  solid
  's'  shine — solid, but painted in the highlight colour
  'o'  hole — inside the silhouette, cut out of the filled variant. NOT drawn on
       the outline variant: the resting tile is deliberately blank, so the bubble
       shows no face until it starts filling.
  '.'  empty

The outline variant is DERIVED (a cell touching an empty cell or the border),
never authored, for the same reason.
"""
import os, sys

# Three colours per icon. The border is a darker rose so the shape reads against
# a pale widget background; the shine is a near-white pink catching the light
# from the top right, the way a glossy object would.
BORDER = "#D1447E"
# NAMES ARE HISTORICAL. `FILL` is whatever the *filled (sent)* variant paints and
# `DEEP` is whatever *delivered* paints — the two swapped when the ladder's colours
# were swapped on hardware, and the values moved rather than the usages, because
# each name is read from two places and a partial rewiring is silent.
FILL   = "#D81B60"   # sent: the server has it, not yet her phone
SHINE  = "#FFD9E8"
IDLE   = "#C98BA8"
DEEP   = "#FF6FA5"   # delivered: it landed on her phone
GOLD   = "#FFC64B"   # seen: a ring outside the shape, she actually looked

def shade_of(fill, f=0.72):
    """The shade tone for a given state's fill.

    Derived rather than pinned to BORDER on purpose: BORDER is lighter than FILL
    in the sent state and darker than DEEP in the delivered one, so a fixed shade
    would flip from shadow to highlight halfway up the ladder. Deriving it from
    the fill keeps a shaded face shaded in every state.
    """
    r, g, b = (int(fill[i:i+2], 16) for i in (1, 3, 5))
    return "#%02X%02X%02X" % (int(r*f), int(g*f), int(b*f))

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

# The set the app ships. Sizes differ per icon on purpose: the phone needs room
# for an earpiece, a screen and a keypad, and the paw is a straight doubling of
# the 11-grid one rather than a redraw. vector_drawable() fits any grid into the
# same 22 units, so mixed sizes still line up optically.
#
# 'd' is the shade tone — a third value between the border and the fill, which is
# what lets the phone have a lit face and a shaded side. See shade_of().
ICONS_APP = {
"heart": """
................
...XXX....XXX...
..XssXX..XXXXX..
.XssXXXXXXXXXXX.
XXsXXXXXXXXXXXXX
XXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXX
.XXXXXXXXXXXXXX.
.XXXXXXXXXXXXXX.
..XXXXXXXXXXXX..
...XXXXXXXXXX...
....XXXXXXXX....
.....XXXXXX.....
......XXXX......
.......XX.......
""",
"bubble": """
..XXXXXXXXXXXX..
.XXXXXXXXXXXXXX.
XXXXXXXXXXXXXXXX
XXXXXoXXXXoXXXXX
XXXXXoXXXXoXXXXX
XXXXXoXXXXoXXXXX
XXXXXXXXXXXXXXXX
XXXXXoXXXXoXXXXX
XXXXXXooooXXXXXX
XXXXXXXXXXXXXXXX
.XXXXXXXXXXXXXX.
..XXXXXXXXXXXX..
...XXXXXX.......
..XXXX..........
.XXX............
XX..............
""",
"paw": """
......XXXX..XXXX......
......XXXX..XXXX......
XXXX..XXXX..XXXX..XXXX
XXXX..XXXX..XXXX..XXXX
XXXX..XXXX..XXXX..XXXX
XXXX..XXXX..XXXX..XXXX
XXXX..............XXXX
XXXX..............XXXX
......................
......................
....XXXXXXXXXXXXXX....
....XXXXXXXXXXXXXX....
..XXXXXXXXXXXXXXXXXX..
..XXXXXXXXXXXXXXXXXX..
XXXXXXXXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXXXXXXXX
..XXXXXXXXXXXXXXXXXX..
..XXXXXXXXXXXXXXXXXX..
....XXXXXXXXXXXXXX....
....XXXXXXXXXXXXXX....
""",
"call": """
....................
.....sssssssssss....
....XXXXXXXXXXdd....
....XXXddddXXXdd....
....XXXXXXXXXXdd....
....XssssssssXdd....
....XssssssssXdd....
....XssssssssXdd....
....XssssssssXdd....
....XssssssssXdd....
....XXXXXXXXXXdd....
....XddXddXddXdd....
....XXXXXXXXXXdd....
....XddXddXddXdd....
....XXXXXXXXXXdd....
....XddXddXddXdd....
....XXXXXXXXXXdd....
....XddXddXddXdd....
....XXXXXXXXXXdd....
....XXXXXXXXXX......
""",
}

def parse(g):
    rows = g.strip("\n").split("\n")
    n = len(rows)
    for r in rows:
        assert len(r) == n, f"grid must be square: {n} rows, row width {len(r)}: {r!r}"
    solid = [[c in "Xsd" for c in r] for r in rows]
    holes = [[c == "o" for c in r] for r in rows]
    shine = [[c == "s" for c in r] for r in rows]
    shade = [[c == "d" for c in r] for r in rows]
    sil   = [[c in "Xsdo" for c in r] for r in rows]
    return solid, sil, holes, shine, shade, n

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

def layers(solid, sil, holes, shine, shade, n):
    """Split into border / interior / shine.

    A shape only one pixel thick has no interior at all — every cell is a border
    cell — so it would render entirely in the dark border colour and look wrong
    beside the chunky shapes. When that happens the shape is painted as fill
    instead, with no border.
    """
    border = [[outline(sil, n)[y][x] and solid[y][x] for x in range(n)] for y in range(n)]
    interior = [[solid[y][x] and not border[y][x] and not shine[y][x] and not shade[y][x]
                 for x in range(n)] for y in range(n)]
    if not any(any(r) for r in interior):
        # an empty grid, not [] — every consumer indexes cells[y][x] by position
        return [[False]*n for _ in range(n)], solid, shine
    # A shade cell is painted as shade wherever it lands, rim included: the whole
    # point is a face that stays darker than the fill.
    border = [[border[y][x] and not shine[y][x] and not shade[y][x]
               for x in range(n)] for y in range(n)]
    return border, interior, shine

def path_of(cells, n):
    unit = 22.0 / n
    return "".join(
        f"M{x*unit:.4f},{y*unit:.4f}h{unit:.4f}v{unit:.4f}h-{unit:.4f}z"
        for y in range(n) for x in range(n) if cells[y][x])

def vector_drawable(paths, n, ty=1):
    """22 units of art inside a 24 viewport, whatever the grid size, so icons of
    different grids line up optically next to each other. `paths` is a list of
    (cells, colour) drawn back to front.

    `ty` exists for the notification icon alone. The widget set shares one offset
    so the four tiles agree with each other, which matters more there than any
    single icon being centred; a status-bar icon has no siblings to line up with
    and is cropped hard, so it gets to be centred on its own bounding box."""
    body = "\n        ".join(
        f'<path android:fillColor="#FF{colour.lstrip("#")}" '
        f'android:pathData="{path_of(cells, n)}" />'
        for cells, colour in paths if any(any(r) for r in cells))
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group android:translateX="1" android:translateY="{ty}">
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
    "bubble": "perimeter",
    "paw": "up",
    "call": "up",
}

def seen_layers(solid, sil, holes, shine, shade, n):
    """Seen: the border turns gold around a deepened fill.

    A shape only one pixel thick has no border layer to gild — layers() paints it
    as pure fill so the letters do not go dark — so for those the glyph itself
    turns gold. Without this, CALL's seen state would be indistinguishable from
    its delivered one.
    """
    b, i, sh = layers(solid, sil, holes, shine, shade, n)
    if not any(any(r) for r in b):
        return [(i, GOLD), (sh, SHINE)]
    return [(i, DEEP), (b, GOLD), (sh, SHINE), (shade, shade_of(DEEP))]

def tile_frame(n):
    """A gold frame around the tile edge — a halo around the whole widget rather
    than around the artwork, so shapes with gaps are not swallowed."""
    return [[y == 0 or x == 0 or y == n-1 or x == n-1 for x in range(n)]
            for y in range(n)]

def glow_ring(sil, n):
    """One cell outward from the silhouette — a ring that sits OUTSIDE the shape.

    A real glow needs blur, which RemoteViews cannot do. A hard pixel ring is the
    honest equivalent in this art style, and it reads at tile size where a soft
    gradient would just look like a smudge.
    """
    ring = [[False]*n for _ in range(n)]
    for y in range(n):
        for x in range(n):
            if sil[y][x]:
                continue
            for dy, dx in ((-1,0),(1,0),(0,-1),(0,1),(-1,-1),(-1,1),(1,-1),(1,1)):
                ny, nx = y+dy, x+dx
                if 0 <= ny < n and 0 <= nx < n and sil[ny][nx]:
                    ring[y][x] = True
                    break
    return ring

def half(solid, sil, holes, n, mode="up"):
    """A mid-fill frame: outlined all round, interior partly filled.

    RemoteViews cannot animate, but Android 12+ tweens a widget's content change,
    so an intermediate frame between outline and filled reads as the icon filling
    rather than as two unrelated pictures.
    """
    border = outline(sil, n)
    inner = [[solid[y][x] and not border[y][x] and not holes[y][x]
              for x in range(n)] for y in range(n)]

    if mode == "perimeter":
        return [(border, FILL)]

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
    if mode == "perimeter":
        # The outline lit up rather than partly filled: the rim switches from the
        # muted idle colour to the full fill colour, so the frame reads as the
        # shape waking up before it fills.
        return [(border, FILL)]
    return [(interior, FILL), (border, BORDER)]

def build(icons, outdir, title):
    os.makedirs(outdir, exist_ok=True)
    span, pad, labelh = 132, 28, 26
    w = pad + len(icons)*(span+pad)
    h = labelh + 3*(span+pad+labelh) + 10
    names = ["I love you", "Thinking of you", "Miss you", "Call me"]

    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
           f'viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="#141216"/>']
    for row, variant in enumerate(["filled (sent)", "delivered", "seen"]):
        oy = labelh + row*(span+pad+labelh)
        svg.append(f'<text x="{pad}" y="{oy-8}" fill="#8a8090" font-family="monospace" '
                   f'font-size="13">{title} · {variant}</text>')
        for col, (key, grid) in enumerate(icons.items()):
            solid, sil, holes, shine, shade, n = parse(grid)
            ox = pad + col*(span+pad)
            svg.append(f'<rect x="{ox-6}" y="{oy-6}" width="{span+12}" height="{span+12}" '
                       f'rx="10" fill="#1e1b21"/>')
            if variant.startswith("filled"):
                b, i, sh = layers(solid, sil, holes, shine, shade, n)
                for cells, colour in ((i, FILL), (b, BORDER), (sh, SHINE), (shade, shade_of(FILL))):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            elif variant.startswith("half"):
                for cells, colour in half(solid, sil, holes, n, FILL_MODE.get(key, "up")):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            elif variant == "delivered":
                b, i, sh = layers(solid, sil, holes, shine, shade, n)
                for cells, colour in ((i, DEEP), (b, BORDER), (sh, SHINE), (shade, shade_of(DEEP))):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            elif variant.startswith("seen"):
                for cells, colour in seen_layers(solid, sil, holes, shine, shade, n):
                    svg.append(svg_tile(cells, n, ox, oy, colour, span))
            else:
                svg.append(svg_tile(outline(sil, n), n, ox, oy, IDLE, span))
            if row == 2:
                svg.append(f'<text x="{ox+span/2}" y="{oy+span+18}" fill="#6f6878" '
                           f'font-family="sans-serif" font-size="11" '
                           f'text-anchor="middle">{names[col]}</text>')
    svg.append("</svg>")
    open(os.path.join(outdir, "preview.svg"), "w").write("".join(svg))

    for key, grid in icons.items():
        solid, sil, holes, shine, shade, n = parse(grid)
        b, i, sh = layers(solid, sil, holes, shine, shade, n)
        open(os.path.join(outdir, f"ic_{key}_filled.xml"), "w").write(
            vector_drawable([(i, FILL), (b, BORDER), (sh, SHINE), (shade, shade_of(FILL))], n))
        open(os.path.join(outdir, f"ic_{key}_outline.xml"), "w").write(
            vector_drawable([(outline(sil, n), IDLE)], n))
        open(os.path.join(outdir, f"ic_{key}_half.xml"), "w").write(
            vector_drawable(half(solid, sil, holes, n, FILL_MODE.get(key, "up")), n))
        b, i, sh = layers(solid, sil, holes, shine, shade, n)
        open(os.path.join(outdir, f"ic_{key}_delivered.xml"), "w").write(
            vector_drawable([(i, DEEP), (b, BORDER), (sh, SHINE), (shade, shade_of(DEEP))], n))
        open(os.path.join(outdir, f"ic_{key}_seen.xml"), "w").write(
            vector_drawable(seen_layers(solid, sil, holes, shine, shade, n), n))
    print(f"{title}: {len(icons)*2} drawables + preview.svg -> {outdir}")

KOTLIN_OUT = "app/src/main/java/com/lovebutton/app/widget/PixelGrids.kt"
NOTIFICATION_OUT = "app/src/main/res/drawable/ic_notification_heart.xml"


def emit_notification_icon(grid):
    """The status-bar icon: the heart as one flat silhouette.

    Android renders a notification's small icon from its ALPHA CHANNEL alone and
    discards every colour in the source, so shipping the widget's three-layer
    heart would not give a pink heart in the tray — it would give the union of
    those layers as one white blob, with the border and the shine invisible.
    Emitting the silhouette on purpose is exactly the shape the system would
    derive anyway, minus the pretence that the colours in the file mean
    anything. The pink is applied at post time with setColor(), which is the one
    channel Android leaves open for it.

    Generated from the same grid as the widget's heart rather than drawn by
    hand, for the reason the whole file exists: two hearts maintained separately
    are two hearts that eventually stop matching.
    """
    _, sil, _, _, _, n = parse(grid)
    # translateY=0, not the shared 1: this grid's top row is blank, so the heart's
    # real bounding box is 10 rows in an 11-row grid. At the shared offset that
    # box would sit hard against the bottom of the 24dp canvas, which the status
    # bar crops. White because only the alpha matters — a pink here would be a lie
    # about where the colour comes from.
    with open(NOTIFICATION_OUT, "w") as f:
        f.write(vector_drawable([(sil, "#FFFFFF")], n, ty=0))
    print(f"wrote {NOTIFICATION_OUT}")


def emit_kotlin(all_icons):
    """The grids as Kotlin, so the app can animate individual pixels.

    The app draws the focal heart on a Canvas rather than from the finished
    VectorDrawable, because a VectorDrawable cannot be lit one cell at a time.
    Emitting from the same run as the XML is what stops the two from drifting;
    hand-copying these grids into Kotlin would guarantee that they eventually did.
    """
    lines = [
        "package com.lovebutton.app.widget",
        "",
        "// GENERATED by scripts/pixel_icons.py — do not edit by hand.",
        "// Regenerate with: python3 scripts/pixel_icons.py",
        "object PixelGrids {",
        "    val GRIDS: Map<String, List<String>> = mapOf(",
    ]
    for name, art in all_icons.items():
        rows = [r for r in art.strip("\n").split("\n")]
        lines.append(f'        "{name}" to listOf(')
        for r in rows:
            lines.append(f'            "{r}",')
        lines.append("        ),")
    lines += ["    )", "}", ""]
    with open(KOTLIN_OUT, "w") as f:
        f.write("\n".join(lines))
    print(f"wrote {KOTLIN_OUT}")

if __name__ == "__main__":
    base = sys.argv[1]
    build(ICONS_11, os.path.join(base, "grid-11"), "11x11")
    build(ICONS_13, os.path.join(base, "grid-13"), "13x13")
    build(ICONS_APP, os.path.join(base, "grid-app"), "app set")
    emit_kotlin(ICONS_APP)   # the app ships this set
    # The tray heart follows the WIDGET's grid (11), not the app set: it is the
    # same picture her tile shows, at the same weight.
    emit_notification_icon(ICONS_11["heart"])
