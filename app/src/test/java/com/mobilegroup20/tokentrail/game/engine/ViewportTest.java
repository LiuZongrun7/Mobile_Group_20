package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link Viewport} 的测试。
 *
 * <p>缩放和拖动是最容易写错的部分：焦点缩放差一个符号、拖动范围没夹住，
 * 在真机上试要试很久才看得出来。这里把它变成一段能跑的算术。
 *
 * <p>用的面板尺寸取典型大屏手机的战场面板：387×643dp @ 2.625。
 */
public class ViewportTest {

    private static final float PANEL_W = 387f * 2.625f;
    private static final float PANEL_H = 643f * 2.625f;
    private static final float EPS = 1e-3f;

    private static Viewport viewport() {
        return new Viewport(PANEL_W, PANEL_H);
    }

    // ---- 默认状态 ----

    /** 不放大时两个坐标系重合：画面和没有缩放功能时一模一样。 */
    @Test
    public void atDefaultZoomLayoutAndScreenCoincide() {
        Viewport v = viewport();

        assertEquals(Viewport.MIN_ZOOM, v.zoom(), EPS);
        assertEquals(0f, v.panX(), EPS);
        assertEquals(0f, v.panY(), EPS);
        assertEquals(123f, v.toScreenX(123f), EPS);
        assertEquals(444f, v.toLayoutX(444f), EPS);
        assertEquals(200f, v.toScreenY(200f), EPS);
    }

    /** 没放大时拖不动——能拖的范围正好是 0。 */
    @Test
    public void cannotPanWhileNotZoomed() {
        Viewport v = viewport();
        v.panBy(10_000f, -10_000f);

        assertEquals(0f, v.panX(), EPS);
        assertEquals(0f, v.panY(), EPS);
    }

    /** 缩回来的同时拖动范围也收回去，不会留下一个偏掉的地图。 */
    @Test
    public void zoomingBackOutResetsThePan() {
        Viewport v = viewport();
        v.setZoom(2f);
        v.panBy(500f, 500f);
        assertTrue(v.panX() > 0f);

        v.setZoom(1f);
        assertEquals(0f, v.panX(), EPS);
        assertEquals(0f, v.panY(), EPS);
    }

    // ---- 缩放范围 ----

    @Test
    public void zoomIsClampedToTheSupportedRange() {
        Viewport v = viewport();

        v.setZoom(0.5f);
        assertEquals(Viewport.MIN_ZOOM, v.zoom(), EPS);

        v.setZoom(99f);
        assertEquals(Viewport.MAX_ZOOM, v.zoom(), EPS);

        v.reset();
        assertEquals(1f, v.zoom(), EPS);
    }

    /** 最大放大倍数和贴图尺寸基准是配套的，别在没改素材的情况下调大。 */
    @Test
    public void maxZoomStaysInTheRangeTheArtWasDesignedFor() {
        assertTrue("放大到 2 倍以上就要重新画素材（见 ART.md）",
                Viewport.MAX_ZOOM <= 2f + EPS);
        assertTrue(Viewport.MAX_ZOOM > 1f);
    }

    // ---- 拖动范围 ----

    @Test
    public void panIsClampedToTheZoomedOutAmount() {
        Viewport v = viewport();
        v.setZoom(2f);

        assertEquals(PANEL_W / 2f, v.maxPanX(), EPS);
        assertEquals(PANEL_H / 2f, v.maxPanY(), EPS);

        v.panBy(1_000_000f, 1_000_000f);
        assertEquals(PANEL_W / 2f, v.panX(), EPS);
        assertEquals(PANEL_H / 2f, v.panY(), EPS);

        v.panBy(-1_000_000f, -1_000_000f);
        assertEquals(-PANEL_W / 2f, v.panX(), EPS);
        assertEquals(-PANEL_H / 2f, v.panY(), EPS);
    }

    /**
     * <b>拖不出空白。</b>不管拖到哪个角，可见的布局范围都落在
     * [0, 面板宽] × [0, 面板高] 之内——画面上不会出现战场以外的空白。
     */
    @Test
    public void theViewNeverLeavesThePanel() {
        float[] zooms = {1f, 1.3f, 1.7f, 2f};
        float[] pans = {-1_000_000f, -300f, 0f, 300f, 1_000_000f};

        for (float zoom : zooms) {
            for (float pan : pans) {
                Viewport v = viewport();
                v.setZoom(zoom);
                v.panBy(pan, pan);

                assertTrue("左上角跑出去了: " + v, v.toLayoutX(0f) >= -EPS);
                assertTrue("左上角跑出去了: " + v, v.toLayoutY(0f) >= -EPS);
                assertTrue("右下角跑出去了: " + v, v.toLayoutX(PANEL_W) <= PANEL_W + EPS);
                assertTrue("右下角跑出去了: " + v, v.toLayoutY(PANEL_H) <= PANEL_H + EPS);
            }
        }
    }

