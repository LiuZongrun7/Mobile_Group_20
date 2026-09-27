#!/usr/bin/env python3
"""把数据中心（核心）的三个级别抠成游戏能用的贴图。

用法（要 Pillow）：

    /tmp/deckvenv/bin/python art/cut_core.py

读 `art/source/core_l{1,2,3}_raw.png`，写出
`app/src/main/res/drawable-nodpi/core_l{1,2,3}.png`。

原图是"品红底 + 一座蓝灰的机房"。抠底用 `cut_tower.py` 里那一套
（`keyed()`，阈值只有一份），这里做三件事：

1. **裁到内容边界**。
2. **等比缩到 3 格宽**（768px）——**底座最宽那一行 = 占地宽**。
3. **贴底、水平居中**放进画布。

<h2>为什么没有镜像这一道</h2>
塔要镜像是因为炮口朝右、而塔一律朝左打。**核心没有朝向**：
`BuildingType.CORE` 的左右探出都是 0，它是往<b>上</b>长的一栋楼。
所以走 `keyed()` 而不是 `load_keyed()`。

<h2>尺寸：画布 3 × 4 格，内容 3 × 2.9~3.3 格</h2>
画布是 `(cols + 左探 + 右探) × (rows + 上探)` 格 × 256px。核心是 3×3、上探 1.0、
左右不探，所以画布 = `3 × 4` 格 = **768 × 1024**（`docs/ART.md` §2.1）。

**定标用的是"底座最宽那一行"，不是"踩地那一行"。** 和塔不一样：塔的底座
直接坐在地上，量踩地那一行就是占地宽；核心这三张图**正中间都有一道台阶
伸到最下面**（画面上那道通向大门的楼梯），所以最底下一行量出来只有
0.8~1.1 格宽——那是台阶，不是底座。底座（带铆钉的那圈裙边）在它上面
0.17~0.24 格的地方才张到满宽（判据是内容宽的 98%；三级分别是 0.17 / 0.22 / 0.24 格）。
所以这里量的是**整张内容最宽的一行**。

三级定标一样，高度由各自原图的宽高比带出来，所以三级不一样高——
这不是错，见下表：

| 级别 | 原图内容 | 宽高比 | 缩完（格） | 画布上方留白 |
| --- | --- | --- | --- | --- |
| L1 | 696 × 753 | 0.92 | 3.00 × **3.25** | 0.75 格 |
| L2 | 775 × 816 | 0.95 | 2.99 × **3.14** | 0.86 格 |
| L3 | 911 × 871 | 1.05 | 3.00 × **2.85** | 1.15 格 |

**为什么台阶落在贴图最下面是对的。** 贴图底边 = 占地框的**前边**（
锚点是占地矩形底边中点），而 3/4 视角里"画面上最靠下"的东西就是离镜头
最近的——对核心来说就是那道往观众方向伸出来的台阶。所以台阶踩着占地框前边、
楼身在它上面 0.17~0.24 格才张到满宽，几何上自洽（塔同理：底座的前沿压着占地框前边）。

**注意 L3 比 L1 矮**：三级画的是三种体型（一级是敦实的方盒子、三级是摊开的
阶梯金字塔），按同一个宽度缩下来，摊得越开的那张越矮。屏幕上"升级了"
靠的是**换了张图**（细节和发光多得多），不是变大一圈——和两种塔的三级
同一个道理。**宽度是硬约束**：内容宽 ≤ 画布宽 768，所以只有"按宽度定标"
这一个方向可选（按高度定标会让 L1 顶到 896px 宽、直接超出画布）。

<h2>验证</h2>
`main()` 把三级的内容框、底座收口那一行、台阶的宽度都换算成"格"打出来，
并对三条硬要求报警：内容宽度必须正好 3 格、必须居中、高度不能超过画布
（超了顶上会被裁掉）。
"""

from PIL import Image
import numpy as np
import pathlib

from cut_tower import CELL, content_box, keyed

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# 成品画布：3 × 4 格。和 BuildingType.CORE 的
# cols / overhangUpCells / overhangLeftCells / overhangRightCells 是一对。
CANVAS_W = round(3 * CELL)        # 768
CANVAS_H = round(4 * CELL)        # 1024

