package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.mobilegroup20.tokentrail.R;
import com.mobilegroup20.tokentrail.game.engine.BoardGeometry;
import com.mobilegroup20.tokentrail.game.engine.Viewport;

/**
 * 游戏页的<b>整页底</b>：一片深色岩石，所有界面元素都浮在它上面。
 *
 * <h2>为什么界面要有"地形底"</h2>
 * <p>规则和战场那边是同一条：<b>不能放东西的地方就是山区</b>。
 * 界面占的那两条（上面的 HUD、下面的按钮和导航）也是不能放东西的地方，
 * 所以它们不该是主题的浅色 surface，而该是同一片山。
 * 这样整页读起来是<b>一张连续的背景图</b>：中间的网格是能打的那块平地，
 * 上下两条是石头，按钮和数字是浮在石头上的东西。
 *
 * <p>比"上面一块白、中间一块绿地、下面一块白"好在哪：后者一眼能看出是
 * "界面 + 嵌在里面的游戏"两层；前者是一整个场景。这是沉浸感的来源。
 *
 * <h2>它跟着战场相机一起动（不是钉死的背景）</h2>
 * <p>这一点是整页分层能不能立住的关键。<b>背景如果不动，拖动时就会露馅</b>——
 * 地图在滑、石头钉在原地，一眼看出石头是"画在界面上的"，不是"世界里的"。
 *
 * <p>所以这里<b>不自己算任何坐标</b>：直接从 {@link BattlefieldView} 那里拿
 * 同一个 {@link Viewport} 和 {@link BoardGeometry}，用和战场
 * {@code onDraw} 里<b>一模一样的三句画布变换</b>：
 *
 * <pre>
 *   canvas.translate(战场面板在屏幕上的位置);
 *   canvas.translate(vp.offsetX(), vp.offsetY());
 *   canvas.scale(vp.zoom(), vp.zoom());
 *   // 之后画的就是布局坐标，和战场里那套完全一样
 * </pre>
 *
 * <p>于是缩放的倍数、平移的位置天然一致，不需要"同步"这回事——
 * 两边本来就是同一个相机拍同一片地。整页（包括 HUD 背后和手势条背后）
 * 都是这张图的一部分。
 *
 * <p><b>斜纹间距按"格"算，不按 dp 算。</b>按 dp 写死的话，放大时格子变大了、
 * 纹理没变大，两边就对不上了。按格算，它就随缩放一起变，和战场右侧那条
 * 山区永远同疏密（那边是一格一道）。
 *
 * <h2>颜色</h2>
 * <p>比战场里的岩石<b>暗一档</b>（{@code game_mountain_deep} vs
 * {@code game_mountain}）：战场是"近处"，要能从背景里跳出来。
 * 这是这一层和战场唯一的区别，纹理本身是一样的。
 *
 * <p>贴图到位之后这个类会被换成一张平铺的岩石贴图
 * （见 {@code docs/ART.md} §4 的"地面 tile·山区暗版"那张），
 * 也就是把 {@link #onDraw} 里那个循环换成 {@code drawBitmap} 重复绘制。
 * 坐标和相机那几句都不用改。
 *
 * <p><b>交接点</b>：这个类是<b>惰性</b>的——相机没接上（布局预览、
 * 或者战场还没量出尺寸）时只铺一层底色，不画纹理。所以它自己不会崩，
 * 但也别指望在 Android Studio 的预览里看到斜纹。
 */
public class MountainBackgroundView extends View {

    /**
     * 斜纹间距，单位是<b>格</b>（不是 dp）。
     *
     * <p>1 格 = 和战场右侧那条山区一样疏密。为什么不是更细的纹理：
     * 更细会好看一点（"远山"的空气透视），但会和战场里的斜纹<b>对不上</b>，
     * 两条边界上会出现疏密不一的接缝。既然这一层是"同一片山铺到屏幕外"，
     * 那就该用同一套纹理——远近靠颜色深浅区分就够了。
     */
    private static final float HATCH_SPACING_CELLS = 1f;

    /** 线宽（dp）。会被相机缩放，和战场里的斜纹一致。 */
    private static final float HATCH_WIDTH_DP = 1.2f;

