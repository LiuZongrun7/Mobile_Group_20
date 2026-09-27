#!/usr/bin/env python3
"""把弩车的原图抠成游戏能用的贴图。

用法（要 Pillow 和 numpy）：

    /tmp/deckvenv/bin/python art/cut_ballista.py

读 `art/source/ballista_l{1,2,3}_raw.png`（常）和
`ballista_l{1,2,3}_fire_raw.png`（开火），写出
`app/src/main/res/drawable-nodpi/ballista_l{1,2,3}.png` 和
`ballista_flash_l{1,2,3}.png`。

弩车和箭塔一样占 2×2、也一样朝左打，所以成品画布和
`cut_tower.py` 是同一套规矩。但**这批原图和箭塔那批有三处不一样**，
下面每一条都是被原图逼出来的，不是随手换的写法：

1. **底色是渐变的，不是纯品红。** 箭塔那批四角取个中位数就能当背景色，
   这批中间比边上亮一截，全局阈值一量就把半个背景算成塔身。
   所以这里用两条判据取并集：**连通性**（从四边往里漫水，漫得到的必然是背景）
   ＋ **颜色**（拿漫出来的点拟合一张二次曲面当"这一点的底色"，
   用来收拾弩身围出来的那些封闭空腔——它们漫不到，但底色就是周围那圈）。
   见 `background_mask` / `background_model`。

2. **尺寸是按"踩地那一段"定的，不是按内容总宽。** 弩车的底座是个外扩的台子，
   它才是踩在 2×2 占地上的那一块，所以判据是"踩地 = 2 格"，别的都跟着它算。
   箭塔那批是按内容总宽缩的，量完回头验踩地——那是因为炮塔的身子和底座差不多宽，
   两种算法结果一样；弩车差得多，按内容总宽缩会让底座和占地框对不上。

3. **开火图和常图是同一张渲染加了光，可以直接作差。**
   证据：L1 的两张踩地宽一模一样（819px）、身体 IoU 0.968，L2 也是（821px、0.936）。
   箭塔那批不是——常图脚宽 879、开火图 697，是重新渲染的，作差作出来是垃圾。
   所以弩车的炮口火焰不用像箭塔那样靠颜色分割去猜位置，直接
   "开火有、常图没有、而且是亮蓝白的光"就是火焰，位置自动是对的。

**镜像是量之前做的，不是量之后。** 原图弩箭朝右、游戏里一律朝左，所以先翻过来，
再量踩地、再搬上画布。反过来（搬完再翻整个画布）会把占地框一起翻走——
占地框从左起 0.9 格变成 0.2 格，底座就和格子对不上了。这个坑踩过。
"""

from collections import deque
import pathlib

import numpy as np
from PIL import Image, ImageFilter

from cut_tower import despill

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "source"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

CELL = 256

# 弩车占 2×2，底座踩在那 2 格上——这是整张图唯一"必须对"的地方
FOOT_CELLS = 2.0

# 成品画布：3.1 格宽 × 2 格高。
# 宽度是量出来的：镜像之后，弩弓往左探出 0.86 格（最宽的是 L2/L3 的常图）、
# 弩尾往右探出 0.17 格（最宽的是 L2），取整成 0.9 和 0.2。
# 和 BuildingType.BALLISTA 的三个 overhang 是一对，改一边要改另一边——
# 跑完脚本会拿踩地那一行反过来验一遍。
CANVAS_W = round(3.1 * CELL)            # 794
CANVAS_H = round(2.0 * CELL)            # 512
FOOTPRINT_LEFT = round(0.9 * CELL)      # 230
FOOTPRINT_RIGHT = FOOTPRINT_LEFT + round(FOOT_CELLS * CELL)   # 742

# 漫水每一步的容差。底色是缓变的（相邻像素差个位数），主体边缘是跳变（几十上百），
# 所以这个数只要比噪声大、比边缘小就行，12 两头都够。
KEY_TOL = 12.0

# 拟合出来的底色算"多远才算主体"。背景处的模型残差 p50 只有 2～3、p90 不到 6，
# 而主体和底色差着上百，所以这两个数在很宽的范围里怎么取都一样（验过 30/80 和 45/100）。
KEY_SOFT = 30.0
KEY_HARD = 80.0

