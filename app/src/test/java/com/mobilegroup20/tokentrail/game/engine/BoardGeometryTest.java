package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link BoardGeometry} 的测试。
 *
 * <p>这里的数字不是随手编的：每一组都对应一台真实设备的可用尺寸，改参数时
 * 一眼能看出"哪种手机会变成什么样"。dp × density = px 的换算写在注释里。
 *
 * <p>战场面板按「宽 = 屏幕宽，高 = 屏幕高 − 260dp」估——上面有状态栏和 HUD、
 * 下面有操作条和导航栏。<b>260dp 是真机量出来的</b>（2026-09-27 压过一轮界面之后：
 * 状态栏 41 + HUD 116 + 操作行 46 + 导航 56），真机那一组见
 * {@link #realDeviceMeasuredOnHbnAl80()}。
 *
 * <p><b>2026-09-27 这组数整体变过一次。</b>那天先把界面从 339dp 压到 260dp、
 * 又把 {@link BoardGeometry#SCREEN_ROWS} 从 18 提到 36（一屏看全整块战场），
 * 所以每个机型上的格子都变小了——上下两件事是一起的，改一个要回头看另一个。
 *
 * <p><b>行列数在所有设备上都是定死的</b>（{@link BoardGeometry#COLS} ×
 * {@link BoardGeometry#ROWS}），算出来的只有格子大小。所以下面这些测试盯的是
 * "格子多大、一屏看得见几列"，不是"排得下几列"。
 */
public class BoardGeometryTest {

    private static final float EPS = 1e-3f;

    /** 一台设备的面板，px。 */
    private static BoardGeometry panel(float wDp, float hDp, float density) {
        return BoardGeometry.fit(wDp * density, hDp * density);
    }

    /**
     * 华为 HBN-AL80，373×843dp @3.375。战场面板 373×583dp 是 2026-09-27 压完界面
     * 之后在真机上量的（{@code play_area} 上下边界 530→2498px，1968px ÷ 3.375 =
     * 583.1dp），界面占掉 260dp。
     */
    private static BoardGeometry realPhone() {
        return panel(373f, 583f, 3.375f);
    }

    // ---- 真实设备上的结果 ----

    /**
     * <b>真机实测过的一组</b>（华为 HBN-AL80）：80 × 36 的战场，一格 15.8dp，
     * 一屏看得见 23.7 列，横向要拖 3.4 屏，<b>纵向一屏看全</b>。
     *
     * <p>这一组是拿手机跑出来的，不是估的：{@code game_grid_info} 那行小字显示的
     * 就是这几个数。改动 {@link BoardGeometry#fit} 或者界面高度之后先看它有没有变。
     */
    @Test
    public void realDeviceMeasuredOnHbnAl80() {
        BoardGeometry g = realPhone();

        assertEquals(BoardGeometry.COLS, g.cols());
        assertEquals(BoardGeometry.ROWS, g.rows());
        // 面板高度是量出来的，±1px 的误差就会让 dp 数在 15.76/15.77 之间跳，
        // 所以这里给 0.05 的容差，不咬着小数点后一位
        assertEquals(15.76f, g.cellPx() / 3.375f, 0.05f);
        assertEquals(23.67f, g.visibleCols(), 0.05f);
        assertEquals(3.38f, g.cols() / g.visibleCols(), 0.01f);
        // 纵向不用拖：36 行 + 1 格余量正好铺满面板高度（583dp × 3.375 = 1967.6px）
        assertEquals(583f * 3.375f, g.contentHeightPx(), EPS);
    }

    /**
     * 典型大屏手机 411×891 @2.625：面板 387×631dp，一格 17.05dp，一屏可见 22.7 列。
     *
     * <p>和真机那组对比着看：格子大小<b>只跟面板高度走</b>，和宽度无关。
     */
    @Test
    public void typicalPhoneGetsSeventeenDpCellsAndSeesTheWholeHeight() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        assertEquals(BoardGeometry.COLS, g.cols());
        assertEquals(BoardGeometry.ROWS, g.rows());
        assertEquals(44.767f, g.cellPx(), 0.01f);
        assertEquals(17.05f, g.cellPx() / 2.625f, 0.01f);
        assertEquals(22.69f, g.visibleCols(), 0.02f);
    }

    /**
     * <b>一屏永远装得下 {@link BoardGeometry#SCREEN_ROWS} 行，而且现在
     * {@code SCREEN_ROWS == ROWS}——整块战场纵向一屏看得全，不用拖。</b>
     * 格子大小就是从这条反推出来的（见 {@code fit()}），和面板宽高比无关。
     *
     * <p><b>这两个断言是一对，方向是"正好"不是"至少"。</b>只留前一个的话，
     * 哪天有人把格子按更小的行数去摊面板高度（屏幕上所有东西放大、纵向又拖起来），
     * 测试不会响；只留后一个的话，"没装下"和"装下了但留了一大截白"都算过。
     * 2026-09-27 之前这条断言的是相反的方向（{@code >}，即"整块装不下"），
     * 那天把 {@code SCREEN_ROWS} 提到 36 之后才反过来的。
     */
    @Test
    public void everyDeviceFitsTheWholeBoardVerticallyWithoutScrolling() {
        float[][] devices = {
                {373f, 583f, 3.375f},  // 真机 HBN-AL80（量出来的）
                {336f, 540f, 3f},      // 小屏 360×800
                {369f, 592f, 2.75f},   // 常见 393×852
                {387f, 631f, 2.625f},  // 大屏 411×891
                {388f, 655f, 3.5f},    // 1440p 412×915
                {776f, 1020f, 2f},     // 平板 800×1280
                {180f, 200f, 1f},      // 极窄的怪面板
        };
        for (float[] d : devices) {
            BoardGeometry g = panel(d[0], d[1], d[2]);
            float panelHeightPx = d[1] * d[2];
            assertTrue(BoardGeometry.SCREEN_ROWS + " 行装不下面板: " + g,
                    BoardGeometry.SCREEN_ROWS * g.cellPx() <= panelHeightPx + EPS);
            assertEquals("内容盒（1 格余量 + " + BoardGeometry.ROWS
                            + " 行）应该正好铺满面板高度，多一分是留白、少一分是要拖: " + g,
                    panelHeightPx, g.contentHeightPx(), EPS);
            assertEquals(BoardGeometry.ROWS, g.rows());
            assertEquals(BoardGeometry.COLS, g.cols());
        }
    }

    /**
     * <b>横向一屏永远看不全</b>——这正是这一版要的效果，不是没做完。
     *
     * <p>80 格比任何手机都宽，所以横向一定有得拖（{@code maxPanX > 0}）。
     * 哪天有台设备能一屏装下 80 格，说明格子小到没法点了。
     */
    @Test
    public void theWorldIsAlwaysWiderThanTheScreenSoItHasToBePanned() {
        float[][] devices = {
                {373f, 583f, 3.375f}, {336f, 540f, 3f}, {387f, 631f, 2.625f},
                {388f, 655f, 3.5f}, {776f, 1020f, 2f},
        };
        for (float[] d : devices) {
            BoardGeometry g = panel(d[0], d[1], d[2]);
            float panelWidthPx = d[0] * d[2];

            assertTrue("一屏就看全了，说明格子太小: " + g, g.boardWidthPx() > panelWidthPx);
            assertTrue("一屏看得见的列数太少，没法操作: " + g, g.visibleCols() >= 8f);
            assertTrue("一屏看得见的列数不该超过总列数: " + g,
                    g.visibleCols() < BoardGeometry.COLS);
        }
    }

    /**
     * 平板：格子跟着面板高度变大（27.6dp，是手机上 15.8dp 的 1.75 倍），
     * 一屏可见的列数反而更多（28.2 列）。行数和列数不变——关卡形状不随屏幕变。
     */
    @Test
    public void tabletGetsBiggerCellsNotMoreColumns() {
        BoardGeometry g = panel(776f, 1020f, 2f);

        assertEquals(BoardGeometry.COLS, g.cols());
        assertEquals(BoardGeometry.ROWS, g.rows());
        assertEquals(55.135f, g.cellPx(), 0.05f);          // px
        assertEquals(27.57f, g.cellPx() / 2f, 0.05f);      // dp
        assertEquals(28.15f, g.visibleCols(), 0.02f);
        assertTrue(BoardGeometry.SCREEN_ROWS * g.cellPx() <= 1020f * 2f + EPS);
    }

    // ---- 格子大小只由高度决定 ----

    /**
     * <b>格子大小与面板宽度无关。</b>把面板横向拉宽两倍，格子一个像素都不变，
     * 只是看得见的列数翻倍。
     *
     * <p>这条挂了就说明有人在 {@code fit()} 里又让宽度参与了格子大小的计算——
     * 那会让同一关在不同手机上格子不一样大。
     */
    @Test
    public void cellSizeDependsOnHeightOnly() {
        BoardGeometry narrow = BoardGeometry.fit(300f, 900f);
        BoardGeometry wide = BoardGeometry.fit(600f, 900f);

        assertEquals(narrow.cellPx(), wide.cellPx(), EPS);
        assertEquals(narrow.visibleCols() * 2f, wide.visibleCols(), EPS);
    }

    // ---- 不变量 ----

    /**
     * 最高的素材放在最上面一行时也必须还在内容盒里——这条挂了就是"第 0 行被裁掉"。
     *
     * <p>哪个最高不写死：从 {@link BuildingType} 里挑，换了美术规格这条跟着走。
     */
    @Test
    public void theTallestSpriteOnTheTopRowIsNeverClipped() {
        BuildingType tallest = BuildingType.CORE;
        for (BuildingType t : BuildingType.values()) {
            if (t.spriteHeightCells() > tallest.spriteHeightCells()) {
                tallest = t;
            }
        }

        float[][] devices = {
                {336f, 540f, 3f}, {369f, 592f, 2.75f}, {387f, 631f, 2.625f},
                {388f, 655f, 3.5f}, {776f, 1020f, 2f}, {200f, 300f, 1f},
        };
        for (float[] d : devices) {
            BoardGeometry g = panel(d[0], d[1], d[2]);
            float spriteTop = g.anchorY(0, tallest.rows)
                    - tallest.spriteHeightCells() * g.cellPx();
            assertTrue(tallest.label + " 跑到内容盒外了: " + g, spriteTop >= -EPS);
        }
    }

    /**
     * 3×3 的核心（footprint 3 格 + 探出 1 格 = 4 格）不只是"不被裁"，而是
     * <b>正好顶到面板上沿</b>：顶上那条余量就是照它裁的，所以贴图上沿落在 0 上，
     * 不裁掉也不留缝。
     *
     * <p>塔不再往上探之后，顶上那条余量从 1.5 格收到了 1 格（由核心决定，
     * 见 {@link BoardGeometry#MAX_OVERHANG_CELLS}），"上面还余着半格"那句话
     * 就不成立了——留白变成零，这一条改盯"零"这个数：
     * 谁再动 {@code MAX_OVERHANG_CELLS}，这里会立刻说清楚差在哪。
     */
    @Test
    public void coreOnTheTopRowSitsExactlyOnTheTopEdge() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        float spriteHeight = (3 + 1f) * g.cellPx();
        assertEquals("核心贴图上沿应该正好落在面板上沿（0），不多不少",
                0f, g.anchorY(0, 3) - spriteHeight, EPS);
    }

    /**
     * <b>贴图永远不会被放大。</b>任何机型、放到最大，素材都只是缩小显示——
     * 放大才会糊，这也是"一格 = 256px"这个基准的选法。
     *
     * <p><b>下界从 0.4 降到了 0.3（2026-09-27）。</b>那天格子从约 26dp 缩到
     * 约 16dp，贴图缩放跟着腰斩：最省的机型（小屏 360×800）放到最大也只有
     * 0.34 倍。**这一条现在是个"还可以更省"的信号，不是错误**——
     * 素材按一格 256px 出图，而放到最大也只需要约 88px/格，
     * 照着 128px/格 重出一遍能省一半内存和包体，画面几乎看不出差别。
     * 那是美术那边的一次重出，没做，记在这里。
     */
    @Test
    public void spritesAreNeverUpscaledOnAnyDevice() {
        float[][] devices = {
                {336f, 540f, 3f}, {369f, 592f, 2.75f}, {387f, 631f, 2.625f},
                {388f, 655f, 3.5f}, {776f, 1020f, 2f},
        };
        for (float[] d : devices) {
            BoardGeometry g = panel(d[0], d[1], d[2]);
            float scale = g.spriteScale(Viewport.MAX_ZOOM);
            assertTrue("放到最大时贴图被放大了: " + scale, scale <= 1f);
            assertTrue("素材浪费得太多: " + scale, scale > 0.3f);
        }
    }

    /**
     * <b>可点区域：翻倍到 36 行之后，塔自己也不够 48dp 了。</b>
     *
     * <p>这是"一屏看全整块战场"直接换来的代价，不是漏了。真机（一格 15.8dp）上：
     * 1×1 的城墙 15.8dp、2×2 的塔 31.5dp、3×3 的核心 47.3dp，
     * <b>三个都不到 48dp</b>。放大到 {@link Viewport#MAX_ZOOM} 倍之后是
     * 31.5 / 63 / 94.7dp，塔和核心才过线，城墙仍然不够。
     *
     * <p>所以这条测试<b>断言的是"确实不够"，而不是"够"</b>——哪天有人把格子改大了
     * （比如把 {@code SCREEN_ROWS} 调回去），这里会立刻响，提醒他那意味着
     * 纵向又要拖了。两个数必须一起看。
     */
    @Test
    public void towerFootprintsAreBelowTheTapTargetAfterFittingTheWholeBoard() {
        BoardGeometry g = realPhone();
        float cellDp = g.cellPx() / 3.375f;

        assertTrue("一格本来就达不到 48dp，这是设计上接受的", cellDp < BoardGeometry.TAP_TARGET_DP);
        assertTrue("2×2 的塔现在也达不到 48dp 了——如果这条挂了，说明格子被改大了，"
                        + "回头确认纵向是不是又要拖：",
                2f * cellDp < BoardGeometry.TAP_TARGET_DP);
        // 放到最大之后，塔过线
        assertTrue("放大到最大时 2×2 的塔必须过线",
                2f * cellDp * Viewport.MAX_ZOOM >= BoardGeometry.TAP_TARGET_DP);
    }

    /** 第 0 列贴着内容盒左边缘，横向不留白；战场整体比面板宽。 */
    @Test
    public void boardStartsAtTheLeftEdgeAndIsWiderThanThePanel() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        // 第 0 列贴着内容盒左边缘，左边没有留白
        assertEquals(0f, g.leftX(0), EPS);
        assertEquals(g.boardWidthPx(), g.contentWidthPx(), EPS);
        // 80 格 × 44.77px，比面板宽得多
        assertEquals(BoardGeometry.COLS * g.cellPx(), g.boardWidthPx(), 0.01f);
    }

    // ---- footprint ----

    @Test
    public void fitsChecksTheWholeFootprintAgainstTheBoard() {
        BoardGeometry g = panel(387f, 631f, 2.625f); // 80 × 36

        assertTrue(g.fits(0, 0, 1, 1));
        assertTrue(g.fits(78, 34, 2, 2));
        assertTrue(g.fits(77, 33, 3, 3));
        assertTrue(g.fits(79, 35, 1, 1));

        assertFalse("右边超出一列", g.fits(79, 0, 2, 2));
        assertFalse("下边超出一行", g.fits(0, 35, 1, 2));
        assertFalse("核心贴右下角放不下", g.fits(78, 34, 3, 3));
        assertFalse("负坐标", g.fits(-1, 0, 2, 2));
        assertFalse("尺寸为 0", g.fits(0, 0, 0, 2));
    }

    /** 一个 footprint 盖住的格子：2×2 盖 4 格，3×3 盖 9 格，一个不多一个不少。 */
    @Test
    public void cellsOfReturnsExactlyTheFootprint() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        Cell[] four = g.cellsOf(3, 5, 2, 2);
        assertEquals(4, four.length);
        assertEquals(new Cell(3, 5), four[0]);
        assertEquals(new Cell(4, 5), four[1]);
        assertEquals(new Cell(3, 6), four[2]);
        assertEquals(new Cell(4, 6), four[3]);

        assertEquals(9, g.cellsOf(0, 0, 3, 3).length);
        assertEquals(1, g.cellsOf(7, 7, 1, 1).length);

        // 每一格都在战场内
        for (Cell c : g.cellsOf(9, 15, 3, 3)) {
            assertTrue(c + " 应该放得下", g.contains(c.col, c.row));
        }
    }

    /**
     * 锚点是 footprint 的<b>底边中点</b>，不是格子中心：
     * 塔和核心都从自己那片地面的前沿往上长。
     */
    @Test
    public void footprintAnchorsAtItsBottomCentre() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        // 1×1：锚点就是格子中心的下边缘
        assertEquals(g.centerX(3), g.anchorX(3, 1), EPS);
        assertEquals(g.centerY(5) + g.cellPx() / 2f, g.anchorY(5, 1), EPS);

        // 2×2：锚点在两列的交界线上、两行的下边缘
        assertEquals(g.leftX(3) + g.cellPx(), g.anchorX(3, 2), EPS);
        assertEquals(g.topY(5) + 2f * g.cellPx(), g.anchorY(5, 2), EPS);

        // 和"格子中心"确实不一样——用错了会差一整格
        assertTrue(Math.abs(g.anchorY(5, 2) - g.centerY(6)) > g.cellPx() * 0.4f);
    }

    // ---- 每种建筑的贴图都不会被裁 ----

    /**
     * <b>上方那 1 格余量正好是为最高的贴图留的。</b>
     *
     * <p>这条把美术规格和几何算死了：哪个建筑向上探出多少格写在
     * {@link BuildingType#overhangUpCells} 里，探出最多的那个必须正好用满余量——
     * 多一分会被裁，少一分就是白留。
     */
    @Test
    public void everyBuildingTypeFitsInsideTheReservedHeadroom() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        for (BuildingType type : BuildingType.values()) {
            float spriteTop = g.anchorY(0, type.rows) - type.spriteHeightCells() * g.cellPx();
            assertTrue(type.label + " 放在第 0 行会被裁掉", spriteTop >= -EPS);
            assertTrue(type.label + " 向上探出超过了给美术的上限",
                    type.overhangUpCells <= BoardGeometry.MAX_OVERHANG_CELLS);
        }

        // 余量不是白留的：向上探出最多的那个（3×3 的核心，楼的顶 1 格）正好用满
        assertEquals(BoardGeometry.MAX_OVERHANG_CELLS, BuildingType.CORE.overhangUpCells, EPS);
        assertEquals(4f, BuildingType.CORE.spriteHeightCells(), EPS);
        assertEquals(2f, BuildingType.TOWER.spriteHeightCells(), EPS);
        // 弩车和塔一样是 2×2、向上不探——两张图等高，火焰图才对得齐（见 BuildingSprites）
        assertEquals(2f, BuildingType.BALLISTA.spriteHeightCells(), EPS);
        assertEquals(1.5f, BuildingType.WALL.spriteHeightCells(), EPS);
    }

    /**
     * <b>横向探出不会被裁</b>，所以它不占 {@link BoardGeometry#fit} 的余量——
     * 但它得小于"最靠边能放建筑的那一列"，否则摆在最边上的那座，
     * 炮管（往左）或者炮尾（往右）会伸到战场外面去。
     *
     * <p>最靠左能放的是可建区第一列（{@link Battlefield#firstBuildableCol()}，
     * 就是敌人通道那 4 格的右边一列）；最靠右那一列的右边还隔着 3 格山区。
     */
    @Test
    public void horizontalOverhangFitsInsideTheBoard() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        for (BuildingType type : BuildingType.values()) {
            assertTrue(type.label + " 向左探出超过了给美术的上限",
                    type.overhangLeftCells <= BoardGeometry.MAX_OVERHANG_LEFT_CELLS);
            assertTrue(type.label + " 向右探出超过了给美术的上限",
                    type.overhangRightCells <= BoardGeometry.MAX_OVERHANG_RIGHT_CELLS);
            assertEquals(type.cols + type.overhangLeftCells + type.overhangRightCells,
                    type.spriteWidthCells(), EPS);
        }

        // 塔：炮管往左 1 格、炮尾往右 0.3 格，画布一共 3.3 格宽
        assertEquals(BoardGeometry.MAX_OVERHANG_LEFT_CELLS,
                BuildingType.TOWER.overhangLeftCells, EPS);
        assertEquals(BoardGeometry.MAX_OVERHANG_RIGHT_CELLS,
                BuildingType.TOWER.overhangRightCells, EPS);
        assertEquals(3.3f, BuildingType.TOWER.spriteWidthCells(), EPS);

        // 弩车：占地和塔一样是 2×2，探出却是另一套数——弓臂几乎和底座一样宽，
        // 往左只探 0.9 格（塔是炮管，探满 1 格），往右 0.2 格，画布 3.1 格宽。
        //
        // 这几个数是**从图上量出来的**（art/cut_ballista.py 量的踩地那一段），
        // 不是"给塔抄一份"。所以塔用得满的余量，弩车用不满——上面那个循环
        // 只查了"不超过上限"，这里把实际值钉住，改图的时候才会发现。
        assertEquals(0.9f, BuildingType.BALLISTA.overhangLeftCells, EPS);
        assertEquals(0.2f, BuildingType.BALLISTA.overhangRightCells, EPS);
        assertEquals(3.1f, BuildingType.BALLISTA.spriteWidthCells(), EPS);
        assertEquals("两种塔占地该一样", BuildingType.TOWER.cols, BuildingType.BALLISTA.cols);
        assertEquals(BuildingType.TOWER.rows, BuildingType.BALLISTA.rows);
        assertTrue("弩车的图该比箭塔窄——占地相同、图不同，这正是探出量的意义",
                BuildingType.BALLISTA.spriteWidthCells() < BuildingType.TOWER.spriteWidthCells());

        // 两种塔摆在最边上都不能探出战场。最靠左能放的是可建区第一列，
        // 最靠右是最后一列（右边还隔着 3 格山区）——弩车比塔窄，但这条对它一样管用：
        // 哪天给它换个更宽的弩弓，这里会先炸，而不是等玩家把塔摆到边上才看出来。
        for (BuildingType tower : new BuildingType[]{BuildingType.TOWER, BuildingType.BALLISTA}) {
            float spriteLeft = g.leftX(Battlefield.ENEMY_LANE_COLS)
                    - tower.overhangLeftCells * g.cellPx();
            assertTrue(tower.label + " 的武器伸到战场左边缘外面去了", spriteLeft > 0f);

            int lastBuildable = BoardGeometry.COLS - Battlefield.MOUNTAIN_COLS - 1;
            float spriteRight = g.leftX(lastBuildable + tower.cols)
                    + tower.overhangRightCells * g.cellPx();
            assertTrue(tower.label + " 的尾巴伸到战场右边缘外面去了",
                    spriteRight < g.boardWidthPx());
        }
    }

    // ---- 手指点的那一格 → 占地左上角 ----

    /** 占地尽量以点中的格子为中心：3×3 点中的是正中那格，2×2 点中的当左上角。 */
    @Test
    public void topLeftForCentresTheFootprintOnTheTappedCell() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        assertEquals(new Cell(4, 4), g.topLeftFor(5, 5, 3, 3));
        assertEquals(new Cell(5, 5), g.topLeftFor(5, 5, 2, 2));
        assertEquals(new Cell(5, 5), g.topLeftFor(5, 5, 1, 1));
    }

    /**
     * 贴着边点的时候把占地推回场内，而不是判定成放不下——
     * 点屏幕右下角想放核心是合理操作。
     */
    @Test
    public void topLeftForPushesTheFootprintBackInsideTheBoard() {
        BoardGeometry g = panel(387f, 631f, 2.625f);   // 80 × 36

        assertEquals(new Cell(77, 33), g.topLeftFor(79, 35, 3, 3));
        assertEquals(new Cell(0, 0), g.topLeftFor(0, 0, 3, 3));
        assertEquals(new Cell(77, 33), g.topLeftFor(100, 100, 3, 3));
        assertEquals(new Cell(0, 0), g.topLeftFor(-50, -50, 3, 3));

        // 不管点多偏，结果一定是一块放得下的占地
        for (int col = -5; col < BoardGeometry.COLS + 5; col++) {
            for (int row = -5; row < BoardGeometry.ROWS + 5; row++) {
                for (BuildingType type : BuildingType.values()) {
                    Cell tl = g.topLeftFor(col, row, type.cols, type.rows);
                    assertTrue("点(" + col + "," + row + ") 放 " + type.label + " 得到 " + tl,
                            g.fits(tl.col, tl.row, type.cols, type.rows));
                }
            }
        }
    }

    // ---- 连续格子坐标 ----

    /** 敌人走在格子中间，位置是小数：{@code xAt(4.5)} 就是第 4 格的中心。 */
    @Test
    public void continuousCellCoordinatesAgreeWithTheIntegerOnes() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        for (int col = 0; col < g.cols(); col++) {
            assertEquals(g.leftX(col), g.xAt(col), EPS);
            assertEquals(g.centerX(col), g.xAt(col + 0.5f), 0.01f);
        }
        assertEquals(g.boardTopPx(), g.yAt(0f), EPS);
        assertEquals(g.centerY(0), g.yAt(0.5f), 0.01f);
        assertEquals(g.boardTopPx() + 7f * g.cellPx(), g.yAt(7f), 0.01f);
    }

    // ---- 拖一排跨了几座 ----

    /**
     * 手指没离开按下那一格：就一座。
     *
     * <p>这一条保的是<b>"点一下建一座"没被拖拽那套逻辑吃掉</b>——
     * 一次点击在触摸事件里也是一次"拖了零格"，算成 0 座的话点击就没反应了。
     */
    @Test
    public void notMovingTheFingerMeansASingleBuilding() {
        assertEquals(1, BoardGeometry.runLength(20, 20, 1));
        assertEquals(1, BoardGeometry.runLength(20, 20, 2));
    }

    /** 墙是 1×1，拖过一格就是两座（按下那座 + 手指停的那座）。 */
    @Test
    public void aWallDragCountsEveryCellItCovers() {
        assertEquals(2, BoardGeometry.runLength(20, 21, 1));
        assertEquals(5, BoardGeometry.runLength(20, 24, 1));
    }

    /**
     * 2×2 的塔要拖过<b>两格</b>才算多一座。
     *
     * <p>拖过一格就多一座的话，铺出来的塔会两两重叠一列——而"重叠"
     * 在这种铺法里是看不出来的（后一座根本放不下），玩家只会觉得拖了没反应。
     */
    @Test
    public void aTowerDragCountsWholeFootprints() {
        assertEquals(1, BoardGeometry.runLength(20, 21, 2));
        assertEquals(2, BoardGeometry.runLength(20, 22, 2));
        assertEquals(2, BoardGeometry.runLength(20, 23, 2));   // 差一点满三座
        assertEquals(3, BoardGeometry.runLength(20, 24, 2));
    }

    /**
     * 往左拖和往右拖一样长。
     *
     * <p>只管长度不管方向：起头那一格由调用方取两端的较小者，
     * 步长恒为一个占地。这样不管往哪边拖，铺出来的都是同一条。
     */
    @Test
    public void draggingTheOtherWayCountsTheSameLength() {
        assertEquals(BoardGeometry.runLength(20, 24, 1), BoardGeometry.runLength(24, 20, 1));
        assertEquals(BoardGeometry.runLength(20, 24, 2), BoardGeometry.runLength(24, 20, 2));
        assertEquals(5, BoardGeometry.runLength(24, 20, 1));
    }

    /** 占地跨度必须是正的：写 0 的话是除零，只会炸在一个和原因无关的地方。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsAZeroSpan() {
        BoardGeometry.runLength(20, 24, 0);
    }

    // ---- 坐标换算 ----

    @Test
    public void everyCellCentreMapsBackToItsOwnCell() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        for (int col = 0; col < g.cols(); col++) {
            for (int row = 0; row < g.rows(); row++) {
                assertEquals("列 " + col, col, g.colAt(g.centerX(col)));
                assertEquals("行 " + row, row, g.rowAt(g.centerY(row)));
            }
        }
    }

    /** 格子边界：左上角属于本格，右下角属于下一格（半开区间）。 */
    @Test
    public void cellEdgesBelongToTheCellOnTheRight() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        assertEquals(0, g.colAt(0f));
        assertEquals(1, g.colAt(g.cellPx()));
        assertEquals(0, g.rowAt(g.boardTopPx()));
        assertEquals(1, g.rowAt(g.boardTopPx() + g.cellPx()));
    }

    /**
     * 点在战场外时 {@link BoardGeometry#colAt} <b>不夹取</b>，返回越界的值，
     * 由 {@link BoardGeometry#contains} 判否。这样"点在战场外"不会被
     * 悄悄当成"点了最边上那格"。
     */
    @Test
    public void pointsOutsideTheBoardReportOutOfRange() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        assertTrue(g.colAt(-1f) < 0);
        assertTrue(g.rowAt(g.boardTopPx() - 1f) < 0);
        assertFalse(g.contains(g.cols(), 0));
        assertFalse(g.contains(0, g.rows()));
    }

    /** 拖拽放置：手指拖出边界外，吸附到最边上的格子，而不是判定成非法。 */
    @Test
    public void nearestCellClampsToTheBoard() {
        BoardGeometry g = panel(387f, 631f, 2.625f);

        assertEquals(new Cell(0, 0), g.nearestCell(-10_000f, -10_000f));
        assertEquals(new Cell(g.cols() - 1, g.rows() - 1), g.nearestCell(10_000f, 10_000f));
        assertEquals(new Cell(3, 5), g.nearestCell(g.centerX(3), g.centerY(5)));
    }

    /** 尺寸不合法时直接报错，不要算出 NaN 再让人慢慢查。 */
    @Test
    public void rejectsNonPositiveSizes() {
        float[][] bad = {{0f, 100f}, {100f, 0f}, {-5f, 100f}};
        for (float[] p : bad) {
            try {
                BoardGeometry.fit(p[0], p[1]);
                fail("应该抛 IllegalArgumentException: " + p[0] + "x" + p[1]);
            } catch (IllegalArgumentException expected) {
                // 正确
            }
        }
    }

    /** 格子和格子坐标是值对象，能直接当 Map 的 key（占位表就是这么存的）。 */
    @Test
    public void cellWorksAsAMapKey() {
        assertEquals(new Cell(2, 3), new Cell(2, 3));
        assertEquals(new Cell(2, 3).hashCode(), new Cell(2, 3).hashCode());
        assertFalse(new Cell(2, 3).equals(new Cell(3, 2)));

        java.util.Map<Cell, String> towers = new java.util.HashMap<>();
        towers.put(new Cell(1, 1), "箭塔");
        assertEquals("箭塔", towers.get(new Cell(1, 1)));
    }
}
