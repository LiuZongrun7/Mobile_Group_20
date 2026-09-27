#!/usr/bin/env python3
"""把草地原图做成一张**无缝、均匀、可平铺**的地面贴图。

用法（要 Pillow 和 numpy）：

    /tmp/deckvenv/bin/python art/cut_grass.py

读 `art/source/tile_grass_raw.png`（1024×1024），写
`app/src/main/res/drawable-nodpi/tile_grass.png`。

和塔/敌人那批不一样：**这张不是抠出来的**，是一张草地贴图。
没有要抠的东西——要解决的是另外三个问题，按重要性排：

1. **无缝**（`seam_free`）。这一版的原图**本来就是无缝的**，所以这一步现在是
   按量出来的数跳过的，见下。
2. **均匀**（`flatten`）。这是这个脚本里最要紧的一步，见下。
3. **不显重复**。贴图只有一个周期，周期一到就从头来。压掉低频 + 把周期拉到
   比一屏还长之后，眼睛就没有可以拿来认路的东西了。

## 为什么要"压掉低频"——`flatten`

平铺的贴图天然有两个敌人：接缝、和**能认出来的大块结构**。前者靠 `seam_free`，
后者靠这一步。

眼睛认出一块地"又回来了"，靠的不是细纹——草叶在哪儿都一样——而是**大块的明暗**：
这一片亮、那一片暗，两片再出现一次，重复就被看穿了。所以办法是把低频除掉、
只留细纹理：算一张**绕圈**的大半径模糊（半径约 0.6 格）当"这一片该有多亮"，
再把原图按这个基准拉平。

> **这一步原本是为了别的事。** 贴图到位之前，可放置区是一格一换的深浅两色
> 棋盘格，给玩家数格子用——而草地自带的起伏比那一档深浅还大，会把棋盘格盖掉。
> 压低频就是为了把棋盘格救回来。**2026-09-27 结果是没有救回来**：真机上量得出来
> （两次构建的差分图是一张干净的棋盘），看着就是一片没有规律的深浅，于是棋盘格
> 删了、数格子改由网格线管（见 `GrassTexture` 的类注释）。**但压低频这件事留下了**
> ——它的另一个作用才是真正要紧的那个：**让平铺的重复不显**。

**模糊必须绕圈**（`box_blur_wrapped`）：不绕圈的话，接缝处的"这一片该多亮"
是按半张图算的，压完接缝两边亮度对不上，前面 `seam_free` 白做。

## 几个数是量出来的，不是挑出来的

| 数 | 值 | 怎么来的 |
| --- | --- | --- |
| `CROP` | **整张 1024，不裁** | 2026-09-27 换的这张原图**整张都是可用的草地**，没有碎石枯叶，量出来 12 格的格间起伏只有 1.38%（上一张实拍照是 10.4%）。**上一版只裁得出 704**，那才是当时"周期"和"清晰度"打架的根源——现在不打架了 |
| `SEAM_NEEDED` | 1.0 | 补缝之前先量一次"对边差异 ÷ 一般行间差异"，小于这个数就说明**本来就无缝**，跳过 `seam_free`。这张量出来是 0.87 / 0.83，所以跳过了——白补一次只会把中间糊掉一条 |
| `TILE_CELLS` | 16 | 见下 |
| `FLATTEN_CELLS` | 0.6 格 | 0.6 格以下算"细纹理"、以上算"大块明暗"。**半径越小压得越狠**（半径趋近全图 = 不压） |
| `CONTRAST` | 0.85 | 明暗整体往平均色收一收。板上要压建筑和敌人，草太花会抢戏；和 `ART.md` §2.3 给山区定的"低对比"是同一个理由 |
| `DESAT` | 0.30 | 同上，管的是颜色那一半。这张原图**蓝通道只有 32.8**（很艳的绿），降饱和这一步顺便把蓝色抬起来，不然归一化要把它乘 3.6 倍、暗部会蓝得发假 |
| `SOFT` | 0.6px | 只是磨掉噪点。**上一版是 1.2px**（那是给 JPEG 的块状噪点用的），这张是 PNG，磨太狠就把细叶磨没了 |

## `TILE_CELLS = 16` 是按"周期要比一屏长"定的

一屏宽 1260px、战场高 1665px（真机华为 HBN-AL80：一格 92.5px）。
**周期只要超过这个尺度，屏幕上就不会同时看到同一个地方两次**：

| 周期 | 一格多少源像素 | 1× 缩放 | 2× 缩放 | 周期（设备px） | 一屏宽几个周期 | 战场高几个周期 |
| --- | --- | --- | --- | --- | --- | --- |
| 12 格 | 85.3 | 1.08× | 2.17× | 1110 | 1.14 | 1.50 |
| **16 格** | **64.0** | **1.45×** | **2.89×** | **1480** | **0.85** | **1.12** |
| 20 格 | 51.2 | 1.81× | 3.61× | 1850 | 0.68 | 0.90 |

**为什么不吃 12 格那版更清楚的（1× 只拉 1.08 倍，几乎原像素上屏）**：
模拟上屏看过，12 格时**竖着一屏能装 1.5 个周期**，原图里那道斜向的浅色条纹
（割草的痕迹）在画面上会**出现两次**，看得见。战场纵向比一屏宽还长，
所以纵向才是重复最容易露出来的那一轴。16 格把它压到 1.12 个周期——
"第二遍"刚好落到画面外。代价是 1× 拉 1.45 倍（比上一版那张实拍照的 1.58 倍还好一点）。
再往长走到 20 格（0.90 个周期）就要拉到 1.81 倍，草叶开始糊，不值。

**要既长又清楚，只能换更大的原图**——这一版已经用满了 1024，没有余量了。

## 颜色：归一化到 `game_grass`

出图把平均色调到 `colors.xml` 里的 `game_grass`，上屏前压到
`game_ground_a`（绿）和 `game_entry_lane`（土）。中间多这一道和山区那张是
同一个理由：**改配色只改 `colors.xml`，图不用重出**，所以 `game_grass`
是个纯内部值，它不上屏。压法两条不一样（绿用 MULTIPLY，土用 ColorMatrix），
理由见 `GrassTexture.ramp`。

**能这么干的前提是"平均色"被精确控制住了**——`normalize` 是最后一步，
前面不管怎么折腾（去低频、降饱和）都在它之前。中间插一步忘了重新归一化，
上屏的绿就会偏掉，而且偏得不明显、很难查。
"""

