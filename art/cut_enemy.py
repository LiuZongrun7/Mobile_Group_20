#!/usr/bin/env python3
"""把三张敌人的原图抠成游戏能用的贴图。

用法（要 Pillow 和 numpy）：

    /tmp/deckvenv/bin/python art/cut_enemy.py

读 `art/source/enemy_{stand,walk,attack}_raw.jpg`，写
`app/src/main/res/drawable-nodpi/enemy_{stand,walk,attack}.png`。

三张原图是**同一个人的三个动作**：站着不动、迈步走、抡键盘砸。这三张贴图
分别对应 `BattlefieldView` 里敌人的三种画法，见那边的 `drawEnemy`。

和 `cut_tower.py` / `cut_ballista.py` 比，这个脚本短很多，因为**这批原图
比塔那批好伺候**，省掉了两样东西：

1. **不用漫水。** 塔那批的弓臂会围出封闭空腔，只能靠连通性 + 二次曲面两条判据
   合起来抠。这里的人物是完整的一块、四周全是底，**背景又是一个光滑的径向渐变**
   （外圈拟合完残差 p95 只有 2~4，人物离底色 100 以上），所以拟合一张二次曲面
   直接按距离切就干净了，不需要从四边往里漫。

2. **不用连通块筛碎点。** 弩车那边要 `drop_specks` 是因为两次渲染的轮廓差
   磨出一圈细边。这里**必须反过来**：走路那张脚下扬起的尘土是几十个**互不相连**
   的小点（208~262 和 778~798 那两撮），它们正是"这个人在跑"的证据，
   按面积筛就全没了。所以这里一个碎点都不删。

**三张原图本来就是对齐的，所以这里只做整体缩放，不做逐张对位。**
量出来的证据：三张的头顶都在第 71 行（一模一样），踩地行分别是 990 / 977 / 992。
也就是说生成的时候就是按同一套取景渲染的。这一点很值钱——**逐张各量各的踩地行
再各自对齐，反而会把这份对齐破坏掉**（走路那张的脚会凭空抬高 13px）。
所以下面只认一个 `GROUND_ROW`，三张共用。

**不镜像。** 塔那批要先翻（原图朝右、游戏里朝左），这批**原图就朝右**，
而敌人正是从左往右走的，翻了反而倒着走。
"""

import pathlib

import numpy as np
from PIL import Image

from cut_tower import despill

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "source"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

CELL = 256

# 出图画布：**体格 1.25 的敌人**（也就是 Brute，三种里最大那只）走完整个画布。
#
# 「体格」是 EnemyType.bodyCells——引擎里那是个碰撞口径（半身位），
# 现在贴图比它高得多，这两件事从此是分开的：碰撞还是 1.0 格，画出来的人有一格二。
# 分开是对的（人本来就比脚踩的那块地宽），但**两边的数要对得上**，
# 所以这里按 bodyCells 线性缩放，一个常数管三种兵：
# 杂兵 1.32 格、快兵 1.06、重甲 1.65。
CANVAS_CELLS_PER_BODY = 1.32

# 画布是按这一档裁的，运行时比它小的往下缩、比它大的不存在。
# 和 EnemyType.BRUTE.bodyCells 是一对，改一边要改另一边。
ART_BODY_CELLS = 1.25

CANVAS_PX = round(CANVAS_CELLS_PER_BODY * ART_BODY_CELLS * CELL)     # 422

# 裁切框。**y 的下沿就是踩地那一行**：裁到这儿之后"贴图底边 = 脚底"，
# 和建筑的锚点规矩（`drawBuilding` 的 bottom 就是占地底边）完全一致，
# 运行时不用再记一个"脚底在画布往下百分之几"的偏移量。
#
# 993 是量出来的：站姿 993、挥击 992、迈步 977。取三张里最低的那个，
# 谁都不会被裁到。
#
# **三张共用这一个数**，不各量各的。它们本来就对齐（头顶都是 71 行），
# 各量各的反而会把这份对齐弄坏：迈步那张的脚只到 977，照它裁的话，
# 那一帧整个人会比其他两帧**低 16px**，走起来一颠一颠的。
# 共用一个数，误差就只是"迈步那帧的脚离画布底边还差 16px"——
# 缩完 0.027 格，一格 23dp，上屏 0.6dp，看不出来。
GROUND_ROW = 993

