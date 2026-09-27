package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;

import androidx.annotation.Nullable;

import com.mobilegroup20.tokentrail.R;
import com.mobilegroup20.tokentrail.game.engine.BoardGeometry;
import com.mobilegroup20.tokentrail.game.engine.BuildingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 建筑的贴图。<b>还没有贴图的建筑返回 {@code null}，由调用方退回色块占位。</b>
 *
 * <p>现在只有箭塔有图（三个等级三张，见 {@code docs/ART.md} §2.1）；核心和城墙
 * 还是 {@link BattlefieldView} 里画的色块。所以这个类是"能查到就给你、查不到就说没有"，
 * 而不是"每种建筑必须有一张"——美术是一张张补上来的，中间态要能跑。
 *
 * <p>除了塔本身，这里还管<b>开火那一下的炮口火焰</b>（{@link #flash}）。它是**另一张图**，
 * 只画炮口那一块（或者那一整条），运行时盖在塔贴图上——两张图的上下沿是同一个数，
 * 横向按 {@link FlashWindow} 定位。
 *
 * <h2>图为什么必须和占地对上</h2>
 *
 * <p>贴图会被拉进 {@code (cols + overhangLeftCells) × (rows + overhangUpCells)} 格
 * 那个矩形里（见 {@link BattlefieldView#drawBuilding}）。所以<b>图和那个矩形宽高比
 * 不一致的话不会报错，只会被悄悄拉变形</b>——一个画得好好的炮塔被压扁 5%，
 * 在手机上看不出来，但"哪里不对"会一直挥之不去。构造时直接按设计尺寸校一遍，
 * 出图出错了当场炸，和 {@link RockTexture} 校 {@code tile_mountain.png} 是同一个理由。
 *
 * <p>校验用的是 {@link BoardGeometry#DESIGN_CELL_PX}，也就是"一格 256 像素"的
 * 设计基准，不是屏幕上的像素——贴图上屏时按格缩放，和屏幕密度无关。
 */
final class BuildingSprites {

    /**
     * 炮口火焰那张图，在塔贴图画布上占横向的哪一段。
     *
     * <p>用"从贴图左沿往左/往右各多少格"来定义，而不是"炮口在哪、往两边摊多宽"：
     * 箭塔的炮口正好在贴图左沿（{@code left = -1.5}，往左伸出去 1.5 格），
     * 弩车的弩箭在贴图里面（{@code left = 0}，整张就是贴图那么大）——
     * 两个用同一对数字就说得清，多一个"炮口"的概念反而要多解释一次。
     *
     * <p>{@code left} 可以是负的（伸到贴图外面去），{@code right} 最多到
     * {@link BuildingType#spriteWidthCells()}。宽度 = {@code right - left}，
     * 出图尺寸就是这个宽度 × {@code spriteHeightCells()} 格。
     *
     * <p>和抠图脚本里的同名常量是一对，改一边要改另一边——
     * 箭塔是 {@code art/cut_muzzle_flash.py}，弩车是 {@code art/cut_ballista.py}。
     */
    static final class FlashWindow {

        /** 窗口左沿，相对贴图左沿，几格（负数 = 伸到贴图左边外面）。 */
        final float leftCells;

        /** 窗口右沿，同上。 */
        final float rightCells;

        FlashWindow(float leftCells, float rightCells) {
            this.leftCells = leftCells;
            this.rightCells = rightCells;
        }

        /** 窗口几格宽——火焰贴图的宽高比就是拿它算的。 */
        float widthCells() {
            return rightCells - leftCells;
        }
    }

    /**
     * 每一种塔的火焰窗口。没有的那种就是"不开火"（{@link #flash} 返回 {@code null}）。
     *
     * <p><b>箭塔</b>：炮口在贴图左沿，火焰往左吐 1.5 格、往右糊回炮身 0.8 格
     * （那 0.8 格是制退器被烤红的那一圈）。所以窗口比贴图还往左多出一截，
     * {@code left} 是负的。
     *
     * <p><b>弩车</b>：整张火焰图就是贴图那么大（0 到 3.1 格）。
     * 不是懒——弩车的光是"整条弩身过电"，原图里就从弩箭那一端一直烧到弩尾，
     * 硬裁一个小窗口反而要挑一个没有道理的位置下刀。
     */
    private static final Map<BuildingType, FlashWindow> FLASH_WINDOWS =
            new EnumMap<>(BuildingType.class);

    static {
        FLASH_WINDOWS.put(BuildingType.TOWER, new FlashWindow(-1.5f, 0.8f));
        FLASH_WINDOWS.put(BuildingType.BALLISTA, new FlashWindow(0f, 3.1f));
    }

    /** 这一种塔的火焰窗口；不攻击的（墙、核心）返回 {@code null}。 */
    @Nullable
    static FlashWindow flashWindow(BuildingType type) {
        return FLASH_WINDOWS.get(type);
    }

    /** 一种建筑一个数组，下标 = 级数 - 1。没有的等级留着 {@code null}。 */
    private final Map<BuildingType, Bitmap[]> byType = new EnumMap<>(BuildingType.class);

    /** 开火那一下的炮口火焰，和 {@link #byType} 同一个结构、同一个下标规则。 */
    private final Map<BuildingType, Bitmap[]> flashByType = new EnumMap<>(BuildingType.class);

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     * 画火焰的笔，和 {@link #paint} 分开：它的不透明度每帧都在变（火焰是淡出的），
     * 共用一支笔的话就得在每一座塔画完都记得还回去，迟早会漏。
     */
    private final Paint flashPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    BuildingSprites(Context context) {
        // 箭塔三级：一级素色、二级蓝、三级红。三张是同一批图，只有发光件的颜色不同。
        load(context, BuildingType.TOWER, new int[]{
                R.drawable.tower_arrow_l1,
                R.drawable.tower_arrow_l2,
                R.drawable.tower_arrow_l3,
        });
        // 开火另有三级各一张。**它只画炮口那一块**（火焰 + 烤红的制退器），
        // 盖在塔贴图上，不是另一张整塔图——为什么这么做见 art/cut_muzzle_flash.py 开头。
        loadFlashes(context, BuildingType.TOWER, new int[]{
                R.drawable.tower_flash_l1,
                R.drawable.tower_flash_l2,
                R.drawable.tower_flash_l3,
        });

        // 弩车三级：一级素木、二级青晶、三级黄铜。和箭塔同一套等级口径。
        load(context, BuildingType.BALLISTA, new int[]{
                R.drawable.ballista_l1,
                R.drawable.ballista_l2,
                R.drawable.ballista_l3,
        });
        // 弩车的开火图是**整张**（0 到 3.1 格），不是炮口那一小块，见 FLASH_WINDOWS。
        loadFlashes(context, BuildingType.BALLISTA, new int[]{
                R.drawable.ballista_flash_l1,
                R.drawable.ballista_flash_l2,
                R.drawable.ballista_flash_l3,
        });
    }

    private void load(Context context, BuildingType type, int[] resIds) {
        Bitmap[] bitmaps = decode(context, type.label, resIds);
        float want = type.spriteWidthCells() / type.spriteHeightCells();
        for (int i = 0; i < bitmaps.length; i++) {
            checkAspect(type.label + " 第 " + (i + 1) + " 级", bitmaps[i], want,
                    String.format("%.1f 格 × %.1f 格", type.spriteWidthCells(), type.spriteHeightCells()));
        }
        byType.put(type, bitmaps);
    }

    private void loadFlashes(Context context, BuildingType type, int[] resIds) {
        FlashWindow window = flashWindow(type);
        if (window == null) {
            throw new IllegalStateException(type.label + " 没有火焰窗口，不该去加载它的火焰图");
        }

        Bitmap[] bitmaps = decode(context, type.label + "的炮口火焰", resIds);
        // 火焰图和塔贴图**一样高**（上下沿对齐，画的时候用同一个 bottom/top），
        // 所以宽高比拿塔的 spriteHeightCells() 当分母
        float want = window.widthCells() / type.spriteHeightCells();
        for (int i = 0; i < bitmaps.length; i++) {
            checkAspect(type.label + " 第 " + (i + 1) + " 级的炮口火焰", bitmaps[i], want,
                    String.format("%.1f 格 × %.1f 格",
                            window.widthCells(), type.spriteHeightCells()));
        }
        flashByType.put(type, bitmaps);
    }

    private static Bitmap[] decode(Context context, String what, int[] resIds) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // drawable-nodpi 本来就不带密度，但 decodeResource 仍可能按屏幕密度缩，
        // 缩完就和"一格 256"对不上了。自己按格算，不让系统插手。
        options.inScaled = false;

        Bitmap[] bitmaps = new Bitmap[resIds.length];
        for (int i = 0; i < resIds.length; i++) {
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resIds[i], options);
            if (bitmap == null) {
                throw new IllegalStateException(
                        what + " 第 " + (i + 1) + " 张读不出来（resId=" + resIds[i] + "）");
            }
            bitmaps[i] = bitmap;
        }
        return bitmaps;
    }

    /** 贴图的宽高比必须等于它要铺的那个矩形，否则会被拉变形。 */
    private static void checkAspect(String what, Bitmap bitmap, float want, String cells) {
        float got = bitmap.getWidth() / (float) bitmap.getHeight();
        if (Math.abs(got - want) > want * 0.02f) {
            throw new IllegalArgumentException(String.format(
                    "%s 的贴图宽高比应该是 %.2f（%s），实际 %dx%d = %.2f；"
                            + "比例不对不会报错，只会在游戏里被拉变形",
                    what, want, cells, bitmap.getWidth(), bitmap.getHeight(), got));
        }
    }

    /**
     * 这一种、这一级的贴图；没有就返回 {@code null}。
     *
     * <p>等级越界按最近的一级算，和 {@code BuildingStats} 里那几个按级查表的方法
     * 一个口径——那里不为一帧的绘制把整局搞崩，这里也不。
     */
    @Nullable
    Bitmap get(BuildingType type, int level) {
        return pick(byType, type, level);
    }

    /** 开火那一下的炮口火焰；这一种没做就返回 {@code null}（按"不开火"画）。 */
    @Nullable
    Bitmap flash(BuildingType type, int level) {
        return pick(flashByType, type, level);
    }

    @Nullable
    private static Bitmap pick(Map<BuildingType, Bitmap[]> source, BuildingType type, int level) {
        Bitmap[] bitmaps = source.get(type);
        if (bitmaps == null) {
            return null;
        }
        int index = Math.max(1, Math.min(level, bitmaps.length)) - 1;
        return bitmaps[index];
    }

    /** 画贴图用的笔：开双线性过滤，缩放时不会出锯齿。 */
    Paint paint() {
        return paint;
    }

    /** 画炮口火焰用的笔，见 {@link #flashPaint}。 */
    Paint flashPaint() {
        return flashPaint;
    }
}
