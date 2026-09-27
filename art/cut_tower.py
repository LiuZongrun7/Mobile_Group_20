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

## 定标：**脚 = 2 格**，炮管可以出格

塔图里能往左右伸的有三样东西：炮管（往左）、炮尾（往右）、**底座那两只外撇的
脚**（往两边）。前两样是武器，伸到邻格上不碍事；**脚伸出去就不行了**——
脚是"这座塔占哪儿"的视觉凭据，出了格就看不出它到底占哪 2×2。

所以定标量的是**脚**：**离地半格以内**最宽的那一行（`FOOT_BAND_CELLS`，炮管在
离地 0.6 格以上，隔得开），把它缩成正好 2 格 = 占地宽；再让这一行的**中心**落在
占地框的中心（画布 x = 512）上。炮管和炮尾跟着这个比例走，伸到哪儿是哪儿。

**第一版量错了地方。** 原来量的是"踩地那一行"（最底下往上数 2px 那行），
量出来 520px ≈ 2.03 格，看着正好——可**脚是越往下越外撇的**，最底下那两行
反而是最窄的一圈接地面。脚真正最宽的地方在离地 0.12 格处：**643px = 2.51 格**，
也就是左右各出格 0.21 / 0.30 格。真机上塔脚探进邻格四分之一格，
一眼就能看出来"这塔没站在格子里"。

改完以后：脚 2.00 格（正好压在占地框上，误差 0.0%），
炮管出格 0.63 格（出格是允许的），炮尾落在占地框右沿以内——塔身整整齐齐在框里，
只有炮管伸出去。顺带塔整体小了 20%，和弩车（底座本来就是 2.00 格）也齐了。

**画布仍然是 845 × 512**，没有跟着内容收紧：`BuildingType.TOWER` 的
`overhangLeftCells / overhangRightCells`（1.0 / 0.3）决定画布宽，改它们要连
`BoardGeometryTest` 一起改，而多出来的透明边不画东西、也不影响对位。
所以左边那 1.0 格的槽位现在只用到 0.63，是**留白**，不是炮管的实际长度。

**炮口火焰跟着一起搬。** 火焰是另一张叠上去的图，横向按"炮口在画布哪儿"定位；
炮口从画布 x = 0 挪到了 x ≈ 0.37 格（95px），所以 `FLASH_BACK_CELLS /
FLASH_OVER_CELLS` 和 `BuildingSprites.FLASH_WINDOWS` 得同步右移同样的距离。
比例也是共用的——`placement()` 就是那条共享的边（下面）。

## 那段"离地半格"是**必须**按格算的（2026-09-27 补）

原来写的是"内容下半部分里最宽的那一行"——按**图**切，不按格。开火那三张图里
火焰从炮口往上窜出去，把内容顶边顶得很高，于是"下半部分"的上沿一路爬到炮管
那一行。三级栽得最狠：内容顶边 y=153、半边带从 y=500 起，量到的"脚"是**炮管**
（1009px，比真脚还宽），整座塔的比例和位置全错，连累炮口火焰偏出去 0.45 格
——抠出来的叠图上只剩火焰左边那条细尾巴，"开火"看着像炮口拖了一根火线。