    private final Paint hatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int baseColor;
    private final float density;

    /** 战场控件：用来算战场面板在屏幕上的位置。 */
    private View panel;

    /** 和战场共用的相机。没接上时为 null，这时只铺底色。 */
    private Viewport viewport;
    private BoardGeometry board;

    /** 复用的两个数组，{@code getLocationInWindow} 会往里写。 */
    private final int[] panelLocation = new int[2];
    private final int[] ownLocation = new int[2];

    public MountainBackgroundView(Context context) {
        this(context, null);
    }

    public MountainBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        density = getResources().getDisplayMetrics().density;
        baseColor = context.getColor(R.color.game_mountain_deep);

        hatch.setColor(context.getColor(R.color.game_mountain_deep_shade));
        hatch.setStrokeWidth(Math.max(1f, HATCH_WIDTH_DP * density));
    }

    // ---- 外面接进来的东西 ----

    /**
     * 告诉这一层：战场面板是哪个控件。
     *
     * <p>要它是因为这一层铺满全屏，而"战场"只是屏幕中间的一块矩形——
     * 画的时候得知道"那个原点在屏幕的哪儿"，才能把同一个相机套上去。
     *
     * <p>传的应该是界面上那个**空占位**（{@code @id/play_area}），不是
     * {@code BattlefieldView} 本身：后者也铺满全屏，用它的位置算出来是 0，
     * 石头会和地错开一整个 HUD 的高度。
     */
    public void setPanel(View panel) {
        this.panel = panel;
        invalidate();
    }

    /**
     * 相机变了。<b>由 {@link BattlefieldView} 在平移/缩放/重新量尺寸时回调</b>，
     * 不是每帧都调——敌人走动不影响相机，那时这一层不需要重画。
     *
     * <p>传 {@code null} 表示还没有相机，退化成只铺一层底色。
     */
    public void setCamera(@Nullable Viewport viewport, @Nullable BoardGeometry board) {
        this.viewport = viewport;
        this.board = board;
        invalidate();
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 底色永远铺满整页，包括还没有相机的那一帧
        canvas.drawColor(baseColor);

        if (viewport == null || board == null || panel == null || panel.getWidth() <= 0) {
            return;
        }

        // 战场面板在本视图里的位置。每次重画时现算：
        // 状态栏高度、HUD 折行都会让这个值变，缓存起来就得再管一套失效逻辑。
        // 这里不是每帧都走（敌人走动时相机没变，不会重画），所以不心疼。
        panel.getLocationInWindow(panelLocation);
        getLocationInWindow(ownLocation);
        float panelLeft = panelLocation[0] - ownLocation[0];
        float panelTop = panelLocation[1] - ownLocation[1];

        float spacing = board.cellPx() * HATCH_SPACING_CELLS;
        if (spacing <= 0f) {
            return;
        }

        canvas.save();

        // ★ 这三句必须和 BattlefieldView.onDraw 里那三句一模一样。
        //   差一句，整页就不再是同一片地了。
        canvas.translate(panelLeft, panelTop);
        canvas.translate(viewport.offsetX(), viewport.offsetY());
        canvas.scale(viewport.zoom(), viewport.zoom());

        // 现在画布是布局坐标。整页四条边换算回布局坐标，就是"这一屏能看到世界的哪一块"
        float left = viewport.toLayoutX(-panelLeft);
        float right = viewport.toLayoutX(w - panelLeft);
        float top = viewport.toLayoutY(-panelTop);
        float bottom = viewport.toLayoutY(h - panelTop);

        // 45° 斜纹是 x + y = 常数 的一族平行线：一条线从 (c − 顶, 顶) 画到 (c − 底, 底)。
        // 起点对齐到 spacing 的整数倍，纹理就<b>钉在世界坐标上</b>——
        // 否则拖动时纹理自己会滑，看起来像背景在追着地图跑。
        float start = (float) Math.floor((left + top) / spacing) * spacing;
        for (float c = start; c <= right + bottom; c += spacing) {
            canvas.drawLine(c - top, top, c - bottom, bottom, hatch);
        }

        canvas.restore();
    }
}