    /** 放大后可见范围确实变小了：放大 2 倍只看得到一半。 */
    @Test
    public void zoomingInHalvesTheVisibleArea() {
        Viewport v = viewport();
        v.setZoom(2f);

        float visibleWidth = v.toLayoutX(PANEL_W) - v.toLayoutX(0f);
        assertEquals(PANEL_W / 2f, visibleWidth, EPS);
    }

    // ---- 焦点缩放 ----

    /**
     * <b>手指在哪儿捏，哪儿就停在原地。</b>这条挂了的话，捏合时地图会从手底下溜走。
     */
    @Test
    public void zoomAtKeepsTheFocalPointUnderTheFinger() {
        Viewport v = viewport();
        float focusX = PANEL_W * 0.25f;
        float focusY = PANEL_H * 0.4f;
        float layoutXBefore = v.toLayoutX(focusX);
        float layoutYBefore = v.toLayoutY(focusY);

        v.zoomAt(focusX, focusY, 1.3f);

        assertEquals(1.3f, v.zoom(), EPS);
        assertEquals("焦点横向没停住", focusX, v.toScreenX(layoutXBefore), 0.01f);
        assertEquals("焦点纵向没停住", focusY, v.toScreenY(layoutYBefore), 0.01f);
    }

    /**
     * <b>已经拖过之后再捏，焦点还得停在原地。</b>
     *
     * <p>这是上面那条最容易漏掉的情况：焦点缩放只在 pan 为 0 时才"碰巧"对，
     * 一旦先把画面拖偏再捏，反解平移量时如果把旧 pan 也减掉一次，
     * 画面会先跳回中间才开始缩放。真机上拖到边上再捏就一眼看得出来，
     * 所以这里专门从"拖过"的状态开始。
     */
    @Test
    public void zoomAtKeepsTheFocalPointAfterPanning() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        v.setZoom(1.5f);
        v.panBy(-400f, 200f);            // 先拖偏：这一版纵向放大后是拖得动的
        assertTrue("前提：这时确实偏了", Math.abs(v.panY()) > 1f);

        float focusX = PANEL_W * 0.35f;
        float focusY = PANEL_H * 0.6f;
        float layoutXBefore = v.toLayoutX(focusX);
        float layoutYBefore = v.toLayoutY(focusY);

        v.zoomAt(focusX, focusY, 1.2f);

