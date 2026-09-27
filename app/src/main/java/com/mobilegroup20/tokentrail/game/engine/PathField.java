package com.mobilegroup20.tokentrail.game.engine;

import java.util.Arrays;
import java.util.Collection;
import java.util.PriorityQueue;
import java.util.function.ToIntFunction;

/**
 * 从目标铺出来的一张"还有多远"的表：每个格子记一个数，<b>敌人只要往数更小的
 * 邻格走，就一定会走到其中某一个目标跟前</b>。
 *
 * <h2>为什么是表，不是给每只敌人跑一次寻路</h2>
 *
 * <p>场上有十几只敌人，每只每帧都要问"下一步往哪儿"。
 * 一只一只去搜（A\*）的话，同一块地形会被搜十几遍，而且每加一只就是一遍。
 * 反过来做——<b>从目标往外铺一次</b>，铺完每个格子都知道了，之后每只敌人
 * 只查一下自己那格和四个邻居，常数时间。表里同时有多个目标时，每个格子
 * 自然被划给<b>离它最近的那个</b>，不需要额外写"去哪儿"的判断。
 *
 * <p>这张表<b>只在建筑变了的时候重铺</b>（摆一座、拆掉一座），
 * 不是每帧。摆一座塔是一秒钟一次的操作，铺 2880 个格子是微不足道的开销；
 * 每帧铺就成了 60 倍。（战场 2026-09-27 从 40×18 翻倍到 80×36，这个数跟着 ×4。）
 *
 * <h2>建筑不是墙，是"很贵的地"</h2>
 *
 * <p>铺表的时候，空地的价钱是 {@link #OPEN_COST}，建筑那一格多少钱<b>由调用方给</b>
 * （{@code stepCost} 这个函数）。这一个选择决定了敌人全部的行为，所以值得说清楚：
 * 定得够贵（贵过任何绕路）的建筑，绕路的总价一定比穿它便宜，于是敌人<b>会绕开</b>
 * ——玩家砌的墙因此成了"把敌人赶到塔底下"的工具，而不是一堵挡住就完事的墙。
 * 反过来说，只要它便宜，为它绕远就不划算了，表上的箭头直接指进它里面，
 * 敌人于是<b>动手拆它</b>。
 *
 * <p>换句话说，"绕"和"拆"不是两套规则，是同一张表在两种价钱下的两个结果。
 * 写成两个 if 的话，边界情况（绕到一半发现绕不过去）会变成一边走一边抖。
 *
 * <p><b>价钱表现在就剩一档</b>（{@code BuildingStats.WALL_PATH_COST}）：墙很贵，
 * 塔和核心是<b>目标</b>、压根不参与价钱（目标那几格恒为 0，见下面）。
 * 这个类自己不知道哪种该贵——它只认函数，那是个玩法决定，不归几何管。
 *
 * <h2>目标自己那几格</h2>
 *
 * <p>目标占的格子价钱是 0（它们是起点）。敌人走到紧挨目标的某一格时，
 * 表会指着目标——而目标是占着的，于是它贴上去停下开啃。
 * "走到目标"和"啃目标"在代码里是同一个事件的先后半步，不需要另写一个判断。
 * 核心是这样，塔也是这样：一张表、同一个机制，这正是"塔和核心优先级相同"
 * 在几何这一层的全部内容。
 */
public final class PathField {

    /** 走在空地上的一步。 */
    public static final int OPEN_COST = 1;

    /** 铺不到 = 走不过去。正常情况下不会出现（战场是连通的），当兜底留着。 */
    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private final int cols;
    private final int rows;

    /** 每个格子到目标的代价，下标是 {@code row * cols + col}。 */
    private final int[] dist;

    private PathField(int cols, int rows, int[] dist) {
        this.cols = cols;
        this.rows = rows;
        this.dist = dist;
    }

    /**
     * 铺一张通向 {@code goals} 的表。
     *
     * @param cols     几列
     * @param rows     几行
     * @param stepCost 踏进某一格要花多少。空地的价钱是 {@link #OPEN_COST}，
     *                 建筑那格给多少由调用方定——见类注释里"绕还是拆"那段
     * @param goals    目标格（核心、以及每座塔占的那几格）。可以有多个，
     *                 每个格子会被划给最近的那个。空的就没有表，调用方该自己处理
     */
    public static PathField to(int cols, int rows, ToIntFunction<Cell> stepCost,
                               Collection<Cell> goals) {
        int[] dist = new int[cols * rows];
        Arrays.fill(dist, UNREACHABLE);

        // Dijkstra。代价都是正数，用优先队列是图省事——2880 个格子，用什么都一样快。
        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        for (Cell goal : goals) {
            if (!inside(goal, cols, rows)) {
                continue;
            }
            int index = goal.row * cols + goal.col;
            dist[index] = 0;
            queue.add(new int[]{0, index});
        }

        while (!queue.isEmpty()) {
            int[] top = queue.poll();
            int cost = top[0];
            int index = top[1];
            if (cost > dist[index]) {
                continue;   // 这是一个被后来的更短路取代的旧条目
            }
            int col = index % cols;
            int row = index / cols;

            for (int side = 0; side < 4; side++) {
                int nextCol = col + (side == 0 ? 1 : side == 1 ? -1 : 0);
                int nextRow = row + (side == 2 ? 1 : side == 3 ? -1 : 0);
                if (nextCol < 0 || nextCol >= cols || nextRow < 0 || nextRow >= rows) {
                    continue;
                }
                int next = nextRow * cols + nextCol;
                // 价钱算在**踏进去**那一格上，所以目标格自己多贵都不影响——
                // 目标是占着格子的，但它必须是 0。
                int cand = cost + stepCost.applyAsInt(new Cell(nextCol, nextRow));
                if (cand < dist[next]) {
                    dist[next] = cand;
                    queue.add(new int[]{cand, next});
                }
            }
        }
        return new PathField(cols, rows, dist);
    }

    /** 这一格离目标还有多贵。见 {@link #UNREACHABLE}。 */
    public int distanceAt(Cell cell) {
        if (!inside(cell, cols, rows)) {
            return UNREACHABLE;
        }
        return dist[cell.row * cols + cell.col];
    }

    /**
     * 站在 {@code from} 上，下一步该踏进哪一格。
     *
     * <p>挑四邻里 {@link #distanceAt 数字最小}的那一个，而且必须<b>严格更小</b>：
     * 不过滤掉"一样大"的话，敌人会在两个同样好的格子之间来回横跳。
     *
     * <p>返回 {@code null} 表示"这儿就是最近的落脚点了"——紧挨着某个目标、
     * 或者四周都被围死。两种情况敌人做的都是同一件事：<b>啃挡在前面的那一座</b>，
     * 所以调用方不需要分开处理。
     */
    public Cell bestNeighbour(Cell from) {
        int here = distanceAt(from);
        if (here == UNREACHABLE) {
            return null;
        }

        Cell best = null;
        int bestDist = here;
        for (int side = 0; side < 4; side++) {
            int col = from.col + (side == 0 ? 1 : side == 1 ? -1 : 0);
            int row = from.row + (side == 2 ? 1 : side == 3 ? -1 : 0);
            if (col < 0 || col >= cols || row < 0 || row >= rows) {
                continue;
            }
            int cand = dist[row * cols + col];
            if (cand < bestDist) {
                bestDist = cand;
                best = new Cell(col, row);
            }
        }
        return best;
    }

    private static boolean inside(Cell cell, int cols, int rows) {
        return cell.col >= 0 && cell.col < cols && cell.row >= 0 && cell.row < rows;
    }
}
