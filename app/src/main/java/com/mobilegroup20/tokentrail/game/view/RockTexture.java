package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;

import com.mobilegroup20.tokentrail.R;

/**
 * 山区岩石贴图，{@link BattlefieldView} 和 {@link MountainBackgroundView} 共用。
 *
 * <h2>整页只有一个色</h2>
 * <p>"不能放东西的地方就是山区"——战场右侧那条山、HUD 背后、按钮背后，
 * 是<b>同一片山</b>，所以就该是<b>同一个颜色</b>。这里只加载一张
 * {@code tile_mountain.png}，只配一个 {@link Paint}。
 *
 * <p>一度分成"近处亮一档／远处暗一档"两支笔：理由是战场要能从背景里跳出来。
 * 放到真机上看是错的——右侧那条的亮度是 115，别处是 74～79，<b>差 35 还多，
 * 而且跟草地（99～118）几乎一样亮</b>。结果是那条石头像贴在右边的一条亮带，
 * 跟上下两条断开，战场右边界反而糊掉了。要"跳出来"的是<b>草地</b>，
 * 不是山——山本来就该连成一片。
 *
 * <h2>锚点和"格"</h2>
 * <p>贴图钉在<b>布局坐标的原点</b>（内容盒左上角），不是屏幕坐标。所以拖动地图时
 * 石头跟着一起走，不会出现"地图在滑、石头钉在屏幕上"那种一眼假的效果。
 *
 * <p>贴图按<b>格</b>算大小，不按 dp 也不按像素：{@link #TILE_CELLS_W} ×
 * {@link #TILE_CELLS_H} 格，每格多少像素由 {@link #setCellPx} 决定。相机缩放不用管，
 * 那由画布矩阵负责。
 *
 * <p><b>一张图还顺带解决了接缝。</b>两处分别出图的话，交界处纹理必然对不齐，
 * 得靠"两边斜纹间距一样"这种约定去凑（这一版之前就是这么干的）。同一张图
 * 同一个锚点铺出来，交界处天然是连续的——本来就不该有交界。
 *
 * <h2>为什么要 MIRROR</h2>
 * <p>贴图竖向只有 {@value #TILE_CELLS_H} 格，而战场右侧那条山是 18 格高的连续竖条——
 * 平铺的话会看到两次多重复，一簇一簇的大石头排队往下走，很扎眼。
 * {@link Shader.TileMode#MIRROR} 让相邻两块互为镜像，<b>周期直接翻倍</b>，
 * 而仓库里不用多存一张两倍大的图（那会是 16MB），石头纹理镜像也看不出破绽。
 */
final class RockTexture {

    /** 贴图覆盖的格数。和 {@code tile_mountain.png} 是一对，改图要一起改。 */
    private static final int TILE_CELLS_W = 16;
    private static final int TILE_CELLS_H = 8;

    private final BitmapShader shader;
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** 贴图上一格占多少像素。由图片自己的宽度算，不是写死的常数。 */
    private final float pxPerCell;

    private float cellPx = -1f;

    RockTexture(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // drawable-nodpi 本来就不带密度，但 decodeResource 仍可能按屏幕密度缩——
        // 这个类自己按格算尺寸，被系统缩一道就对不上了。
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.tile_mountain, options);
        if (bitmap == null) {
            throw new IllegalStateException("tile_mountain.png 读不出来");
        }

        pxPerCell = bitmap.getWidth() / (float) TILE_CELLS_W;
        float expected = pxPerCell * TILE_CELLS_H;
        if (Math.abs(bitmap.getHeight() - expected) > expected * 0.02f) {
            // 长了或扁了都不会报错，只会让石头被悄悄拉变形——那是最难查的一类问题。
            throw new IllegalArgumentException(String.format(
                    "tile_mountain.png 的宽高比应该是 %d:%d，实际 %dx%d（按 %d 格宽算，高应约 %.0f）",
                    TILE_CELLS_W, TILE_CELLS_H, bitmap.getWidth(), bitmap.getHeight(),
                    TILE_CELLS_W, expected));
        }

        shader = new BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR);
        paint.setShader(shader);

        // 贴图是按 game_mountain 归一化的（美术出图时就调成那个平均色），
        // 上屏要的是 game_mountain_deep，所以乘一个系数压下去。
        // MULTIPLY 只能压暗，系数 = 目标 / 基准。两个颜色都从资源取，
        // 调色板改了这里自动跟上，不留魔数。
        int base = context.getColor(R.color.game_mountain);
        int target = context.getColor(R.color.game_mountain_deep);
        paint.setColorFilter(new PorterDuffColorFilter(
                ratio(base, target), PorterDuff.Mode.MULTIPLY));
    }

    private static int ratio(int base, int target) {
        return Color.rgb(
                Math.round(255f * Color.red(target) / Color.red(base)),
                Math.round(255f * Color.green(target) / Color.green(base)),
                Math.round(255f * Color.blue(target) / Color.blue(base)));
    }

    /**
     * 一格多少像素。面板尺寸变了就要重调，否则石头会和格子对不上。
     *
     * <p>重复调用同一个值不会做任何事——两处都可能在布局阶段被反复通知。
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

    /** 铺石头用的笔。整页就这一支，战场和背景拿到的是同一个对象。 */
    Paint paint() {
        return paint;
    }
}
