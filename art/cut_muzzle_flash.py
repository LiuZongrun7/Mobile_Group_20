#!/usr/bin/env python3
"""把"开火"那三张图里的炮口火焰抠出来，做成**叠在塔上**的贴图。

用法（要 Pillow，和 `cut_tower.py` 同一个环境）：

    /tmp/deckvenv/bin/python art/cut_muzzle_flash.py

读 `art/source/tower_l{1,2,3}_fire_raw.png`，写出
`app/src/main/res/drawable-nodpi/tower_flash_l{1,2,3}.png`。

## 为什么是"叠一层"，不是"换一张整图"

开火那三张图是**重画过的**，不是"素图 + 一团火"：塔身在两张图里的大小、
位置都不一样（素图里底座踩地 879px，开火图里只有 697px），直接做差分会把
"两个塔身对不齐"的边缘全算成火焰，糊出一圈鬼影。所以这里不差分，改成：

1. 按**颜色**把火焰挑出来——火焰是暖色（R ≥ B）**而且够亮**（G ≥ 60），
   塔身是冷色（B > R 一大截）。挑完取**最大的那一团**，顺带就把炮口制退器上
   那圈被烤红的辉光一起收进来了（那也是开火的一部分，本来就该亮）。
   G 那道门槛是必须的：三级塔身满是红色发光条，只按"暖色"挑，那些条会和
   制退器连成**一整条链**，"最大的团"取到的是火焰 + 炮管上那条亮条，
   叠上去等于把炮管重画一遍，位置差半像素就是重影。见 `WARM_MIN_G`。
2. 用**脚 = 2 格**把开火图缩到和素图**同一个比例**，再用**脚的中心**对齐——
   和 `cut_tower.py` 共用 `ct.placement()` 那一份量法，不是各量各的。
   （原来量的是"底座踩地那 2px"、对齐用的是炮尾；2026-09-27 塔改成按脚定标
   之后这里跟着换，理由见 `cut_tower.py` 开头。）
3. 只把火焰那块切出来，输出成一张**和塔贴图同高**的图，横向按"炮口在哪儿"
   定位。运行时它盖在塔贴图上，见 `BattlefieldView.drawMuzzleFlashes`。

## 输出尺寸

`(FLASH_BACK + FLASH_OVER) × 2` 格，即 `(1.13 + 1.17) × 2` = `589 × 512`。
坐标是**塔贴图画布的坐标**：叠图的右边缘对着画布 x = `FLASH_OVER` 格，
左边缘对着 x = `-FLASH_BACK` 格。**炮口不在 x = 0**——按脚定标之后，
炮口落在画布 x ≈ 0.37 格（三级 0.36~0.40），所以这一对数字是**围着炮口
各留 1.5 / 0.8 格**、再整体右移 0.375 格来的。
上下和塔贴图完全一样，所以运行时两者的 top/bottom 是同一个数。

`FLASH_BACK`/`FLASH_OVER` 只管"窗口开多大"，火焰具体落在窗口里的哪儿是
**量出来的**。脚本跑完会把量到的落点打出来，贴着边就说明窗口开小了。
"""

import collections
import pathlib
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import cut_tower as ct
from cut_tower import CANVAS_H, CELL, FOOTPRINT_CENTRE

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

PLAIN = {
    1: "tower_l1_raw.png",
    2: "tower_l2_raw.png",
    3: "tower_l3_raw.png",
}
FIRING = {
    1: "tower_l1_fire_raw.png",
    2: "tower_l2_fire_raw.png",
    3: "tower_l3_fire_raw.png",
}

# 叠图窗口，**都是塔贴图画布的坐标**（不是"相对炮口"）。
# 口径仍是"往炮口左边伸出 1.5 格、往炮管上压回来 0.8 格"，
# 但炮口在画布 x ≈ 0.375 格（见 cut_tower.py 的定标），所以整体右移了这么多：
#   -1.5 + 0.375 = -1.125 → 取 -1.13
#   +0.8 + 0.375 = +1.175 → 取 +1.17   （和正好还是 2.30 格，FLASH_W 不变）
# 三级量出来的炮口是 0.359 / 0.371 / 0.395 格，取中间那档，两边各差 0.03 格以内。
# 窗口**只管开多大**；火在窗口里落在哪儿由下面的 CORE_LEAD_CELLS 定位。
# 改这里要同步改 BuildingSprites 里的 FLASH_WINDOWS。
FLASH_BACK_CELLS = 1.13
FLASH_OVER_CELLS = 1.17
FLASH_W = round((FLASH_BACK_CELLS + FLASH_OVER_CELLS) * CELL)
FLASH_H = CANVAS_H