# 作差的判据是**亮度差了多少**，不是"颜色变了没有"。
#
# 一开始写的是"颜色差得远就算"，结果 2 级和 3 级的弩身被整片糊上：
# 那两级的弩身本来就全是发亮的青色符文和晶体，两次渲染差半个像素，
# 符文边上就磨出一圈"颜色差得远"的边，整条身子都算成了光。
#
# 换成亮度差就分得开了：光是把暗的地方照亮（木头石头也就 200～350，
# 能量是 500～765），而"边磨出来的那圈"亮度基本没变。
# 765 是纯白（三个通道全 255）。
#
# 420 是看图定的，不是算出来的：再低（340）弩身的轮廓线上会浮出一圈细白边——
# 那是两次渲染的剪影差一个像素磨出来的，亮度刚好落在 340～420 这一段；
# 再高（500）光本身开始缺角，1 级那团光爆会瘦一圈。
GLOW_ADDED = 420.0

# 开火图那儿至少得不透明，才谈得上"多出来的光"
GLOW_MIN_ALPHA = 60

# 小于这么多像素的连通块直接不要。细长的光刺要留着（所以不能靠形态学腐蚀），
# 但两次渲染的轮廓线磨出来的那一圈是又细又长的一条，按面积也筛不掉，
# 只能靠上面那个亮度阈值。这个数管的是剩下的零星碎点。
GLOW_MIN_PIXELS = 120

# 2 级的开火图是个孤例：**它把放电画在了弩尾**（原图的左边），
# 别的两级都画在弩箭那一端（原图的右边）。镜像过来它就跑到屁股上去了，
# 所以这一级的光层在镜像之后单独再翻一次，让它从弩箭那端冒出来。
# 只有这一级要翻——别顺手给 1、3 级也加上。
GLOW_FLIP = {2}

LEVELS = {1: "ballista_l1_raw.png", 2: "ballista_l2_raw.png", 3: "ballista_l3_raw.png"}
FIRING = {1: "ballista_l1_fire_raw.png", 2: "ballista_l2_fire_raw.png",
          3: "ballista_l3_fire_raw.png"}


def background_mask(a: np.ndarray, tol: float = KEY_TOL) -> np.ndarray:
    """从四边往里漫水，返回"是背景"的掩码。

    每一步只跟<b>已经判成背景的那个邻居</b>比，不跟一个全局基准色比——
    这正是对付渐变底色的办法：梯度是缓的，一格一格走过去就到底了；
    主体边缘是跳变，当场停住。
    """
    h, w, _ = a.shape
    bg = np.zeros((h, w), bool)
    seen = np.zeros((h, w), bool)
    queue = deque()

    for x in range(w):
        for y in (0, h - 1):
            if not seen[y, x]:
                seen[y, x] = bg[y, x] = True
                queue.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if not seen[y, x]:
                seen[y, x] = bg[y, x] = True
                queue.append((y, x))

    ai = a.astype(np.int16)
    t2 = tol * tol
    while queue:
        y, x = queue.popleft()
        c = ai[y, x].astype(np.int32)
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if ny < 0 or ny >= h or nx < 0 or nx >= w or seen[ny, nx]:
                continue
            d = ai[ny, nx].astype(np.int32) - c
            if int(d[0]) ** 2 + int(d[1]) ** 2 + int(d[2]) ** 2 <= t2:
                seen[ny, nx] = True
                bg[ny, nx] = True
                queue.append((ny, nx))
    return bg


