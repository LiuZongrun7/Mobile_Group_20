package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;

import com.mobilegroup20.tokentrail.R;

/**
 * 战场地面（可放置区 + 敌人通道）的草地贴图。用法和 {@link RockTexture} 是同一套，
 * 但**一张图要压出两种颜色**，所以这里是两支笔、不是一个。
 *
 * <h2>一张图，两种地形</h2>
 * <p>可放置区的绿和敌人通道的土黄，现在铺的都是<b>同一张</b>
 * {@code tile_grass.png}，只是各配一个颜色滤镜把它压到
 * {@code game_ground_a} / {@code game_entry_lane}。
 * 也就是说"哪块地是什么地形"这个信息<b>没有丢</b>，它从"两种纯色"变成了
 * "一张图上的两个系数"。
 *
 * <h2>棋盘格没了，改用网格线（2026-09-27）</h2>
 * <p>贴图到位之前，可放置区是<b>一格一换的深浅两色</b>，为的是让玩家数得出格子。
 * 换成实拍草地之后这一招失效了：草地自己的明暗起伏（实拍，一格平均下来还有
 * 百分之十几，叶尖和花是白的、叶缝是黑的）把那一档深浅盖掉了。
 * 真机量过——棋盘格确实画上去了（两次构建的差分图是一张干净的棋盘），
 * 台阶约 10~19%，但压在草上就是看不出来。
 *
 * <p><b>与其留一个看不出来的棋盘格，不如把数格子的活交给网格线</b>：
 * 格线是硬边，草的明暗盖不住它。所以这里只剩一支"可放置区"的笔，
 * 深浅那一档去掉了，{@code game_grid_line} 相应加深了一点。
 *
 * <h2>为什么是两支笔</h2>
 * <p>{@link Paint#setColorFilter} 和 {@link Paint#setShader} 都是笔上的状态，
 * 一支笔画两种地形就要在 {@code onDraw} 里来回改。两支笔各自带着自己的系数，
 * 画的时候只是换对象。
 *
 * <h2>锚点和"格"</h2>
 * <p>和岩石那张完全一致：贴图钉在<b>布局坐标的原点</b>，按<b>格</b>算大小，
 * 相机缩放由画布矩阵负责。所以逐格分段画也不会在接缝处断开——
 * 相邻两格拿到的是同一张图的同一块。
 *
 * <h2>平铺用 REPEAT，不是 MIRROR</h2>
 * <p>岩石那张用 {@link Shader.TileMode#MIRROR}，因为它只有 8 格高、而右侧那条山
 * 有 18 格，普通平铺的周期太短。草地<b>不能用镜像</b>：草叶是有方向的，
 * 镜像会在接缝处造出一片左右对称的"蝴蝶"，比重复还扎眼。
 * 换成把周期本身拉长（16 格）＋ 出图时压掉低频，让重复不容易被认出来。
 *
 * <p>原来周期是按<b>横向一屏宽（1260px）和战场高（1665px）</b>定的：16 格在真机上
 * 约 1480px，两个尺度都比它短，所以屏幕上看不到同一个地方出现两次。
 * **2026-09-27 战场翻倍（36 行）又压了界面（一格 26.5dp → 15.8dp）之后，
 * 这条不成立了**：周期跟着格子缩到 **851px**，而屏幕还是 2844px 高，
 * 竖着一屏里能看到 **3.3 个周期**（比原来那版的 1.9 还多）。
 * 现在挡住重复的是出图时那道 {@code flatten}（格间亮度起伏 0.5%），
 * **不是周期长度**。取舍和那张对照表在 {@code art/cut_grass.py} 开头，
 * 重算过的数在 {@code docs/ART.md} §2.5。
 */
final class GrassTexture {

    /** 贴图覆盖的格数。和 {@code tile_grass.png} 是一对，改图要一起改。 */
    private static final int TILE_CELLS = 16;

    private final BitmapShader shader;
    private final Matrix matrix = new Matrix();

    /** 可放置区。 */
    private final Paint buildable = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** 左边那条敌人通道。同一张图压成土色。 */
    private final Paint lane = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** 贴图上一格占多少像素。由图片自己的宽度算，不是写死的常数。 */
    private final float pxPerCell;

    private float cellPx = -1f;

