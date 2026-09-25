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
     * 也就是真机上那一块（40 × 18）。
     */
    private static Battlefield battlefield() {
        return new Battlefield(BoardGeometry.fit(387f * 2.625f, 643f * 2.625f));
    }

    // ---- 占地与占位 ----

    @Test
    public void towerOccupiesItsWholeFootprint() {
        Battlefield bf = battlefield();
        Building tower = bf.place(BuildingType.TOWER, 4, 7);
        assertNotNull(tower);

        // 2×2 压住 4 个格子，多一格都不行
        assertTrue(tower.covers(new Cell(4, 7)));
        assertTrue(tower.covers(new Cell(5, 7)));
        assertTrue(tower.covers(new Cell(4, 8)));
        assertTrue(tower.covers(new Cell(5, 8)));
        assertFalse(tower.covers(new Cell(6, 7)));
        assertFalse(tower.covers(new Cell(4, 9)));

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
        assertNotNull(bf.place(BuildingType.TOWER, 4, 7));   // 占 (4..5, 7..8)

        assertFalse("完全重合", bf.canPlace(BuildingType.TOWER, 4, 7));
        assertFalse("压住一格", bf.canPlace(BuildingType.TOWER, 5, 8));
        assertFalse("压住一格", bf.canPlace(BuildingType.WALL, 5, 8));
        assertFalse("核心压住塔的一角", bf.canPlace(BuildingType.CORE, 3, 6));

        // 贴着放可以
        assertTrue("贴右边", bf.canPlace(BuildingType.TOWER, 6, 7));
        assertTrue("贴下边", bf.canPlace(BuildingType.WALL, 4, 9));
    }

    /** 放不下时返回 null，不抛异常——手指点到放不下的地方是正常操作。 */
    @Test
    public void placingSomewhereIllegalReturnsNullAndChangesNothing() {
        Battlefield bf = battlefield();

        assertNull("右下角放不下 3×3", bf.place(BuildingType.CORE, 10, 16));
        assertNull("负坐标", bf.place(BuildingType.TOWER, -1, 4));
        assertNull("入侵通道", bf.place(BuildingType.WALL, 0, 4));

        assertTrue("失败的放置不该留下任何东西", bf.buildings().isEmpty());
        assertFalse(bf.canPlace(BuildingType.CORE, 10, 16));
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
        bf.place(BuildingType.WALL, 5, 5);

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

        bf.place(BuildingType.WALL, 5, 5);
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
        Building tower = bf.place(BuildingType.TOWER, 4, 7);

        assertFalse(bf.canPlace(BuildingType.TOWER, 4, 7));
        assertTrue(bf.remove(tower));
        assertTrue(bf.canPlace(BuildingType.TOWER, 4, 7));
        assertNull(bf.buildingAt(new Cell(4, 7)));
        assertFalse("拆两遍不该成功", bf.remove(tower));
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
        bf.place(BuildingType.WALL, 6, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        advanceFor(bf, 6f);   // 足够走完 5 格多

        assertEquals("应该贴在城墙左边", 5.5f, e.x, 1e-3f);
        assertTrue(e.blocked);
        assertEquals("没漏怪", 0, bf.leaks());
    }

    /** 塔也挡路（这一版没有寻路，敌人不会绕）。 */
    @Test
    public void aTowerBlocksToo() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 5, 3);   // 占 (5..6, 3..4)
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 1f);

        bf.advance(0.5f);   // 一步 0.45 格，只够挪一小段

        assertEquals(1.45f, e.x, 1e-3f);
        assertFalse(e.blocked);

        advanceFor(bf, 20f);
        assertEquals("撞上塔之后贴住", 4.5f, e.x, 1e-3f);
        assertTrue(e.blocked);
    }

    /**
     * 一帧跨好几格时不能穿过障碍。<b>手机卡一下就会发生</b>：
     * 掉帧时 dt 变大，不检查中间格子的话敌人会直接穿墙。
     */
    @Test
    public void aLongFrameCannotTunnelThroughABlocker() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.WALL, 6, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        bf.advance(10f);   // 一步 9 格，足够跨过城墙

        assertEquals(5.5f, e.x, 1e-3f);
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

    // ---- 打起来 ----

    /**
     * <b>射程里最靠前的那一只挨打，哪怕另一只离塔更近。</b>
     *
     * <p>规则是"打最靠前的"，不是"打离自己最近的"（理由见
     * {@code Battlefield.frontmostInRange}——"就近"试过一版，五波的演示场面
     * 第 2 波就会 Overrun）。这一条要把两种规则<b>分得开</b>：
     * 两只都得在射程里，而且"更靠前的"必须同时是"更远的"，否则选谁都能过，
     * 测试就等于没验。
     *
     * <p>摆成：一只从左边<b>正在靠近</b>（x=4.2，离塔心 2.34 格），
     * 另一只已经<b>走过去</b>了（x=8.0，离塔心 2.50 格，仍在射程里）。
     * 按"最靠前"该打后面那只，按"最近"该打前面那只——两种规则给出相反的结果。
     *
     * <p><b>塔摆在院子中间那条道旁边</b>（占第 2–3 行），敌人走的第 4 行不受影响，
     * 所以两只都是直着往右走、"靠前"和"x 大"在这条里是一回事，算术干净。
     * 场上<b>摆了核心</b>，所以走的是真的寻路表那一支；没核心那一支由下面
     * {@link #withoutACoreTheProgressStillRunsFromTheLeft} 盯着。
     */
    @Test
    public void aTowerShootsTheEnemyFurthestAlongNotTheNearestOne() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.CORE, 20, 4);                    // 核心占 (20..22, 4..6)
        Building tower = bf.place(BuildingType.TOWER, 5, 2);   // 占 (5..6, 2..3)，圆心 (6, 3)
        Enemy approaching = bf.spawn(EnemyType.GRUNT, 4, 4.2f);   // 离塔心 2.34 格
        Enemy passed = bf.spawn(EnemyType.GRUNT, 4, 8.0f);        // 离塔心 2.50 格

        // 先钉住"两只都在射程里"：射程 2.5 + 敌人半个身位 0.5 = 够到 3.0 格。
        // 少了这一句，哪天射程被调小、远的那只其实够不着，这条就又变成没验了。
        double reach = BuildingStats.rangeCells(BuildingType.TOWER, 1)
                + EnemyType.GRUNT.halfBodyCells();
        assertTrue("已经走过去的那只确实在射程里，不然这条测试什么也没验",
                reach > Math.hypot(8.0 - 6.0, 4.5 - 3.0));
        // 再钉住"近的那只确实更近"：不成立的话两种规则会挑到同一只，也等于没验
        assertTrue("靠近的那只确实离塔更近",
                Math.hypot(4.2 - 6.0, 4.5 - 3.0) < Math.hypot(8.0 - 6.0, 4.5 - 3.0));

        // 半秒：一级塔一秒一发，所以这半秒里<b>正好一发</b>（一帧就打完整个装填周期
        // 的话会打两发，见 theFireRateDoesNotDependOnTheFrameRate 那条）；
        // 而子弹 9 格/秒，半秒够飞 4.5 格，2.5 格那一段早落地了
        bf.advance(0.5f);

        assertEquals("走得更靠前的那只挨了一发（14 点）",
                EnemyType.GRUNT.maxHp - 14f, passed.hp, 1e-3f);
        assertEquals("更近、但还在后头的那只一点没掉",
                EnemyType.GRUNT.maxHp, approaching.hp, 1e-3f);
        assertSame(passed, tower.target);
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
     * 于是 x 大的反而进度小、才是更靠前的那只。场景和上面那条<b>只差一个核心</b>。
     */
    @Test
    public void withoutACoreTheProgressStillRunsFromTheLeft() {
        Battlefield bf = battlefield();   // 这个场不摆核心
        Building tower = bf.place(BuildingType.TOWER, 5, 2);
        Enemy behind = bf.spawn(EnemyType.GRUNT, 4, 4.2f);
        Enemy ahead = bf.spawn(EnemyType.GRUNT, 4, 8.0f);

        bf.advance(0.5f);

        assertEquals("右边那只才是更靠前的", EnemyType.GRUNT.maxHp - 14f, ahead.hp, 1e-3f);
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
        bf.place(BuildingType.TOWER, 5, 3);      // 圆心 (6, 4)
        Enemy e = bf.spawn(EnemyType.GRUNT, 4, 8f);   // 射程里，2.06 格

        bf.advance(0.05f);   // 一帧：开火，子弹出膛

        assertEquals("出膛了一发", 1, bf.projectiles().size());
        assertEquals("还在飞，血一点没掉", EnemyType.GRUNT.maxHp, e.hp, 1e-3f);

        advanceFor(bf, 0.5f);   // 2.06 格 ÷ 9 格每秒 ≈ 0.25 秒，早落地了

        assertEquals("落地才扣这一发", EnemyType.GRUNT.maxHp - 14f, e.hp, 1e-3f);
        assertTrue("打完了，天上没东西了", bf.projectiles().isEmpty());
        // 装填是一秒，而这 0.55 秒里只该有那一发（血只掉了一发，不是两发 28 点）
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
        Enemy at60Hz = shelledFor(4.5f, 0.05f);
        Enemy at120Hz = shelledFor(4.5f, 0.02f);

        // 不然"两边相等"可能只是因为一炮都没打出去，那这条测试什么也没验
        assertTrue("至少得打中三发，实际 " + (EnemyType.BRUTE.maxHp - at60Hz.hp),
                at60Hz.hp <= EnemyType.BRUTE.maxHp - 3 * 14f);
        assertEquals("两种帧率挨的伤害该一模一样", at60Hz.hp, at120Hz.hp, 1e-3f);
    }

    /**
     * 一座 1 级塔打一只走进射程里停住的重甲，按给定帧长推 {@code seconds} 秒，返回那只敌人。
     *
     * <p>选重甲是因为它够厚：4.5 秒里打中的那几发（70 点）远不到 160，
     * 血量能一直当"挨了多少"的读数用。它啃穿那座塔要 3.8 秒，而碰到塔要 1.6 秒，
     * 所以这 4.5 秒里塔还站着（塔一倒就没人开火了）。
     */
    private static Enemy shelledFor(float seconds, float step) {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 5, 3);              // 圆心 (6, 4)，射程 2.5
        Enemy brute = bf.spawn(EnemyType.BRUTE, 4, 3.5f);   // 一开局就在射程里
        for (float t = 0f; t < seconds; t += step) {
            bf.advance(step);
        }
        return brute;
    }

    /**
     * <b>已经在天上的伤害也算数</b>：塔不会对着"确定要死的那只"再多打。
     *
     * <p>五座塔围着一只 45 血的杂兵，第一帧只该有<b>四发</b>出膛
     * （45 ÷ 14 = 3.2，四发刚好够）。少了这一问（{@code damageInFlightAt}），
     * 五座会一起吐五发——多出来的那发打空，而它本来可以打给后面那只。
     * 一座塔看不出区别，五座塔一起打一波的时候，火力是"铺开"还是"堆在一只身上"
     * 全靠这一条。
     */
    @Test
    public void towersDoNotPileOntoAnEnemyThatIsAlreadyCovered() {
        Battlefield bf = battlefield();
        // 五座塔都在射程里，而且都不挡着那一行（第 9 行空着）
        bf.place(BuildingType.TOWER, 7, 7);    // 圆心 (8, 8)
        bf.place(BuildingType.TOWER, 9, 7);    // 圆心 (10, 8)
        bf.place(BuildingType.TOWER, 11, 7);   // 圆心 (12, 8)
        bf.place(BuildingType.TOWER, 8, 11);   // 圆心 (9, 12)
        bf.place(BuildingType.TOWER, 10, 11);  // 圆心 (11, 12)
        Enemy grunt = bf.spawn(EnemyType.GRUNT, 9, 10.5f);

        bf.advance(0.05f);

        assertEquals("45 血 ÷ 14 伤 = 四发就够，第五座塔该把这一发留给下一只",
                4, bf.projectiles().size());

        advanceFor(bf, 1f);
        assertFalse("四发都落地了，杂兵该死", grunt.alive());
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
     * 会以别的形式冒出来，比如以后加溅射时溅到尸体上）。
     */
    @Test
    public void aBulletThatArrivesAfterItsTargetIsGoneDealsNoDamage() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 5, 3);
        Enemy doomed = bf.spawn(EnemyType.GRUNT, 4, 8f);

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
        bf.place(BuildingType.TOWER, 5, 3);
        bf.spawn(EnemyType.GRUNT, 4, 8f);
        bf.advance(0.05f);
        assertFalse("先得真有一发在天上，不然这条测试什么也没验",
                bf.projectiles().isEmpty());

        bf.clear();

        assertTrue(bf.projectiles().isEmpty());
    }

    /** 射程外的打不着。射程从建筑占地中心算，单位是格。 */
    @Test
    public void enemiesOutsideTheRangeTakeNoDamage() {
        Battlefield bf = battlefield();
        bf.place(BuildingType.TOWER, 5, 3);
        Enemy far = bf.spawn(EnemyType.GRUNT, 10, 20f);   // 另一行，离得很远

        advanceFor(bf, 3f);

        assertEquals("满血：没被打过", EnemyType.GRUNT.maxHp, far.hp, 1e-3f);
        assertNull("没有目标", bf.buildingAt(new Cell(5, 3)).target);
    }

    /**
     * <b>紧贴在城墙后面的那座塔，打得到正在啃墙的那只敌人。</b>
     *
     * <p>这条盯的是一个真机上撞到的边界：墙宽 1 格、敌人半个身位、塔自己
     * 半个占地，加起来圆心到圆心正好 2.5 格多一点——只按"敌人中心在不在圈里"
     * 判的话，差 0.05 格打不着。
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
        buildWallColumn(bf, 6);                                 // 第 6 列全砌上
        Building wall = bf.buildingAt(new Cell(6, 3));
        Building tower = bf.place(BuildingType.TOWER, 7, 3);    // 紧贴在墙后面
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        advanceFor(bf, 6f);   // 走到墙前（约 3.9 秒）再啃两秒

        assertEquals("该贴在城墙左边", 5.5f, e.x, 1e-3f);
        assertTrue("城墙在挨啃", wall.damaged());
        assertTrue("墙后那座塔该打得到它——这正是砌墙的目的",
                e.hp < EnemyType.GRUNT.maxHp);
        assertSame(e, tower.target);
        // 反过来也一样重要：敌人在啃墙，够不着墙后面那座塔（隔了一格，
        // 不在它的四邻里），所以塔一点血都不该掉。墙"替后面的塔挨打"这句话，
        // 前半句由 wall.damaged() 盯着，后半句就是这一条。
        assertEquals("墙挡住了，塔不该挨啃", tower.maxHp(), tower.hp, 1e-3f);
    }

    /**
     * <b>一座 1 级塔打得死杂兵，打不死重甲。</b>
     *
     * <p>这条盯着的是 {@link BuildingStats} 里那组数（射程 2.5 格 / 14 dps）
     * 和 {@link EnemyType} 里那组数（杂兵 45 血 12 dps、重甲 160 血 26 dps）
     * 之间的关系——<b>改任何一边都可能把这条打破</b>：
     *
     * <ul>
     *   <li>杂兵：45 血 ÷ 14 dps ≈ 3.2 秒，而它啃穿 100 血的塔要 8.3 秒
     *       → 塔赢；</li>
     *   <li>重甲：160 血 ÷ 14 dps ≈ 11.4 秒，而它啃穿塔只要 3.8 秒
     *       → 塔先倒。玩家必须再加一座塔、或者升到 2 级。</li>
     * </ul>
     *
     * <p>这就是"什么时候该升级"这个问题的答案，所以它得是一条测试，
     * 而不是一段注释。
     */
    @Test
    public void oneLevelOneTowerKillsAGruntButNotABrute() {
        Battlefield gruntField = battlefield();
        gruntField.place(BuildingType.TOWER, 5, 3);
        Enemy grunt = gruntField.spawn(EnemyType.GRUNT, 4, 1f);
        advanceFor(gruntField, 12f);
        assertFalse("杂兵该被打死", grunt.alive());
        assertTrue("塔还站着", gruntField.buildings().size() == 1);

        Battlefield bruteField = battlefield();
        Building tower = bruteField.place(BuildingType.TOWER, 5, 3);
        Enemy brute = bruteField.spawn(EnemyType.BRUTE, 4, 1f);
        advanceFor(bruteField, 12f);
        assertTrue("一座 1 级塔打不死重甲", brute.alive());
        assertFalse("塔反而被啃掉了", bruteField.buildings().contains(tower));
    }

    /**
     * 体格不只是"画多大"：大块头<b>从更远处就开始挨打</b>。
     *
     * <p>塔的判定是"圈碰到身子就算"，用的半径是"射程 + 半个身位"。重甲比杂兵宽，
     * 所以同一座塔够得着更远的那一只。这不是 bug，是"大块头好打中"这件事
     * 在判定上的样子，也和画面上那个更宽的身子对得上——画的和判的是同一个数。
     *
     * <p>距离取两种身位判定的正中间（1 级塔射程 2.5：杂兵够到 3.0、
     * 重甲够到 3.125），两边各留 0.0625 格。以后调体格或射程，
     * 两边都会先撞上这条测试，不会悄悄变成"两种敌人一样好打"。
     */
    @Test
    public void aBulkyEnemyTakesFireFromFurtherOut() {
        // 塔占 (5,3)–(6,4)，中心 (6,4)；敌人走第 4 行，中心 y = 4.5
        float x = 9.0214f;   // 到塔心 3.0625 格

        Battlefield gruntField = battlefield();
        Building gruntTower = gruntField.place(BuildingType.TOWER, 5, 3);
        gruntField.spawn(EnemyType.GRUNT, 4, x);
        gruntField.advance(0.001f);   // 小到几乎不动：挨不挨打在这一帧就定了
        assertNull("杂兵还在圈外", gruntTower.target);

        Battlefield bruteField = battlefield();
        Building bruteTower = bruteField.place(BuildingType.TOWER, 5, 3);
        Enemy brute = bruteField.spawn(EnemyType.BRUTE, 4, x);
        bruteField.advance(0.001f);
        assertSame("重甲的身子探进圈里了，该挨打", brute, bruteTower.target);
    }

    /**
     * 体格也决定它<b>贴到挡路建筑多近</b>：身体右边缘碰到就停，不是中心碰到。
     *
     * <p>所以重甲停在 {@code 6 - 0.625 = 5.375}，杂兵停在 {@code 6 - 0.5 = 5.5}
     * （杂兵那条在 {@code aTowerRightBehindAWallStillShootsWhatIsChewingIt} 里）。
     * 差 0.125 格肉眼看不出来，但"画的身体和判的身体是同一个"这件事得有人盯着——
     * 分开写迟早会变成"贴图宽了、判定没跟上"，那时敌人看着压在墙上却还在挨打不到。
     */
    @Test
    public void aBulkyEnemyFlattensItsBodyAgainstTheWall() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 6, 3);
        Enemy brute = bf.spawn(EnemyType.BRUTE, 3, 2f);

        // 0.55 格/秒走过去约 6.1 秒，再啃不到 2 秒（26 dps，墙有 150 血，还啃不穿）
        advanceFor(bf, 8f);

        assertEquals(6f - EnemyType.BRUTE.halfBodyCells(), brute.x, 1e-3f);
        assertTrue("停下来啃了", brute.blocked);
        assertTrue("墙还在", wall.hp > 0f);
    }

    /** 敌人啃的是挡住它的那一座：城墙会掉耐久。 */
    @Test
    public void anEnemyChewsTheBuildingThatBlocksIt() {
        Battlefield bf = battlefield();
        Building wall = bf.place(BuildingType.WALL, 6, 3);
        bf.spawn(EnemyType.GRUNT, 3, 2f);

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
        Building tower = bf.place(BuildingType.TOWER, 6, 3);   // 占 (6..7, 3..4)，挡住第 4 行
        Building wall = bf.place(BuildingType.WALL, 5, 3);     // 在重甲斜上方，没挡路
        Enemy brute = bf.spawn(EnemyType.BRUTE, 4, 2f);

        advanceFor(bf, 8f);   // 0.55 格/秒走过去约 6.1 秒，再啃不到 2 秒

        assertEquals("贴在挡路那座塔的左边", 6f - EnemyType.BRUTE.halfBodyCells(), brute.x, 1e-3f);
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
        Building wall = bf.place(BuildingType.WALL, 6, 3);
        Enemy e = bf.spawn(EnemyType.GRUNT, 3, 2f);

        // 150 血 ÷ 12 dps = 12.5 秒啃穿，再加上走过去的时间
        advanceFor(bf, 20f);

        assertFalse("墙该被啃掉了", bf.buildings().contains(wall));
        assertTrue("那块地还回来了", bf.canPlace(BuildingType.WALL, 6, 3));
        assertTrue("敌人继续往右走", e.x > 6f);
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
        Building tower = bf.place(BuildingType.TOWER, 5, 3);
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

        for (int wave = 1; wave <= Waves.TOTAL_WAVES; wave++) {
            assertFalse("这一局还没结束", bf.decided());
            assertTrue("第 " + wave + " 波该发得出去", bf.spawnWave(new Random(wave)));
            // 够最慢的重甲从左边走到右边出界（40 格 ÷ 0.55 格每秒 ≈ 73 秒）
            advanceFor(bf, 80f);
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