# x 的裁切框：三张的内容合起来占 x 147~989（最左是挥击那张甩出去的电弧，
# 最右是举起来的键盘）。裁成和高度一样宽的正方形、横着对准原图中心（512），
# 两边都留得出余量，而且**画布中点正好落在人物站的地方**——三张的脚分别站在
# 536 / 514 / 476，离画布中点最远的那张差 36px，缩完 0.06 格，上屏 1dp 出头。
# 裁成正方形是为了让成品宽高比正好 1.0，运行时那个矩形不用再算比例。
CROP_LEFT = round(512 - GROUND_ROW / 2)                               # 16
CROP_RIGHT = CROP_LEFT + GROUND_ROW                                   # 1009

# 底色拟合的取样圈宽度。人物不碰边（量过：三张的内容都在 x 147~989、y 71~992
# 之内），所以最外圈必然是纯背景，拿它拟合没有污染。
RING = 8

# 到拟合底色的距离 → alpha。背景处残差 p95 只有 2~4，人物离底色 100 以上，
# 所以这两个数在很宽的范围里怎么取都一样（和塔那批一个口径）。
KEY_SOFT = 30.0
KEY_HARD = 80.0

POSES = {
    "stand": "enemy_stand_raw.jpg",
    "walk": "enemy_walk_raw.jpg",
    "attack": "enemy_attack_raw.jpg",
}


def background_model(a: np.ndarray) -> np.ndarray:
    """拿最外一圈像素拟合一张二次曲面，当作"这一点的底色"。

    底色是"中间亮、四角暗"的一团径向渐变，不是纯色，所以不能用四角中位数
    当全局基准（那样中间会被整片判成人物）。二次够用：拟合完在背景处的残差
    p95 只有 2~4。

    归一化到 [-1, 1] 再拟合：不然 x² 到 1e6 量级，和常数项差六个数量级，
    最小二乘会病态。
    """
    h, w, _ = a.shape
    ring = np.zeros((h, w), bool)
    ring[:RING, :] = ring[-RING:, :] = True
    ring[:, :RING] = ring[:, -RING:] = True
    ys, xs = np.nonzero(ring)

    basis = _basis(xs, ys, w, h)
    coef, *_ = np.linalg.lstsq(basis, a[ys, xs].astype(np.float64), rcond=None)

    gy, gx = np.mgrid[0:h, 0:w]
    return _basis(gx.ravel(), gy.ravel(), w, h) @ coef


def _basis(xs: np.ndarray, ys: np.ndarray, w: int, h: int) -> np.ndarray:
    xn = xs / (w - 1) * 2 - 1
    yn = ys / (h - 1) * 2 - 1
    return np.stack([np.ones_like(xn), xn, yn, xn * xn, xn * yn, yn * yn], axis=1)


def keyed(src_name: str) -> Image.Image:
    """读原图、抠掉渐变底。**不裁不缩也不镜像**，还是 1024×1024。"""
    a = np.asarray(Image.open(SRC / src_name).convert("RGB")).astype(np.float32)
    model = background_model(a).reshape(a.shape)

    dist = np.sqrt(((a - model) ** 2).sum(axis=2))
    alpha = np.clip((dist - KEY_SOFT) / (KEY_HARD - KEY_SOFT), 0.0, 1.0)
    return Image.fromarray(
        np.dstack([despill(a), alpha * 255.0]).astype(np.uint8), "RGBA")


def body_mask(image: Image.Image) -> np.ndarray:
    """不透明、而且**不是青色发光**的那部分。

    发光要从"踩地行"里排除掉：挥击那张甩出去的电弧扫得很低，青色像素一直
    铺到第 898 行——不排掉的话，量出来的踩地行会跟着电弧跑。
    """
    a = np.asarray(image).astype(np.float32)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    opaque = a[:, :, 3] > 128
    glow = (b - r > 45) & (b > 130) & (g - r > 15)
    return opaque & ~glow