# 占地框在画布里的位置：左右都不探，所以框就是整幅画布的宽
FOOTPRINT_LEFT = 0
FOOTPRINT_RIGHT = CANVAS_W

# 内容宽度，单位是格。**这一行是这张图的定标**——= 占地宽，理由见模块开头。
# 三个级别共用它，高度各自由原图的宽高比带出来。
CONTENT_CELLS_W = 3.0

# 踩地那一行往上数几行量出来的宽度 ≈ 台阶宽度（最底下 1~2 行是抗锯齿毛边）
GROUND_INSET = 2

# 真机上一格多少像素（华为 HBN-AL80 量出来的，见 docs/ART.md §5）。
# 只用来把"格"换算成肉眼能感知的像素，打印用，不参与出图。
DEVICE_CELL = 92.5

LEVELS = {1: "core_l1_raw.png", 2: "core_l2_raw.png", 3: "core_l3_raw.png"}


def cut_one(src_name: str) -> Image.Image:
    """抠底 → 裁边 → 缩到 3 格宽 → 贴底居中。返回成品画布。"""
    out = keyed(src_name)

    box = content_box(out)
    if box is None:
        raise SystemExit(f"{src_name} 抠完什么都不剩，背景判据大概不对")
    out = out.crop(box)

    # 按**宽度**定标（= 占地宽），高度跟着原图的宽高比走
    scale = (CONTENT_CELLS_W * CELL) / out.width
    size = (round(CONTENT_CELLS_W * CELL), max(1, round(out.height * scale)))
    out = out.resize(size, Image.LANCZOS)
    if out.height > CANVAS_H:
        raise SystemExit(f"内容高 {out.height}px 超出画布 {CANVAS_H}px，顶会被裁掉")

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    canvas.paste(out, ((CANVAS_W - out.width) // 2, CANVAS_H - out.height), out)
    return canvas


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for level, src in LEVELS.items():
        img = cut_one(src)
        dst = OUT / f"core_l{level}.png"
        img.save(dst)

        opaque = np.asarray(img.getchannel("A")) > 8
        ys, xs = np.where(opaque)
        top, bottom = ys.min(), ys.max()
        width = xs.max() - xs.min() + 1
        height = bottom - top + 1

        # 逐行的宽度：从底往上找第一行张到满宽的，那就是台阶和底座的分界
        spans = {}
        for y in range(top, bottom + 1):
            row = np.where(opaque[y])[0]
            if row.size:
                spans[y] = int(row.max() - row.min() + 1)
        full = next(y for y in range(bottom, top - 1, -1)
                    if y in spans and spans[y] >= width * 0.98)
        stair = spans[max(y for y in spans if y <= bottom - GROUND_INSET)]

        def cells(px: int) -> float:
            return px / CELL

        src_size = Image.open(ROOT / "art" / "source" / src).size
        print(f"{dst.name}: {img.width}×{img.height}")
        print(f"  内容 {cells(width):.2f} × {cells(height):.2f} 格"
              f"（原图 {src_size[0]}×{src_size[1]}）"
              f"  画布上方留白 {cells(top):.2f} 格")
        print(f"  最下面 {cells(bottom - full):.2f} 格是台阶（踩地一行宽 {cells(stair):.2f} 格），"
              f"往上到 y={full} 张到满宽 {cells(spans[full]):.2f} 格")

        width_err = abs(width - CONTENT_CELLS_W * CELL) / CELL
        centre_err = abs((xs.min() + xs.max() + 1) / 2 - CANVAS_W / 2)
        print(f"  宽度误差 {width_err * 100:.1f}%   居中误差 {centre_err:.1f}px")
        if width_err > 0.02:
            print("  ⚠️  内容宽度不是 CONTENT_CELLS_W 格，缩放写错了")
        if centre_err > 2:
            print("  ⚠️  内容没居中，会和占地框错开")
        if height > CANVAS_H:
            print("  ⚠️  内容比画布还高，顶上会被裁掉")


if __name__ == "__main__":
    main()
