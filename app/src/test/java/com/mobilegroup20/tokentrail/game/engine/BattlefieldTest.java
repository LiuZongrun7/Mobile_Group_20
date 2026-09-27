package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * {@link Battlefield} 的测试。
 *
 * <p>盯的是三条最容易写错的规则：占地占位（2×2 的塔要压住 4 个格子）、
 * 左边那条入侵通道不能被占、以及敌人被挡/漏怪的判定。
 */
public class BattlefieldTest {

    /**
     * 典型大屏手机的战场：{@link BoardGeometry#COLS} × {@link BoardGeometry#ROWS}，
     * 也就是真机上那一块（80 × 36）。
     */
    private static Battlefield battlefield() {
        return new Battlefield(BoardGeometry.fit(387f * 2.625f, 643f * 2.625f));
    }

    // ---- 占地与占位 ----

    @Test
    public void towerOccupiesItsWholeFootprint() {
        Battlefield bf = battlefield();
        int col = bf.firstBuildableCol();   // 就地摆在可建区最左边那一列
        Building tower = bf.place(BuildingType.TOWER, col, 7);
        assertNotNull(tower);

        // 2×2 压住 4 个格子，多一格都不行
        assertTrue(tower.covers(new Cell(col, 7)));
        assertTrue(tower.covers(new Cell(col + 1, 7)));
        assertTrue(tower.covers(new Cell(col, 8)));
        assertTrue(tower.covers(new Cell(col + 1, 8)));
        assertFalse(tower.covers(new Cell(col + 2, 7)));
        assertFalse(tower.covers(new Cell(col, 9)));

        assertEquals(4, BuildingType.TOWER.cellCount());
        assertEquals(9, BuildingType.CORE.cellCount());
        assertEquals(1, BuildingType.WALL.cellCount());
    }