# 窗口右边缘往里收的羽化宽度（格）——最后这 0.3 格把不透明度压到 0，
# 免得窗口那条切边在炮管上留一道硬印子。见 cut_one 里那段"为什么右边要收边"。
FLASH_FADE_CELLS = 0.3

# 火焰判据里的 g 门槛。**没有它三级会连同炮管一起抠下来**：
# 火焰和烤红的制退器是暖色也够亮（g 上百），而塔身那些红色发光条的 g 只有
# 几十；只按"暖色"（`r >= b`）挑，三级塔身上那一串红条会和制退器连成一条链，
# "最大的团"取到的是 815px 宽的一整条（火焰 + 炮管上的发光条），
# 抠出来的叠图上就多画了一道炮管——那道亮条素图上本来就有，位置还差半像素，
# 看着就是重影。加上 g >= 60 正好把链切断，留下的就是火焰加制退器那一圈烤红。
WARM_MIN_G = 60


def warm_mask(image: Image.Image) -> np.ndarray:
    """火焰（含被烤红的制退器）：暖色、够亮、不透明的像素；炮管上的红条不算。"""
    a = np.asarray(image).astype(np.int16)
    r, g, b, alpha = a[:, :, 0], a[:, :, 1], a[:, :, 2], a[:, :, 3]
    return (alpha > 60) & (r >= b) & (g >= WARM_MIN_G)


def largest_blob(mask: np.ndarray) -> np.ndarray:
    """最大的一团连通块（四邻域）。三级塔身上的红色发光条是散块，选不上。"""
    h, w = mask.shape
    seen = np.zeros((h, w), dtype=bool)
    best: list[tuple[int, int]] = []
    ys, xs = np.where(mask)
    for y0, x0 in zip(ys.tolist(), xs.tolist()):
        if seen[y0, x0]:
            continue
        seen[y0, x0] = True
        queue = collections.deque([(y0, x0)])
        blob = []
        while queue:
            y, x = queue.popleft()
            blob.append((y, x))
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    queue.append((ny, nx))
        if len(blob) > len(best):
            best = blob

    out = np.zeros((h, w), dtype=bool)
    for y, x in best:
        out[y, x] = True
    return out


def cut_one(level: int) -> tuple[Image.Image, dict]:
    fire = ct.load_keyed(FIRING[level])
    rgba = np.asarray(fire).astype(np.int16)
    alpha = rgba[:, :, 3]

    blob = largest_blob(warm_mask(fire))
    body = (alpha > 60) & ~blob
    if not body.any():
        raise SystemExit(f"l{level}: 挑完火焰之后塔身空了，颜色判据该调了")
    # **炮口不在这张图上量。** 火是贴着炮口长出来的，两张糊在一起，
    # 想从开火图里认出"炮口在哪一列"只能靠猜（试过按 alpha 取最左、
    # 也试过把火那团胀大几像素再扣掉，量出来差半格，三级之间还互相矛盾）。
    # 炮口去哪儿问塔贴图——它是同一个 `placement()` 量出来的，
    # 左边那列就是炮口，可靠得多。见下面 `verify_against_sprite()`。

    # 比例和锚点从**塔身（不含火焰）**量，而且走 cut_tower 那一份共享的量法——
    # 两张图只有共用同一条规矩，才会落在同一个比例、同一个位置上。
    place = ct.placement((alpha > 8) & ~blob)
    scale, anchor_x, y_bottom = place.scale, place.centre, place.bottom

    layer = np.zeros_like(rgba, dtype=np.uint8)
    layer[blob] = rgba[blob]
    layer = Image.fromarray(layer, "RGBA")

    # 画布坐标 ← 开火图坐标（和 cut_tower.placement 里那两行是同一对）：
    #   x = (px - anchor_x) * scale + FOOTPRINT_CENTRE   （脚的中心对着占地框中心）
    #   y = CANVAS_H - (y_bottom - py) * scale           （脚对着塔贴图的下沿）
    # 反解出叠图窗口该盖住的那块原图区域，再裁+缩——比逐像素搬要干净，也没有空洞
    px_left = anchor_x + (-FLASH_BACK_CELLS * CELL - FOOTPRINT_CENTRE) / scale
    px_right = anchor_x + (FLASH_OVER_CELLS * CELL - FOOTPRINT_CENTRE) / scale
    py_top = y_bottom - CANVAS_H / scale
    crop = layer.crop((round(px_left), round(py_top), round(px_right), round(y_bottom)))
    out = crop.resize((FLASH_W, FLASH_H), Image.LANCZOS)

    # **右边收边**：暖色那团会顺着炮管上本来就有的红色发光条一直往下爬
    # （三级最明显，量出来能爬到炮口右边 1.5 格）。爬到那儿已经不是"开火"了，
    # 是塔自己的涂装，所以窗口到 FLASH_OVER_CELLS 为止。
    # 但硬切会在炮管上留一条发光条被齐根截断的竖边，所以最后这 0.15 格把
    # 不透明度压到 0——制退器那圈烤红在 0.25 格以内，压不到它。
    a = np.asarray(out.getchannel("A")).astype(np.float32)
    x_cells = (np.arange(FLASH_W) - FLASH_BACK_CELLS * CELL) / CELL
    fade_from = FLASH_OVER_CELLS - FLASH_FADE_CELLS
    ramp = np.clip((FLASH_OVER_CELLS - x_cells) / FLASH_FADE_CELLS, 0.0, 1.0)
    ramp[x_cells <= fade_from] = 1.0
    out.putalpha(Image.fromarray((a * ramp[None, :]).astype(np.uint8), "L"))

    # 被窗口切掉的暖色像素：应该只有炮管上的发光条，不该有火焰本体
    fy, fx = np.where(blob)
    cx = (fx - anchor_x) * scale + FOOTPRINT_CENTRE
    cut = int((cx > FLASH_OVER_CELLS * CELL).sum())

    # 量一遍：火焰落在窗口的哪儿。左边/上边/下边贴边就是真被裁了
    opaque = np.asarray(out.getchannel("A")) > 8
    oy, ox = np.where(opaque)
    info = {
        "level": level,
        "scale": scale,
        "anchor_x": anchor_x,
        "blob": int(blob.sum()),
        "cut": cut,
        "canvas_x": ((ox.min() - FLASH_BACK_CELLS * CELL) / CELL,
                     (ox.max() - FLASH_BACK_CELLS * CELL) / CELL),
        "canvas_y": ((FLASH_H - 1 - oy.max()) / CELL, (FLASH_H - 1 - oy.min()) / CELL),
    }
    return out, info