    GrassTexture(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 和 RockTexture 同一个理由：drawable-nodpi 不带密度，但 decodeResource
        // 仍可能按屏幕密度缩一道，而这个类自己按格算尺寸，被缩过就对不上了。
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.tile_grass, options);
        if (bitmap == null) {
            throw new IllegalStateException("tile_grass.png 读不出来");
        }

        // 正方形贴图，宽高必须一样——不等的话草会被悄悄拉长，看不出是 bug。
        if (Math.abs(bitmap.getWidth() - bitmap.getHeight()) > bitmap.getWidth() * 0.02f) {
            throw new IllegalArgumentException(String.format(
                    "tile_grass.png 应该是正方形，实际 %dx%d",
                    bitmap.getWidth(), bitmap.getHeight()));
        }
        pxPerCell = bitmap.getWidth() / (float) TILE_CELLS;

        shader = new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        for (Paint paint : new Paint[] {buildable, lane}) {
            paint.setShader(shader);
        }

        // 贴图是按 game_grass 归一化的（美术出图时就调成那个平均色），
        // 上屏要的是另外两个色。绿的用乘法压下去就够，土的不行，理由见 ramp。
        int base = context.getColor(R.color.game_grass);
        tint(buildable, base, context.getColor(R.color.game_ground_a));
        lane.setColorFilter(new ColorMatrixColorFilter(
                ramp(base, context.getColor(R.color.game_entry_lane))));
    }

    private static void tint(Paint paint, int base, int target) {
        paint.setColorFilter(new PorterDuffColorFilter(ratio(base, target), PorterDuff.Mode.MULTIPLY));
    }

    private static int ratio(int base, int target) {
        return Color.rgb(
                Math.round(255f * Color.red(target) / Color.red(base)),
                Math.round(255f * Color.green(target) / Color.green(base)),
                Math.round(255f * Color.blue(target) / Color.blue(base)));
    }

    /**
     * 压成<b>单一色调</b>的矩阵：先取亮度，再按目标色的比例铺开。
     *
     * <p><b>通道那条为什么要单独一套，不能也用 MULTIPLY。</b>乘法是逐通道各乘各的，
     * 它只在"目标色和基准色色相差不多"时才不露馅——两个绿之间没问题。
     * 土黄和草绿差着一整个色相：草叶的高光是接近白的黄绿（比如 230,240,220），
     * 乘上"红不压、绿蓝都压"的系数之后红就冒出来了，<b>通道会整条变成粉红色</b>。
     * 真机上验过，就是一片鲑鱼粉。
     *
     * <p>所以那条改用矩阵：每个通道都取同一份亮度，再乘各自的系数。色相被
     * 完全丢掉、只留明暗，出来的是一条<b>土色的路</b>——正好是要的效果，
     * 而且草叶的细纹留下来变成了碎石感。
     *
     * <p>系数从资源算，不留魔数：目标通道值 ÷ 基准色的<b>亮度</b>。
     * 这样"改调色板只改 colors.xml"这条规矩在这条通道上也成立。
     */
    private static ColorMatrix ramp(int base, int target) {
        float lum = luminance(base);
        float[] m = new float[20];
        for (int c = 0; c < 3; c++) {
            float k = channel(target, c) / lum;
            m[c * 5] = k * 0.299f;
            m[c * 5 + 1] = k * 0.587f;
            m[c * 5 + 2] = k * 0.114f;
        }
        m[18] = 1f;                      // alpha 原样透传
        return new ColorMatrix(m);
    }

    private static float luminance(int color) {
        return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
    }

    private static int channel(int color, int c) {
        return c == 0 ? Color.red(color) : c == 1 ? Color.green(color) : Color.blue(color);
    }

    /**
     * 一格多少像素。面板尺寸变了就要重调，否则草会和格子对不上。
     *
     * <p>三支笔共用同一个 shader，所以这里改一次三支一起跟上——
     * 这也是"一个 shader、一个矩阵"要单独拿出来的原因。
     */
    void setCellPx(float cellPx) {
        if (Math.abs(cellPx - this.cellPx) < 0.01f) {
            return;
        }
        this.cellPx = cellPx;
        float scale = cellPx / pxPerCell;
        matrix.setScale(scale, scale);
        shader.setLocalMatrix(matrix);
    }

    /** 可放置区。整片同色——格子的边界由网格线说，不由深浅说，见类注释。 */
    Paint buildable() {
        return buildable;
    }

    /** 敌人通道。整条同色，不交替——通道不是"格子"，是路。 */
    Paint lane() {
        return lane;
    }
}