def measure(image: Image.Image) -> int:
    """量踩地行 = 最下面那条**还有一定墨量**的行。

    不取"最后一个有像素的行"：鞋底下面拖着一条抗锯齿的渐隐尾巴（站姿那张
    第 993 行只剩 8 个像素），照它量踩地行会凭空往下跑几行。

    判据取两个数的较大者，缺一不可：
    - 整张图最宽那行的 3%——防的是"人物越往下越细"这种正常收窄；
    - 20 个像素——防的是**整张图本身就很窄**时百分比太小。只按 3% 算的话，
      站姿那张的阈值是 12，第 993 行那 8 个像素刚好卡在门槛下、990 行那 41 个
      刚过线，量出来是对的；但这是个巧合，不是判据在起作用。
    """
    body = body_mask(image)
    ink = body.sum(axis=1)
    rows = np.nonzero(ink)[0]
    floor = max(ink.max() * 0.03, 20)
    return int(rows[ink[rows] >= floor].max())


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    measured = {}

    for pose, src_name in POSES.items():
        image = keyed(src_name)
        ground = measure(image)
        ys, _ = np.where(np.asarray(image)[:, :, 3] > 128)
        top = int(ys.min())
        measured[pose] = (top, ground)

        # 三张共用一套裁切和缩放（理由见模块开头），所以裁的是一刀切。
        out = image.crop((CROP_LEFT, 0, CROP_RIGHT, GROUND_ROW)) \
                   .resize((CANVAS_PX, CANVAS_PX), Image.LANCZOS)
        Image.fromarray(np.asarray(out), "RGBA").save(OUT / f"enemy_{pose}.png")

        # 缩完之后人物占几格——这个数就是"贴图比碰撞盒大多少"，
        # 和 EnemyType 里那几个 bodyCells 一起决定上屏多大。
        height_cells = (GROUND_ROW - top) / GROUND_ROW * CANVAS_CELLS_PER_BODY * ART_BODY_CELLS
        print(f"enemy_{pose}.png  {CANVAS_PX}×{CANVAS_PX}  "
              f"头顶 {top} 踩地 {ground}  "
              f"人物高 {height_cells:.2f} 格（体格 1.25 时）/ "
              f"{height_cells / ART_BODY_CELLS:.2f} 格（体格 1.0 时）")

    # 验"三张本来就是对齐的"这条前提——整套做法都建在它上面。
    # 不成立的话就不能三张共用一刀切，得逐张对位（那样走路那张的脚会凭空抬起来）。
    tops = {p: v[0] for p, v in measured.items()}
    grounds = {p: v[1] for p, v in measured.items()}
    print(f"\n对齐检查：头顶 {tops}  踩地 {grounds}")
    if max(tops.values()) - min(tops.values()) > 8:
        print("  ⚠️  三张的头顶不在同一行——它们不是按同一套取景渲染的，"
              "三张共用一刀切会把人物画得一高一低")
    below = {p: g for p, g in grounds.items() if g > GROUND_ROW}
    if below:
        print(f"  ⚠️  {below} 的脚在裁切线 {GROUND_ROW} 以下，鞋底会被削掉一截——"
              f"GROUND_ROW 要放大到 {max(grounds.values())}")
    print(f"  踩地行散得最开的差 {max(grounds.values()) - min(grounds.values())}px，"
          f"缩完约 {(max(grounds.values()) - min(grounds.values())) / GROUND_ROW * 1.23:.3f} 格"
          f"——对不齐的那点误差在这儿，一格 23dp，上屏不到 1dp")
    print(f"\n画布 {CANVAS_PX}px = {CANVAS_CELLS_PER_BODY * ART_BODY_CELLS:.2f} 格"
          f"（EnemySprites 里那个数要跟这儿一样）")


if __name__ == "__main__":
    main()
