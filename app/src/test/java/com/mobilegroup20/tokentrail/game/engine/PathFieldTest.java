package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * {@link PathField} 的测试：那张"还有多远"的表铺得对不对。
 *
 * <p>盯的是两件事，因为敌人全部的行为都从这两件事长出来：
 *
 * <ul>
 *   <li><b>表上的数一定是往核心递减的</b>——不递减就会原地打转；</li>
 *   <li><b>绕路一定比穿墙便宜</b>——不成立的话敌人会直着穿墙过来，
 *       玩家砌的墙就白砌了。</li>
 * </ul>
 *
 * <p>棋盘就用真的尺寸（{@link BoardGeometry#COLS} × {@link BoardGeometry#ROWS}），
 * 因为"绕路最远能有多远"和棋盘大小直接相关，拿个小棋盘测等于没测。
 */
public class PathFieldTest {

    private static final int COLS = BoardGeometry.COLS;
    private static final int ROWS = BoardGeometry.ROWS;

    /**
     * 铺一张通向 {@code goal} 的表。
     *
     * <p><b>目标格也放进 {@code blocked}</b>，因为真实调用就是这样：核心占着那几格，
     * 而它同时在占位表和目标里（见 {@code Battlefield.rebuildPathIfStale}）。
     * 只测"目标格是空的"那种情况的话，就漏掉了真正跑起来的那一条。
     */
    private static PathField to(Cell goal, Cell... blocked) {
        Set<Cell> walls = new HashSet<>(Arrays.asList(blocked));
        walls.add(goal);
        return PathField.to(COLS, ROWS, uniform(walls), Collections.singletonList(goal));
    }

    /**
     * "挡路的格子一律按墙算"的代价函数。
     *
     * <p>这个类测的是<b>表铺得对不对</b>，不是"哪种建筑该贵"——那个口径在
     * {@code Battlefield.stepCost}，而它现在只剩一档：墙很贵、别的都是目标
     * （见 {@link BuildingStats#WALL_PATH_COST}）。这里正好用同一档，
     * 于是"绕路一定比穿墙便宜"这条在测试里是最强的那个版本。
     */
    private static ToIntFunction<Cell> uniform(Set<Cell> blocked) {
        return cell -> blocked.contains(cell)
                ? BuildingStats.WALL_PATH_COST
                : PathField.OPEN_COST;
    }

    /** 从 {@code from} 开始一直走，把踩过的格子按顺序记下来。 */
    private static List<Cell> walk(PathField field, Cell from, int limit) {
        List<Cell> visited = new ArrayList<>();
        Cell at = from;
        for (int i = 0; i < limit; i++) {
            visited.add(at);
            Cell next = field.bestNeighbour(at);
            if (next == null) {
                break;
            }
            at = next;
        }
        return visited;
    }

    /** 一整列都堵上。返回堵住的那些格子，方便断言"没从这儿过"。 */
    private static Cell[] sealedColumn(int col) {
        Cell[] cells = new Cell[ROWS];
        for (int row = 0; row < ROWS; row++) {
            cells[row] = new Cell(col, row);
        }
        return cells;
    }

    // ---- 空地 ----

    /** 空地上"还有多远"就是曼哈顿距离：横着走几格加竖着走几格。 */
    @Test
    public void onOpenGroundTheDistanceIsJustHowManyCellsAway() {
        PathField field = to(new Cell(5, 5));

        assertEquals("目标格自己是 0", 0, field.distanceAt(new Cell(5, 5)));
        assertEquals("左边一格", 1, field.distanceAt(new Cell(4, 5)));
        assertEquals("右边一格", 1, field.distanceAt(new Cell(6, 5)));
        assertEquals("上边一格", 1, field.distanceAt(new Cell(5, 4)));
        assertEquals("下边一格", 1, field.distanceAt(new Cell(5, 6)));
        assertEquals("同一行最左边", 5, field.distanceAt(new Cell(0, 5)));
        assertEquals("对角线那头", 34 + 12, field.distanceAt(new Cell(39, 17)));
    }

    /** 目标格自己也是"占着的"，但它不能因此变贵——它是起点，代价是 0。 */
    @Test
    public void theGoalIsFreeEvenThoughItIsOccupied() {
        PathField field = to(new Cell(5, 5));

        assertEquals(0, field.distanceAt(new Cell(5, 5)));
        assertEquals("旁边那一格的下一步就是它", new Cell(5, 5), field.bestNeighbour(new Cell(4, 5)));
    }

    /**
     * <b>表上的数每一步都严格变小。</b>
     *
     * <p>这是"不会原地打转"的全部理由：只要每一步都更小，就不可能在两个格子之间
     * 来回横跳（那要求两个数互相小于对方）。反过来，如果允许"一样大"，
     * 敌人会在两个同样好的格子之间抖，看起来像卡住了，而且永远走不到核心。
     *
     * <p>所以这条不是"顺便查一下"，它是这张表的合约。
     */
    @Test
    public void everyStepGoesStrictlyDownhill() {
        // 有墙也有缺口，让表里既有绕路的部分也有贴墙的部分
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 2));
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        int reachable = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                Cell here = new Cell(col, row);
                int mine = field.distanceAt(here);
                if (mine == PathField.UNREACHABLE) {
                    continue;
                }
                reachable++;
                Cell next = field.bestNeighbour(here);
                if (mine == 0) {
                    assertNull("已经到目标了，没有下一步", next);
                    continue;
                }
                assertTrue("每一格都该有下一步（" + here + "）", next != null);
                assertTrue("下一步必须更近：" + here + " " + mine + " → " + next,
                        field.distanceAt(next) < mine);
            }
        }
        assertEquals("空地加上缺口那一格，整块棋盘都该铺得到", COLS * ROWS, reachable);
    }

    // ---- 有墙：绕还是拆 ----

    /**
     * <b>墙上有缺口就绕。</b>这就是"墙体是引导工具、不是挡板"的实现。
     *
     * <p>最利落的一条：站在缺口下边那一格（9,1），下一步该是<b>往上一格</b>回缺口，
     * 而不是往右踏进墙里。两个价钱差着三个数量级（2 对 4096），
     * 所以这条断言其实是在查"绕路一定比穿墙便宜"。
     */
    @Test
    public void aWallWithAGapIsWalkedAroundNotThrough() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 0));   // 缺口开在最上面一行
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        assertEquals("该退回缺口，不是穿墙", new Cell(9, 0),
                field.bestNeighbour(new Cell(9, 1)));
    }

    /** 缺口在哪儿，远处的敌人就往哪儿拐。 */
    @Test
    public void theHerdFunnelsTowardsTheGap() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 2));
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        List<Cell> path = walk(field, new Cell(0, 17), 400);

        assertEquals("最后停在核心那一格", new Cell(30, 9), path.get(path.size() - 1));
        assertTrue("缺口该在路线上", path.contains(new Cell(10, 2)));
        for (Cell cell : path) {
            assertFalse("一步都不该踏进墙里：" + cell, walls.contains(cell));
        }
    }

    /**
     * 靠缺口那一头起步的敌人，跨过城墙时正好在缺口那一行。
     *
     * <p>断言的是"被赶到缺口里"这件事本身，而不是"第几步往上拐"——起步那一格上
     * "往右"和"往上"经常一样近，走哪边取决于邻居的遍历顺序，
     * 那不是这条测试要说的事（说清楚了，以后改遍历顺序就不会误伤它）。
     */
    @Test
    public void theHerdCrossesTheWallExactlyAtTheGap() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 2));
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        List<Cell> path = walk(field, new Cell(0, 17), 400);
        Cell crossing = null;
        for (Cell cell : path) {
            if (cell.col == 10) {
                crossing = cell;
                break;
            }
        }
        assertEquals("跨过城墙时正好在缺口那一行", new Cell(10, 2), crossing);
    }

    /**
     * <b>墙围死了就只能拆。</b>一整列堵满、没有任何缺口，于是穿墙是唯一的路，
     * 表上的箭头就指向那面墙。
     *
     * <p>这正是"绕"和"拆"是同一张表的两个结果、而不是两套规则的地方：
     * 玩家看得见的是两个行为，代码里只有一条"往数更小的邻格走"。
     */
    @Test
    public void aSealedWallIsWalkedIntoBecauseThereIsNoWayAround() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        // 核心在墙右边，所以从左边去哪儿都必须跨过第 10 列
        assertTrue("绕不过去：墙两头都顶到边界了",
                field.distanceAt(new Cell(9, 5)) > 0);

        List<Cell> path = walk(field, new Cell(0, 5), 400);
        Cell firstWall = null;
        for (Cell cell : path) {
            if (walls.contains(cell)) {
                firstWall = cell;
                break;
            }
        }
        assertTrue("总得踏进墙里——那是唯一的路", firstWall != null);
        assertEquals("踏进去的那一格在第 10 列", 10, firstWall.col);
        assertEquals("然后才到核心", new Cell(30, 9), path.get(path.size() - 1));
    }

    /** 远处绕一大圈，还是比穿一格墙便宜。这条是 {@code BLOCKED_COST} 那个数存在的理由。 */
    @Test
    public void goingAllTheWayAroundIsStillCheaperThanOneWallCell() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 0));   // 缺口在最远的那一头
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        int around = field.distanceAt(new Cell(9, 17));
        // 从 (9,17) 绕到缺口再折回 (9,9)：上 17 行 + 跨 2 格 + 下 9 行，怎么算都远小于 4096
        assertTrue("绕路的价钱是 " + around + "，得远小于穿一格墙",
                around < 100);
    }

    // ---- 边界 ----

    /** 棋盘外的格子没有表：敌人还没进场的时候就在这种格子上。 */
    @Test
    public void offTheBoardIsUnreachable() {
        PathField field = to(new Cell(5, 5));

        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(-1, 5)));
        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(COLS, 5)));
        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(5, -1)));
        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(5, ROWS)));
        assertNull("棋盘外没有下一步", field.bestNeighbour(new Cell(-1, 5)));
    }

    /** 没有目标就没有表——调用方（{@code Battlefield}）靠这个走"一路向右"那一支。 */
    @Test
    public void noGoalMeansNothingIsReachable() {
        PathField field = PathField.to(COLS, ROWS, uniform(Collections.emptySet()),
                Collections.emptyList());

        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(0, 0)));
        assertNull(field.bestNeighbour(new Cell(0, 0)));
    }

    /** 目标落在棋盘外时当没有——不能让一格界外的坐标把整张表铺歪。 */
    @Test
    public void aGoalOutsideTheBoardIsIgnored() {
        PathField field = PathField.to(COLS, ROWS, uniform(Collections.emptySet()),
                Collections.singletonList(new Cell(-3, 5)));

        assertEquals(PathField.UNREACHABLE, field.distanceAt(new Cell(0, 5)));
    }

    /**
     * 从最远的角落走到底：能到、而且<b>一步都不重复</b>。
     *
     * <p>"不重复"比"能到"更值钱：能到只说明终点对，不重复才说明路上没绕圈。
     * 敌人卡在原地转圈是这类算法最典型的坏法，而它在截图上看不出来。
     */
    @Test
    public void aWalkFromTheFarCornerArrivesWithoutDoublingBack() {
        Set<Cell> walls = new HashSet<>(Arrays.asList(sealedColumn(10)));
        walls.remove(new Cell(10, 8));
        PathField field = PathField.to(COLS, ROWS, uniform(walls),
                Collections.singletonList(new Cell(30, 9)));

        List<Cell> path = walk(field, new Cell(0, 0), COLS * ROWS);
        assertEquals("走到了", new Cell(30, 9), path.get(path.size() - 1));

        Deque<Cell> seen = new ArrayDeque<>();
        for (Cell cell : path) {
            assertFalse("这一格走过了：" + cell, seen.contains(cell));
            seen.add(cell);
        }
        assertTrue("再远也不该超过棋盘格数（" + path.size() + " 步）",
                path.size() <= COLS * ROWS);
    }
}
