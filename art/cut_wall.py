#!/usr/bin/env python3
"""把城墙的原图抠成游戏能用的贴图。

用法（要 Pillow）：

    /tmp/deckvenv/bin/python art/cut_wall.py

读 `art/source/wall_raw.png`，写出 `app/src/main/res/drawable-nodpi/wall.png`。

原图是"品红底 + 一根敦实的蓝灰石柱"。抠底用的是 `cut_tower.py` 里那一套
（`keyed()`，阈值只有一份），这里只做三件事：

1. **裁到内容边界**——原图四周留了一大片品红。
2. **等比缩到 0.9 格宽**（230px）。
3. **贴底、水平居中**放进画布。

<h2>为什么没有镜像这一道</h2>
箭塔要镜像是因为炮口朝右、而塔一律朝左打；弩车同理。**城墙没有朝向**：
`BuildingType.WALL` 的左右探出都是 0，横着摆一列和竖着摆一列用的是同一张图。
而且这张原图本来就左右对称（不透明区域的镜像差异 0.3%，左右两半的平均色
差 7.5/255），镜像等于没镜。所以这里走 `keyed()` 而不是 `load_keyed()`。

<h2>尺寸：画布 1 × 1.5 格，内容 0.90 × 1.12 格</h2>
画布是 `(cols + 左探 + 右探) × (rows + 上探)` 格 × 256px。城墙是 1×1、上探 0.5、
左右不探，所以画布 = `1 × 1.5` 格 = **256 × 384**（`docs/ART.md` §2.1）。

但**原图的形状塞不满这个画布**：内容是 652 宽 × 809 高（宽高比 0.81）。
按宽度定标就是 1.00 × 1.24 格，按高度定标就是 0.80 × 1.00 格，两个极端都不好：

| 定标方式 | 成品内容 | 竖着堆一列（障壁的常见形状） | 横着摆一排 |
| --- | --- | --- | --- |
| 按宽度 1 格 | 1.00 × 1.24 格 | 每格之间**露出 0.26 格草地**（真机 24px） | 底座 0.94 格，缝 0.06 格 |
| 按高度 1 格 | 0.80 × 1.00 格 | 无缝，但一根根像**路桩** | 每格之间露出 0.20 格（18px） |
| **宽 0.90 格（取这个）** | **0.90 × 1.12 格** | **相邻两格重叠 0.12 格**（真机 11px）→ 看着像砌起来的 | 每格之间只剩 0.10 格（9px） |

取中间这一档，两轴都不露大缝：**竖着堆会重叠**（画的时候靠下的后画，
下方那格的柱冠正好压在上方那格的柱基上，真机上看就是一道砌缝），
**横着摆只留一条细缝**。
两个极端各有一个方向明显不像"墙"——按宽度是竖着断成一节节，按高度是横着断成一节节。

重叠这一点是量过的、不是碰巧：`BattlefieldView.drawSprites` 按**脚踩的 y 升序**
排序，越靠下的越后画，所以**下面那格的柱冠压在上面那格的柱基上**——
顺序反过来的话就变成柱基糊住柱冠，砌缝的位置就对不上了。

**内容宽必须 < 1 格**，不然柱子会伸进左右邻格：0.90 留了 0.10 格余量，
脚本里也拿这一条当断言（超出画布宽度直接报错退出）。

<h2>验证</h2>
`main()` 会把量出来的几段宽度换算成"格"打出来，并对三条硬要求报警：
内容宽度必须等于定标值（差得多说明缩放写错了）、不能宽过画布（宽了会伸进
左右邻格）、内容要居中（偏了会一边挤一边空）。接地段和柱基的宽度是打印出来
供对照的，不是断言——它们由原图的形状决定。
"""

from PIL import Image
import numpy as np
import pathlib

from cut_tower import CELL, content_box, keyed

SRC_NAME = "wall_raw.png"

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# 成品画布：1 × 1.5 格。和 BuildingType.WALL 的
# cols / overhangUpCells / overhangLeftCells / overhangRightCells 是一对。
CANVAS_W = round(1.0 * CELL)      # 256
CANVAS_H = round(1.5 * CELL)      # 384

# 占地框在画布里的位置：左右都不探，所以框就是整幅画布的宽
FOOTPRINT_LEFT = 0
FOOTPRINT_RIGHT = CANVAS_W

# 内容的宽度，单位是格。**这一行是这张图的定标**——改它墙就变大变小，
# 高度由原图的宽高比带出来（0.90 格宽 → 1.11 格高），不单独设。
# 必须 < 1：宽过一格柱子就伸进左右邻格了。理由见模块开头那张表。
CONTENT_CELLS_W = 0.90

