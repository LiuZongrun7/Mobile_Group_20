#!/usr/bin/env python3
"""Flag ink that spills out of the boxes a figure is supposed to keep it in.

Why this exists: the figures are hand-placed TikZ, so a caption that is two
words too long silently runs over a card border or past the phone frame. It
looks fine at 30% zoom and terrible on a projector, and nothing in the LaTeX
log mentions it -- `Overfull` only covers horizontal boxes in the text flow,
not nodes positioned at explicit coordinates.

Usage: check_fig.py <figure> <origin_px> <px_per_cm> <y_top_cm> <box>...
Each box is `name:x0,y0,x1,y1` in TikZ centimetres. For every box we scan a
thin band just outside all four borders; any ink there is a spill.
"""
import sys

from PIL import Image

DARK = 690          # sum of RGB below this counts as ink


SIZE = (0, 0)


def ink(px, x, y):
    if not (0 <= x < SIZE[0] and 0 <= y < SIZE[1]):
        return False
    r, g, b = px[x, y][:3]
    return r + g + b < DARK


def main():
    name, ox, sc, ytop = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
    im = Image.open(name + '-1.png').convert('RGB')
    global SIZE
    SIZE = im.size
    px = im.load()

    def to_px(x, y):
        return int(ox + x * sc), int((ytop - y) * sc)

    bad = 0
    for spec in sys.argv[5:]:
        label, rect = spec.split(':')
        x0, y0, x1, y1 = (float(v) for v in rect.split(','))
        # Start 6 px out: a border stroke is centred on the nominal rectangle, so
        # the line itself lies ~2 px (0.7 pt) or ~4 px (1.2 pt) beyond it. Closer
        # bands would just report the box's own outline.
        for edge, band in (
            ('right',  [(x1 + d / sc, y) for d in (6, 7, 8, 9) for y in frac(y0, y1)]),
            ('left',   [(x0 - d / sc, y) for d in (6, 7, 8, 9) for y in frac(y0, y1)]),
            ('top',    [(x, y1 + d / sc) for d in (6, 7, 8, 9) for x in frac(x0, x1)]),
            ('bottom', [(x, y0 - d / sc) for d in (6, 7, 8, 9) for x in frac(x0, x1)]),
        ):
            hits = [(x, y) for x, y in band if ink(px, *to_px(x, y))]
            if hits:
                bad += 1
                worst = max(hits, key=lambda p: abs(p[0] - (x0 + x1) / 2))
                print(f'  SPILL {label} {edge}: {len(hits)} px, worst at '
                      f'x={worst[0]:.2f} y={worst[1]:.2f} cm')
    print(('FAIL ' if bad else 'ok   ') + name + f'  ({bad} spills)')
    return 1 if bad else 0


def frac(a, b, n=60):
    return [a + (b - a) * i / n for i in range(1, n)]


if __name__ == '__main__':
    sys.exit(main())