    /** 占地是真的登记到每个格子上了，不只是记了个左上角。 */
    @Test
    public void everyCellOfTheFootprintKnowsItsBuilding() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 8, 10);
        assertNotNull(core);

        for (Cell cell : bf.board().cellsOf(8, 10, 3, 3)) {
            assertEquals(core, bf.buildingAt(cell));
        }
        assertNull(bf.buildingAt(new Cell(7, 10)));
        assertNull(bf.buildingAt(new Cell(8, 13)));
    }

    /** 压住别人的格子就放不下——包括"只压住一角"。 */
    @Test
    public void cannotPlaceOverlappingAnotherBuilding() {
        Battlefield bf = battlefield();
        // 比可建区左边缘再往右一格：左边那一格得留给下面那座核心——核心压塔的一角
        // 要压在一块真的可建的地上，不能是被通道挡下来的
        int col = bf.firstBuildableCol() + 1;
        assertNotNull(bf.place(BuildingType.TOWER, col, 7));   // 占 (col..col+1, 7..8)

        assertFalse("完全重合", bf.canPlace(BuildingType.TOWER, col, 7));
        assertFalse("压住一格", bf.canPlace(BuildingType.TOWER, col + 1, 8));
        assertFalse("压住一格", bf.canPlace(BuildingType.WALL, col + 1, 8));
        assertFalse("核心压住塔的一角", bf.canPlace(BuildingType.CORE, col - 1, 6));

        // 贴着放可以
        assertTrue("贴右边", bf.canPlace(BuildingType.TOWER, col + 2, 7));
        assertTrue("贴下边", bf.canPlace(BuildingType.WALL, col, 9));
    }

    /** 放不下时返回 null，不抛异常——手指点到放不下的地方是正常操作。 */
    @Test
    public void placingSomewhereIllegalReturnsNullAndChangesNothing() {
        Battlefield bf = battlefield();

        assertNull("右下角放不下 3×3", bf.place(BuildingType.CORE,
                bf.board().cols() - 2, bf.board().rows() - 2));
        assertNull("负坐标", bf.place(BuildingType.TOWER, -1, 4));
        assertNull("入侵通道", bf.place(BuildingType.WALL, 0, 4));

        assertTrue("失败的放置不该留下任何东西", bf.buildings().isEmpty());
        assertFalse(bf.canPlace(BuildingType.CORE, bf.board().cols() - 2, bf.board().rows() - 2));
        assertFalse(bf.canPlace(BuildingType.TOWER, -1, 4));
        assertFalse(bf.canPlace(BuildingType.WALL, 0, 4));
    }

    /** 左边缘是敌人通道，整列都不能放东西。 */
    @Test
    public void theEntryColumnCannotBeBuiltOn() {
        Battlefield bf = battlefield();

        int reserved = Battlefield.ENEMY_LANE_COLS;
        assertTrue("敌人通道至少要有几格，否则敌人一出现就贴着可建区", reserved >= 2);
        assertEquals("通道最左边那几列就是 ENEMY_LANE 地形",
                Terrain.ENEMY_LANE, bf.terrainAt(0, 5));
        assertEquals(Terrain.ENEMY_LANE, bf.terrainAt(reserved - 1, 17));

        for (int col = 0; col < reserved; col++) {
            for (int row = 0; row < bf.board().rows(); row++) {
                assertFalse("通道第 " + col + " 列不该能放东西",
                        bf.canPlace(BuildingType.WALL, col, row));
            }
            assertNull(bf.place(BuildingType.WALL, col, 5));
        }
        assertTrue("通道右边第一列可以建", bf.canPlace(BuildingType.WALL, reserved, 5));
    }

    /** 右边缘是山区，也不能放东西——核心背后的那几格石头。 */
    @Test
    public void theMountainBandCannotBeBuiltOnEither() {
        Battlefield bf = battlefield();
        int mountain = Battlefield.MOUNTAIN_COLS;
        assertTrue("山区至少要占几格，否则看不出地图有个右边界", mountain >= 2);

        for (int col = bf.board().cols() - mountain; col < bf.board().cols(); col++) {
            for (int row = 0; row < bf.board().rows(); row++) {
                assertEquals("第 " + col + " 列该是山区", Terrain.MOUNTAIN, bf.terrainAt(col, row));
                assertFalse("山区分不出东西", bf.canPlace(BuildingType.WALL, col, row));
                assertFalse("核心也不能摆在山上", bf.canPlace(BuildingType.CORE, col, row));
            }
        }

        // 山区左边那一列是可建的——核心就摆在那儿
        int lastBuildable = bf.board().cols() - mountain - 1;
        assertEquals(lastBuildable, bf.lastBuildableCol());
        assertEquals(Terrain.BUILDABLE, bf.terrainAt(lastBuildable, 0));
        assertTrue(bf.canPlace(BuildingType.WALL, lastBuildable, 5));
    }

    /**
     * 三区加起来正好是整张地图，而且第一个可建区紧接在通道右边。
     * 这几个数就是背景图的划分（见 {@code docs/ART.md} §5），
     * 改 {@code COLS} 或两个区宽时这里会立刻发现对不上。
     */
    @Test
    public void theThreeZonesTileTheWholeBoardWithNoGaps() {
        Battlefield bf = battlefield();

        assertEquals(Battlefield.ENEMY_LANE_COLS, bf.firstBuildableCol());
        assertEquals(bf.board().cols() - Battlefield.MOUNTAIN_COLS - 1, bf.lastBuildableCol());
        assertTrue("可建区不能是空的", bf.lastBuildableCol() >= bf.firstBuildableCol());

        // 每一列都属于且只属于一区
        for (int col = 0; col < bf.board().cols(); col++) {
            Terrain t = bf.terrainAt(col, 0);
            if (col < bf.firstBuildableCol()) {
                assertEquals(Terrain.ENEMY_LANE, t);
            } else if (col > bf.lastBuildableCol()) {
                assertEquals(Terrain.MOUNTAIN, t);
            } else {
                assertEquals(Terrain.BUILDABLE, t);
            }
        }

        // 地形是一整列一样的（现在按列分段，不是按格子）
        for (int col = 0; col < bf.board().cols(); col++) {
            for (int row = 1; row < bf.board().rows(); row++) {
                assertEquals("第 " + col + " 列上下应该同一种地形",
                        bf.terrainAt(col, 0), bf.terrainAt(col, row));
            }
        }
    }

    @Test
    public void terrainQueryRejectsCellsOutsideTheBoard() {
        Battlefield bf = battlefield();
        try {
            bf.terrainAt(-1, 0);
            fail("越界的列应该报错");
        } catch (IllegalArgumentException expected) {
            // 正确
        }
        try {
            bf.terrainAt(0, bf.board().rows());
            fail("越界的行应该报错");
        } catch (IllegalArgumentException expected) {
            // 正确
        }
    }

    // ---- 挪核心 ----

    /** 核心能挪，挪完旧位置的格子要还回来。 */
    @Test
    public void theCoreCanBeMovedAndTheOldCellsComeBack() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 20, 6);
        assertNotNull(core);

        assertTrue(bf.moveCore(30, 9));

        assertEquals("场上还是只有一座核心", 1, bf.buildings().size());
        assertEquals(30, bf.core().col());
        assertEquals(9, bf.core().row());
        assertTrue(bf.core().covers(new Cell(30, 9)));
        assertNull("旧位置的占位要撤掉", bf.buildingAt(new Cell(20, 6)));
        assertTrue("旧位置能放别的东西了", bf.canPlace(BuildingType.TOWER, 20, 6));
    }

    /**
     * 挪一格也要能成功——<b>这是最容易写错的一步</b>：不把核心自己从占位表里
     * 让开的话，新旧占地重叠会被判成"压住自己"。
     */
    @Test
    public void nudgingTheCoreByOneCellWorks() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 6);

        assertTrue("往右挪一格", bf.moveCore(21, 6));
        assertEquals(21, bf.core().col());
        assertTrue("往下挪一格", bf.moveCore(21, 7));
        assertEquals(21, bf.core().col());
        assertEquals(7, bf.core().row());
        assertEquals(1, bf.buildings().size());
    }

    /** 挪不过去就原地不动：不能压别的建筑、不能上山、不能进通道。 */
    @Test
    public void aMoveThatDoesNotFitLeavesTheCoreWhereItWas() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 6);
        bf.place(BuildingType.TOWER, 25, 6);   // 占 (25..26, 6..7)

        assertFalse("压到塔上", bf.moveCore(25, 6));
        assertFalse("压到塔的一角", bf.moveCore(26, 7));
        assertFalse("山上", bf.moveCore(bf.board().cols() - 2, 6));
        assertFalse("通道里", bf.moveCore(1, 6));
        assertFalse("出界", bf.moveCore(bf.board().cols(), 6));

        assertEquals("失败之后核心必须还在原地", 20, bf.core().col());
        assertEquals(6, bf.core().row());
        assertEquals(2, bf.buildings().size());
    }

    /** 场上没有核心时挪不了——没有东西可挪，不凭空造一座。 */
    @Test
    public void movingWithoutACoreDoesNothing() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.WALL, bf.firstBuildableCol(), 5);

        assertFalse(bf.moveCore(20, 6));
        assertNull(bf.core());
        assertEquals("不该凭空多出一座核心", 1, bf.buildings().size());
    }

    /** 点在原地算成功（什么都不用做），不然界面会误报一次"放不下"的红闪。 */
    @Test
    public void movingOntoItsOwnCellIsFine() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 6);

        assertTrue(bf.moveCore(20, 6));
        assertEquals(20, bf.core().col());
        assertEquals(1, bf.buildings().size());
    }

    // ---- 挪任意一座建筑 ----

    /**
     * 挪动不是核心专属的：墙、塔也能挪。
     *
     * <p>这是"摆错了随时能改"这条规则的地基。只有核心能挪的话，塔放歪一格
     * 就只能拆掉重买——钱白花、等级归零，于是没人敢在开局就下手摆，
     * 而摆位是这游戏唯一的策略动作。
     */
    @Test
    public void anyBuildingCanBeMovedNotJustTheCore() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 10, 5);
        Building tower = bf.place(BuildingType.TOWER, 20, 5);
        assertNotNull(wall);
        assertNotNull(tower);

        assertTrue("墙也能挪", bf.move(wall, 11, 6));
        assertEquals(11, wall.col());
        assertEquals(6, wall.row());

        assertTrue("塔也能挪", bf.move(tower, 30, 10));
        assertEquals(30, tower.col());
        assertEquals(10, tower.row());

        assertEquals("挪动不等于再放一座", 2, bf.buildings().size());
    }

    /**
     * 挪完等级要跟着走。
     *
     * <p>这一条盯的是 {@link Battlefield#move} 没有写成"拆了重放"：那样会新建一个
     * {@code Building}，等级回到 1——挪一下掉两级，玩家会以为升级的钱白花了。
     * 而且拿着旧引用的地方（详情面板、战场上的高亮）会指向一座已经不在场上的建筑。
     */
    @Test
    public void movingATowerCarriesItsLevelAlong() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 20, 5);
        assertTrue(bf.upgrade(tower));
        assertTrue(bf.upgrade(tower));
        assertEquals(3, tower.level);

        assertTrue(bf.move(tower, 25, 9));

        assertEquals("挪完还是 3 级", 3, tower.level);
        assertSame("而且还得是同一条记录，不是新的一座",
                tower, bf.buildingAt(new Cell(25, 9)));
        assertEquals(1, bf.buildings().size());
    }

    /** 挪完旧占地要**整块**还回来，新占地要**整块**记上。 */
    @Test
    public void theWholeFootprintMovesOverNotJustTheCorner() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 20, 5);   // 占 (20..21, 5..6)

        assertTrue(bf.move(tower, 30, 10));                     // 占 (30..31, 10..11)

        for (int col = 20; col <= 21; col++) {
            for (int row = 5; row <= 6; row++) {
                assertNull("旧格子 (" + col + "," + row + ") 要还回来",
                        bf.buildingAt(new Cell(col, row)));
            }
        }
        for (int col = 30; col <= 31; col++) {
            for (int row = 10; row <= 11; row++) {
                assertSame("新格子 (" + col + "," + row + ") 要记上",
                        tower, bf.buildingAt(new Cell(col, row)));
            }
        }
    }

    /**
     * 挪一格（新旧占地重叠一半）也要成功。
     *
     * <p>{@code move} 里"先还旧格子、再占新格子"那个顺序就是为这一条写的：
     * 反过来的话，重叠的那几格会被当成"被自己占着"，于是"往右挪一格"永远失败，
     * 而玩家只会看到红闪一下、不知道为什么。
     */
    @Test
    public void nudgingATowerByOneCellWorks() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 20, 5);

        assertTrue("往右挪一格", bf.move(tower, 21, 5));
        assertEquals(21, tower.col());
        assertTrue("往下挪一格", bf.move(tower, 21, 6));
        assertEquals(6, tower.row());

        assertNull("挪走之后最左那一列要空出来", bf.buildingAt(new Cell(20, 5)));
        assertSame(tower, bf.buildingAt(new Cell(22, 7)));
        assertEquals(1, bf.buildings().size());
    }

    /** 挪不过去就原地不动：压着别人、通道、山区、出界，一种都不能改坐标。 */
    @Test
    public void aMoveThatDoesNotFitLeavesTheBuildingWhereItWas() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 20, 5);   // 占 (20..21, 5..6)
        bf.place(BuildingType.WALL, 25, 5);

        assertFalse("压到墙上", bf.move(tower, 25, 5));
        assertFalse("压到墙的一角", bf.move(tower, 24, 5));
        assertFalse("通道里", bf.move(tower, 1, 5));
        assertFalse("压在山区上", bf.move(tower, bf.lastBuildableCol(), 5));
        assertFalse("出界", bf.move(tower, bf.board().cols(), 5));

        assertEquals("失败之后必须还在原地", 20, tower.col());
        assertEquals(5, tower.row());
        assertSame(tower, bf.buildingAt(new Cell(20, 5)));
        assertEquals("也不该多出/少掉一座", 2, bf.buildings().size());
    }

    /**
     * 不在场上的建筑挪不动——已经拆掉的，或者压根是别处 new 出来的。
     *
     * <p>不查这一条的话，一个"拆过了还在被界面拿着"的引用能把占位表写花：
     * 建筑不在 {@code buildings} 里却占上了格子，既不显示、也拆不掉，
     * 那块地从此谁也放不了。
     */
    @Test
    public void movingABuildingThatIsNotOnTheFieldDoesNothing() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 20, 5);
        assertTrue(bf.remove(tower));

        assertFalse("已经拆掉了", bf.move(tower, 30, 9));
        assertNull("不该把它又摆回场上", bf.buildingAt(new Cell(30, 9)));
        assertEquals(0, bf.buildings().size());

        assertFalse("凭空造的对象", bf.move(new Building(BuildingType.TOWER, 30, 9), 31, 9));
        assertFalse("null 也不行", bf.move(null, 31, 9));
        assertEquals("更不该凭空多出一座", 0, bf.buildings().size());
        assertTrue("那块地还得是空的", bf.canPlace(BuildingType.TOWER, 31, 9));
    }

    /** 挪完之后敌人的目标也跟着变：老位置不再是"摸到就漏"，新位置才是。 */
    @Test
    public void enemiesLeakAtTheCoreWhereverItNowIs() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 10);
        assertTrue(bf.moveCore(12, 10));

        // 从核心左边走过来，会撞在新位置上
        bf.spawn(EnemyType.GRUNT, 10, 5f);
        advanceFor(bf, 10f);   // 够走到新位置（11.5）还富余

        assertEquals(1, bf.leaks());
        // 摸到核心之后**不消失**，站在那儿继续啃——所以场上还有它，
        // 而且就贴在核心左边缘（第 12 列左边 → x = 11.5）
        assertEquals(1, bf.enemies().size());
        assertEquals(11.5f, bf.enemies().get(0).x, 1e-2f);
    }

    /**
     * {@link Battlefield#core()}：界面拿它决定开局镜头对准哪儿。
     * 场上没有核心时返回 {@code null}，不抛异常也不瞎指一个。
     */
    @Test
    public void coreFindsTheCoreAndIsNullWhenThereIsNone() {
        Battlefield bf = battlefield();
        assertNull("还没放核心时应该是 null", bf.core());

        assertNotNull("先得真有一座墙站在场上，不然下面那一问什么也没验",
                bf.place(BuildingType.WALL, bf.firstBuildableCol(), 5));
        assertNull("城墙不是核心", bf.core());

        bf.place(BuildingType.CORE, 30, 7);
        assertNotNull(bf.core());
        assertEquals(BuildingType.CORE, bf.core().type);
        assertEquals(30, bf.core().col());

        bf.remove(bf.core());
        assertNull("拆掉之后又没有了", bf.core());
    }

    /** 拆掉之后格子要还回来。 */
    @Test
    public void removingABuildingFreesItsCells() {
        Battlefield bf = battlefield();
        int col = bf.firstBuildableCol();
        Building tower = bf.place(BuildingType.TOWER, col, 7);

        assertFalse(bf.canPlace(BuildingType.TOWER, col, 7));
        assertTrue(bf.remove(tower));
        assertTrue(bf.canPlace(BuildingType.TOWER, col, 7));
        assertNull(bf.buildingAt(new Cell(col, 7)));
        assertFalse("拆两遍不该成功", bf.remove(tower));
    }

    // ---- 整排：选中与整体挪动 ----

    private static List<Cell> cellsOf(List<Building> group) {
        List<Cell> out = new ArrayList<>();
        for (Building building : group) {
            out.add(new Cell(building.col(), building.row()));
        }
        return out;
    }

    /**
     * 一列墙点出整排：同类型、首尾相接的那一串，上下都走到头。
     *
     * <p>这是"点两下选中整排"的地基。选出来的顺序是按坐标排的（上到下），
     * 界面拿它去画高亮、算总价。
     */
    @Test
    public void aColumnOfWallsComesBackAsOneLine() {
        Battlefield bf = battlefield();
        List<Building> walls = new ArrayList<>();
        for (int row = 10; row < 15; row++) {
            walls.add(bf.place(BuildingType.WALL, 20, row));
        }

        List<Building> line = bf.lineOf(walls.get(2));   // 从中间那一座点下去

        assertEquals("中间点下去也要把上下两头都收进来", 5, line.size());
        assertEquals(cellsOf(walls), cellsOf(line));
    }

    /**
     * <b>竖排优先。</b>一个 2×2 的方块（两列两行塔）两条都成立，取竖的那条。
     *
     * <p>战场上纵深比宽度金贵，玩家布防时脑子里那条线是"这一列"，
     * 不是"这一行"。
     */
    @Test
    public void aColumnBeatsARowWhenBothAreThere() {
        Battlefield bf = battlefield();
        Building topLeft = bf.place(BuildingType.TOWER, 20, 4);
        Building topRight = bf.place(BuildingType.TOWER, 22, 4);
        Building bottomLeft = bf.place(BuildingType.TOWER, 20, 6);
        bf.place(BuildingType.TOWER, 22, 6);

        List<Building> line = bf.lineOf(topLeft);

        assertEquals(2, line.size());
        assertEquals("竖着那一列：上下的两座", cellsOf(List.of(topLeft, bottomLeft)),
                cellsOf(line));
        assertFalse("横着那两座不算这条线上", line.contains(topRight));
    }

    /** 没有竖排时才轮到横排。 */
    @Test
    public void aRowIsUsedWhenThereIsNoColumn() {
        Battlefield bf = battlefield();
        Building left = bf.place(BuildingType.TOWER, 20, 4);
        Building right = bf.place(BuildingType.TOWER, 22, 4);
        Building farRight = bf.place(BuildingType.TOWER, 24, 4);

        List<Building> line = bf.lineOf(left);

        assertEquals(3, line.size());
        assertEquals(cellsOf(List.of(left, right, farRight)), cellsOf(line));
    }

    /** 孤零零一座就返回它自己——界面靠这个决定"选中整排"那个按钮亮不亮。 */
    @Test
    public void aLonelyBuildingIsALineOfOne() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 20, 5);

        assertEquals(List.of(wall), bf.lineOf(wall));
    }

    /**
     * <b>断了就不算一排。</b>中间空一格的两个塔群合成一排的话，一起挪的时候
     * 中间那一格会被拉平，谁也没想那样。
     */
    @Test
    public void aGapBreaksTheLineInTwo() {
        Battlefield bf = battlefield();
        Building above = bf.place(BuildingType.WALL, 20, 5);
        Building below = bf.place(BuildingType.WALL, 20, 7);   // 第 6 行空着

        assertEquals(List.of(above), bf.lineOf(above));
        assertEquals(List.of(below), bf.lineOf(below));
    }

    /**
     * <b>一排是"挨着的同类"，不是"挨着的任何东西"。</b>
     *
     * <p>墙连着塔的时候，从墙点下去只该选中墙。混着选的话，
     * "一起升级"会按最贵的那座报总价，而玩家看着一排墙按了按钮。
     */
    @Test
    public void neighboursOfAnotherKindAreNotPartOfTheLine() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 20, 5);
        bf.place(BuildingType.TOWER, 20, 6);

        assertEquals(List.of(wall), bf.lineOf(wall));
    }

    /**
     * <b>不比等级。</b>一列墙里有一座升过级了，它还是那一列。
     *
     * <p>玩家眼里的"一列墙"是摆在那儿的一串，不是"一串一样强的"。
     * 一起升级时到顶的那座跳过就是了（玩法侧的事）。
     */
    @Test
    public void aLineDoesNotCareAboutLevels() {
        Battlefield bf = battlefield();
        Building low = bf.place(BuildingType.WALL, 20, 5);
        Building high = bf.place(BuildingType.WALL, 20, 6);
        assertTrue(bf.upgrade(high));
        assertTrue(bf.upgrade(high));
        assertEquals(3, high.level);

        assertEquals(2, bf.lineOf(low).size());
        assertEquals(2, bf.lineOf(high).size());
    }

    /**
     * 2×2 的塔"挨着"是按<b>占地</b>算的，不是左上角差一格。
     *
     * <p>第 4 行那座占 4-5 行、第 6 行那座占 6-7 行，看着就是贴在一起的，
     * 所以它们是一排。反过来把"挨着"写成左上角差 2 才算的话，
     * 一列塔永远选不中。
     */
    @Test
    public void towersTouchByFootprintNotByCorner() {
        Battlefield bf = battlefield();
        Building above = bf.place(BuildingType.TOWER, 20, 4);   // 占 4..5 行
        Building below = bf.place(BuildingType.TOWER, 20, 6);   // 占 6..7 行

        assertEquals(cellsOf(List.of(above, below)), cellsOf(bf.lineOf(above)));
    }

    /** 不在场上的建筑（拆过了、别的局留下的）没有整排，返回空表。 */
    @Test
    public void aBuildingThatIsNotOnTheFieldHasNoLine() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 20, 5);
        bf.remove(wall);

        assertTrue(bf.lineOf(wall).isEmpty());
        assertTrue(bf.lineOf(null).isEmpty());
    }

    /**
     * 一列墙整体往右挪一格：<b>新格子有一半正是自己这一排的旧格子。</b>
     *
     * <p>这条是 {@link Battlefield#moveAll} 存在的理由里最容易写错的一处。
     * 判定时不把整排自己让开的话，整体平移会当场判失败——而整体平移
     * 正是这个功能最常见的用法。
     */
    @Test
    public void aWholeColumnSlidesSidewaysOverItsOwnCells() {
        Battlefield bf = battlefield();
        List<Building> walls = new ArrayList<>();
        for (int row = 10; row < 15; row++) {
            walls.add(bf.place(BuildingType.WALL, 20, row));
        }

        assertTrue("整体平移：新格子压着的全是自己人", bf.moveAll(walls, 1, 0));

        for (int i = 0; i < walls.size(); i++) {
            assertEquals(21, walls.get(i).col());
            assertEquals(10 + i, walls.get(i).row());
        }
        for (int row = 10; row < 15; row++) {
            assertSame("新格子记上了", walls.get(row - 10), bf.buildingAt(new Cell(21, row)));
            assertNull("最左边那一列让出来了", bf.buildingAt(new Cell(20, row)));
        }
        assertEquals("挪动不等于再建一排", 5, bf.buildings().size());
    }

    /** 整体平移不花钱，也不新建记录——等级、引用都跟着走。 */
    @Test
    public void slidingAColumnCarriesEveryLevelAlong() {
        Battlefield bf = battlefield();
        Building low = bf.place(BuildingType.WALL, 20, 5);
        Building high = bf.place(BuildingType.WALL, 20, 6);
        assertTrue(bf.upgrade(high));
        assertTrue(bf.upgrade(high));

        List<Building> line = List.of(low, high);
        assertTrue(bf.moveAll(line, 0, 3));

        assertEquals(3, high.level);
        assertSame(high, bf.buildingAt(new Cell(20, 9)));
        assertSame(low, bf.buildingAt(new Cell(20, 8)));
        assertEquals(2, bf.buildings().size());
    }

    /**
     * <b>要么整排都挪，要么整排都不动。</b>
     *
     * <p>最后一格压着一座排外的塔：那一座挪不过去，于是另外四座也不动。
     * 允许"挪过去三座、剩下两座卡住"的话，玩家自己都记不清哪几座动了。
     */
    @Test
    public void oneBlockedMemberStopsTheWholeColumn() {
        Battlefield bf = battlefield();
        List<Building> walls = new ArrayList<>();
        for (int row = 10; row < 15; row++) {
            walls.add(bf.place(BuildingType.WALL, 20, row));
        }
        Building stray = bf.place(BuildingType.WALL, 21, 14);   // 挡在最后一格要去的路上

        assertFalse(bf.moveAll(walls, 1, 0));

        for (int i = 0; i < walls.size(); i++) {
            assertEquals("一座都不该动", 20, walls.get(i).col());
            assertEquals(10 + i, walls.get(i).row());
            assertSame(walls.get(i), bf.buildingAt(new Cell(20, 10 + i)));
        }
        assertEquals(21, stray.col());
        assertEquals(14, stray.row());
    }

    /** 出界、压通道、压山区：和单座一样挪不过去，而且同样一座都不动。 */
    @Test
    public void aColumnCannotSlideOffTheBuildableGround() {
        Battlefield bf = battlefield();
        int edge = bf.firstBuildableCol();   // 可建区最左那一列，左边就是通道
        Building top = bf.place(BuildingType.WALL, edge, 10);
        Building bottom = bf.place(BuildingType.WALL, edge, 11);
        List<Building> line = List.of(top, bottom);

        assertFalse("再往左一格是通道，不是空地", bf.moveAll(line, -1, 0));
        assertEquals(edge, top.col());
        assertEquals(edge, bottom.col());

        assertFalse("往下挪出行高也是不行的", bf.moveAll(line, 0, bf.board().rows()));
        assertEquals(10, top.row());
    }

    /**
     * {@link Battlefield#canMoveAll} 是 {@link Battlefield#moveAll} 的试算，
     * <b>两者必须严格同进同退</b>。
     *
     * <p>拖动时的虚影就是拿它决定画绿还是画红：它说行、{@code moveAll} 说不行的话，
     * 玩家看到一块绿框、松手却弹回来，而且不知道是为什么。反过来更糟——
     * 画着红框却能挪过去，等于把"这里放不下"这条提示变成假话。
     */
    @Test
    public void canMoveAllAgreesWithMoveAllOnEveryCase() {
        Battlefield bf = battlefield();

        // 一列墙往右一格：新格子压着的全是自己人，两支都该说行
        List<Building> column = new ArrayList<>();
        for (int row = 10; row < 15; row++) {
            column.add(bf.place(BuildingType.WALL, 20, row));
        }
        assertTrue(bf.canMoveAll(column, 1, 0));
        assertTrue(bf.moveAll(column, 1, 0));

        // 通道那一侧：出可建区，两支都该说不行
        List<Building> atEdge = new ArrayList<>();
        int edge = bf.firstBuildableCol();
        for (int row = 10; row < 13; row++) {
            atEdge.add(bf.place(BuildingType.WALL, edge, row));
        }
        assertFalse(bf.canMoveAll(atEdge, -1, 0));
        assertFalse("试算说不行，真挪也必须不行", bf.moveAll(atEdge, -1, 0));
        assertEquals("而且一座都没动", edge, atEdge.get(0).col());

        // 压着排外的建筑
        Building stray = bf.place(BuildingType.WALL, 22, 10);
        assertFalse(bf.canMoveAll(column, 1, 0));
        assertFalse(bf.moveAll(column, 1, 0));
        assertEquals("连没被压到的那几座也不许动", 21, column.get(0).col());
        assertEquals(22, stray.col());

        // 空表 / null：两边都是 false
        assertFalse(bf.canMoveAll(List.of(), 1, 0));
        assertFalse(bf.canMoveAll(null, 1, 0));
        assertFalse(bf.moveAll(List.of(), 1, 0));
    }

    /** 原地不动算成功（和单座 {@code move} 一个口径），空的一排算失败。 */
    @Test
    public void aZeroSlideSucceedsAndAnEmptyGroupFails() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 20, 5);

        assertTrue(bf.moveAll(List.of(wall), 0, 0));
        assertEquals(20, wall.col());

        assertFalse(bf.moveAll(List.of(), 1, 0));
        assertFalse(bf.moveAll(null, 1, 0));
    }

    /** 场上已经没有的成员混在里面，整排也不动。 */
    @Test
    public void aGroupWithAGhostInItDoesNotMoveAtAll() {
        Battlefield bf = battlefield();
        Building kept = bf.place(BuildingType.WALL, 20, 5);
        Building gone = bf.place(BuildingType.WALL, 20, 6);
        bf.remove(gone);

        assertFalse(bf.moveAll(List.of(kept, gone), 1, 0));
        assertEquals("在场的那座也不许动", 20, kept.col());
        assertEquals(5, kept.row());
    }

    // ---- 敌人 ----

    @Test
    public void enemiesWalkRightAtTheConfiguredSpeed() {
        Battlefield bf = battlefield();
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 0f);
        float speed = EnemyType.GRUNT.speedCellsPerSec;

        bf.advance(1f);
        assertEquals(speed, e.x, 1e-4f);
        bf.advance(2f);
        assertEquals(speed * 3f, e.x, 1e-4f);

        // 非正数的 dt 不该推动任何东西
        bf.advance(0f);
        bf.advance(-5f);
        assertEquals(speed * 3f, e.x, 1e-4f);
    }

    /**
     * 三种敌人的速度是<b>排好序</b>的：快兵最快、重甲最慢。
     *
     * <p>不这么定的话，"快兵"这个名字就是假的，而且 {@link Waves} 里
     * "重甲在前、快兵压尾"那套难度设计会反过来——慢的重甲压尾的话，
     * 它还没走到，前面的已经被清完了。
     */
    @Test
    public void runnersAreFasterThanGruntsAndBrutesAreSlower() {
        assertTrue("快兵要比杂兵快",
                EnemyType.RUNNER.speedCellsPerSec > EnemyType.GRUNT.speedCellsPerSec);
        assertTrue("重甲要比杂兵慢",
                EnemyType.BRUTE.speedCellsPerSec < EnemyType.GRUNT.speedCellsPerSec);
        assertTrue("重甲也最厚",
                EnemyType.BRUTE.maxHp > EnemyType.GRUNT.maxHp
                        && EnemyType.GRUNT.maxHp > EnemyType.RUNNER.maxHp);
        assertTrue("三种都会咬人", EnemyType.GRUNT.attacks()
                && EnemyType.RUNNER.attacks() && EnemyType.BRUTE.attacks());
    }

    /** 城墙挡路：敌人停在它左边贴着，而不是穿过去。 */
    @Test
    public void aWallStopsAnEnemyJustBeforeIt() {
        Battlefield bf = battlefield();
        int wallCol = bf.firstBuildableCol() + 2;   // 墙砌在可建区里、离通道口两格
        bf.place(BuildingType.WALL, wallCol, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);   // 从通道里走过来

        advanceFor(bf, 6f);   // 足够走完 5 格多

        assertEquals("应该贴在城墙左边", wallCol - 0.5f, e.x, 1e-3f);
        assertTrue(e.blocked);
        assertEquals("没漏怪", 0, bf.leaks());
    }

    /**
     * 塔也挡路（这一版没有寻路，敌人不会绕）。
     *
     * <p><b>用弩车摆，不用大炮。</b>这条要推满二十秒，而大炮十格以内不开火——
     * 敌人贴到它身上啃八秒就把它拆了，然后继续往前走，二十秒后根本不在原位。
     * 弩车贴脸直射，能自己把啃它的那只收拾掉，塔才站得住。
     */
    @Test
    public void aTowerBlocksToo() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);   // 占 (towerCol..towerCol+1, 3..4)
        float from = bf.firstBuildableCol() - 3f;    // 从通道里出发
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, from);

        bf.advance(0.5f);   // 一步 0.45 格，只够挪一小段

        assertEquals(from + 0.45f, e.x, 1e-3f);
        assertFalse(e.blocked);

        advanceFor(bf, 20f);
        assertEquals("撞上塔之后贴住", towerCol - 0.5f, e.x, 1e-3f);
        assertTrue(e.blocked);
    }

    /**
     * 一帧跨好几格时不能穿过障碍。<b>手机卡一下就会发生</b>：
     * 掉帧时 dt 变大，不检查中间格子的话敌人会直接穿墙。
     */
    @Test
    public void aLongFrameCannotTunnelThroughABlocker() {
        Battlefield bf = battlefield();
        int wallCol = bf.firstBuildableCol() + 2;
        bf.place(BuildingType.WALL, wallCol, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);

        bf.advance(10f);   // 一步 9 格，足够跨过城墙

        assertEquals(wallCol - 0.5f, e.x, 1e-3f);
        assertTrue(e.blocked);
    }

    /** 摸到核心算漏怪，而且**只算一次**——它会站在那儿一直啃。 */
    @Test
    public void reachingTheCoreCountsAsALeakOnce() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 8, 10);
        Enemy e = bf.spawn(EnemyType.GRUNT, 11, 7f);   // 走在核心占的第 11 行

        advanceFor(bf, 5f);   // 够摸到核心（x = 7.5）

        assertEquals(1, bf.leaks());
        assertTrue("摸到核心之后留在场上继续啃", bf.enemies().contains(e));
        assertTrue(e.reachedCore);
        assertEquals(7.5f, e.x, 1e-2f);

        // 再啃十秒，还是只算一次
        advanceFor(bf, 10f);
        assertEquals("一只敌人只算一次漏", 1, bf.leaks());
    }

    /** 那一行没有核心也没挡住，敌人会从右边走出去，也算漏。 */
    @Test
    public void anEnemyThatCrossesTheFieldLeaks() {
        Battlefield bf = battlefield();
        // 战场有 40 格，从最右边两格出发，一步就跨出去了
        bf.spawn(EnemyType.GRUNT, 0, bf.board().cols() - 2f);

        bf.advance(5f);   // 一步 4.5 格，跨过右边缘

        assertEquals(1, bf.leaks());
        assertTrue(bf.enemies().isEmpty());
    }

    /**
     * {@link Enemy#travelled} 记的是<b>路程</b>：直着走的时候，它就等于 x 挪了多少。
     *
     * <p>没有核心、没有建筑的时候敌人一路向右（{@code nextCell} 退化成"右边那一格"），
     * 所以这一局里横的位移就是全部路程。
     */
    @Test
    public void travelledEqualsHowFarItWalked() {
        Battlefield bf = battlefield();
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        bf.advance(1f);   // 杂兵 0.9 格/秒

        assertEquals(0.9f, e.x - 2f, 1e-3f);
        assertEquals("直着走：路程 = 横着挪了多少", e.x - 2f, e.travelled, 1e-3f);
    }

    /**
     * <b>竖着换行的那几格也要算进路程</b>——这就是"纵向走的时候不迈步子"那个 bug。
     *
     * <p>视图早先是拿 {@code enemy.x} 除步长来翻走路的两帧的，往右走看着一切正常；
     * 可敌人往核心走是要<b>换行</b>的，而那几格 {@code x} 一动不动，于是整只敌人
     * 平移着上去、腿一下都不迈。修法是让引擎在唯一一处改坐标的地方记路程。
     *
     * <p>所以这条测试盯的是"<b>只横着挪了 6.5 格，路程却是 12 格多</b>"：
     * 差的这 6 格就是竖着走的那一段，看 {@code x} 是永远看不见的。
     */
    @Test
    public void sidewaysStepsCountTowardsDistanceToo() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 8, 10);   // 逼敌人从第 3 行下到核心那几行
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        advanceFor(bf, 20f);   // 走到底：贴着核心那一列停住开啃

        assertEquals("横着只挪了 6.5 格", 6.5f, e.x - 2f, 1e-2f);
        assertEquals("竖着降了 6 格", 6f, e.y - 3.5f, 1e-2f);
        // 拐弯那一段是斜着过去的（引擎朝的是下一格的中心，不是先横后竖），
        // 所以路程比"横的加竖的"（12.5）略短一点。
        assertTrue("一共走了 " + e.travelled + " 格，不该只有横着那 6.5 格",
                e.travelled > 11.5f);
    }

    /** 停下来啃东西的时候不涨：没动就是没动，那时候画的是挥击。 */
    @Test
    public void chewingDoesNotCountAsWalking() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.WALL, bf.firstBuildableCol() + 2, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);

        advanceFor(bf, 6f);   // 走过去贴上，然后开始啃
        assertTrue(e.blocked);
        float whenItBarked = e.travelled;

        advanceFor(bf, 5f);   // 啃五秒

        assertEquals("啃的时候一步没走", whenItBarked, e.travelled, 1e-3f);
    }

    // ---- 打起来 ----

    /**
     * <b>射界里最靠前的那一只挨打，哪怕另一只离塔更近。</b>
     *
     * <p>规则是"打最靠前的"，不是"打离自己最近的"（理由见
     * {@code Battlefield.frontmostInRange}——"就近"试过一版，五波的演示场面
     * 第 2 波就会 Overrun）。这一条要把两种规则<b>分得开</b>：
     * 两只都得在射界里，而且"更靠前的"必须同时是"更远的"，否则选谁都能过，
     * 测试就等于没验。
     *
     * <p><b>2026-09-27 起这条用弩车摆，不用大炮。</b>大炮换成十到二十格的扇环之后，
     * 它十格以内压根不打，而这条要的是"两只都够得着、只有挑谁的区别"——
     * 拿大炮摆就得把两只都摆到十几格外，还得同时让"更靠前的"更远，
     * 越摆越绕。弩车贴脸直射、射界又宽，正是这条规则合适的舞台
     * （大炮那一套射界几何由 {@code CostTest} 里的 FireArc 那几条直接盯着）。
     *
     * <p><b>朝左的扇形让"更靠前"和"更近"差点变成同一件事</b>：塔全朝左，
     * 射界里的一切都在它左边，于是 x 越大离塔越近。这里靠<b>行</b>把两者掰开：
     * 更靠前的那只走第 4 行（离核心那三行只差 0 格，进度 12），离塔心 2.19 格；
     * 更靠后的那只走第 2 行（离核心多绕 2 格，进度 14），离塔心只有 1.77 格。
     * 按"最靠前"该打第 4 行那只，按"最近"该打第 2 行那只——两种规则给出相反的结果。
     *
     * <p>两只的 x 只差 0.1 格，看着像并排：这不是随手摆的。射界朝左的前提下，
     * 要让"更靠前的"同时"更远的"，两只的 x 就<b>不能拉开</b>（拉远了近的那只必然
     * 也更靠后），差多少由射程和射界的余量算出来，最多半格出头。所以这条测试
     * 真正验的是<b>行</b>带来的进度差，不是 x。
     *
     * <p><b>塔摆在院子中间那条道旁边</b>（占第 2–3 行），场上<b>摆了核心</b>，
     * 所以走的是真的寻路表那一支；没核心那一支由下面
     * {@link #withoutACoreTheProgressStillRunsFromTheLeft} 盯着。
     */
    @Test
    public void aTowerShootsTheEnemyFurthestAlongNotTheNearestOne() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 4);                    // 核心占 (20..22, 4..6)
        int towerCol = bf.firstBuildableCol() + 1;
        Building tower = bf.place(BuildingType.BALLISTA, towerCol, 2);   // 占 (9..10, 2..3)
        float centreX = towerCol + 1f;                          // 2×2 的圆心 (10, 3)
        Enemy ahead = bf.spawn(EnemyType.GRUNT, 4, centreX - 1.6f);    // 更靠前，离塔心 2.19 格
        Enemy behind = bf.spawn(EnemyType.GRUNT, 2, centreX - 1.7f);   // 更靠后，离塔心 1.77 格

        // 先钉住"两只都在射界里"。少了这一句，哪天射界被调小、远的那只其实够不着，
        // 这条就又变成没验了。用的就是引擎那一句判定，不另抄一份公式。
        BuildingStats.FireArc arc = BuildingStats.fireArc(BuildingType.BALLISTA, 1);
        for (Enemy at : new Enemy[]{ahead, behind}) {
            assertTrue("两只都得在射界里，不然这条测试什么也没验",
                    arc.catches(at.x - centreX, at.y - 3.0f,
                            EnemyType.GRUNT.halfBodyCells()));
        }
        // 再钉住"近的那只确实更近"：不成立的话两种规则会挑到同一只，也等于没验
        assertTrue("靠后的那只确实离塔更近",
                Math.hypot(behind.x - centreX, behind.y - 3.0f)
                        < Math.hypot(ahead.x - centreX, ahead.y - 3.0f));

        // 半秒：一级弩车 2.6 秒一发，所以这半秒里<b>正好一发</b>（一帧就打完整个
        // 装填周期的话会打两发，见 theFireRateDoesNotDependOnTheFrameRate 那条）；
        // 而子弹 9 格/秒，半秒够飞 4.5 格，2.2 格那一段早落地了
        bf.advance(0.5f);

        assertEquals("走得更靠前的那只挨了一发（34 点）",
                EnemyType.GRUNT.maxHp - 34f, ahead.hp, 1e-3f);
        assertEquals("更近、但还在后头的那只一点没掉",
                EnemyType.GRUNT.maxHp, behind.hp, 1e-3f);
        assertSame(ahead, tower.target);
    }

    /**
     * <b>场上没核心时，"最靠前"的方向不能反过来。</b>
     *
     * <p>这一条盯的是 {@code Battlefield.progressOf} 两个分支的<b>方向要一致</b>。
     * 猜错方向的后果很隐蔽：它只在<b>场上没核心</b>时发作，有核心的正常对局里
     * 永远走不到那一支（{@code path == null}）。上面那条走真的寻路表，
     * 这条特意用没有核心的场，两条合起来才盖全。
     *
     * <p>没核心时敌人退回"一路向右"，那么"还剩多少路"就是"离右边界还有多远"，
     * 于是 x 大的反而进度小、才是更靠前的那只。和上面那条的区别只在"有没有核心"
     * 和两只摆得近一点（这里验的是方向，不需要把"更靠前的"也摆成更远的那只）。
     */
    @Test
    public void withoutACoreTheProgressStillRunsFromTheLeft() {
        Battlefield bf = battlefield();   // 这个场不摆核心
        int towerCol = bf.firstBuildableCol() + 1;
        Building tower = bf.place(BuildingType.BALLISTA, towerCol, 2);   // 圆心 (10, 3)
        Enemy behind = bf.spawn(EnemyType.GRUNT, 2, towerCol - 1.8f);
        Enemy ahead = bf.spawn(EnemyType.GRUNT, 4, towerCol - 0.8f);

        bf.advance(0.5f);

        assertEquals("右边那只才是更靠前的", EnemyType.GRUNT.maxHp - 34f, ahead.hp, 1e-3f);
        assertEquals("左边那只一点没掉", EnemyType.GRUNT.maxHp, behind.hp, 1e-3f);
        assertSame(ahead, tower.target);
    }

    // ---- 弹道 ----

    /**
     * 塔是"装填好了打一发"，不是每帧都在扣血。
     *
     * <p>盯的是"伤害在<b>子弹落地</b>那一刻才发生"这件事：扣血如果还留在
     * {@code fireTowers} 里（出膛就扣），画面上会看到敌人先掉血、子弹后飞到的倒放。
     */
    @Test
    public void aTowerFiresOnItsReloadTimerNotEveryFrame() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);   // 圆心 (towerCol + 1, 4)
        Enemy e = bf.spawn(EnemyType.GRUNT, 4, towerCol - 1f);   // 射界里，2.06 格

        bf.advance(0.05f);   // 一帧：开火，子弹出膛

        assertEquals("出膛了一发", 1, bf.projectiles().size());
        assertEquals("还在飞，血一点没掉", EnemyType.GRUNT.maxHp, e.hp, 1e-3f);

        advanceFor(bf, 0.5f);   // 2.06 格 ÷ 9 格每秒 ≈ 0.25 秒，早落地了

        assertEquals("落地才扣这一发", EnemyType.GRUNT.maxHp - 34f, e.hp, 1e-3f);
        assertTrue("打完了，天上没东西了", bf.projectiles().isEmpty());
        // 装填是 2.6 秒，而这 0.55 秒里只该有那一发（血只掉了一发，不是两发 68 点）
    }

    /**
     * 同样的时间、不同的帧长，挨的伤害必须一样。
     *
     * <p>装填计时是"减 dt、开火时再加回去"（{@code Battlefield.fireTowers}），
     * 换成"开火时直接赋值成间隔"的话，每发都会白丢一帧的时间
     * （dt 0.05 / 间隔 1.0 = 5%），而且帧长不同结果不同——同一条路配同样的塔，
     * 60Hz 的手机守得住、120Hz 的守不住，这种 bug 在真机上基本查不出来。
     */
    @Test
    public void theFireRateDoesNotDependOnTheFrameRate() {
        Enemy at60Hz = shelledFor(4f, 0.05f);
        Enemy at120Hz = shelledFor(4f, 0.02f);

        // 不然"两边相等"可能只是因为一炮都没打出去，那这条测试什么也没验
        assertTrue("至少得打中两发，实际 " + (EnemyType.BRUTE.maxHp - at60Hz.hp),
                at60Hz.hp <= EnemyType.BRUTE.maxHp - 2 * 34f);
        assertEquals("两种帧率挨的伤害该一模一样", at60Hz.hp, at120Hz.hp, 1e-3f);
    }

    /**
     * 一座 1 级弩车打一只走进射界里停住的重甲，按给定帧长推 {@code seconds} 秒，返回那只敌人。
     *
     * <p>选重甲是因为它够厚：4 秒里打中的那两发（68 点）远不到 160，
     * 血量能一直当"挨了多少"的读数用。
     *
     * <p><b>为什么是 4 秒、不是从前那个 4.5。</b>弩车比从前的箭塔薄（80 血对 100），
     * 重甲 26 dps 啃穿它只要 3.1 秒，而重甲贴到它身上要 1.6 秒——4.5 秒时弩车
     * 只剩 5 点血活着，那种"差一点就崩"的余量不该出现在一条测试里。4 秒留出
     * 二十点，同时仍然够打满两发（间隔 2.6 秒）。
     */
    private static Enemy shelledFor(float seconds, float step) {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);       // 圆心 (towerCol + 1, 4)，射界 3.0
        Enemy brute = bf.spawn(EnemyType.BRUTE, 4, towerCol - 1.5f);   // 一开局就在射界里
        for (float t = 0f; t < seconds; t += step) {
            bf.advance(step);
        }
        return brute;
    }

    /**
     * <b>已经在天上的伤害也算数</b>：塔不会对着"确定要死的那只"再多打。
     *
     * <p>两座塔都够得着同一只杂兵，而这只兵只剩 14 血——一发就够。
     * 第一帧只该有<b>一发</b>出膛。少了这一问（{@code damageInFlightAt}），
     * 两座会一起吐两发——多出来的那发打空，而它本来可以打给后面那只。
     * 一座塔看不出区别，一排塔一起打一波的时候，火力是"铺开"还是"堆在一只身上"
     * 全靠这一条。
     *
     * <p><b>为什么血要手动按到 14。</b>旧的写法是"五座塔围着一只 45 血的杂兵，
     * 第五座收手"，但改成朝左的扇形之后<b>那个局面摆不出来了</b>：射界只有 120°，
     * 加上塔是 2×2 的占地，全盘扫下来能同时打一只敌人的塔<b>最多三座</b>
     * （再想加一座，角度就顶到 60° 的边上或者身子叠上了）。所以这里直接把血量
     * 按到"一发就死"——验的是那一问本身，不是血量从哪来。
     *
     * <p><b>用的是弩车</b>：一发 34 点，比那 14 点血富余得多；而且它贴脸直射，
     * 两座摆得近也都在射界里（大炮十格以内压根不开火，摆不出这个局面）。
     */
    @Test
    public void towersDoNotPileOntoAnEnemyThatIsAlreadyCovered() {
        Battlefield bf = battlefield();
        // 两座都在射界里（各偏离正左 45°），而且都不占第 9 行——敌人走的那一行
        int towerCol = bf.firstBuildableCol() + 3;
        bf.place(BuildingType.BALLISTA, towerCol, 7);    // 圆心 (towerCol + 1, 8)，离敌人 2.12 格
        bf.place(BuildingType.BALLISTA, towerCol, 10);   // 圆心 (towerCol + 1, 11)，离敌人 2.12 格
        Enemy grunt = bf.spawn(EnemyType.GRUNT, 9, towerCol - 0.5f);
        grunt.hp = 14f;   // 一发就死

        bf.advance(0.05f);

        assertEquals("一发就够打死，第二座塔该把这一发留给下一只",
                1, bf.projectiles().size());

        advanceFor(bf, 1f);
        assertFalse("那发落地了，杂兵该死", grunt.alive());
        assertTrue("死了之后没人再开火", bf.projectiles().isEmpty());
    }

    /**
     * 子弹落地时问的是"出膛时瞄的那只还喘不喘气"，不是"这个地方现在站着谁"。
     *
     * <p><b>真实玩法里造不出这个局面</b>：塔只对活人开火，而"天上已有的伤害够不够"
     * 是挑目标时要减掉的（{@code damageInFlightAt}），所以一只敌人要死，
     * 一定是被最后落地的那一发打死的——天上不会有跟着白飞的子弹。
     * 这里直接把血按到 0 是为了守住那一问本身：少了它，{@code moveProjectiles}
     * 会去扣一只已经不在场上的敌人的血（不崩，但"打死了还继续打"这件事迟早
     * 会以别的形式冒出来）。
     *
     * <p><b>这条只管没有溅射的那些（弩车）。</b>大炮那一发反过来——目标死了
     * 它照样炸，旁边站着的照样挨，见 {@link #aShellStillExplodesWhereItsTargetDied}。
     * 两支的分岔就在 {@code Projectile.splashCells} 那一个数上。
     */
    @Test
    public void aBulletThatArrivesAfterItsTargetIsGoneDealsNoDamage() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);
        Enemy doomed = bf.spawn(EnemyType.GRUNT, 4, towerCol - 1f);

        bf.advance(0.05f);
        assertEquals("一发在天上", 1, bf.projectiles().size());

        doomed.hp = 0f;

        advanceFor(bf, 1f);

        assertTrue("那一发到了就消失，不留在天上", bf.projectiles().isEmpty());
        assertFalse(doomed.alive());
        assertEquals("没多记一笔漏怪", 0, bf.leaks());
    }

    /** 重开一局不该带着上一局的子弹：它们瞄的敌人已经不在了。 */
    @Test
    public void clearingTheFieldTakesTheShotsOutOfTheAir() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);
        bf.spawn(EnemyType.GRUNT, 4, towerCol - 1f);
        bf.advance(0.05f);
        assertFalse("先得真有一发在天上，不然这条测试什么也没验",
                bf.projectiles().isEmpty());

        bf.clear();

        assertTrue(bf.projectiles().isEmpty());
    }

    /**
     * 射界外（太远）的打不着。距离从建筑占地的中心算，单位是格。
     *
     * <p>用弩车摆：大炮的射界还有个"太近也不行"的内径，那半边在
     * {@link #theCannonCannotShootAnythingInsideItsBlindSpot} 里。
     */
    @Test
    public void enemiesOutsideTheRangeTakeNoDamage() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        bf.place(BuildingType.BALLISTA, towerCol, 3);
        Enemy far = bf.spawn(EnemyType.GRUNT, 10, 20f);   // 另一行，离得很远

        advanceFor(bf, 3f);

        assertEquals("满血：没被打过", EnemyType.GRUNT.maxHp, far.hp, 1e-3f);
        assertNull("没有目标", bf.buildingAt(new Cell(towerCol, 3)).target);
    }

    /**
     * <b>塔只往左打。</b>射界是朝左的扇形，所以站在塔<b>右边</b>的敌人，
     * 哪怕一样近、哪怕在射程里，也不挨打——它已经走过去了。
     *
     * <p>这条是"塔不转向、一律朝左"（{@code BuildingStats.FireArc}）这个设定的
     * 直接后果，也是"摆位"之所以有意义的全部来路：
     * 塔摆在敌人来路的左边才有用，摆在右边等于白摆。
     *
     * <p>两只<b>一样近</b>（都离塔心 1.58 格），唯一的差别是在塔的哪一侧。
     * 所以"打哪只"这件事把"射界"和"就近"分得干干净净：按就近是平局，
     * 按整圆两只都该挨打，只有按"朝左的扇形"才只剩左边那只。
     */
    @Test
    public void towersOnlyShootToTheirLeft() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 1;
        Building tower = bf.place(BuildingType.BALLISTA, towerCol, 3);   // 占 (towerCol..towerCol+1, 3..4)
        float centreX = towerCol + 1f;                                // 圆心
        Enemy left = bf.spawn(EnemyType.GRUNT, 4, centreX - 1.5f);     // 离塔心 1.58 格
        Enemy right = bf.spawn(EnemyType.GRUNT, 4, centreX + 1.5f);    // 同样 1.58 格，但在右边

        bf.advance(0.05f);
        assertSame("先得真挑中了左边那只，不然这条测试什么也没验", left, tower.target);

        advanceFor(bf, 0.5f);
        assertEquals("左边那只挨了一发", EnemyType.GRUNT.maxHp - 34f, left.hp, 1e-3f);
        assertEquals("右边那只一点没掉，哪怕它一样近", EnemyType.GRUNT.maxHp, right.hp, 1e-3f);
    }

    /**
     * <b>大炮十格以内不开火。</b>它的射界是一个十到二十格的扇环，
     * 内径那十格是<b>真的打不着</b>，不是"命中率低"。
     *
     * <p>这条盯的是 {@link BuildingStats#CANNON_INNER_CELLS} 那个数真的被用上了。
     * 射界最容易写错的地方就是漏掉内径——只写"外径二十格"的话，
     * 代码跑起来一切正常、测试也全绿，只有摆到阵地上才发现敌人贴到脸上它一声不吭。
     * 所以这里放一只<b>正对着炮口、五格远</b>的敌人：方向对、距离够近、
     * 什么都不缺，只差"太近了"。
     */
    @Test
    public void theCannonCannotShootAnythingInsideItsBlindSpot() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 4;
        float centreX = towerCol + 1f;
        Building cannon = bf.place(BuildingType.TOWER, towerCol, 3);
        Enemy close = bf.spawn(EnemyType.GRUNT, 4, centreX - 5f);   // 正左五格

        advanceFor(bf, 3f);

        assertNull("十格以内不开火", cannon.target);
        assertEquals("一发都没挨", EnemyType.GRUNT.maxHp, close.hp, 1e-3f);
    }

    /**
     * <b>大炮的射界是有限张角的：正左、上下各三十度，一共六十度。</b>
     * 十六格远是够得着的，可偏到旁边去它就不打了。
     *
     * <p>对照组是这条测试的另一半：<b>同样十六格</b>、同样一只杂兵，
     * 挪到正左方向上就立刻被瞄上。两半合起来才说明拦下第一只的是<b>角度</b>，
     * 而不是距离或者别的什么——只验前一半的话，射界写成"什么都不打"也能过。
     *
     * <p><b>偏出去的那只必须真的在三十度以外，所以它被往外挪过两次。</b>
     * 从前张角是十五度、这只兵在第 10 行（横向 15 格、纵向 6.5 格，偏 23°），
     * 改宽到三十度之后 23° 就<b>落在射界里面了</b>——那一版会静悄悄地变成
     * "打得到"、这条测试跟着变红，还算好的；真正危险的是反过来：
     * 测试里写死的角度一旦比真实张角还宽，它就永远绿，什么也验不到。
     * 现在放在第 14 行：横向 15 格、纵向 10.5 格，偏约 35°，
     * 离三十度那条边有五度的余量，而离二十格外径还有一格半的余量
     * （hypot(15, 10.5) ≈ 18.3）——**两边都卡得住**。
     */
    @Test
    public void theCannonIgnoresWhatIsOffToTheSideOfItsWedge() {
        Battlefield offAxis = battlefield();
        Building cannon = offAxis.place(BuildingType.TOWER, 30, 3);     // 圆心 (31, 4)
        offAxis.spawn(EnemyType.GRUNT, 14, 16f);                        // dx -15、dy +10.5

        offAxis.advance(0.001f);

        assertNull("十六格远够得着，卡住它的是角度", cannon.target);

        Battlefield onAxis = battlefield();
        Building alignedCannon = onAxis.place(BuildingType.TOWER, 30, 3);
        Enemy aligned = onAxis.spawn(EnemyType.GRUNT, 4, 16f);          // dx -15、dy +0.5

        onAxis.advance(0.001f);

        assertSame("摆正了就在射界里，所以上面那条拦下来的是角度", aligned,
                alignedCannon.target);
    }

    /**
     * <b>一发炮弹炸一格，旁边的跟着一起挨。</b>
     *
     * <p>两只杂兵离着一格（第 4 行和第 5 行，纵向正好 1.0 格），炮只瞄得中一只，
     * 但落点周围一格里的都吃这一发的伤害。
     *
     * <p>时间卡在"第一发落地、第二发还没落地"之间：十五格、两者相向十格每秒，
     * 第一发约一点五秒落地；而装填是一点六秒、第二发还要再飞一点四秒——
     * 所以两秒的窗口里<b>只该有那一发</b>，挨的伤害正好是一个二十四点。
     * 不然"两只都掉了血"可能只是两发各打一只，这条就什么也没验到。
     */
    @Test
    public void aShellSplashesOntoEveryoneWithinOneCell() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 30, 3);                    // 圆心 (31, 4)
        Enemy aimed = bf.spawn(EnemyType.GRUNT, 4, 16f);        // 正左十五格，被瞄的那只
        Enemy beside = bf.spawn(EnemyType.GRUNT, 5, 16f);       // 就在它旁边一格

        advanceFor(bf, 2f);

        assertEquals("被瞄的那只挨了一发", EnemyType.GRUNT.maxHp - 24f, aimed.hp, 1e-3f);
        assertEquals("旁边那只也挨了同一发——溅射一格", EnemyType.GRUNT.maxHp - 24f,
                beside.hp, 1e-3f);
    }

    /**
     * <b>目标在半路上死了，炮弹照样落地炸开。</b>
     *
     * <p>这是"炮弹"和"弩箭"最大的差别。弩箭的目标没了就是白飞一趟
     * （{@link #aBulletThatArrivesAfterItsTargetIsGoneDealsNoDamage}）；
     * 炮弹的结算看的是<b>落点周围有谁</b>，跟当初瞄的是谁已经没关系了。
     *
     * <p>所以这一发不能省：落点那一格旁边站着的敌人该挨打。
     * 实现上只差一行——{@code Battlefield.moveProjectiles} 里先看
     * {@code splashCells} 再问 {@code lands()}，两条路就分开了。
     *
     * <p>时间窗口和溅射那条一样，卡在第一发落地、第二发没落地之间。
     *
     * <p><b>挨炸的那只为什么是"跟在后面一格"，不是"站在旁边一格"。</b>
     * 炮弹认的是<b>目标倒下时那个点</b>——它冻在那儿，不再跟着谁走。而别人还在走：
     * 十五格飞一点五秒，这期间大家往前挪了一格三，站在旁边的那只早就出了爆炸圈。
     * 跟在后面那只反倒正好补上来（它往前走的距离差不多把那一格追平了），
     * 落在离爆心半格的地方。这不是巧合挑出来的好看数字，
     * 而是"落点冻结"这个模型在场上真实的样子。
     */
    @Test
    public void aShellStillExplodesWhereItsTargetDied() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 30, 3);                    // 圆心 (31, 4)
        Enemy doomed = bf.spawn(EnemyType.GRUNT, 4, 16f);       // 走在前面的那只，被瞄的就是它
        Enemy behind = bf.spawn(EnemyType.GRUNT, 4, 15f);       // 跟在后面一格，同一条道

        bf.advance(0.05f);   // 开火，炮弹出膛
        assertEquals("先得真有一发在天上，不然这条测试什么也没验",
                1, bf.projectiles().size());

        doomed.hp = 0f;      // 别的塔（或者别的什么）抢先把它收了

        advanceFor(bf, 2f);

        assertEquals("目标没了，炮弹照样炸在原地，跟在后面那只正好走到那儿",
                EnemyType.GRUNT.maxHp - 24f, behind.hp, 1e-3f);
    }

    /**
     * <b>紧贴在城墙后面的那座塔，打得到正在啃墙的那只敌人。</b>
     *
     * <p>这条盯的是一个真机上撞到的边界：墙宽 1 格、敌人半个身位、塔自己
     * 半个占地，加起来圆心到圆心 2.55 格——只按"敌人中心在不在圈里"判的话，
     * 对着 2.5 格射程的老箭塔差 0.05 格打不着。
     *
     * <p>而城墙的全部价值就是替后面的塔争取时间
     * （见 {@link BuildingStats#maxHp}）：塔不打贴在墙上的那只，墙就白砌了。
     * 所以判定要按"圈碰到敌人的身子就算"（{@code Battlefield.frontmostInRange}）。
     *
     * <p><b>墙为什么是一整列（而不是挡住第 3 行的那一格）。</b>因为敌人现在的
     * 目的地是<b>最近的建筑</b>，而这里唯一的建筑就是那座塔（{@code Battlefield.isTarget}）
     * ——只砌一格的话，敌人会从墙下面绕过去直接啃塔，"啃墙"这件事压根不会发生，
     * 这条测试也就测不到它要测的那个边界了。封成一列，塔成了唯一的目标而唯一的
     * 路又必须穿过墙，敌人才真的会停下来啃墙。
     *
     * <p>这条同时也是那个玩法后果的记录：<b>墙挡不住一个"目标是塔"的敌人，
     * 只能拖住它</b>——除非把通往那座塔的路整个封上。
     */
    @Test
    public void aTowerRightBehindAWallStillShootsWhatIsChewingIt() {
        Battlefield bf = battlefield();
        int wallCol = bf.firstBuildableCol() + 2;
        buildWallColumn(bf, wallCol);                           // 这一列全砌上
        Building wall = bf.buildingAt(new Cell(wallCol, 3));
        Building tower = bf.place(BuildingType.BALLISTA, wallCol + 1, 3);   // 紧贴在墙后面
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);
        // 血量手动加上去，为的是让它活到墙前。这条测的是"墙后那座塔够不够得着"，
        // 而弩车一发 34 点、杂兵只有 45——不加油的话它两秒半就在半路上被打死了，
        // "啃墙"这件事压根不会发生。
        e.hp = 300f;

        advanceFor(bf, 6f);   // 走到墙前（约 3.9 秒）再啃两秒

        assertEquals("该贴在城墙左边", wallCol - 0.5f, e.x, 1e-3f);
        assertTrue("城墙在挨啃", wall.damaged());
        assertTrue("墙后那座塔该打得到它——这正是砌墙的目的", e.hp < 300f);
        assertSame(e, tower.target);
        // 反过来也一样重要：敌人在啃墙，够不着墙后面那座塔（隔了一格，
        // 不在它的四邻里），所以塔一点血都不该掉。墙"替后面的塔挨打"这句话，
        // 前半句由 wall.damaged() 盯着，后半句就是这一条。
        assertEquals("墙挡住了，塔不该挨啃", tower.maxHp(), tower.hp, 1e-3f);
    }

    /**
     * <b>一门 1 级大炮清得掉走过它那条走廊的敌人。</b>
     *
     * <p>这条盯着的是 {@link BuildingStats} 那组数（内径十格 / 外径二十格 /
     * 24 点一发 / 1.6 秒装填）和 {@link EnemyType} 那组数（杂兵 45 血 0.9 格每秒）
     * 之间的关系——<b>改任何一边都可能把这条打破</b>：杂兵从十六格外走进来，
     * 到十格盲区的边上要走六格 ≈ 6.7 秒，这期间炮打出四发（96 点），
     * 而打死它只要两发——**余量很宽，所以这条测试现在是个下限**，
     * 真要看"守住几成"得跑 {@code DemoSceneProbeTest}。
     *
     * <p><b>这条和从前那条不是一回事了。</b>从前它叫
     * {@code oneLevelOneTowerKillsAGruntButNotABrute}，盯的是"打不死重甲 ——
     * 所以你该升级"。换成十到二十格的扇环之后那句话不成立了：重甲慢
     * （0.55 格每秒），在十格深的走廊里要待十七八秒，一门一级炮打满两百多点，
     * <b>照样打得死</b>。所以重甲那半边删掉了，不再假装它还是那个结论。
     *
     * <p><b>而且没有重新配平过</b>：换成扇环那次只动了射界，
     * 2026-09-27 改张角那次只动了"单发和装填的比"（DPS 没变，见
     * {@link BuildingStats#TOWER_DAMAGE_PER_SHOT}）。这条现在是"当前数值长什么样"
     * 的记录，不是一份调好的平衡表。
     */
    @Test
    public void oneLevelOneCannonClearsItsCorridor() {
        Battlefield bf = battlefield();
        Building cannon = bf.place(BuildingType.TOWER, 20, 3);   // 圆心 (21, 4)
        Enemy grunt = bf.spawn(EnemyType.GRUNT, 4, bf.firstBuildableCol() - 3f);   // 正左十六格

        advanceFor(bf, 12f);

        assertFalse("杂兵该被打死", grunt.alive());
        assertTrue("炮还站着", bf.buildings().contains(cannon));
    }

    /**
     * <b>两种塔的射界在场上正好互补：贴脸那一段只有弩车管，远处那一段只有大炮管。</b>
     *
     * <p>{@code CostTest} 里那条比的是两张表上的数；这一条比的是<b>场上</b>的那件事：
     * 同一个位置、同一只敌人，一座没有目标、另一座咬着不放。射界的差别只有在这种
     * 边界上才看得见——平时两只都打得到，看不出区别。
     *
     * <ul>
     *   <li>三格多一点：大炮十格以内压根不开火，弩车（射界 3.0 格）咬着不放；</li>
     *   <li>十六格：弩车差得远，大炮（外径二十格）够得着。</li>
     * </ul>
     *
     * <p>两个方向都验，是因为它们各自可能独立地坏掉：内径写成 0 就丢掉前一半，
     * 外径写成 3 就丢掉后一半，而<b>只验一边的时候另一半坏了是完全看不出来的</b>。
     *
     * <p>距离都留了余量：3.14 对 3.0 + 0.4（快兵半个身位）= 3.4，
     * 16.008 对 20 + 0.625 ——推进一帧（快兵挪 0.08 格）之后结论都不变。
     */
    @Test
    public void aBallistaCoversWhatTheCannonCannot() {
        int towerCol = 30;
        float centreX = towerCol + 1f;   // 2×2 的圆心

        Battlefield cannonField = battlefield();
        Building cannon = cannonField.place(BuildingType.TOWER, towerCol, 3);
        Enemy cannonPrey = cannonField.spawn(EnemyType.RUNNER, 4, centreX - 3.1f);

        Battlefield ballistaField = battlefield();
        Building ballista = ballistaField.place(BuildingType.BALLISTA, towerCol, 3);   // 同一格
        Enemy ballistaPrey = ballistaField.spawn(EnemyType.RUNNER, 4, centreX - 3.1f);

        cannonField.advance(0.05f);
        ballistaField.advance(0.05f);

        assertNull("3.14 格，在大炮十格的盲区里", cannon.target);
        assertSame("同一只敌人，弩车该咬着不放", ballistaPrey, ballista.target);

        // 两只都满血：这一条比的是"瞄不瞄得上"，不是打死没打死
        assertEquals(EnemyType.RUNNER.maxHp, cannonPrey.hp, 1e-3f);

        Battlefield farCannonField = battlefield();
        Building farCannon = farCannonField.place(BuildingType.TOWER, towerCol, 3);
        Enemy farPrey = farCannonField.spawn(EnemyType.RUNNER, 4, centreX - 16f);

        Battlefield farBallistaField = battlefield();
        Building farBallista = farBallistaField.place(BuildingType.BALLISTA, towerCol, 3);
        farBallistaField.spawn(EnemyType.RUNNER, 4, centreX - 16f);

        farCannonField.advance(0.05f);
        farBallistaField.advance(0.05f);

        assertSame("十六格，大炮够得着", farPrey, farCannon.target);
        assertNull("弩车那三格射界差得远", farBallista.target);
    }

    /**
     * <b>一级弩车一发带走快兵，一级大炮一发带不走。</b>
     *
     * <p>这就是花将近两倍价钱买弩车买到的东西，也是 {@code BuildingStats} 里
     * "两种塔拿伤害换射程"那句话的账：大炮的每秒伤害其实更高、够得着的地方也远得多，
     * 但弩车赢的是<b>每一发都终结掉一只</b>。快兵 28 血——弩车一级 34 点一发就够，
     * 大炮一级 24 点还得再来一次。
     *
     * <p><b>两边差距被压缩过一次，这条是它还剩多少的记录。</b>大炮单发从 14 提到 24
     * 之后，"弩车一发、大炮两发"这个对比还在（24 &lt; 28 &lt; 34），但不再是一倍半
     * 那么悬殊——真正撑住两种塔分工的是 DPS 那半边（弩车更低）和盲区，
     * 见 {@code CostTest.ballistaTradesDamageRateForReach}。
     *
     * <p><b>两边的距离不一样，是有意的。</b>快兵得放进大炮那条十到二十格的走廊里
     * 才谈得上"挨了一发"（摆到三格远的话它压根不开火，这条会变成
     * "两只都活着、都没挨打"，什么也没验到）。所以弩车那边贴脸 1.58 格、
     * 大炮那边正左十六格。
     *
     * <p>时间也不一样：弩车 0.5 秒（一发，够快兵挨的），大炮 1.9 秒——
     * 十五格 ÷ 两者相向的 10.6 格每秒 ≈ 1.4 秒第一发落地，
     * 而第二发要等装填的 1.6 秒、再飞一点四秒，总在三秒左右才落地。
     * 窗口卡在这中间，挨的伤害才正好是一个二十四点。
     */
    @Test
    public void oneBallistaBoltDropsARunnerThatTheCannonNeedsTwoShotsFor() {
        int towerCol = 30;
        float centreX = towerCol + 1f;

        Battlefield ballistaField = battlefield();
        ballistaField.place(BuildingType.BALLISTA, towerCol, 3);
        Enemy ballistaPrey = ballistaField.spawn(EnemyType.RUNNER, 4, centreX - 1.5f);

        Battlefield cannonField = battlefield();
        cannonField.place(BuildingType.TOWER, towerCol, 3);
        Enemy cannonPrey = cannonField.spawn(EnemyType.RUNNER, 4, centreX - 16f);

        advanceFor(ballistaField, 0.5f);
        advanceFor(cannonField, 1.9f);

        assertFalse("弩车一发就该了结它", ballistaPrey.alive());
        assertTrue("大炮一发打不死（24 点，快兵有 28 血）", cannonPrey.alive());
        assertEquals("正好剩一发炮弹的伤害", EnemyType.RUNNER.maxHp - 24f,
                cannonPrey.hp, 1e-3f);
    }

    /**
     * 体格不只是"画多大"：大块头<b>从更远处就开始挨打</b>。
     *
     * <p>判定是"射界里的身子碰到就算"，用的半径是"外径 + 半个身位"。重甲比杂兵宽，
     * 所以同一门炮够得着更远的那一只。这不是 bug，是"大块头好打中"这件事
     * 在判定上的样子，也和画面上那个更宽的身子对得上——画的和判的是同一个数。
     *
     * <p>距离取两种身位判定的正中间（外径 20：杂兵够到 20.5、重甲够到 20.625），
     * 两边各留 0.0625 格。以后调体格或射界，两边都会先撞上这条测试，
     * 不会悄悄变成"两种敌人一样好打"。
     *
     * <p>摆在<b>外径</b>上而不是内径上，是因为外径这边没有别的干扰：
     * 内径那条线上敌人正朝炮走过来，还会同时被"角度"和"太近"两件事影响。
     */
    @Test
    public void aBulkyEnemyTakesFireFromFurtherOut() {
        int towerCol = 30;
        float centreX = towerCol + 1f;   // 2×2 的圆心 x；敌人走第 4 行，中心 y = 4.5
        // 到炮心 20.56 格：横向 20.554、纵向 4.5 - 4 = 0.5，hypot 起来正好 20.56。
        // 摆在炮的左边（射界朝左，而且正左方向偏不到 2°——离三十度那条边还远得很）
        float x = centreX - 20.554f;

        Battlefield gruntField = battlefield();
        Building gruntCannon = gruntField.place(BuildingType.TOWER, towerCol, 3);
        gruntField.spawn(EnemyType.GRUNT, 4, x);
        gruntField.advance(0.001f);   // 小到几乎不动：挨不挨打在这一帧就定了
        assertNull("杂兵的身子还在二十格外面", gruntCannon.target);

        Battlefield bruteField = battlefield();
        Building bruteCannon = bruteField.place(BuildingType.TOWER, towerCol, 3);
        Enemy brute = bruteField.spawn(EnemyType.BRUTE, 4, x);
        bruteField.advance(0.001f);
        assertSame("重甲的身子探进射界里了，该挨打", brute, bruteCannon.target);
    }

    /**
     * 体格也决定它<b>贴到挡路建筑多近</b>：身体右边缘碰到就停，不是中心碰到。
     *
     * <p>所以重甲停在 {@code 6 - 0.625 = 5.375}，杂兵停在 {@code 6 - 0.5 = 5.5}
     * （杂兵那条在 {@link #aTowerRightBehindAWallStillShootsWhatIsChewingIt} 里）。
     * 差 0.125 格肉眼看不出来，但"画的身体和判的身体是同一个"这件事得有人盯着——
     * 分开写迟早会变成"贴图宽了、判定没跟上"，那时敌人看着压在墙上却还在挨打不到。
     */
    @Test
    public void aBulkyEnemyFlattensItsBodyAgainstTheWall() {
        Battlefield bf = battlefield();
        int wallCol = bf.firstBuildableCol() + 2;
        Building wall = bf.place(BuildingType.WALL, wallCol, 3);
        Enemy brute = bf.spawn(EnemyType.BRUTE, 3, bf.firstBuildableCol() - 2f);

        // 0.55 格/秒走过去约 6.1 秒，再啃不到 2 秒（26 dps，墙有 150 血，还啃不穿）
        advanceFor(bf, 8f);

        assertEquals(wallCol - EnemyType.BRUTE.halfBodyCells(), brute.x, 1e-3f);
        assertTrue("停下来啃了", brute.blocked);
        assertTrue("墙还在", wall.hp > 0f);
    }

    /** 敌人啃的是挡住它的那一座：城墙会掉耐久。 */
    @Test
    public void anEnemyChewsTheBuildingThatBlocksIt() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, bf.firstBuildableCol() + 2, 3);
        bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);

        advanceFor(bf, 5f);   // 走到墙前约 3.9 秒 + 啃约 1.1 秒

        float expected = BuildingStats.maxHp(BuildingType.WALL, 1) - EnemyType.GRUNT.dps * 1.1f;
        assertEquals("挨了大约一秒多的啃", expected, wall.hp, EnemyType.GRUNT.dps * 0.2f);
        assertTrue(wall.damaged());
    }

    /**
     * <b>身边有几座时，咬最近的那一座，不分种类。</b>
     *
     * <p>规矩见 {@code Battlefield.nearestBite}：塔、核心、城墙一视同仁，
     * 谁离身体近咬谁。这一条摆的是重甲——身位 1.25，比杂兵宽，所以"近"和"远"
     * 分得开：它贴在挡路的那座塔左边（0.625 格），而斜上方那面墙只有 0.5 格。
     *
     * <p>但墙是例外：<b>它没挡路，所以不咬</b>。要是把"挨着的墙"也算进候选，
     * 这一口的 0.5 < 0.625，重甲就会放着面前的塔不啃、转身去拆旁边那面墙上。
     * 这条测试真正盯的就是这一件事——所以断言的是"墙一点没掉"，
     * 而不只是"塔掉了血"。
     */
    @Test
    public void anEnemyBitesTheNearestBuildingButNeverAWallThatIsNotInItsWay() {
        Battlefield bf = battlefield();
        int towerCol = bf.firstBuildableCol() + 2;
        Building tower = bf.place(BuildingType.TOWER, towerCol, 3);   // 占 (towerCol..towerCol+1, 3..4)，挡住第 4 行
        Building wall = bf.place(BuildingType.WALL, towerCol - 1, 3); // 在重甲斜上方，没挡路
        Enemy brute = bf.spawn(EnemyType.BRUTE, 4, bf.firstBuildableCol() - 2f);

        advanceFor(bf, 8f);   // 0.55 格/秒走过去约 6.1 秒，再啃不到 2 秒

        assertEquals("贴在挡路那座塔的左边",
                towerCol - EnemyType.BRUTE.halfBodyCells(), brute.x, 1e-3f);
        assertTrue("停下来啃了", brute.blocked);
        assertTrue("塔在挨啃", tower.damaged());
        assertEquals("没挡路的那面墙一点没掉——墙不是候选",
                BuildingStats.maxHp(BuildingType.WALL, 1), wall.hp, 1e-3f);
    }

    // "挡路的墙就咬"那一半不在这儿：绕不开的局面要砌满一整列才造得出来，
    // 见下面寻路那一节的 aSealedWallColumnGetsChewedThroughAtTheCrossing。

    /** 城墙被啃穿之后敌人继续往前走，那块地也还回来了。 */
    @Test
    public void anEnemyWalksOnOnceTheWallIsChewedThrough() {
        Battlefield bf = battlefield();
        int wallCol = bf.firstBuildableCol() + 2;
        Building wall = bf.place(BuildingType.WALL, wallCol, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, bf.firstBuildableCol() - 2f);

        // 150 血 ÷ 12 dps = 12.5 秒啃穿，再加上走过去的时间
        advanceFor(bf, 20f);

        assertFalse("墙该被啃掉了", bf.buildings().contains(wall));
        assertTrue("那块地还回来了", bf.canPlace(BuildingType.WALL, wallCol, 3));
        assertTrue("敌人继续往右走", e.x > wallCol);
    }

    // ---- 寻路：绕还是拆 ----

    /** 在第 {@code col} 列砌一道墙，留出 {@code gapRows} 那几个缺口。 */
    private static List<Building> buildWallColumn(Battlefield bf, int col, int... gapRows) {
        Set<Integer> gaps = new HashSet<>();
        for (int gap : gapRows) {
            gaps.add(gap);
        }
        List<Building> walls = new ArrayList<>();
        for (int row = 0; row < bf.board().rows(); row++) {
            if (gaps.contains(row)) {
                continue;
            }
            Building wall = bf.place(BuildingType.WALL, col, row);
            assertNotNull("第 " + row + " 行的墙该放得下", wall);
            walls.add(wall);
        }
        return walls;
    }

    /**
     * <b>墙上有缺口就绕，一道墙都不啃。</b>
     *
     * <p>敌人从第 17 行出发、缺口开在第 0 行，它得往上爬 17 行才够得着。
     * 这条盯的正是"墙是引导工具"：玩家砌一道留了一个口的墙，
     * 敌人<span>全部</span>从那个口走，墙的耐久一点不掉——
     * 于是那道墙的价值不是"挡住了多少伤害"，而是"把敌人赶到哪几座塔底下"。
     */
    @Test
    public void enemiesWalkThroughTheGapWithoutTouchingTheWall() {
        Battlefield bf = battlefield();
        List<Building> walls = buildWallColumn(bf, 10, 0);   // 缺口开在最上面一行
        Building core = bf.place(BuildingType.CORE, 20, 8);
        assertNotNull(core);

        Enemy e = bf.spawn(EnemyType.GRUNT, 17, 2f);
        advanceFor(bf, 60f);

        for (Building wall : walls) {
            assertFalse("绕过去的，不该啃任何一道墙：" + wall, wall.damaged());
        }
        assertTrue("该绕到墙右边去了（现在 x=" + e.x + "）", e.x > 11f);
        assertEquals("最后摸到核心，算一次漏", 1, bf.leaks());
    }

    /**
     * <b>墙围死了就只能拆。</b>整列砌满、一个口都不留，于是敌人对着挡路的那一座动手，
     * 而且只啃那一座——不会顺手把整列都拆了。
     */
    @Test
    public void aSealedWallColumnGetsChewedThroughAtTheCrossing() {
        Battlefield bf = battlefield();
        buildWallColumn(bf, 10);   // 第 10 列全砌上
        bf.place(BuildingType.CORE, 20, 8);

        bf.spawn(EnemyType.GRUNT, 8, 2f);   // 和核心同一行：正对着的那一座是最近的路
        advanceFor(bf, 20f);

        Building inTheWay = bf.buildingAt(new Cell(10, 8));
        assertNotNull(inTheWay);
        assertTrue("挡路的那一座该在掉耐久", inTheWay.damaged());
        Building elsewhere = bf.buildingAt(new Cell(10, 0));
        assertNotNull(elsewhere);
        assertFalse("别处的墙不该陪着挨打", elsewhere.damaged());
    }

    /**
     * <b>绕墙、拆塔</b>——两样都是建筑，敌人对它们的态度却相反。
     *
     * <p>这是"墙贵、塔是目的地"那套口径的行为测试（{@link BuildingStats#WALL_PATH_COST}
     * ＋ {@code Battlefield.isTarget}）。同一个位置换成不同建筑，敌人的反应必须分开：
     *
     * <ul>
     *   <li><b>墙</b>贵到任何绕路都比它便宜 → 敌人绕过去，墙一点没掉；</li>
     *   <li><b>塔</b>不设路障 → 表上的箭头指进塔里，敌人停下来拆。</li>
     * </ul>
     *
     * <p><b>塔那一支不只有一条机制在支撑它。</b>塔既是寻路表的目标
     * （{@code Battlefield.isTarget}），在价钱表上又和空地一样便宜、
     * 挡在路上就会被啃——两条路都通到"塔挨啃"这个结果。所以<b>这条测试盯不住
     * "塔是不是目标"</b>：把 {@code isTarget} 改回"只有核心"，它照样过。
     * 目标集合那一件事由
     * {@link #anEnemyGoesForTheNearerTowerAndLeavesTheCoreAlone} 盯着
     * ——那条的塔摆在路边、压根不挡路，只有"塔是目的地"才解释得了敌人为什么去啃它。
     *
     * <p>两条各推进同样的时间，其余条件完全一样，所以掉不掉血只可能是
     * 建筑类型造成的。
     */
    @Test
    public void enemiesDetourAroundAWallButBreakThroughATower() {
        // 墙：一道挡住第 4 行、但上下都留了口的墙——绕得过去
        Battlefield withWall = battlefield();
        withWall.place(BuildingType.CORE, 30, 4);
        Building wall = withWall.place(BuildingType.WALL, 10, 4);
        withWall.spawn(EnemyType.GRUNT, 4, 6f);
        advanceFor(withWall, 6f);

        assertFalse("墙不该挨啃——绕得过去", wall.damaged());
        assertTrue("墙还在", withWall.buildings().contains(wall));

        // 塔：同一个位置，敌人改成停下来拆
        Battlefield withTower = battlefield();
        withTower.place(BuildingType.CORE, 30, 4);
        Building tower = withTower.place(BuildingType.TOWER, 10, 4);
        Enemy breaker = withTower.spawn(EnemyType.GRUNT, 4, 6f);
        advanceFor(withTower, 6f);

        assertTrue("塔该挨啃（" + tower.hp + "/" + tower.maxHp() + "）", tower.damaged());
        assertTrue("它是停下来啃的", breaker.blocked);
    }

    /**
     * <b>核心不带优先权：路上有座塔更近，敌人就冲塔去了。</b>
     *
     * <p>这条盯着的是那个"敌人明明贴着塔还是奔核心去"的手感。从前寻路表只有
     * <b>一个</b>目标（核心），塔只是"很贵的地"——摆在路边的塔，敌人压根不会正眼看它。
     * 现在目标集合是<b>所有塔 + 核心</b>（{@code Battlefield.isTarget}），
     * 谁近去谁，于是"塔和核心优先级相同"这句话才有画面上的落点。
     *
     * <p>局面刻意做得很干净：核心在很远的地方（第 30 列），塔摆在敌人进场的
     * 那一行上（第 8 列）——塔近得毫无争议，所以敌人<b>必须</b>去啃它，
     * 而且<b>一下都不该碰核心</b>。
     *
     * <p>反过来也验了这里的"近"是<b>寻路表上的价钱</b>而不是直线距离：
     * 塔要是真的比核心远，这一条就该反过来。那种局面在
     * {@code enemiesFromAnyLaneReachTheCoreNow} 里。
     */
    @Test
    public void anEnemyGoesForTheNearerTowerAndLeavesTheCoreAlone() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 30, 4);    // 远在天边
        Building tower = bf.place(BuildingType.TOWER, 8, 2);   // 就在半路上
        bf.spawn(EnemyType.GRUNT, 4, 2f);                      // 和塔只差几格

        advanceFor(bf, 10f);

        assertTrue("该冲塔去（塔还剩 " + tower.hp + "/" + tower.maxHp() + "）",
                tower.damaged());
        assertEquals("核心一下都不该挨：它太远了", core.maxHp(), core.hp, 1e-3f);
        assertEquals("塔是目的地，不是漏过去——不该记漏怪", 0, bf.leaks());
    }

    /**
     * <b>不管从哪一行进来，敌人都能走到核心跟前。</b>
     *
     * <p>这条是那个"这一局其实输不了"的 bug 的回归测试。从前敌人只会直着走，
     * 而核心只占 3 行：18 条路里只有那 3 条的敌人会撞上核心，其余 15 条的敌人
     * 一路走出右边缘、只被记一笔漏怪，<b>核心一滴血都不掉</b>——
     * 于是五波打完永远算守住，玩家怎么摆都输不了。
     *
     * <p>现在它们会拐弯：从第 0 行进来的也会往下绕到核心那几行。
     * 所以这里刻意把敌人放在<b>离核心最远的那一行</b>（核心在第 10–12 行，
     * 敌人在第 0 行），要的就是"隔了 10 行也照样摸到"。
     */
    @Test
    public void enemiesFromAnyLaneReachTheCoreNow() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 8, 10);   // 第 8–10 列、第 10–12 行
        assertNotNull(core);

        Enemy e = bf.spawn(EnemyType.GRUNT, 0, 2f);   // 第 0 行：从前这一只永远走不到
        advanceFor(bf, 30f);

        assertEquals("从前这个行数走不到核心，现在算一次漏", 1, bf.leaks());
        assertTrue("核心该挨咬了（" + core.hp + "/" + core.maxHp() + "）",
                core.hp < core.maxHp());
        assertTrue("它站到了核心旁边（第 " + e.lane() + " 行、第 " + e.col() + " 列）",
                standsNextTo(e, core));
        assertTrue("在啃", e.blocked);
        assertFalse("第 0 行出身的，现在不在第 0 行了", e.lane() == 0);
    }

    /** 这一格是不是紧挨着那座建筑——四条边任意一条贴着都算。 */
    private static boolean standsNextTo(Enemy e, Building building) {
        Cell cell = e.cell();
        return building.covers(new Cell(cell.col + 1, cell.row))
                || building.covers(new Cell(cell.col - 1, cell.row))
                || building.covers(new Cell(cell.col, cell.row + 1))
                || building.covers(new Cell(cell.col, cell.row - 1));
    }

    /**
     * 从每一行各派一只，五波之内一定打得掉核心——<b>输得掉了</b>。
     *
     * <p>上一条测的是"摸得到"，这一条测的是后果：核心的耐久是有限的，
     * 放着不管就到了 {@link Battlefield.Outcome#OVERRUN}。
     * 这才是"这一局有输赢"的完整意思。
     */
    @Test
    public void aRunCanActuallyBeLostNow() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 8, 10);

        // 五只，行号全在核心那三行之外
        int[] lanes = {0, 2, 5, 14, 17};
        for (int lane : lanes) {
            bf.spawn(EnemyType.GRUNT, lane, 2f);
        }
        assertEquals(Battlefield.Outcome.ONGOING, bf.outcome());

        advanceFor(bf, 60f);

        assertEquals("五只一起啃，核心该倒了", Battlefield.Outcome.OVERRUN, bf.outcome());
        assertNull("核心从场上消失", bf.core());
    }

    /** 核心被打掉：这一局就结束了，而且核心会从场上消失。 */
    @Test
    public void destroyingTheCoreEndsTheRun() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 8, 10);
        assertEquals(Battlefield.Outcome.ONGOING, bf.outcome());
        assertFalse(bf.decided());

        // 五只一起上：600 ÷ (5 × 12) = 10 秒啃穿
        for (int i = 0; i < 5; i++) {
            bf.spawn(EnemyType.GRUNT, 11, 7f + i * 0.2f);
        }
        advanceFor(bf, 25f);

        assertEquals(Battlefield.Outcome.OVERRUN, bf.outcome());
        assertTrue(bf.decided());
        assertFalse("核心该从场上消失", bf.buildings().contains(core));
        assertNull("场上没有核心了", bf.core());
        assertTrue("占的九个格子都还了", bf.canPlace(BuildingType.CORE, 8, 10));
    }

    /**
     * 结束之后战场就冻住了：敌人不再动、塔也不再开火。
     *
     * <p>让尸体继续爬、或者让塔继续打空气，只会让"已经结束了"这件事
     * 在画面上看不出来。
     */
    @Test
    public void nothingMovesAfterTheRunIsDecided() {
        Battlefield bf = battlefield();
        Building core = bf.place(BuildingType.CORE, 8, 10);
        core.hp = 1f;   // 一点血，一下就没了
        Enemy e = bf.spawn(EnemyType.GRUNT, 11, 7f);

        advanceFor(bf, 20f);
        assertEquals(Battlefield.Outcome.OVERRUN, bf.outcome());

        float parked = e.x;
        int buildings = bf.buildings().size();
        advanceFor(bf, 5f);
        assertEquals("敌人不该再走", parked, e.x, 0f);
        assertEquals("场上也不该再变", buildings, bf.buildings().size());
    }

    /** 升级顺带回满血：被打残的塔升一级之后是满的（{@code Building.repair}）。 */
    @Test
    public void upgradingATowerRepairsIt() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, bf.firstBuildableCol() + 1, 3);
        tower.hp = 20f;
        assertTrue(tower.damaged());

        assertTrue(bf.upgrade(tower));

        assertEquals(2, tower.level);
        assertEquals("按新等级回满", BuildingStats.maxHp(BuildingType.TOWER, 2), tower.hp, 1e-3f);
        assertFalse(tower.damaged());
    }

    // ---- 波次 ----

    /** 一波的敌人要错开进场，不能挤成一个点。 */
    @Test
    public void aWaveTricklesInInsteadOfArrivingAllAtOnce() {
        Battlefield bf = battlefield();
        assertTrue(bf.spawnWave(new Random(7)));

        int size = Waves.size(1);
        assertTrue("第一波不该是空的", size > 0);
        assertEquals(size, bf.enemies().size());
        assertEquals(1, bf.waves());

        float first = bf.enemies().get(0).x;
        float last = bf.enemies().get(size - 1).x;
        assertTrue("最后一只应该在更左边（画面外）", last < first);
        for (Enemy e : bf.enemies()) {
            assertTrue("都该从画面外开始", e.x < 0f);
            assertTrue(e.lane() >= 0 && e.lane() < bf.board().rows());
        }
    }

    /**
     * 一波没打完不许发下一波。
     *
     * <p>同时来两波的话敌人数量翻倍、而塔的 dps 没变，玩家看到的只是"忽然多了一倍"
     * ——那不是难度，是算错账。
     */
    @Test
    public void aWaveCannotStartWhileTheLastOneIsOnTheField() {
        Battlefield bf = battlefield();
        assertTrue(bf.spawnWave(new Random(7)));
        int size = bf.enemies().size();

        assertFalse("上一波还在场上", bf.spawnWave(new Random(8)));
        assertEquals("波数不该变", 1, bf.waves());
        assertEquals("也不该多出一批敌人", size, bf.enemies().size());
    }

    /**
     * 打起来了就不许动建筑——放新的、挪、升级<b>问的是同一句话</b>。
     *
     * <p>这条规矩的用法是"界面每次要改建筑之前先问一声"（{@code BattlefieldView}
     * 那边每一处改建筑的地方都过 {@code reworkLocked()}），所以这里验的是
     * <b>这句话什么时候为真</b>，而不是某一次操作被挡下来了
     * （挡下来那一半在视图层，没有单元测试）。
     *
     * <p><b>关键是"一波清完就解锁"。</b>要是按"这一局打没打完"来判，五波打满之前
     * 一次阵型都改不了，中间那些空当——本来是留给玩家摆位和升级的——全废了。
     */
    @Test
    public void reworkingBuildingsIsLockedWhileEnemiesAreOnTheField() {
        Battlefield bf = battlefield();

        assertTrue("开局场上空的，随便摆", bf.canReworkBuildings());
        Building tower = bf.place(BuildingType.TOWER, bf.firstBuildableCol(), 7);
        assertNotNull(tower);

        assertTrue(bf.spawnWave(new Random(7)));
        assertFalse("一波的人还在场上", bf.canReworkBuildings());
        assertFalse("这一局也还没打完", bf.decided());

        for (Enemy enemy : bf.enemies()) {
            enemy.hp = 0f;
        }
        bf.advance(0.05f);   // 清场发生在 advance 里，不是设 hp 的那一刻
        assertTrue("都清干净了", bf.enemies().isEmpty());
        assertTrue("清完就该解锁——离打完第五波还早得很", bf.canReworkBuildings());
    }

    /**
     * 场上有人就锁，<b>不管这人是"发了一波"来的还是别人摆进来的</b>。
     *
     * <p>判的是 {@code enemies} 空不空，不是 {@code waves} 发过没有：发了一波再清光
     * 应该解锁（上一条），而一波都没发、场上却有人时必须也是锁的——不然
     * 这条规矩就变成了"发过波次之后才生效"，和它要挡的事情对不上。
     */
    @Test
    public void aLoneEnemyLocksReworkingEvenWithoutAWave() {
        Battlefield bf = battlefield();
        assertTrue(bf.canReworkBuildings());

        bf.spawn(EnemyType.GRUNT, 7, 20f);
        assertEquals("一波都没发过", 0, bf.waves());
        assertFalse("场上有人，就是锁的", bf.canReworkBuildings());

        advanceFor(bf, 80f);   // 场上没东西拦它，一路走出右边
        assertTrue(bf.enemies().isEmpty());
        assertTrue("走出场就解锁", bf.canReworkBuildings());
    }

    /**
     * 打完五波算守住——<b>哪怕一只都没拦住</b>。
     *
     * <p>这里场上什么建筑都没有：敌人一路走到右边出界，全算漏，但核心没被打掉
     * （压根没有核心），所以判定是"守住了"。这不是漏洞，是刻意的：这一版
     * 唯一的"输"是核心被打掉，"漏了几只"是成绩不是生死。
     * 真机上核心是开局就摆好的，玩家拆不掉它。
     */
    @Test
    public void clearingAllFiveWavesIsADefendedRun() {
        Battlefield bf = battlefield();

        // 够最慢的重甲从画面外走到右边出界：cols 格 ÷ 0.55 格每秒 ≈ 145 秒，
        // 再留两成余量——一波里排在后面的那几只起点还要再往左退几格
        float crossing = bf.board().cols() / EnemyType.BRUTE.speedCellsPerSec * 1.2f;
        for (int wave = 1; wave <= Waves.TOTAL_WAVES; wave++) {
            assertFalse("这一局还没结束", bf.decided());
            assertTrue("第 " + wave + " 波该发得出去", bf.spawnWave(new Random(wave)));
            advanceFor(bf, crossing);
            assertTrue("第 " + wave + " 波该清完了", bf.enemies().isEmpty());
        }

        assertEquals(Battlefield.Outcome.DEFENDED, bf.outcome());
        assertTrue(bf.decided());
        assertFalse("五波之后没有第六波", bf.spawnWave(new Random(9)));
        assertEquals(Waves.TOTAL_WAVES, bf.waves());
        assertTrue("该漏了不少", bf.leaks() > 0);
    }

    /** 重开一局：建筑、敌人、计数全清空。 */
    @Test
    public void clearResetsEverything() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 8, 10);
        bf.spawnWave(new Random(1));
        advanceFor(bf, 30f);

        bf.clear();

        assertTrue(bf.buildings().isEmpty());
        assertTrue(bf.enemies().isEmpty());
        assertEquals(0, bf.waves());
        assertEquals(0, bf.leaks());
        assertTrue("占位表也要清掉", bf.canPlace(BuildingType.CORE, 8, 10));
        assertEquals("输了赢了的记号也要清掉", Battlefield.Outcome.ONGOING, bf.outcome());
        assertFalse(bf.decided());
    }

    /** 越界的行会被夹进战场，不会造出一个走不到的敌人。 */
    @Test
    public void spawningClampsTheLaneIntoTheBoard() {
        Battlefield bf = battlefield();

        assertEquals(0, bf.spawn(EnemyType.GRUNT, -3, 0f).lane());
        assertEquals(bf.board().rows() - 1, bf.spawn(EnemyType.GRUNT, 999, 0f).lane());
    }

    /** {@code null} 当杂兵处理：不该为一个不该出现的类型把整局搞崩。 */
    @Test
    public void spawningWithoutATypeFallsBackToAGrunt() {
        Battlefield bf = battlefield();
        assertSame(EnemyType.GRUNT, bf.spawn(null, 3, 0f).type);
    }

    /** 按秒推进，一步 0.05 秒——模拟真机的帧率，也顺便走一遍跨格检查。 */
    private static void advanceFor(Battlefield bf, float seconds) {
        float step = 0.05f;
        for (float t = 0f; t < seconds; t += step) {
            bf.advance(step);
        }
    }
}
