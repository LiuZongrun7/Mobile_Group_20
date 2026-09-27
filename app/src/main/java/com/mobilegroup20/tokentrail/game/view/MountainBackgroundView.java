package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Canvas;
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
 * <h2>纹理</h2>
 * <p>铺的是 {@link RockTexture} 那张岩石贴图，和战场右侧那条山<b>同一张图、
 * 同一个锚点、同一个颜色</b>——两边拿的就是同一个 {@code Paint} 对象，
 * 所以战场上那条山和这里的背景是<b>一片连续不断的石头</b>，中间没有缝、
 * 也没有色差。锚点钉在布局坐标上，跟着相机一起动。
 *
 * <p>于是"战场右边界在哪"由<b>草地到石头的界线</b>来说，不由颜色深浅来说。
 * 这样整页是<b>一张石头底 + 中间一块草地</b>，而不是"暗底 + 嵌一块亮矩形"。
 *
 * <p><b>交接点</b>：这个类是<b>惰性</b>的——相机没接上（布局预览、
 * 或者战场还没量出尺寸）时只铺一层底色，不画纹理。所以它自己不会崩，
 * 但也别指望在 Android Studio 的预览里看到石头。
 */
public class MountainBackgroundView extends View {

    private final RockTexture rock;
    private final int baseColor;

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

        baseColor = context.getColor(R.color.game_mountain_deep);
        rock = new RockTexture(context);
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
        if (board != null) {
            rock.setCellPx(board.cellPx());
        }
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

        if (board.cellPx() <= 0f) {
            return;
        }

        canvas.save();

        // ★ 这三句必须和 BattlefieldView.onDraw 里那三句一模一样。
        //   差一句，整页就不再是同一片地了。
        canvas.translate(panelLeft, panelTop);
        canvas.translate(viewport.offsetX(), viewport.offsetY());
        canvas.scale(viewport.zoom(), viewport.zoom());

        // 现在画布是布局坐标，贴图就铺在这套坐标上：视口以外也照铺，
        // 反正整页（HUD 背后、手势条背后）都是这张图的一部分。
        // 用 drawPaint 而不是算四条边画矩形——贴图本来就无限平铺，
        // 这里要的就是"把当前画布铺满"。
        canvas.drawPaint(rock.paint());

        canvas.restore();
    }
}