        assertEquals("焦点横向没停住（旧 pan 被减了两次）",
                focusX, v.toScreenX(layoutXBefore), 0.01f);
        assertEquals("焦点纵向没停住（旧 pan 被减了两次）",
                focusY, v.toScreenY(layoutYBefore), 0.01f);
    }

    /**
     * "放大 → 拖 → 再放大 → 缩回去"应该原样回到原处。
     *
     * <p>这是玩家最常做的一串操作。判据是精确的：同一个焦点放大 {@code f} 倍、
     * 再缩小 {@code 1/f} 倍，缩放倍数和平移量都必须<b>一模一样</b>地回到起点。
     * 中间少算或多算一次平移，回程就对不上——而"闪到上下中间"正是旧 pan
     * 被减了两遍时解出来的值，所以这条能直接钉住那个现象。
     */
    @Test
    public void zoomingInAndBackOutReturnsToTheSameView() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        v.setZoom(1.4f);
        v.panBy(0f, 1_000_000f);                     // 纵向拖到最边上，错得最明显
        float panXBefore = v.panX();
        float panYBefore = v.panY();

        float focusX = PANEL_W / 2f;
        float focusY = PANEL_H / 2f;
        v.zoomAt(focusX, focusY, 1.2f);
        v.zoomAt(focusX, focusY, 1f / 1.2f);

        assertEquals("缩放倍数没回到起点", 1.4f, v.zoom(), EPS);
        assertEquals("横向平移没回到起点", panXBefore, v.panX(), 0.01f);
        assertEquals("纵向平移没回到起点", panYBefore, v.panY(), 0.01f);
    }

    /** 连续捏合：倍数相乘，焦点一直停住。 */
    @Test
    public void repeatedZoomAtCompoundsTheFactor() {
        Viewport v = viewport();
        float focusX = PANEL_W / 2f;
        float focusY = PANEL_H / 2f;

        v.zoomAt(focusX, focusY, 1.2f);
        v.zoomAt(focusX, focusY, 1.2f);

        assertEquals(1.44f, v.zoom(), 0.01f);
        // 焦点在面板正中，所以平移量应该一直是 0
        assertEquals(0f, v.panX(), EPS);
    }

    /**
     * 焦点在边角、又捏得很大时，拖动范围会先被夹住——
     * 这时候保住"不出现空白"比保住焦点位置重要。
     */
    @Test
    public void zoomAtTheCornerClampsAndStillShowsTheBoard() {
        Viewport v = viewport();

        v.zoomAt(0f, 0f, 10f);

        assertEquals(Viewport.MAX_ZOOM, v.zoom(), EPS);
        assertEquals(v.maxPanX(), v.panX(), EPS);
        assertEquals(v.maxPanY(), v.panY(), EPS);
        assertTrue(v.toLayoutX(0f) >= -EPS);
        assertTrue(v.toLayoutY(0f) >= -EPS);
    }

    // ---- 和 BoardGeometry 合起来用 ----

    /**
     * 放大后<b>依然能点准格子</b>：屏幕上的点先过相机换算回布局坐标，
     * 再交给 {@link BoardGeometry} 找格子。
     *
     * <p>渲染和触摸都走这条路。放大后点错格是这类代码最典型的 bug，
     * 所以这里把全流程串起来测。
     */
    @Test
    public void tapsStillFindTheRightCellWhenZoomedAndPanned() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        v.setZoom(1.8f);
        v.panBy(150f, -220f);

        for (int col = 0; col < g.cols(); col++) {
            for (int row = 0; row < g.rows(); row++) {
                // 格子中心在屏幕上的位置
                float screenX = v.toScreenX(g.centerX(col));
                float screenY = v.toScreenY(g.centerY(row));
                if (screenX < 0f || screenX > PANEL_W || screenY < 0f || screenY > PANEL_H) {
                    continue; // 这一格当前不在屏幕上
                }

                // 换算回布局坐标，再找格子
                assertEquals("列 " + col, col, g.colAt(v.toLayoutX(screenX)));
                assertEquals("行 " + row, row, g.rowAt(v.toLayoutY(screenY)));
            }
        }
    }

    /** 放大 2 倍后，相邻两个格子在屏幕上的间距也正好变成 2 倍。 */
    @Test
    public void cellSpacingOnScreenScalesWithZoom() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        float atOne = v.toScreenX(g.centerX(1)) - v.toScreenX(g.centerX(0));
        assertEquals(g.cellPx(), atOne, EPS);

        v.setZoom(2f);
        float atTwo = v.toScreenX(g.centerX(1)) - v.toScreenX(g.centerX(0));
        assertEquals(g.cellPx() * 2f, atTwo, EPS);
    }

    /** 贴图缩放系数要把相机缩放算进去，否则地图放大了贴图不跟着放大。 */
    @Test
    public void spriteScaleIncludesTheZoom() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);

        // 一格 88.84px，素材按 256px 画，所以放到最大也才 0.69
        assertEquals(0.347f, g.spriteScale(1f), 0.001f);
        assertEquals(0.694f, g.spriteScale(Viewport.MAX_ZOOM), 0.001f);
        // 放到最大也还是缩小显示，素材不用再画大
        assertTrue(g.spriteScale(Viewport.MAX_ZOOM) < 1f);
    }

    // ---- 内容和面板不一样大 ----

    /** 内容比面板矮（战场就是这种情况）：居中，上下各留一点，拖不动。 */
    @Test
    public void contentShorterThanThePanelStaysCentred() {
        float contentHeight = PANEL_H * 0.9f;
        Viewport v = new Viewport(PANEL_W, PANEL_H, PANEL_W, contentHeight);

        assertEquals(0f, v.maxPanY(), EPS);
        v.panBy(0f, 500f);
        assertEquals("没得拖", 0f, v.panY(), EPS);

        // 内容中心落在面板中心
        float contentCentreOnScreen = v.toScreenY(contentHeight / 2f);
        assertEquals(PANEL_H / 2f, contentCentreOnScreen, EPS);
    }

    /**
     * 内容比面板高（例如列数被夹住的平板）：能上下拖，
     * 但拖到头时内容边缘正好和面板边缘对齐，<b>拖不出空白</b>。
     */
    @Test
    public void panIsClampedByTheContentNotThePanel() {
        float contentHeight = PANEL_H * 1.5f;
        Viewport v = new Viewport(PANEL_W, PANEL_H, PANEL_W, contentHeight);
        v.setZoom(2f);

        // 内容高 1.5 面板、放大 2 倍 = 3 面板，超出 2 面板，单边可拖 1 面板
        assertEquals(PANEL_H, v.maxPanY(), EPS);

        // 往下拖到底 = 看到内容的最上面
        v.panBy(0f, 1_000_000f);
        assertEquals(0f, v.toLayoutY(0f), EPS);                     // 内容顶边贴住面板顶边
        assertEquals(PANEL_H / 2f, v.toLayoutY(PANEL_H), EPS);

        // 往上拖到底 = 看到内容的最下面
        v.panBy(0f, -1_000_000f);
        assertEquals(contentHeight, v.toLayoutY(PANEL_H), 0.01f);   // 内容底边贴住面板底边
    }

    // ---- 40 格宽的世界：横向必须能拖 ----

    /** 一块 40 格宽的战场在手机上：不放大时横向就该能拖，而且拖得动 2 屏多。 */
    @Test
    public void wideWorldCanBePannedHorizontallyAtDefaultZoom() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        assertEquals(BoardGeometry.COLS, g.cols());
        // 40 格 × 88.84px = 3553px，面板 1016px，单边可拖 (3553-1016)/2
        assertEquals(1268.8f, v.maxPanX(), 0.5f);
        assertTrue("战场上比屏幕宽，横向必须拖得动", v.maxPanX() > 0f);

        // 纵向仍然拖不动：18 行正好装下，这是硬约束
        assertEquals(0f, v.maxPanY(), EPS);

        // 拖到最左：内容左边缘贴住面板左边缘，左边不留白
        v.panBy(1_000_000f, 0f);
        assertEquals(0f, v.toLayoutX(0f), EPS);
        // 拖到最右：内容右边缘贴住面板右边缘
        v.panBy(-1_000_000f, 0f);
        assertEquals(g.boardWidthPx(), v.toLayoutX(PANEL_W), 0.01f);
    }

    /**
     * {@link Viewport#showRightEdge}：<b>开局取景</b>——画面停在战场最右边。
     *
     * <p>核心摆在靠右、紧挨着右侧山区，贴右边缘正好把核心和山区一起框进来。
     */
    @Test
    public void showRightEdgePutsTheRightEndOfTheBoardOnScreen() {
        BoardGeometry g = BoardGeometry.fit(PANEL_W, PANEL_H);
        Viewport v = new Viewport(PANEL_W, PANEL_H, g.boardWidthPx(), g.contentHeightPx());

        v.showRightEdge();

        // 右边缘贴住面板右边，一点空白都不露
        assertEquals("右边不该露白", g.boardWidthPx(), v.toLayoutX(PANEL_W), 0.01f);
        assertEquals(v.maxPanX(), -v.panX(), EPS);

        // 开局就能看见最右那几格（山区）
        float rightmostCellCentre = g.centerX(BoardGeometry.COLS - 1);
        assertTrue("最右一列要在屏幕上", v.toScreenX(rightmostCellCentre) <= PANEL_W);
        assertTrue(v.toScreenX(rightmostCellCentre) > 0f);

        // 屏幕左边缘落在战场内部：左边那大半张地图在屏幕外，往左拖才看得到
        float leftmostVisible = v.toLayoutX(0f);
        assertTrue("左边那大半张地图应该在屏幕外", leftmostVisible > 0f);
        assertEquals("可见的正好是最右一屏", g.boardWidthPx() - PANEL_W,
                leftmostVisible, 0.01f);
    }

    /**
     * 内容比面板窄时贴右边缘等于居中，不会把画面推到空白里去。
     * 竖屏手机上不会发生（战场总是比面板宽），但面板横过来时就会。
     */
    @Test
    public void showRightEdgeIsHarmlessWhenTheContentFitsThePanel() {
        float contentWidth = PANEL_W * 0.5f;
        Viewport v = new Viewport(PANEL_W, PANEL_H, contentWidth, PANEL_H);

        v.showRightEdge();

        assertEquals("没得拖就居中", 0f, v.panX(), EPS);
        assertEquals("内容中心落在面板中心", PANEL_W / 2f, v.toScreenX(contentWidth / 2f), EPS);
    }

    // ---- 参数校验 ----

    @Test
    public void rejectsBadInput() {
        try {
            new Viewport(0f, 100f);
            fail("面板宽为 0 应该报错");
        } catch (IllegalArgumentException expected) {
            // 正确
        }

        Viewport v = viewport();
        try {
            v.zoomAt(10f, 10f, 0f);
            fail("缩放倍数为 0 应该报错");
        } catch (IllegalArgumentException expected) {
            // 正确
        }
        try {
            v.zoomAt(10f, 10f, -1f);
            fail("负数倍数应该报错");
        } catch (IllegalArgumentException expected) {
            // 正确
        }
    }

    /** 布局坐标和屏幕坐标之间来回换，要能回到原值。 */
    @Test
    public void coordinateRoundTripIsStable() {
        float[] zooms = {1f, 1.35f, 2f};
        for (float zoom : zooms) {
            Viewport v = viewport();
            v.setZoom(zoom);
            v.panBy(80f, -60f);

            for (float x = 0f; x <= PANEL_W; x += 97f) {
                assertEquals(x, v.toLayoutX(v.toScreenX(x)), 0.01f);
                assertEquals(x, v.toScreenX(v.toLayoutX(x)), 0.01f);
            }
        }
    }
}