import pathlib

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "source"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# 裁切框：这一版的原图整张都可用，所以不裁。理由见模块开头的表。
CROP_LEFT, CROP_TOP, CROP_PX = 0, 0, 1024

# 贴图铺满几格 × 几格。改这个数要同时改 `GrassTexture.TILE_CELLS`。
TILE_CELLS = 16

# 补缝之前先量一次接缝比（对边差异 ÷ 一般行间差异）。小于这个数就不补——
# 原图本来无缝的话，补一次只会白白把中间糊掉一条。这张量出来是 0.87 / 0.83。
SEAM_NEEDED = 1.0

# 补缝：把图平移半张（原图的四条边在中间相遇），再用一份错开的副本
# 在中间那条带上交叉淡化。带宽和错开量都是图宽的几分之几，不是固定像素——
# 换一张图重跑，比例不变。
SEAM_SHIFT = 0.21
SEAM_BAND = 0.20

# 压低频的模糊半径，单位是"格"。
FLATTEN_CELLS = 0.6

# 去饱和：往灰度靠多少。0 是原样，1 是全灰。
DESAT = 0.30

# 整体对比度：明暗往平均色收多少。1 是原样、0 是全平。
#
# **这一档是拿"棋盘格还看不看得出来"定出来的**，不是为了好看。草地上压着
# 一格一换的棋盘格（玩家数格子用），而实拍草的明暗起伏本来就比那 10% 的台阶大，
# 不收对比度的话棋盘格会被草自己的斑驳整个盖掉——量过：整幅对比度 1.0 时
# 格间随机起伏 ±22%，棋盘格台阶只有 10%，屏幕上就是一片看不出规律的深浅。
CONTRAST = 0.85

# 磨掉噪点用的高斯半径（px）。这一版是 PNG、没有块状噪点，所以比上一版轻。
SOFT = 0.6

LUM = np.array([0.299, 0.587, 0.114], np.float32)

# 真机上一格多少设备像素（华为 HBN-AL80，面板 27.4dp × 3.375）。
# 只用来打印"上屏会被缩放多少倍"，不参与出图。
DEVICE_CELL = 92.5


def box_blur_wrapped(t: np.ndarray, r: int) -> np.ndarray:
    """绕圈的盒式模糊：越界的地方接着对面取，模糊完照样无缝。

    做法是把图摞三份做前缀和、取中间那份的窗口，绕回来的部分由上下两份接上。
    **直接对下标取模再相减是不行的**：窗口一绕圈，"右端"就比"左端"小了，
    减出来是负数——第一版就是这么写的，压完低频格间起伏反而涨到 127%。

    只对二维图（灰度）用，两个轴各来一次。
    """
    n = t.shape[0]
    out = t
    for axis in (0, 1):
        tri = np.concatenate([out, out, out], axis=axis)
        pad = np.zeros_like(np.take(tri, [0], axis=axis))
        c = np.cumsum(np.concatenate([pad, tri], axis=axis), axis=axis)
        i = np.arange(n)
        out = (np.take(c, n + i + r + 1, axis=axis)
               - np.take(c, n + i - r, axis=axis)) / (2 * r + 1)
    return out


