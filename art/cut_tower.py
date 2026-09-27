#!/usr/bin/env python3
"""把箭塔的原图抠成游戏能用的贴图。

用法（要 Pillow）：

    /tmp/deckvenv/bin/python art/cut_tower.py

读 `art/source/tower_l{1,2,3}_raw.png`，写出
`app/src/main/res/drawable-nodpi/tower_arrow_l{1,2,3}.png`。

原图是"纯品红底 + 朝右的炮塔"。这里做四件事，顺序不能换：

1. **抠底**：按四角取背景色，算每个像素到背景色的距离，远的透明、近的保留，
   中间一段做羽化。品红底是 AI 生成时按 `docs/ART.md` §1.3 要的，所以这里能
   一键搞定，不用手抠。
2. **水平镜像**：原图的炮口朝**右**，而游戏里塔一律朝**左**打
   （`BuildingStats.AIM_HALF_ANGLE_DEG`）。不镜像的话就是"炮口顶着敌人来的方向"。
3. **裁到内容边界**，再去掉四周的透明边——原图四周留了不少品红。
4. **缩到成品尺寸**并**贴底对齐**。锚点在贴图底边，下面留白 =
   塔浮在半空。

尺寸是 `(cols + 左探 + 右探) × (rows + 上探)` 格 × 256px，
即 `(2 + 1 + 0.3) × 2 = 3.3 × 2` 格 = 845 × 512，见 `docs/ART.md` §2.1。

**画布边界是量出来的，不是拍的。** 原图里这三段的相对宽度是固定的，
把"踩地那一行"定成 2 格（= 占地宽度）之后，另外两段就有了确定的值：

| 段 | 量出来的 | 取整后 |
| --- | --- | --- |
| 炮管（左） | 1.03 格 | 1.0 |
| 底座踩地（中） | 2.00 格 | 2.0（= `cols`） |
| 炮尾（右） | 0.28 格 | 0.3 |

取整的误差不到 0.03 格（屏幕上约 2px），换来的是三个整数好念。
**关键在于中段必须正好是 2 格**：它对着的就是占地框，差一点点
摆塔的时候就会看出来塔身和虚框错开。这也是右边那 0.3 格存在的理由——
不把它算进画布，中段就只能靠缩小整张图来迁就，塔会白白小一圈。
"""

from PIL import Image
import numpy as np
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "source"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# 成品画布：3.3 格宽 × 2 格高，一格 256px。
# 和 BuildingType.TOWER 的 cols / overhangLeftCells / overhangRightCells 是一对，
# 改那边要改这边（跑完脚本会用踩地那一行反过来验一遍，对不上会打印出来）。
CELL = 256
CANVAS_W = round(3.3 * CELL)    # 845 = (2 + 1.0 + 0.3) 格
CANVAS_H = round(2.0 * CELL)    # 512

# 占地框在画布里的位置：左边让出 overhangLeftCells 格，宽 cols 格
FOOTPRINT_LEFT = round(1.0 * CELL)
FOOTPRINT_RIGHT = round((1.0 + 2.0) * CELL)

# 背景判定：到这个距离以内算背景。羽化带宽 40，让边缘有一点过渡，
# 不然像素边缘会留一圈硬品红。
KEY_SOFT = 40.0
KEY_HARD = 90.0

LEVELS = {1: "tower_l1_raw.png", 2: "tower_l2_raw.png", 3: "tower_l3_raw.png"}


def despill(rgb: np.ndarray) -> np.ndarray:
    """把"品红底混进塔身"的那部分颜色拉回来。

    只在 R、B 都高出 G 一截的地方动手，而且只压到 `G + 余量`，不做全量替换——
    塔的暗部本来就是偏紫的深蓝（原图轮廓线），压太狠会把轮廓洗白。
    """
    out = rgb.copy()
    r, g, b = out[:, :, 0], out[:, :, 1], out[:, :, 2]
    hit = (r - g > 40) & (b - g > 40)
    out[:, :, 0] = np.where(hit, np.minimum(r, g + 25), r)
    out[:, :, 2] = np.where(hit, np.minimum(b, g + 35), b)
    return out


def load_keyed(src_name: str) -> Image.Image:
    """读一张原图、抠掉品红底、水平镜像。返回的还是原图尺寸，没裁没缩。

    `cut_muzzle_flash.py` 也调这个——它要拿同一套抠图规则处理开火的那三张，
    两边的背景判据必须是一个，不然塔身和火焰的边界对不上。
    """
    im = Image.open(SRC / src_name).convert("RGB")
    a = np.asarray(im).astype(np.float32)

    # 背景色取四角的中位数——生成图四周是纯色，中间才是主体
    corners = np.concatenate([
        a[:16, :16].reshape(-1, 3), a[:16, -16:].reshape(-1, 3),
        a[-16:, :16].reshape(-1, 3), a[-16:, -16:].reshape(-1, 3),
    ])
    bg = np.median(corners, axis=0)

    # 到背景色的欧氏距离 → alpha
    dist = np.sqrt(((a - bg) ** 2).sum(axis=2))
    alpha = np.clip((dist - KEY_SOFT) / (KEY_HARD - KEY_SOFT), 0.0, 1.0)

    # 去品红边（despill）：半透明的边缘像素是"塔身和品红底混出来的"，
    # 直接留会沿轮廓镶一圈紫边。判据是 R 和 B 同时明显高于 G——
    # 塔自己的蓝色高光 R 很低，所以不会被误伤。
    a = despill(a)

    rgba = np.dstack([a, alpha * 255.0]).astype(np.uint8)

    # 抠完再镜像：炮口朝左（见模块开头第 2 条）
    return Image.fromarray(rgba, "RGBA").transpose(Image.FLIP_LEFT_RIGHT)


def content_box(image: Image.Image):
    """不透明内容的边界框，`(left, top, right, bottom)`；什么都没有就返回 None。"""
    return image.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()


def cut_one(src_name: str) -> Image.Image:
    out = load_keyed(src_name)

    # 裁到不透明内容的边界
    box = content_box(out)
    out = out.crop(box)

    # 等比缩放到画布宽度，贴底、水平居中
    scale = CANVAS_W / out.width
    size = (CANVAS_W, max(1, round(out.height * scale)))
    out = out.resize(size, Image.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    canvas.paste(out, (0, CANVAS_H - out.height), out)
    return canvas


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for level, src in LEVELS.items():
        img = cut_one(src)
        dst = OUT / f"tower_arrow_l{level}.png"
        img.save(dst)
        # 量一遍：底座踩地那一段必须落在占地框上。这是整张图唯一"必须对"的地方，
        # 对不上不会报错，只会在摆塔的时候发现塔身和虚框错开。
        opaque = np.asarray(img.getchannel("A")) > 8
        ys, xs = np.where(opaque)
        ground = np.where(opaque[ys.max() - 2])[0]
        g0, g1 = int(ground.min()), int(ground.max())
        left_err = (g0 - FOOTPRINT_LEFT) / CELL
        right_err = (g1 - FOOTPRINT_RIGHT) / CELL
        print(f"{dst.name}: {img.width}×{img.height}"
              f"  内容 x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}"
              f"  踩地 x {g0}..{g1}（占地框 {FOOTPRINT_LEFT}..{FOOTPRINT_RIGHT}）"
              f"  左右误差 {left_err:+.2f} / {right_err:+.2f} 格")
        if max(abs(left_err), abs(right_err)) > 0.15:
            print("  ⚠️  踩地和占地框差得有点多，画布宽度或 overhang 该重算")


if __name__ == "__main__":
    main()