按**离地格数**算就和图里有没有火无关了：先按"下半部分"粗估一版比例，再用它把
带收到离地 `FOOT_BAND_CELLS` 格，量第二遍。带变 → 脚变 → 比例变 → 带变，
三轮足够收敛（实测其余五张图两轮下来一个像素都没动）。
"""

from PIL import Image
import numpy as np
import pathlib
from typing import NamedTuple

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

# 占地框的中心。脚那一行的中心对到这里——脚不出格靠的就是这一条。
FOOTPRINT_CENTRE = (FOOTPRINT_LEFT + FOOTPRINT_RIGHT) // 2

# **定标：脚 = 占地宽 = 2 格**（理由见模块开头）。
FOOT_CELLS = 2.0

# "脚"在**离地这么高**的这一段里找（格）。炮管离地 0.6 格以上，隔得开；
# 脚外撇得最开的地方在离地 0.12 格，隔不出去。
#
# **不能用"内容高度的一半"**：那是按图切的，图里只要有东西往上伸出去
# （开火图里的火焰就是），内容顶边就被顶高，半边带跟着往上爬到炮管那一行。
# 三级开火图正是这么栽的——火焰把内容顶边顶到 y=153，半边带从 y=500 起，
# 量到的"脚"是炮管（1009px，比真脚还宽），整座塔的比例和位置全错，
# 连累炮口火焰偏出去 0.45 格。按格数算就和图里有没有火无关了。
FOOT_BAND_CELLS = 0.5

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


def keyed(src_name: str) -> Image.Image:
    """读一张原图、抠掉品红底。返回的还是原图尺寸，没裁没缩、**没镜像**。

    这是"抠底"本身，所有品红底的原图共用一套判据——`cut_muzzle_flash.py`
    （开火的发光）和 `cut_wall.py`（城墙）都从这里拿，阈值只有一份，
    不然塔身和火焰/城墙的边界对不上。

    **镜像不在这里做**：镜像是因为资产自己朝错了方向（炮口朝右），
    那是每张图各自的事，不是抠底的一部分。城墙就是左右对称的，镜像等于没镜。
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

    # 去品红边（despill）：半透明的边缘像素是"主体和品红底混出来的"，
    # 直接留会沿轮廓镶一圈紫边。判据是 R 和 B 同时明显高于 G——
    # 主体自己的蓝色高光 R 很低，所以不会被误伤。
    a = despill(a)

    rgba = np.dstack([a, alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def load_keyed(src_name: str) -> Image.Image:
    """`keyed()` 之后再做水平镜像：炮口朝左（见模块开头第 2 条）。"""
    return keyed(src_name).transpose(Image.FLIP_LEFT_RIGHT)


# 一行/一列至少要有这么多个不透明像素，才算"这行有内容"。
# 生成图的边缘偶尔留一两个杂点（七张原图里有两张中招）：
# - 数据中心二级那张的右上角有一个，把它算进去，边界框从 775px 变成 901px、
#   顶边从 91 变成 0——缩完的内容差 42%，而且偏心 54px，一眼看得出歪了；
# - 箭塔三级那张左右各有一列只有 1 个像素的杂边。
# 所以单个像素**不能**算进边界框，否则整张图的缩放和居中一起被带偏。
CONTENT_MIN_RUN = 3


def box_of(opaque: np.ndarray):
    """不透明内容的边界框，`(left, top, right, bottom)`；什么都没有就返回 None。

    比 `getbbox()` 多一道门槛：一行/一列要至少有 `CONTENT_MIN_RUN` 个
    不透明像素才算数。孤立的杂点撑不起一行，所以拉不动边界框。
    """
    rows = np.where(opaque.sum(axis=1) >= CONTENT_MIN_RUN)[0]
    cols = np.where(opaque.sum(axis=0) >= CONTENT_MIN_RUN)[0]
    if rows.size == 0 or cols.size == 0:
        return None
    return int(cols.min()), int(rows.min()), int(cols.max()) + 1, int(rows.max()) + 1


def content_box(image: Image.Image):
    """`box_of()` 的贴图版：直接读 alpha 通道。"""
    return box_of(np.asarray(image.getchannel("A")) > 8)


class Placement(NamedTuple):
    """一张贴图的定标和锚点，见 `placement()`。"""

    scale: float           # 原图像素 → 画布像素
    centre: float          # 脚那一行的水平中心（原图坐标）
    foot_y: int            # 脚那一行是第几行（原图坐标），只用来打日志
    bottom: int            # 内容最低一行（原图坐标）


def placement(mask: np.ndarray) -> Placement:
    """量一张贴图的定标和锚点。

    `mask` 是"算数的不透明像素"（火焰那几张要把火排除掉，见
    `cut_muzzle_flash.py`）。三个数都是**原图像素坐标**：

    - `scale`——原图像素 → 画布像素的倍数，让脚那一行正好 2 格；
    - `foot_centre_x`——脚那一行水平中心，它在画布上对着 `FOOTPRINT_CENTRE`；
    - `bottom_y`——不透明内容的最低一行，它在画布上贴着底边。

    于是画布坐标就是：

        x = (px - foot_centre_x) * scale + FOOTPRINT_CENTRE
        y = CANVAS_H - (bottom_y - py) * scale

    **塔贴图和炮口火焰共用这一份**：火焰是另一张渲染，只有拿同一条规矩量它，
    才会落在和塔贴图同一个比例、同一个位置上。
    """
    # 边界也走 `box_of()`——**不能直接 any(axis=1)**：边缘一个杂点就能把
    # 底边撑下去，脚的比例跟着全歪。开火那三张图左边正好有这么一颗，
    # 量出来的"炮口"落在画布 x = -0.5 格（真正的炮口在 +0.37）。
    box = box_of(mask)
    if box is None:
        raise SystemExit("这张图抠完什么都不剩，背景判据大概不对")
    top, bottom = box[1], box[3] - 1

    # 脚 = **离地 FOOT_BAND_CELLS 格以内**最宽的那一行（脚越往下越外撇，
    # 最宽处在离地 0.12 格上下，所以这段带里量得到）。
    #
    # 带的高度要用"格"表示，可格子多大得先有比例——鸡生蛋。所以先按
    # "内容下半部分"粗估一版，拿它算出带高再量一遍。带变 → 脚变 → 比例变 →
    # 带变，三轮足够收敛（实测第二轮就一模一样了）。
    band_top = round(top + (bottom - top) * 0.5)
    foot = None
    for _ in range(3):
        foot = None
        for y in range(band_top, bottom + 1):
            xs = np.where(mask[y])[0]
            if xs.size == 0:
                continue
            span = int(xs.max() - xs.min()) + 1
            if foot is None or span > foot[0]:
                foot = (span, (int(xs.min()) + int(xs.max()) + 1) / 2.0, y)
        if foot is None:
            raise SystemExit("下半部分一行都没有，FOOT_BAND_CELLS 大概取错了")
        band_top = max(bottom - round(FOOT_BAND_CELLS * CELL / ((FOOT_CELLS * CELL) / foot[0])), top)
    span, centre, foot_y = foot
    return Placement((FOOT_CELLS * CELL) / span, centre, foot_y, bottom)


def cut_one(src_name: str) -> Image.Image:
    out = load_keyed(src_name)

    # 裁到不透明内容的边界
    box = content_box(out)
    out = out.crop(box)

    place = placement(np.asarray(out.getchannel("A")) > 8)
    size = (max(1, round(out.width * place.scale)), max(1, round(out.height * place.scale)))
    out = out.resize(size, Image.LANCZOS)

    # 脚的中心对到占地框中心、内容贴底
    x = round(FOOTPRINT_CENTRE - place.centre * place.scale)
    if x < 0 or x + out.width > CANVAS_W:
        raise SystemExit(f"缩完横向放到画布外了（x={x}，宽 {out.width}，画布 {CANVAS_W}）")

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    canvas.paste(out, (x, CANVAS_H - out.height), out)
    return canvas


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for level, src in LEVELS.items():
        img = cut_one(src)
        dst = OUT / f"tower_arrow_l{level}.png"
        img.save(dst)
        # 量一遍：**脚必须正好落在占地框上**。这是整张图唯一"必须对"的地方，
        # 对不上不会报错，只会看着塔没站在格子里。
        opaque = np.asarray(img.getchannel("A")) > 8
        ys, xs = np.where(opaque)
        place = placement(opaque)
        foot = np.where(opaque[place.foot_y])[0]
        f0, f1 = int(foot.min()), int(foot.max())
        print(f"{dst.name}: {img.width}×{img.height}"
              f"  内容 x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}"
              f"  脚 y={place.foot_y} x {f0}..{f1}（占地框 {FOOTPRINT_LEFT}..{FOOTPRINT_RIGHT}）")
        print(f"  脚宽 {(f1 - f0 + 1) / CELL:.2f} 格"
              f"  左右误差 {(f0 - FOOTPRINT_LEFT) / CELL:+.2f}"
              f" / {(f1 - FOOTPRINT_RIGHT) / CELL:+.2f} 格"
              f"  炮管出格 {(FOOTPRINT_LEFT - xs.min()) / CELL:+.2f} 格"
              f"  炮尾 {(xs.max() - FOOTPRINT_RIGHT) / CELL:+.2f} 格（正=出格）")
        if max(abs(f0 - FOOTPRINT_LEFT), abs(f1 - FOOTPRINT_RIGHT)) > 0.02 * CELL:
            print("  ⚠️  脚没落在占地框上——摆塔时能看出塔和虚框错开")
        if xs.min() < 0 or xs.max() >= CANVAS_W:
            print("  ⚠️  内容顶到画布边了，会被裁")


if __name__ == "__main__":
    main()