def seam_ratio(t: np.ndarray) -> float:
    """接缝比：对边之间的差异 ÷ 一般两行之间的差异。

    1.0 是"对边和随便两行一样像"——也就是**本来就无缝**。大于 1 才值得补。
    这个数要在补缝**之前**量，补完再量当然就好了。
    """
    ref = max(float(np.abs(t[0] - t[t.shape[0] // 4]).mean()), 1e-6)
    return max(float(np.abs(t[0] - t[-1]).mean()),
               float(np.abs(t[:, 0] - t[:, -1]).mean())) / ref


def seam_free(crop: np.ndarray) -> np.ndarray:
    """把四条边挪到中间去，再补掉中间那道缝，得到一张数学上无缝的图。

    平移半张之后，原图的左边和右边在图像**中间**相遇——那里本来就不该有接缝，
    因为它们是同一张图的边。同理上下。补缝带是一条 smoothstep 的羽化，
    模糊半径小、带宽窄；宽了平铺以后会留下一道看得见的暗带（山区那张踩过）。
    """
    n = crop.shape[0]
    half = n // 2
    shift = int(n * SEAM_SHIFT)
    band = n * SEAM_BAND

    a = np.roll(crop, (half, half), axis=(0, 1)).astype(np.float32)

    def blend(img, other, axis):
        idx = np.arange(n)
        w = np.clip(1.0 - np.abs(idx - half) / (band / 2.0), 0.0, 1.0)
        w = w * w * (3 - 2 * w)                      # smoothstep
        if axis == 1:
            return img * (1 - w)[None, :, None] + other * w[None, :, None]
        return img * (1 - w)[:, None, None] + other * w[:, None, None]

    b = blend(a, np.roll(a, (0, shift), axis=(0, 1)), 1)   # 先竖缝
    return np.clip(blend(b, np.roll(b, (shift, 0), axis=(0, 1)), 0), 0, 255)


def flatten(t: np.ndarray, radius: int) -> np.ndarray:
    """除掉低频亮度，只留细纹理。理由和量到的数见模块开头。"""
    low = np.maximum(box_blur_wrapped(t @ LUM, radius), 8.0)
    # 按"这一片该有多亮"反向缩放三个通道。**除的是同一个数**，
    # 所以色相和饱和度原样保留，只是明暗被拉平。
    return t * (low.mean() / low)[:, :, None]


def soften(t: np.ndarray, sigma: float) -> np.ndarray:
    """小半径高斯，磨掉 JPEG 的块状噪点。

    用 `np.roll` 加权求和而不是 `np.convolve`：`roll` 本来就是绕圈的，
    接缝天然保住；`convolve` 得自己补边界，补错一次前面就白做了。
    核只有 7 个点，两轴各滚 7 次，慢不到哪去。
    """
    k = np.arange(-int(3 * sigma), int(3 * sigma) + 1)
    w = np.exp(-0.5 * (k / sigma) ** 2)
    w /= w.sum()
    for axis in (0, 1):
        acc = np.zeros_like(t)
        for offset, weight in zip(k, w):
            acc += float(weight) * np.roll(t, int(offset), axis=axis)
        t = acc
    return t


def normalize(t: np.ndarray, target: np.ndarray) -> np.ndarray:
    """把平均色拉到 target。**必须是最后一步**，理由见模块开头。

    **不能拿 `target / 当前平均` 一把乘完**：草叶的高光是纯白，乘上去会有一批
    像素顶到 255 被裁掉，裁掉的那部分平均值就补不回来了——第一版这么写，
    出来的绿比目标暗 7%（199 → 184）。看着"差不多"，但这一版整个上屏颜色
    都建在"平均值正好等于目标"上，差 7% 就是整块地偏暗 7%，而且很难查。

    所以改成**逐通道二分**找一个倍数，使"裁完之后"的平均值正好落在目标上。
    单调、必然收敛，十几步就到小数点后几位。
    """
    out = np.empty_like(t)
    for c in range(3):
        lo, hi = 0.0, 4.0
        while np.clip(t[:, :, c] * hi, 0, 255).mean() < target[c]:
            hi *= 2                      # 目标比预想的亮:先把上界撑开
        for _ in range(60):
            mid = (lo + hi) / 2
            if np.clip(t[:, :, c] * mid, 0, 255).mean() < target[c]:
                lo = mid
            else:
                hi = mid
        out[:, :, c] = np.clip(t[:, :, c] * (lo + hi) / 2, 0, 255)
    return out


def cell_spread(t: np.ndarray) -> float:
    """一格一格取平均之后，格与格之间的亮度起伏（百分比）。

    这就是"平铺的重复显不显"的那个数：一格的尺度上还剩多少明暗差别。
    越小越均匀，眼睛越没有可以认路的东西。压低频之前约 10%，
    之后要做到 4% 以下。
    """
    k = t.shape[0] // TILE_CELLS
    small = t[:k * TILE_CELLS, :k * TILE_CELLS].reshape(
        TILE_CELLS, k, TILE_CELLS, k, 3).mean((1, 3))
    lum = small @ LUM
    return float(lum.std() / lum.mean() * 100)


def game_color(name: str) -> np.ndarray:
    """从 `colors.xml` 里读一个 `game_*` 颜色——调色板的唯一出处。"""
    import re
    xml = (ROOT / "app" / "src" / "main" / "res" / "values" / "colors.xml").read_text()
    m = re.search(r'<color name="%s">#([0-9A-Fa-f]{6})</color>' % name, xml)
    if not m:
        raise SystemExit("colors.xml 里没有 %s" % name)
    v = m.group(1)
    return np.array([int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16)], np.float32)


def main() -> None:
    src = np.asarray(Image.open(SRC / "tile_grass_raw.png").convert("RGB")).astype(np.float32)
    crop = src[CROP_TOP:CROP_TOP + CROP_PX, CROP_LEFT:CROP_LEFT + CROP_PX]
    assert crop.shape == (CROP_PX, CROP_PX, 3), crop.shape

    target = game_color("game_grass")

    # 补缝之前先量一次：本来就无缝就跳过，别白糊中间一条。理由见常量表。
    before = seam_ratio(crop)
    t = normalize(seam_free(crop) if before > SEAM_NEEDED else crop, target)
    raw_spread = cell_spread(t)
    t = flatten(t, int(CROP_PX / TILE_CELLS * FLATTEN_CELLS))
    t = soften(t, SOFT)
    mean = t.reshape(-1, 3).mean(0)
    t = mean + (t - mean) * CONTRAST
    gray = (t @ LUM)[:, :, None]
    t = normalize(t * (1 - DESAT) + gray * DESAT, target)
    final_spread = cell_spread(t)

    # 出图和上屏之间没有别的缩放，所以这里就是最终像素。
    Image.fromarray(np.clip(t, 0, 255).astype(np.uint8)).save(OUT / "tile_grass.png")

    # 验三件事：平均色正好是 game_grass（乘完系数才等于 game_ground_a）、
    # 平铺的重复不显、每条边和对面接得上（接缝比，1.0 = 无缝）。
    mean = t.reshape(-1, 3).mean(0)

    print(f"tile_grass.png  {CROP_PX}×{CROP_PX}  "
          f"{TILE_CELLS}×{TILE_CELLS} 格  {CROP_PX / TILE_CELLS:.1f} px/格")
    print(f"  平均色 {mean.round(1)}  归一化目标 game_grass {target}")
    print(f"  格间亮度起伏：压低频前 {raw_spread:.1f}%  →  出图 {final_spread:.1f}%"
          f"   （越小越均匀，平铺的重复越不显）")
    # 周期落在"一屏宽 1260px / 战场高 1665px"的什么位置——见模块开头的取舍表。
    print(f"  周期 {DEVICE_CELL * TILE_CELLS:.0f}px"
          f"（一屏宽 {1260 / (DEVICE_CELL * TILE_CELLS):.2f} 个、"
          f"战场高 {1665 / (DEVICE_CELL * TILE_CELLS):.2f} 个周期）")
    print(f"  接缝比 补前 {before:.2f} → 补后 {seam_ratio(t):.2f}"
          f"   （1.0 = 无缝；补前就小于 {SEAM_NEEDED} 的话这一步是跳过的）")
    # 92.5 / 185 是真机上"1× / 2× 时一格多少设备像素"（见模块开头的表）。
    print(f"  上屏：1× 缩放 {DEVICE_CELL * TILE_CELLS / CROP_PX:.2f}×，"
          f"2× 缩放 {DEVICE_CELL * 2 * TILE_CELLS / CROP_PX:.2f}×")


if __name__ == "__main__":
    main()