# 踩地那一行往上数几行来量接地段：最底下 1~2 行是抗锯齿的毛边，量出来偏窄
GROUND_INSET = 2

# 真机上一格多少像素（华为 HBN-AL80 量出来的，见 docs/ART.md §5）。
# 只用来把"格"换算成肉眼能感知的像素，打印用，不参与出图。
DEVICE_CELL = 92.5


def cut_one(src_name: str) -> Image.Image:
    """抠底 → 裁边 → 缩到 0.9 格宽 → 贴底居中。返回成品画布。"""
    out = keyed(src_name)

    box = content_box(out)
    if box is None:
        raise SystemExit(f"{src_name} 抠完什么都不剩，背景判据大概不对")
    out = out.crop(box)

    # 按**宽度**定标，高度跟着原图的宽高比走（理由见模块开头那张表）
    scale = (CONTENT_CELLS_W * CELL) / out.width
    size = (round(CONTENT_CELLS_W * CELL), max(1, round(out.height * scale)))
    out = out.resize(size, Image.LANCZOS)
    if out.width > CANVAS_W:
        raise SystemExit(f"内容宽 {out.width}px 超出画布 {CANVAS_W}px，会盖到左右邻格")

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    canvas.paste(out, ((CANVAS_W - out.width) // 2, CANVAS_H - out.height), out)
    return canvas


def rows(opaque: np.ndarray):
    """逐行的不透明段，`(y, 最左, 最右)`，只给非空行。"""
    out = []
    for y in range(opaque.shape[0]):
        xs = np.where(opaque[y])[0]
        if xs.size:
            out.append((y, int(xs.min()), int(xs.max())))
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    img = cut_one(SRC_NAME)
    dst = OUT / "wall.png"
    img.save(dst)

    opaque = np.asarray(img.getchannel("A")) > 8
    ys, xs = np.where(opaque)
    profile = rows(opaque)
    top, bottom = ys.min(), ys.max()

    # 最宽的一行（柱基那道沿）和踩地那一段
    widest = max(profile, key=lambda r: r[2] - r[1])
    ground = next(r for r in reversed(profile) if r[0] <= bottom - GROUND_INSET)

    def cells(px: int) -> float:
        return px / CELL

    width = xs.max() - xs.min() + 1
    height = bottom - top + 1

    print(f"{dst.name}: {img.width}×{img.height}")
    print(f"  内容 x {xs.min()}..{xs.max()} y {top}..{bottom}"
          f"  = {cells(width):.2f} × {cells(height):.2f} 格")
    print(f"  画布上方留白 {top}px（{cells(top):.2f} 格）"
          f"—— BuildingType.WALL 的 overhangUp=0.5 在图上留的空")
    print(f"  最宽的一行（柱基）y={widest[0]} 宽 {widest[2] - widest[1] + 1}px"
          f" = {cells(widest[2] - widest[1] + 1):.2f} 格")
    print(f"  踩地 y={ground[0]} x {ground[1]}..{ground[2]} = {cells(ground[2] - ground[1] + 1):.2f} 格")
    # 真机上一格 92.5px（HBN-AL80 量出来的），这里换算一遍——
    # 贴图上的一格是 256px，两个数差 2.8 倍，不换算容易把 30px 当成"肉眼可见的大缝"
    print(f"  竖着堆一列：相邻两格重叠 {cells(height) - 1:+.2f} 格"
          f"（真机 {abs(height - CELL) * DEVICE_CELL / CELL:.0f}px，负=露缝）")
    print(f"  横着摆一排：相邻两格留缝 {1 - cells(width):.2f} 格"
          f"（真机 {(CELL - width) * DEVICE_CELL / CELL:.0f}px）")

    width_err = abs(width - CONTENT_CELLS_W * CELL) / CELL
    centre_err = abs((xs.min() + xs.max() + 1) / 2 - CANVAS_W / 2)
    print(f"  宽度误差 {width_err * 100:.1f}%   居中误差 {centre_err:.1f}px")
    if width_err > 0.02:
        print("  ⚠️  内容宽度不是 CONTENT_CELLS_W 格，缩放写错了")
    if width > FOOTPRINT_RIGHT - FOOTPRINT_LEFT:
        print("  ⚠️  内容比占地框还宽，会和左右邻格的墙叠在一起")
    if centre_err > 2:
        print("  ⚠️  内容没居中，左右邻格的墙会一边挤一边空")


if __name__ == "__main__":
    main()