def background_model(a: np.ndarray, bg: np.ndarray) -> np.ndarray:
    """拿漫出来的背景点拟合一张二次曲面，当作"这一点的底色"。

    为什么不能只用连通性：弩弓的弓臂围出好几块封闭的空腔，漫水漫不进去，
    会被整块当成主体，成品上就是几个紫色的补丁。它们的底色就是周围那圈背景的底色，
    所以这里拟合一个光滑的模型去判，不靠连通性。

    二次够用：这批图的底子是"四角暗、中间亮"的一团，二次曲面拟合完
    在背景处的残差 p50 只有 2～3。
    """
    h, w, _ = a.shape
    ys, xs = np.nonzero(bg)
    step = max(1, ys.size // 20000)          # 抽两万个点就够了，最小二乘不用上百万
    ys, xs = ys[::step], xs[::step]

    # 归一化到 [-1, 1]：不然 x² 到 1e6 量级，和常数项差六个数量级，最小二乘会病态
    xn = xs / (w - 1) * 2 - 1
    yn = ys / (h - 1) * 2 - 1
    basis = np.stack([np.ones_like(xn), xn, yn, xn * xn, xn * yn, yn * yn], axis=1)
    coef, *_ = np.linalg.lstsq(basis, a[ys, xs].astype(np.float64), rcond=None)

    gy, gx = np.mgrid[0:h, 0:w]
    gxn = gx / (w - 1) * 2 - 1
    gyn = gy / (h - 1) * 2 - 1
    full = np.stack([np.ones_like(gxn), gxn, gyn,
                     gxn * gxn, gxn * gyn, gyn * gyn], axis=-1)
    return full @ coef


def load_keyed(src_name: str) -> Image.Image:
    """读一张原图，抠掉渐变底、水平镜像。**不裁不缩**，还是原图尺寸。

    镜像在这儿做、不放到最后，理由见模块开头那段。
    """
    a = np.asarray(Image.open(SRC / src_name).convert("RGB")).astype(np.float32)
    bg = background_mask(a)
    model = background_model(a, bg)

    dist = np.sqrt(((a - model) ** 2).sum(axis=2))
    alpha = np.clip((dist - KEY_SOFT) / (KEY_HARD - KEY_SOFT), 0.0, 1.0)
    alpha[bg] = 0.0          # 漫出来的必然是背景，模型拟合得再好也不许翻案

    rgba = np.dstack([despill(a), alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA").transpose(Image.FLIP_LEFT_RIGHT)


def ground_row(opaque: np.ndarray) -> int:
    """踩地那一行 = 最下面 12% 里最宽的一行。

    不取"最后一行"：像素边缘的抗锯齿会在底座下面拖出几个孤零零的点，
    照最后一行量出来只有几个像素宽。
    """
    ys, _ = np.where(opaque)
    bottom = int(ys.max())
    band = range(max(0, bottom - int(opaque.shape[0] * 0.12)), bottom + 1)
    best, best_w = bottom, -1
    for y in band:
        xs = np.where(opaque[y])[0]
        if xs.size and (int(xs.max()) - int(xs.min())) > best_w:
            best, best_w = y, int(xs.max()) - int(xs.min())
    return best


def measure(image: Image.Image):
    """在原图坐标系里量出：缩放比、踩地那一行的位置和左右端。"""
    opaque = np.asarray(image.getchannel("A")) > 8
    row = ground_row(opaque)
    xs = np.where(opaque[row])[0]
    g0, g1 = int(xs.min()), int(xs.max())
    scale = FOOT_CELLS * CELL / (g1 - g0 + 1)
    return scale, row, g0, g1


def to_canvas(image: Image.Image, scale: float, ground_y: int, g0: int) -> Image.Image:
    """按"踩地那一段正好落在占地框上"把整张原图搬到成品画布上。

    用的是仿射变换而不是"裁到内容再缩"：常图和开火图要落在**同一套坐标**里
    才能作差，各自裁各自的框就对不齐了。
    """
    a = np.asarray(image).astype(np.float32) / 255.0
    premul = np.dstack([a[:, :, :3] * a[:, :, 3:4], a[:, :, 3]])   # 预乘，半透明边缘才不插值出黑圈
    out = Image.fromarray((premul * 255.0).astype(np.uint8), "RGBA").transform(
        (CANVAS_W, CANVAS_H), Image.AFFINE,
        # 输出(x',y') → 原图(x,y)：x 让踩地左端落在 FOOTPRINT_LEFT，y 让踩地那一行落在画布底边。
        # y 那一行两个系数都是正的：画布和原图都是"往下 y 变大"，
        # 写成 -1/s 会把整张图翻到画布外面去（踩地那一行跑到画面外，只剩底下一条）。
        (1.0 / scale, 0.0, g0 - FOOTPRINT_LEFT / scale,
         0.0, 1.0 / scale, ground_y - CANVAS_H / scale),
        resample=Image.BICUBIC, fillcolor=(0, 0, 0, 0))

    o = np.asarray(out).astype(np.float32)
    alpha = np.maximum(o[:, :, 3:4], 1e-6) / 255.0
    rgb = np.clip(o[:, :, :3] / 255.0 / alpha, 0.0, 1.0)           # 除回来
    return np.dstack([rgb * 255.0, o[:, :, 3]])


def drop_specks(mask: np.ndarray, min_pixels: int = 24) -> np.ndarray:
    """去掉小于 `min_pixels` 的连通块（八邻域）。

    <b>不能用形态学腐蚀去噪点。</b>试过：光爆是一圈细刺，腐蚀一次整团就没了，
    量出来 0 像素——不是没抠到，是被自己磨掉了。按面积筛才分得清"细长的光刺"
    和"零星噪点"，这两样腐蚀法看成同一种东西。
    """
    h, w = mask.shape
    out = np.zeros_like(mask)
    seen = np.zeros_like(mask)
    for sy in range(h):
        for sx in range(w):
            if not mask[sy, sx] or seen[sy, sx]:
                continue
            blob = []
            queue = deque([(sy, sx)])
            seen[sy, sx] = True
            while queue:
                y, x = queue.popleft()
                blob.append((y, x))
                for ny in (y - 1, y, y + 1):
                    for nx in (x - 1, x, x + 1):
                        if (0 <= ny < h and 0 <= nx < w and mask[ny, nx]
                                and not seen[ny, nx]):
                            seen[ny, nx] = True
                            queue.append((ny, nx))
            if len(blob) >= min_pixels:
                for y, x in blob:
                    out[y, x] = True
    return out


def extract_glow(idle: np.ndarray, fire: np.ndarray) -> np.ndarray:
    """开火图比常图**亮出来**的那一块 = 炮口火焰。返回一张 RGBA 光层。

    亮度取三通道之和（0..765），透明的地方按 0 算——常图那儿是空的，
    开火图那儿有光，差值本来就是"从无到有的这团光"。
    """
    lum_idle = idle[:, :, :3].sum(axis=2) * (idle[:, :, 3] > GLOW_MIN_ALPHA)
    lum_fire = fire[:, :, :3].sum(axis=2) * (fire[:, :, 3] > GLOW_MIN_ALPHA)

    glow = drop_specks(
        ((lum_fire - lum_idle) > GLOW_ADDED) & (fire[:, :, 3] > GLOW_MIN_ALPHA),
        GLOW_MIN_PIXELS)

    alpha = (glow * 255).astype(np.uint8)
    # 羽化一圈：作差出来的边界是硬的，直接上屏会看到锯齿
    alpha = np.asarray(
        Image.fromarray(alpha, "L").filter(ImageFilter.GaussianBlur(1.2)))
    # 颜色取开火图自己的——光是什么颜色，原图上写着
    return np.dstack([fire[:, :, :3], alpha])


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for level in (1, 2, 3):
        idle_src = load_keyed(LEVELS[level])
        scale, ground_y, g0, _ = measure(idle_src)
        idle = to_canvas(idle_src, scale, ground_y, g0)

        # 开火图不能沿用常图量出来的比例尺：两张是各自渲染的，踩地要各量各的。
        # （实测 L1/L2 两张的踩地宽一模一样，L3 差 2.7%——所以这儿不能想当然。）
        fire_src = load_keyed(FIRING[level])
        f_scale, f_ground_y, f_g0, _ = measure(fire_src)
        fire = to_canvas(fire_src, f_scale, f_ground_y, f_g0)

        glow = extract_glow(idle, fire)
        # 整层一起翻（颜色和 alpha 都要翻）。只翻 alpha 的话，
        # 光会被搬到另一端去、颜色却还留在原地，叠上去是一块黑斑——踩过这个坑。
        if level in GLOW_FLIP:
            glow = glow[:, ::-1]

        Image.fromarray(idle.astype(np.uint8), "RGBA").save(
            OUT / f"ballista_l{level}.png")
        Image.fromarray(glow.astype(np.uint8), "RGBA").save(
            OUT / f"ballista_flash_l{level}.png")

        # 验一遍：踩地那一段必须落在占地框上，而且压缩完的成品还得是这个宽度
        opaque = idle[:, :, 3] > 8
        bottom = int(np.where(opaque)[0].max())
        xs = np.where(opaque[bottom])[0]
        print(f"ballista_l{level}.png / ballista_flash_l{level}.png  "
              f"{CANVAS_W}×{CANVAS_H}  比例尺 {scale:.3f}"
              f"  踩地 x {xs.min()}..{xs.max()}（占地框 {FOOTPRINT_LEFT}..{FOOTPRINT_RIGHT}）"
              f"  左右误差 {(xs.min()-FOOTPRINT_LEFT)/CELL:+.2f} / "
              f"{(xs.max()-FOOTPRINT_RIGHT)/CELL:+.2f} 格"
              f"  光层 {(glow[:, :, 3] > 8).sum()} px")
        if max(abs(xs.min() - FOOTPRINT_LEFT), abs(xs.max() - FOOTPRINT_RIGHT)) > 0.15 * CELL:
            print("  ⚠️  踩地和占地框差得有点多，画布宽度或 overhang 该重算")


if __name__ == "__main__":
    main()