def verify_against_sprite(level: int, info: dict) -> None:
    """拿塔贴图核对火焰落点：火焰左沿该在炮口左边一点点。

    塔贴图由 `cut_tower.py` 产出，和这张火焰图共用同一条 `placement()`，
    所以两者的画布坐标是同一个坐标系——**这是唯一可靠的"炮口在哪儿"**，
    也是这条流水线上最容易错的地方（窗口那对常数是手写的）。
    """
    sprite = OUT / f"tower_arrow_l{level}.png"
    if not sprite.exists():
        print(f"  （{sprite.name} 还没出，跳过炮口核对——先跑 art/cut_tower.py）")
        return

    box = ct.content_box(Image.open(sprite))
    muzzle = box[0] / CELL                       # 塔贴图最左边那列 = 炮口
    left = info["canvas_x"][0]
    reach = muzzle - left
    print(f"  炮口在画布 x {muzzle:+.2f} 格（塔贴图最左沿），"
          f"火焰左沿在 {left:+.2f} → 火往左吐出 {reach:.2f} 格"
          f"（窗口留了 {FLASH_BACK_CELLS:.2f} 格）")
    # 火焰伸多长是美术的事，伸不满窗口很正常；**顶到窗口边才是错**——
    # 那说明窗口开小了，火被齐根切掉。
    if left <= -FLASH_BACK_CELLS + 0.05:
        print("  ⚠️  火焰顶到窗口左边了——FLASH_BACK_CELLS 要加大")
    if reach < 0:
        print("  ⚠️  火焰整个落在炮口右边了——窗口的横向位置该重算")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for level in sorted(FIRING):
        img, info = cut_one(level)
        dst = OUT / f"tower_flash_l{level}.png"
        img.save(dst)
        left, right = info["canvas_x"]
        bottom, top = info["canvas_y"]
        print(f"{dst.name}: {img.width}×{img.height}"
              f"  锚点 {info['anchor_x']:.0f}px → 比例 {info['scale']:.3f}"
              f"  暖色 {info['blob']}px，其中 {info['cut']}px 落在窗口右边被切掉（炮管上的发光条）")
        print(f"  火焰落在画布 x {left:+.2f} .. {right:+.2f} 格、"
              f"y {bottom:.2f} .. {top:.2f} 格（从底边算）"
              f"；窗口 x {-FLASH_BACK_CELLS:+.2f} .. {FLASH_OVER_CELLS:+.2f}")
        verify_against_sprite(level, info)
        # 右边是故意切的，不算越界；上/下贴边才是真被裁了
        if bottom < 0.05 or top > FLASH_H / CELL - 0.05:
            print("  ⚠️  火焰顶到窗口上下边了——检查 y 对齐")


if __name__ == "__main__":
    main()
