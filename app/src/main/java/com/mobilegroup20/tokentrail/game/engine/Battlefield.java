package com.mobilegroup20.tokentrail.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 战场上的东西：放了哪些建筑、场上还有哪些敌人，以及<b>它们每一帧怎么互相消耗</b>。
 *
 * <p><b>不 import 任何 {@code android.*}</b>，所以这些规则能脱离模拟器跑测试
 * （见 {@code BattlefieldTest}）——敌人走到哪儿会被挡住、塔打不打得死一只杂兵、
 * 核心能撑多久，在真机上试要试很久，在这里是一段能跑的算术。
 *
 * <h2>一帧里发生什么</h2>
 *
 * <p>{@link #advance} 干四件事，<b>顺序不能换</b>：
 *
 * <ol>
 *   <li>{@code fireTowers}——塔瞄一下，装填好了就吐一发子弹；</li>
 *   <li>{@code moveProjectiles}——子弹飞一帧，飞到了就结算伤害。
 *       排在敌人之前：<b>这一帧刚被打死的敌人就没机会再咬了</b>，反过来
 *       （先动敌人）会出现"已经死了还在拆墙"的一帧，而且"一座塔能不能在敌人
 *       进射程的瞬间救下那面墙"这种事会变得说不清；</li>
 *   <li>{@code moveEnemies}——敌人走一格或者停下来啃东西；</li>
 *   <li>{@code clearTheDead}——统一清场：不喘气的敌人移走、没耐久的建筑拆掉、
 *       核心倒下就记一笔"这局完了"。</li>
 * </ol>
 *
 * <p><b>伤害是"一发一发"的，不是每秒多少点。</b>塔按
 * {@link BuildingStats#fireIntervalSeconds} 吐 {@link Projectile}，
 * 子弹飞到了才扣血（见 {@link Projectile}：它是追着目标打的，
 * 所以出膛就等于会命中，目标先死除外）。这样"打了"和"掉血"之间有个看得见的过程，
 * 也让"好几座塔一起糊一只"不再是无损的最优解——会打空。
 *
 * <p>代价是伤害不再和帧率完全无关，而是<b>被"发射的时机跟着帧走"影响几个百分点</b>：
 * 装填计时用的是"减 dt、开火时再加回去"（见 {@link #fireTowers}），
 * 所以长跑下来射速是准的，误差只在一帧以内。{@code BattlefieldTest} 里有一条
 * 测试拿两种帧率跑同一段时间，比谁挨的伤害多。
 *
 * <h2>这一版有什么、没有什么</h2>
 * <p><b>有</b>：占地占位、左边缘是入侵口、敌人从左边进场<b>自己找路去最近的建筑</b>
 * （塔和核心平等，见 {@link #isTarget}）、被城墙和塔挡住就停下开啃、
 * 塔<b>打子弹</b>（{@link Projectile}）并且挑射程里最靠前的那只、血量和耐久、
 * 建筑被打掉、核心被打掉就输、五波打完算守住（{@link Waves}）。
 *
 * <p><b>还没有</b>：敌人之间互相挡（同一条路上会叠在一起）、溅射/减速这类塔的变种、
 * 掉落奖励（资源只从结算来，见 {@code CONTRACTS.md} §8）、波次之间的倒计时。
 * 建造成本不在这里，在 {@link ShopCatalog}——这个类只管
 * "放得下吗"，不管"买得起吗"，两件事混在一起就没法单独测几何了。
 *
 * <h2>敌人怎么走</h2>
 * <p>敌人占 1×1，位置是连续格子坐标的 {@code x}／{@code y}，两个都是它身体的中心。
 * <b>方向不是"一路向右"，是"照着寻路表往最近的建筑走"</b>：每帧问一次
 * {@link PathField#bestNeighbour}（就是四邻里表上数更小的那一格），朝那一格的中心
 * 按 {@link EnemyType#speedCellsPerSec} 迈一步；踏上下一格之前先看那一格有没有东西：
 * 是城墙或塔就贴上去开啃，是核心就停下来啃核心。
 *
 * <p><b>"最近的建筑"里的"近"是寻路表上的价钱，不是直线距离</b>：敌人会算上绕路，
 * 所以隔着一面墙的塔，可能反而是远的那一座。
 *
 * <p>所以<b>墙不是"挡住就完事"，是"把敌人赶到你想让它去的地方"</b>：留一个缺口，
 * 敌人就绕去缺口；围死了，它就只能拆。<b>"从别的行漏过去"已经不存在了</b>——
 * 敌人会拐弯。但拐向哪儿现在有两种落点：核心，或者路上那座更近的塔。
 *
 * <p>场上既没有核心也没有塔时（测试里那种空场地）退回"一路向右"：走到右边缘
 * 出界就算漏怪，不减核心的耐久，因为核心没在那儿挡着。
 */
public final class Battlefield {

    /**
     * 最左边几列是 {@link Terrain#ENEMY_LANE 敌人通道}（不能建东西）。
     *
     * <p>敌人从左边来，通道这一段是"看得见但打不着"的：敌人走进来、玩家有时间
     * 看清是哪条路、再决定往哪补塔。留 1 列的话敌人一出现就已经贴着可建区了，
     * 没有反应时间。
     *
     * <p>通道也是"左边更长"这件事的落点：{@link BoardGeometry#COLS} 是 40 格，
     * 减掉这 4 格和右边的 {@link #MOUNTAIN_COLS} 格，可建区正好 33 格。
     */
    public static final int ENEMY_LANE_COLS = 4;

    /**
     * 最右边几列是 {@link Terrain#MOUNTAIN 山区}（也不能建东西）。
     *
     * <p>作用是给地图一个自然的右边界：核心要贴着右边摆，没有这一段的话
     * 核心就像是贴在画面边缘被裁掉了。它同时也是"以后要在这边加东西"的余量。
     *
     * <p>换成纯玩法的话说：这 3 格是 {@link #terrainAt} 里唯一一处"不能建、
     * 但敌人也不会从这儿进来"的地——它和通道是两回事，所以颜色也不一样。
     */
    public static final int MOUNTAIN_COLS = 3;

    /**
     * 一座塔一帧最多吐几发。
     *
     * <p>正常永远到不了：最短的间隔是 0.75 秒，而调用方把一帧夹在
     * {@code BattlefieldView.MAX_FRAME_SECONDS}（0.05 秒）以内，一帧最多一发。
     * 留这个上限是防<b>退化的 dt</b>：测试里会直接 {@code advance(5f)}，
     * 万一哪天有个 dt 变成几百秒，没有上限的话这里会一次造出几百颗子弹
     * （而它们还都在同一个位置、朝同一个目标）。
     */
    private static final int MAX_SHOTS_PER_FRAME = 8;

    /**
     * 一波打完没打完、这一局是输是赢。
     *
     * <p>{@link #ONGOING} 之外的两种都是<b>终局</b>：到了就停下不推了
     * （{@link #advance} 直接返回），界面也就不会再要帧。这是刻意的——
     * 让尸体继续爬、或者让塔继续打空气，只会让"已经结束了"这件事看不出来。
     */
    public enum Outcome {
        /** 还在打。 */
        ONGOING,
        /** 核心被打没了。 */
        OVERRUN,
        /** 五波全清，核心还在。 */
        DEFENDED
    }

    private final BoardGeometry board;

    /** 格子 → 建筑。占地是多格，所以同一个建筑会在好几个格子上出现。 */
    private final Map<Cell, Building> occupancy = new HashMap<>();

    private final List<Building> buildings = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();

    /**
     * 天上还在飞的子弹，按出膛顺序。
     *
     * <p><b>它是一份真实的账，不只是给绘图看的。</b>挑目标的时候要问它"这一只
     * 是不是已经有子弹在路上了"（见 {@link #damageInFlightAt}）——不然十座塔会
     * 一起对着同一只快兵吐十发，前面两发打死它、后面八发打空。
     */
    private final List<Projectile> projectiles = new ArrayList<>();

    /**
     * 敌人<b>去哪儿</b>的"还有多远"表。见 {@link PathField}。
     *
     * <p>目标集合是<b>所有塔 + 核心</b>（{@link #isTarget}），墙不在里面。
     * 也就是说敌人不认"核心"这一个终点：塔和核心在它眼里<b>一样是目标</b>，
     * 它去最近的那一座。所以一座塔只要离敌人比核心近，敌人就是冲着塔来的。
     *
     * <p>{@code null} 表示场上既没有塔也没有核心（目标集合是空的）——这时敌人
     * 一路向右，和加寻路之前的行为一样。
     */
    private PathField path;

    /**
     * "离核心还有多远"的表，<b>只用来算谁最靠前</b>（{@link #progressOf}），
     * 不指导走路。
     *
     * <p>为什么不和 {@link #path} 合一张：那张表的目标是"所有塔 + 核心"，
     * 于是站在塔跟前的敌人数值特别小、看着最"靠前"，塔就会集体去招呼一个
     * 正在拆塔的敌人，而不是拦真正逼近核心的那只。"谁最危险"这个问题问的是
     * 核心，所以得单独有一张只从核心铺出来的表。
     *
     * <p>代价是建筑一动要铺两遍。铺一遍是 720 个格子的 Dijkstra，摆一座塔
     * 触发一次，可以忽略。
     *
     * <p>{@code null} 表示场上没有核心。
     */
    private PathField corePath;

    /**
     * 上面那张表要不要重铺。
     *
     * <p><b>只在建筑动了的时候置位</b>，不是每帧。铺一次是 720 个格子的 Dijkstra，
     * 摆一座塔触发一次可以忽略，每帧一次就是 60 倍的浪费——而地形不动的时候，
     * 铺出来的表一模一样。
     *
     * <p>置位的活儿全在 {@link #occupy}／{@link #vacate} 里：占位表只有这两个入口，
     * 所以"改了地形忘了重铺"这种 bug 无处可藏。
     */
    private boolean pathDirty = true;

    private int waves;
    private int leaks;

    /**
     * 核心倒过了没有。
     *
     * <p><b>要单独记一笔，不能靠"场上还有没有核心"推。</b>核心被打掉之后会被移出
     * 战场（它得从画面上消失），那时 {@link #core()} 返回 {@code null}——
     * 而"没有核心"和"核心刚被打掉"是两件完全不同的事（测试里就有不摆核心的局面）。
     * 用引用去推，会把后者读成前者。
     *
     * <p>只有 {@link #clear} 会把它清掉；终局之后不重开是刻意的，见 {@link Outcome}。
     */
    private boolean overrun;

    public Battlefield(BoardGeometry board) {
        if (board == null) {
            throw new IllegalArgumentException("战场几何不能为空");
        }
        this.board = board;
    }

    public BoardGeometry board() {
        return board;
    }

    // ---- 建筑 ----

    /** 已经放下的建筑，按放下顺序。渲染前自己按行排一下（见 {@code BattlefieldView}）。 */
    public List<Building> buildings() {
        return Collections.unmodifiableList(buildings);
    }

    /** 占了这一格的建筑，没有就返回 {@code null}。 */
    public Building buildingAt(Cell cell) {
        return occupancy.get(cell);
    }

    /**
     * 场上的核心，没有就返回 {@code null}。
     *
     * <p>返回第一个而不是"唯一一个"：规则上核心只该有一座，但场上真出现两座时
     * 这里不该崩，也不该假装看不见。{@link #moveCore} 靠它找到要挪的那一座。
     */
    public Building core() {
        for (Building building : buildings) {
            if (building.type == BuildingType.CORE) {
                return building;
            }
        }
        return null;
    }

    /**
     * 把一座建筑的占地记进占位表。
     *
     * <p>占位表只有这一个"写"入口（另一个是 {@link #vacate}），所以"地形变了要重铺寻路表"
     * 这件事只需要在这儿写一次。摆、挪、清场都走这里，谁也不会漏。
     */
    private void occupy(Building building) {
        for (Cell cell : board.cellsOf(building.col(), building.row(),
                building.type.cols, building.type.rows)) {
            occupancy.put(cell, building);
        }
        pathDirty = true;
    }

    /** 把一座建筑的占地从占位表里划掉。见 {@link #occupy}。 */
    private void vacate(Building building) {
        for (Cell cell : board.cellsOf(building.col(), building.row(),
                building.type.cols, building.type.rows)) {
            occupancy.remove(cell);
        }
        pathDirty = true;
    }

    // ---- 地形 ----

    /**
     * 这一格是什么地形。越界的行列会抛异常——它是个"不该发生"的编程错误，
     * 不像手指点歪那样属于正常输入。
     *
     * <p>现在的地形是<b>按列分段</b>的：左边 {@link #ENEMY_LANE_COLS} 格通道、
     * 右边 {@link #MOUNTAIN_COLS} 格山区、中间全可建。用"按格子查"而不是
     * "按列查"是为了以后能在中间也摆山（关卡编辑），到时候只改这一个方法，
     * 画图和判定都不用动。
     */
    public Terrain terrainAt(int col, int row) {
        if (col < 0 || col >= board.cols() || row < 0 || row >= board.rows()) {
            throw new IllegalArgumentException(
                    "地形查询越界: (" + col + ", " + row + ")，战场是 "
                            + board.cols() + "×" + board.rows());
        }
        if (col < ENEMY_LANE_COLS) {
            return Terrain.ENEMY_LANE;
        }
        if (col >= board.cols() - MOUNTAIN_COLS) {
            return Terrain.MOUNTAIN;
        }
        return Terrain.BUILDABLE;
    }

    /** 可建区第一列（含）。 */
    public int firstBuildableCol() {
        return ENEMY_LANE_COLS;
    }

    /** 可建区最后一列（含）。 */
    public int lastBuildableCol() {
        return board.cols() - MOUNTAIN_COLS - 1;
    }

    // ---- 放置 ----

    /** 这个位置能不能放这类建筑。 */
    public boolean canPlace(BuildingType type, int col, int row) {
        return canPlace(type, col, row, null);
    }

    /**
     * 同上，但判定时<b>假装某座建筑不存在</b>。
     *
     * <p>只有一个用它的地方：挪核心（{@link #moveCore}）。核心挪一格时新旧占地会
     * 重叠，不把它自己让开的话，"往右挪一格"会被判成压住自己而失败。
     *
     * @param ignore 判定时要忽略的建筑，可以为 {@code null}
     */
    public boolean canPlace(BuildingType type, int col, int row, Building ignore) {
        if (type == null || !board.fits(col, row, type.cols, type.rows)) {
            return false;
        }
        // 占地压到的每一格都得是空地：越界在上面查过了，
        // 这儿查的是"是否可建"（通道和山区都不能建）和"有没有人占"
        for (Cell cell : board.cellsOf(col, row, type.cols, type.rows)) {
            if (!terrainAt(cell.col, cell.row).buildable()) {
                return false;
            }
            Building occupant = occupancy.get(cell);
            if (occupant != null && occupant != ignore) {
                return false;
            }
        }
        return true;
    }

    /**
     * 放一座建筑。
     *
     * @return 放下的建筑；{@link #canPlace} 不通过时返回 {@code null}
     *         （而不是抛异常：手指点到放不下的地方是正常操作，不是程序错误）
     */
    public Building place(BuildingType type, int col, int row) {
        if (!canPlace(type, col, row)) {
            return null;
        }
        Building building = new Building(type, col, row);
        buildings.add(building);
        occupy(building);
        return building;
    }

    /** 拆掉一座建筑，把格子还回去。 */
    public boolean remove(Building building) {
        if (building == null || !buildings.remove(building)) {
            return false;
        }
        vacate(building);
        return true;
    }

    /**
     * 把场上的某座建筑挪到新位置。
     *
     * <p><b>不花钱。</b>这和"再买一座"是两件事：挪动不消耗资源，所以没有
     * {@link ShopCatalog} 那一套问价/扣钱。参考的是部落冲突——摆错位置随时能改，
     * 玩家才敢在开局就下手摆。
     *
     * <p><b>是"改坐标"不是"拆了重放"</b>（{@link Building#relocateTo}）：
     * 同一座建筑挪完还是同一个对象，所以
     * <ul>
     *   <li>{@code level} 带得走——升到 3 级的塔挪一下不会退回 1 级；</li>
     *   <li>拿着这个引用的地方（详情面板、战场上的选中高亮）不会指向一座
     *       已经不在场上的建筑。</li>
     * </ul>
     *
     * <p>三步走，任何一步不成立都当没发生过：
     * <ol>
     *   <li>建筑不是场上的（拆过了、或者是别的局留下的）→ {@code false}；</li>
     *   <li>新位置放不下（压到别的建筑、压到通道或山区、出界）→ 原地不动，{@code false}；</li>
     *   <li>放得下 → 把旧格子还回去、占上新格子、改坐标，{@code true}。</li>
     * </ol>
     *
     * <p>判定新位置时必须把<b>它自己</b>从占位表里让开，否则"往右挪一格"会压到自己而失败。
     * 这也是 {@link #canPlace(BuildingType, int, int, Building)} 那个重载存在的唯一理由。
     *
     * @return 挪成功了返回 {@code true}；建筑不在场上或挪不过去返回 {@code false}
     */
    public boolean move(Building building, int col, int row) {
        if (building == null || !buildings.contains(building)) {
            return false;
        }
        if (building.col() == col && building.row() == row) {
            return true;   // 点在原地：什么也不用做，但这是成功，不是失败
        }
        if (!canPlace(building.type, col, row, building)) {
            return false;
        }

        // 先还旧格子、再占新格子。顺序不能反：两块占地重叠时（比如往右挪一格），
        // 先占新的会把重叠的那几格算成"被自己占着"，旧格子就还不干净。
        vacate(building);
        building.relocateTo(col, row);
        occupy(building);
        return true;
    }

    /**
     * 把场上的核心挪到新位置——<b>核心是"挪"不是"再放一座"</b>。
     *
     * <p>核心该摆哪儿是玩家开局的第一个决定（守哪条路、留多少纵深），所以它得能改。
     * 界面上就是"选中核心 → 点新位置"，和放塔是同一个动作，区别只在于场上已经有核心时
     * 这次点击是<b>移动</b>而不是新增。
     *
     * <p>规则本身在 {@link #move}，这里只是"帮我找到场上那个核心"。
     *
     * @return 挪成功了返回 {@code true}；没核心或挪不过去返回 {@code false}
     */
    public boolean moveCore(int col, int row) {
        return move(core(), col, row);
    }

    /**
     * 给一座已有的建筑升一级。
     *
     * <p><b>这里只管"等级"这一个数，不管钱。</b>和 {@link #place} 一样：
     * 引擎判定"成不成立"，价格和余额是玩法侧的账（{@link BuildingStats#upgradeCost}
     * + 界面上的钱包）。所以调用方必须<b>先问价、后调这里</b>，顺序反了就是白升级。
     *
     * <p>不成立的情况一律返回 {@code false} 且什么都不改：
     * 建筑不是场上的（拆过了、或者是别的局留下的），或者已经到
     * {@link BuildingStats#MAX_LEVEL}。<b>三种建筑都能升</b>（墙和核心涨耐久，
     * 见 {@link BuildingStats#maxHp}），所以这里没有"这种建筑不能升"这一支。
     *
     * <p><b>升级顺带回满血</b>（{@link Building#repair}）：一座被打掉一半的塔升一级
     * 之后是满的。这样"修复"不用单独做一个动作和一套界面，升级这个按钮也就多了一层
     * 理由——被打残的塔升一级，比再买一座新的划算。<b>墙和核心更明显</b>：
     * 它们升级的全部意义就是耐久，不回满的话"升级"和"修好"就得分成两件事做。
     *
     * @return 真涨了一级返回 {@code true}
     */
    public boolean upgrade(Building building) {
        if (building == null || !buildings.contains(building)) {
            return false;
        }
        if (!BuildingStats.canUpgrade(building.type, building.level)) {
            return false;
        }
        building.level++;
        // 必须在 level++ 之后：maxHp 是跟着等级算的，先修就是按旧等级修
        building.repair();
        return true;
    }

    /** 清空重新摆。切关卡、重开一局时用。 */
    public void clear() {
        buildings.clear();
        occupancy.clear();
        enemies.clear();
        // 天上的也要清：不清的话重开一局会带着上一局最后那几发飞进来，
        // 而它们瞄的敌人已经不在场上了（不算错误，但纯属白画）
        projectiles.clear();
        waves = 0;
        leaks = 0;
        overrun = false;
        pathDirty = true;
    }

    // ---- 敌人 ----

    public List<Enemy> enemies() {
        return Collections.unmodifiableList(enemies);
    }

    /**
     * 天上还在飞的子弹，按出膛顺序。渲染读它画子弹本身。
     *
     * <p><b>它们是"这一帧真正在发生的事"，不是"这一帧刚发生的"</b>：
     * 一发出膛到命中要飞二十几帧，所以这个列表平时是"最近几秒的火力"。
     * 界面那边判断"要不要继续要下一帧"也得看它——只看敌人列表的话，
     * 最后一波清空的那一刻，天上那几发会僵在半路（见 {@code BattlefieldView.tick}）。
     */
    public List<Projectile> projectiles() {
        return Collections.unmodifiableList(projectiles);
    }

    /** 累计发了几波。也是"现在打到第几波"。 */
    public int waves() {
        return waves;
    }

    /**
     * 累计漏了几个。
     *
     * <p>两种都算：<b>摸到核心</b>的（会开始啃核心），和<b>从右边走出去</b>的
     * （核心没挡住那几行，谁也拦不住）。前者一只只记一次，见 {@link Enemy#reachedCore}。
     *
     * <p>它和核心耐久不是一回事：核心还有 200 点血的时候可能已经漏了六只，
     * 而那六只正在啃。这个数是"防线被穿透了几次"，给结算和界面用。
     */
    public int leaks() {
        return leaks;
    }

    /**
     * 这一局是输是赢，还是还在打。
     *
     * <p>三件事决定它，顺序就是优先级：
     * <ol>
     *   <li>核心被打掉过（{@link #overrun}）→ {@link Outcome#OVERRUN}。
     *       <b>排在"全清"前面</b>：最后一波的最后一只敌人和核心同归于尽时，
     *       该报的是输——玩家心里那局已经结束了；</li>
     *   <li>五波发完而且场上没人了 → {@link Outcome#DEFENDED}；</li>
     *   <li>其余 → {@link Outcome#ONGOING}（包括"一波都没发过"）。</li>
     * </ol>
     */
    public Outcome outcome() {
        if (overrun) {
            return Outcome.OVERRUN;
        }
        if (Waves.allCleared(waves, enemies.size())) {
            return Outcome.DEFENDED;
        }
        return Outcome.ONGOING;
    }

    /** 打完了没有（输了或者守住了）。界面用它决定要不要收手。 */
    public boolean decided() {
        return outcome() != Outcome.ONGOING;
    }

    /**
     * 场上还剩几只（没算还没进场的那部分——它们已经在 {@link #enemies()} 里了，
     * 只是 {@code x} 还是负的）。
     */
    public int enemiesLeft() {
        return enemies.size();
    }

    /**
     * 在指定位置放一只敌人。
     *
     * <p>给测试和"手动摆一个场景"用：{@code x} 用连续格子坐标，
     * 负数表示还没进场。
     *
     * @param type 哪一种敌人，数值见 {@link EnemyType}
     * @param row  走哪一行，越界会被夹进战场
     * @return 放下的敌人
     */
    public Enemy spawn(EnemyType type, int row, float x) {
        Enemy enemy = new Enemy(type == null ? EnemyType.GRUNT : type,
                Math.max(0, Math.min(board.rows() - 1, row)), x);
        enemies.add(enemy);
        return enemy;
    }

    /**
     * 发下一波：按 {@link Waves} 的曲线决定来哪些敌人，每只随机挑一行，
     * 从画面<b>左边外面</b>出发，按顺序错开进场。
     *
     * <p><b>一波没打完不许发下一波。</b>同时来两波的话，敌人数量翻倍但塔的 dps 没变，
     * 玩家看到的只是"忽然多了一倍"——那不是难度，是算错账。而且界面上那个
     * "还剩几只"会同时表示两件事，说不清楚。想加难度请改 {@link Waves} 的曲线。
     *
     * <p>错开的间距是 {@code 0.55} 格：一只接一只地走进来，玩家看得清
     * "这一波来了多少、都是什么"，而不是一坨同时出现在通道口。
     *
     * @return 真发了返回 {@code true}；打完了、上一波还没清完、或者五波都发过了返回
     *         {@code false}（不是错误，是"现在不该发"）
     */
    public boolean spawnWave(Random random) {
        if (decided() || !enemies.isEmpty() || waves >= Waves.TOTAL_WAVES) {
            return false;
        }
        waves++;
        List<EnemyType> lineup = Waves.composition(waves);
        for (int i = 0; i < lineup.size(); i++) {
            // 错开进场：第 i 只在画面外再往左退一点，于是它们排队走进来
            spawn(lineup.get(i), random.nextInt(board.rows()), -1f - i * 0.55f);
        }
        return true;
    }

    /**
     * 推一帧：塔开火、子弹飞、敌人走或者啃、然后清场。
     *
     * <p>四步的顺序和理由见类注释。终局之后直接返回——已经结束的局不该再动。
     *
     * @param dtSeconds 距上一帧的秒数；不是正数就直接返回
     */
    public void advance(float dtSeconds) {
        if (dtSeconds <= 0f || overrun) {
            return;
        }
        // 先补寻路表：这一帧"塔打谁"和"敌人往哪走"都要用它，而且都在下面几步里，
        // 晚一步补就会出现"塔按旧地形挑目标、敌人按新地形走"的半帧不一致
        rebuildPathIfStale();
        fireTowers(dtSeconds);
        moveProjectiles(dtSeconds);
        moveEnemies(dtSeconds);
        clearTheDead();
    }

    /**
     * 每座塔：先看炮口对着谁，再看装填好了没有，好了就吐一发。
     *
     * <p><b>"瞄准"和"开火"是两件事，别混。</b>瞄准每帧都做——挑射程里
     * <b>最靠前</b>的那只（理由见 {@link #frontmostInRange}），
     * 挑出来的 {@link Building#target} 会一直留着给绘图看（换真贴图之后
     * 塔身/炮管按它转向）。开火只在装填归零的那几帧发生。
     *
     * <p><b>装填计时用"减 dt、开火时再加回去"，不是"开火时赋值"。</b>
     * 赋值的话，一帧多出来的那点时间（dt 0.05、间隔 0.75 → 每发白丢 0.05÷0.75 ≈ 6.7%）
     * 会一直累积，于是实际射速取决于帧率。<b>空闲时把负数夹回 0</b>：
     * 不然一只敌人被挡在圈外等了三秒，塔一进圈就补上三发，看着像卡了一下。
     *
     * <p>{@code while} 而不是 {@code if}：dt 大到跨过一整个间隔时（测试里会
     * {@code advance(5f)}），该补的发数要全补上，射速才和帧率无关。上限见
     * {@link #MAX_SHOTS_PER_FRAME}。
     */
    private void fireTowers(float dtSeconds) {
        for (Building tower : buildings) {
            double interval = BuildingStats.fireIntervalSeconds(tower.type, tower.level);
            if (interval <= 0.0) {
                continue;   // 墙和核心：压根没有"开火"这回事
            }

            tower.target = frontmostInRange(tower);
            tower.cooldownSeconds -= dtSeconds;
            if (tower.target == null) {
                if (tower.cooldownSeconds < 0f) {
                    tower.cooldownSeconds = 0f;   // 空闲不攒进度，见上面那段
                }
                continue;
            }

            for (int shots = 0; shots < MAX_SHOTS_PER_FRAME; shots++) {
                if (tower.cooldownSeconds > 0f) {
                    break;
                }
                projectiles.add(new Projectile(tower, tower.target,
                        // 出膛点是**占地中心**：范围圈的圆心、判定用的圆心、
                        // 从前那条连线的起点，三处现在还是同一个点
                        tower.col() + tower.type.cols / 2f,
                        tower.row() + tower.type.rows / 2f,
                        (float) BuildingStats.damagePerShot(tower.type, tower.level),
                        BuildingStats.PROJECTILE_SPEED_CELLS_PER_SEC));
                tower.cooldownSeconds += (float) interval;
            }
        }
    }

    /**
     * 天上那些子弹飞一帧，飞到了就结算。
     *
     * <p><b>打中的是"出膛时瞄的那一只"，不是"落点上现在站着谁"。</b>
     * 目标半路死了（或者已经被清场移走）这一发就白飞了——不减别人的血，
     * 也不复活它。留这个规矩是因为它才是塔防里那个直觉："这颗炮弹是冲它去的"。
     * 以后要加溅射，"落点周围有谁"就是在这一支里往外找（{@link Projectile#aimX()}）。
     *
     * <p>唯一一处"打到也没用"的情况是<b>从右边缘走出去的那只</b>（场上没核心时
     * 才会发生）：它已经不在 {@link #enemies} 里了，扣它的血不会有任何后果
     * （漏怪在走出场那一刻就记过了）。为它多做一次成员检查不值得，写在这儿免得
     * 以后有人看见"打进了一只不在场上的敌人"以为出了 bug。
     */
    private void moveProjectiles(float dtSeconds) {
        if (projectiles.isEmpty()) {
            return;   // 每帧都调，空的时候别白建迭代器
        }
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile shot = it.next();
            if (!shot.step(dtSeconds)) {
                continue;   // 还在路上
            }
            it.remove();
            if (shot.lands()) {
                shot.target.hp -= shot.damage;
            }
        }
    }

    /**
     * 天上还有多少伤害是冲着这一只去的。
     *
     * <p>挑目标时要减掉它（见 {@link #frontmostInRange}）：<b>"已经被打死的账"
     * 不该再重复记一遍</b>。没有这一问的话，六座塔会在同一帧里对着同一只 28 血的
     * 快兵各吐一发（= 84 点），前两发就够了，后四发全打空——<b>而塔明明可以
     * 把那四发打给后面那只</b>。这一个加法就是"火力会不会自动铺开"的全部。
     *
     * <p>只算还在天上的，不算"这一帧已经扣掉的"：已经扣掉的已经从
     * {@code hp} 里减了，再减一遍会重复。
     *
     * <p>复杂度是"塔 × 圈里的敌人 × 天上的子弹"，一帧几十×几十×几十次浮点加法；
     * 和画一遍地面（几十个矩形 + 一整片斜纹）比起来不算什么，所以不为它建表。
     */
    private float damageInFlightAt(Enemy enemy) {
        float total = 0f;
        for (Projectile shot : projectiles) {
            if (shot.target == enemy) {
                total += shot.damage;
            }
        }
        return total;
    }

    /**
     * 射程里<b>离这座塔最近</b>的那只敌人，没有就 {@code null}。
     *
     * <p>距离是<b>圆心到圆心</b>，用格子坐标算：建筑取占地矩形的中心，
     * 敌人取身体的中心（{@link Enemy#centreX()}）。两个中心都在"连续格子坐标"
     * 这一套里，和界面上画的那个圈用的是同一个圆心——圈画在哪儿就打得到哪儿，
     * 不会出现"看着在圈里却没挨打"。
     *
     * <p><b>再加半个身位（{@link EnemyType#halfBodyCells()}），判定改成"圈碰到就算"。</b>
     * 圈画的是"离我 {@code range} 格以内"，而敌人的判定点是它的<b>中心</b>：
     * 只按中心算的话，半个身子探进圈里的敌人反而不挨打。
     *
     * <p>这不是理论问题，真机上就是<b>紧贴城墙的那只</b>：塔摆在墙后面、
     * 敌人贴在墙左边，圆心到敌人中心正好 {@code 2.5} 格多一点（墙宽 1 格 +
     * 敌人半个身位 + 塔自己的半个占地 = 2.5，再加上纵向错开半格），
     * 差 0.05 格打不着。而城墙的全部价值就是"替后面的塔多争取几秒"
     * （见 {@link BuildingStats#WALL_HP}）——塔不打贴在墙上的那只，
     * 墙就白砌了。加上这半个身位之后，墙后紧贴的那座塔打得着，
     * 而且画面上"圈碰到了敌人"和"敌人在挨打"这两件事重新对得上。
     *
     * <p>身位是<b>逐只</b>算的，不是全局一个常数：重甲比杂兵宽，所以它能从
     * 更远处就开始挨打。这正好和它"厚"这件事配套——不然画面上一只大块头
     * 站在圈外挨打，或者小个子快兵冲进圈里还不挨打。
     *
     * <h2>为什么是"最靠前"而不是"最近"</h2>
     *
     * <p>"最靠前"= <b>寻路表上到核心还剩的价钱最小</b>（不是 x 最大——敌人会绕路，
     * 一只拐到上面去的可能 x 更小而其实更接近核心）。"打离自己最近的那只"试过一版，
     * 又改回来了，理由是实测：20 个种子的演示场面里"最靠前"守住 6 个、"就近"只守住 3 个
     * （量的方法见 {@code DemoSceneProbeTest}）。差距不大，但方向是稳的。
     * （那次量的是<b>塔瞄谁</b>，敌人当时还是"一路奔核心"；后来敌人也改成了
     * "去最近的建筑"，见上一段。）
     *
     * <p>"就近"在画面上的好处是说得通（圈只有 2.5 格，塔不会越过贴脸的那只去打
     * 斜对面的），但代价是塔<b>不再优先拦截最危险的那只</b>：火力被近处、其实
     * 还离核心很远的敌人分走，该拦的那只就多走了几步。塔防里这几步就是漏怪。
     *
     * <p>所以这一支认的是"拦最危险的那只"。想让某条路上多拦几只，办法是
     * <b>在那条路上多摆一座塔</b>——只是别指望每座塔自己越位去挑。
     *
     * <p><b>但"最靠前"不是"守得住五波"的充分条件。</b>敌人挑谁当目的地也一起改过
     * 一轮（现在是"最近的建筑"，塔和核心平等），那一轮把演示场面又推回到第 3 波崩。
     * 三组口径各跑一遍的数据记在 {@code docs/TASKS.md} 的"演示场面"那一节
     * ——动这两个口径之前先看那张表。
     *
     * <p><b>已经被子弹包住的那只跳过</b>（{@link #damageInFlightAt}）：
     * 塔能看见天上飞着什么，所以不会六座塔一起糊一只快兵。
     * 于是"最靠前的那只"实际上读作"最靠前、而且还没被安排掉的那只"。
     */
    private Enemy frontmostInRange(Building tower) {
        double range = BuildingStats.rangeCells(tower.type, tower.level);
        if (range <= 0.0) {
            return null;
        }
        float centreX = tower.col() + tower.type.cols / 2f;
        float centreY = tower.row() + tower.type.rows / 2f;

        Enemy best = null;
        int bestProgress = 0;
        for (Enemy enemy : enemies) {
            if (!enemy.alive()) {
                continue;   // 这一帧刚被打死的，不该再挨第二座塔的火力
            }
            double reach = range + enemy.type.halfBodyCells();
            double dx = enemy.centreX() - centreX;
            double dy = enemy.centreY() - centreY;
            if (Math.hypot(dx, dy) > reach) {
                continue;
            }
            // 天上已经有足够的伤害冲着它去了：这一发留给后面那只。
            // 见 damageInFlightAt——加弹道之前没有这一问，因为那时伤害是即时的，
            // 不存在"打空"。
            if (enemy.hp - damageInFlightAt(enemy) <= 0f) {
                continue;
            }
            int progress = progressOf(enemy);
            if (best == null || progress < bestProgress) {
                best = enemy;
                bestProgress = progress;
            }
        }
        return best;
    }

    /**
     * 这一只离核心还剩多少路。<b>数越小越靠前</b>——"最靠前"就是挑最小的那个。
     *
     * <p>走的是 {@link #corePath}（只从核心铺出来的那张），不是 {@link #path}
     * （目标是"所有塔 + 核心"）。用后者的话，一只正在拆塔的敌人数值特别小、
     * 看着最靠前，塔就会集体去招呼它，而真正逼近核心的那只没人管。
     * "谁最危险"问的是核心，所以这张表得单独有一份。
     *
     * <p>正常走的是寻路表：{@link PathField#distanceAt} 就是从核心铺出来的价钱，
     * 所以它天然是"还剩多少"，而且会算上绕路。<b>不能拿 x 当进度</b>：敌人会拐弯，
     * 一只绕到上面去的可能 x 更小、其实离核心更近。
     *
     * <p>场上没核心时（{@code corePath == null}）退回"一路向右"，那时候进度就是
     * "离右边界还有多远"，所以是 {@code cols - x}。这里<b>两个分支的方向必须一致</b>
     * ——写成 {@code x} 的话就成了"挑最后面那只"，而这只在没核心的场面里才看得出来，
     * 有核心时永远走不到这一支。
     */
    private int progressOf(Enemy enemy) {
        if (corePath != null) {
            return corePath.distanceAt(enemy.cell());
        }
        return board.cols() - (int) enemy.x;
    }

    /**
     * 把场上所有敌人朝<b>目的地</b>推一帧；前面有东西挡住就停下来啃它。
     *
     * <p><b>目的地是"最近的建筑"，塔和核心一样算数</b>（{@link #isTarget}），
     * 由 {@link PathField} 铺出来的那张表说了算。所以"塔摆在敌人不走的路上"
     * 这句话已经不成立了：一座孤零零摆在角落的塔，也会把附近那几波敌人引过去拆。
     * 这就是"塔和核心优先级相同"落到画面上的样子。墙不在目的地之列。
     *
     * <p><b>啃谁：身体挨着的那几座里最近的一座，不分种类。</b>塔、核心、城墙
     * 一视同仁（{@link #nearestBite}）——"这座是塔所以先打它"这种偏好不存在，
     * 谁近咬谁。<b>唯一的例外是城墙</b>：一面墙只有在<b>寻路表真的指进它里面</b>
     * 的时候才会挨咬，也就是"绕不过去了"。旁边顺手能够着的墙不算数——
     * 否则重甲会放着挡在面前的塔不啃、转身去啃旁边那面没挡路的墙。
     *
     * <p>"绕不过去"这件事不用在这儿判断，{@link PathField} 已经判完了：
     * 墙那格的价钱（{@link BuildingStats#WALL_PATH_COST}）比整块战场所有格子加起来还大，
     * 所以表上指进墙里 ⟺ 没有更便宜的路。这一层只管"最近的那座是谁"。
     *
     * <p><b>"绕墙、拆塔"是两处配合出来的</b>：墙贵到绕多远都比拆近，所以敌人绕着
     * 墙走；塔是<b>目的地</b>，表上的箭头本来就指着它，走到跟前发现占着，就停下开啃。
     * 拆塔不需要塔额外"便宜"——它压根不参与价钱计算。
     *
     * <p>每一帧都要重新判定"下一步那一格"：挡路的东西被打掉之后，
     * 下一帧这里就查不到它了，敌人自然继续往前走。建筑被打掉还会让寻路表作废重铺，
     * 于是它当场就能找到新出现的那条路。
     *
     * <p>寻路表在 {@link #advance} 开头补好了，这里直接用。
     */
    private void moveEnemies(float dtSeconds) {
        Iterator<Enemy> it = enemies.iterator();
        while (it.hasNext()) {
            Enemy e = it.next();
            if (!e.alive()) {
                continue;   // 这一帧刚被打死的，不再动也不再啃
            }
            e.blocked = false;

            // 这一帧还剩多少路可走。写成 while 而不是"走一次"：掉帧时 dt 大，
            // 一帧可能跨过好几格，而每跨一格都要重新问一次"下一步往哪儿"。
            float left = e.type.speedCellsPerSec * dtSeconds;
            while (left > 0f) {
                Cell next = nextCell(e);
                if (next == null) {
                    break;      // 没有更好的下一步（被围死），站着
                }
                if (next.col >= board.cols()) {
                    leaks++;    // 从右边走出场，也是漏（场上没核心时才会走到这一步）
                    it.remove();
                    break;
                }
                Building blocker = occupancy.get(next);
                if (blocker == null || blocker.hp <= 0f) {
                    // 空着，或者这一帧刚被别的敌人啃穿（还没清场）——都当路通了
                    left = stepToward(e, next.col + 0.5f, next.row + 0.5f, left);
                    continue;
                }

                // 贴到它身上再啃。贴住的位置是"身体边缘顶到那一格的边上"，
                // 身体不进去——重甲那么大一只，画在墙里面看着就是穿模。
                float half = e.type.halfBodyCells();
                float targetX = e.x;
                float targetY = e.y;
                if (next.col > e.col()) {
                    targetX = next.col - half;          // 从左边顶住
                } else if (next.col < e.col()) {
                    targetX = next.col + 1f + half;     // 从右边顶住
                } else if (next.row < e.lane()) {
                    targetY = next.row + 1f + half;     // 从下边顶住
                } else {
                    targetY = next.row - half;          // 从上边顶住
                }
                // 贴住也是"走过去"，不是"改坐标"：敌人踏进这一格的时候身体边缘离墙
                // 还有小半格，直接改坐标会看到一跳。而且没贴住就不能啃——
                // 否则它隔着半格咬人，城墙白少挨半格的血。
                boolean snug = Math.hypot(targetX - e.x, targetY - e.y) <= left;
                stepToward(e, targetX, targetY, left);
                if (!snug) {
                    break;      // 这一帧就走完这半格，下一帧再啃
                }

                e.blocked = true;   // 啃核心也算"卡住了"，画面上都是停下来拆东西
                // 漏怪按"碰到核心"数，不按"正在啃核心"数：站在核心边上啃旁边的塔，
                // 也已经算是摸到核心了。见 nearestBite——咬谁和碰到谁是两件事。
                if (!e.reachedCore && touchesCore(e)) {
                    e.reachedCore = true;   // 只数一次，然后站在那儿一直啃
                    leaks++;
                }
                Building victim = nearestBite(e, blocker);
                if (victim != null && e.type.attacks()) {
                    victim.hp -= e.type.dps * dtSeconds;
                }
                break;
            }
        }
    }

    /**
     * 这一口咬谁：身体挨着的那几座里<b>最近的一座，不分种类</b>。
     *
     * <p><b>候选是哪些。</b>四邻里占着格子的建筑，再加上 {@code blocker}
     * （寻路表指着的那一座）。后者本来就在四邻之中，单独传进来是为了<b>平手时它优先</b>：
     * 距离一样近的时候先咬挡路的那座，局面才会往前走。不这么定的话，
     * 两座一样近的建筑会让敌人来回换着咬，谁也拆不掉。
     *
     * <p><b>墙不是候选——除非它就是 {@code blocker}。</b>顺手够得着的墙不咬：
     * "绕不过去"由 {@link PathField} 判完了（建筑那格的价钱比整块战场还大），
     * 表指进墙里就说明没有别的路，那面墙才是 {@code blocker}。
     * 少了这一条，重甲会放着挡在面前的塔不啃、转身去啃旁边那面没挡路的墙。
     *
     * <p>四邻按"右、左、下、上"固定顺序扫，所以"同样近的两座非墙建筑"也有确定的
     * 结果，不会一帧一个样。
     */
    private Building nearestBite(Enemy e, Building blocker) {
        Cell here = e.cell();

        // 从 blocker 起算而不是从 null 起算：平手时它赢，见上面那段
        Building best = blocker != null && blocker.hp > 0f ? blocker : null;
        double bestDistance = best == null ? Double.MAX_VALUE : biteDistance(e, best);

        for (int side = 0; side < 4; side++) {
            Building candidate = occupancy.get(neighbourOf(here, side));
            if (candidate == null || candidate.hp <= 0f) {
                continue;
            }
            if (candidate.type == BuildingType.WALL && candidate != blocker) {
                continue;   // 顺手够得着的墙不咬，只有挡路的才拆
            }
            double distance = biteDistance(e, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 敌人身体中心到这座建筑占地矩形<b>最近那一条边</b>的距离，单位格。
     *
     * <p>量的是"到矩形"而不是"到中心"：挨着咬这件事看的是边——一座 3×3 的核心
     * 和一座 2×2 的塔谁离敌人的身体更近，和它们的中心在哪儿没关系。
     * 身体中心已经在矩形里的时候返回 0。
     */
    private static double biteDistance(Enemy e, Building building) {
        double left = building.col();
        double right = building.col() + building.type.cols;
        double top = building.row();
        double bottom = building.row() + building.type.rows;

        // 每一维各自夹到 0：正对着矩形的时候那一维就是 0，只有错开的那一维才算距离
        double dx = Math.max(Math.max(left - e.x, 0.0), e.x - right);
        double dy = Math.max(Math.max(top - e.y, 0.0), e.y - bottom);
        return Math.hypot(dx, dy);
    }

    /**
     * 敌人身体现在挨没挨着核心。漏怪计数用它，见 {@link #moveEnemies}。
     *
     * <p>按"碰到"而不是"正在啃"：站在核心边上啃旁边的塔，也已经摸到核心了。
     */
    private boolean touchesCore(Enemy e) {
        Cell here = e.cell();
        for (int side = 0; side < 4; side++) {
            Building building = occupancy.get(neighbourOf(here, side));
            if (building != null && building.hp > 0f && building.type == BuildingType.CORE) {
                return true;
            }
        }
        return false;
    }

    /** {@code cell} 的第 {@code side} 个四邻：0 右、1 左、2 下、3 上。越界也照算。 */
    private static Cell neighbourOf(Cell cell, int side) {
        switch (side) {
            case 0:
                return new Cell(cell.col + 1, cell.row);
            case 1:
                return new Cell(cell.col - 1, cell.row);
            case 2:
                return new Cell(cell.col, cell.row + 1);
            default:
                return new Cell(cell.col, cell.row - 1);
        }
    }

    /**
     * 站在 {@code from} 上，下一步该踏进哪一格。
     *
     * <p>没有寻路表（场上没核心）时是"右边那一格"——加寻路之前的行为，
     * 保留它是为了那些不摆核心的局面（测试里有）还能照常走。
     *
     * <p>刚出场、还在 {@code x < 0} 的敌人也走这一支：它的格子不在表里，
     * 表查不到就不知道该往哪拐，先走进战场再说。
     */
    private Cell nextCell(Enemy e) {
        Cell here = e.cell();
        if (path != null && path.distanceAt(here) != PathField.UNREACHABLE) {
            return path.bestNeighbour(here);
        }
        return new Cell(here.col + 1, here.row);
    }

    /**
     * 朝一个连续坐标点走，最多走 {@code budget} 格。
     *
     * @return 还剩多少路没走完。走到了（或者已经在那儿了）返回剩下的，
     *         没走到返回 {@code 0}——调用方靠它决定还要不要再走一段。
     */
    private static float stepToward(Enemy e, float targetX, float targetY, float budget) {
        float dx = targetX - e.x;
        float dy = targetY - e.y;
        float distance = (float) Math.hypot(dx, dy);
        if (distance <= budget || distance < 1e-4f) {
            e.x = targetX;
            e.y = targetY;
            return Math.max(0f, budget - distance);
        }
        e.x += dx / distance * budget;
        e.y += dy / distance * budget;
        return 0f;
    }

    /**
     * 地形变过就重铺两张寻路表。见 {@link #pathDirty}。
     *
     * <p>两张表的目标不一样，所以分开铺：{@link #path} 管"去哪儿"（所有塔 + 核心），
     * {@link #corePath} 只管"谁最靠前"（只有核心）。任一为空都退回 {@code null}，
     * 敌人一路向右。
     */
    private void rebuildPathIfStale() {
        if (!pathDirty) {
            return;
        }
        pathDirty = false;

        // 去哪儿：塔和核心平等，谁近去谁。墙不是目标（它是"引导工具"，
        // 只在真的绕不过去时才被啃——见 nearestBite 和 BuildingStats.WALL_PATH_COST）。
        List<Cell> targets = new ArrayList<>();
        for (Building building : buildings) {
            if (isTarget(building)) {
                // cellsOf 给的是数组，PathField 收 Collection——包一层，不改几何那边的签名
                targets.addAll(Arrays.asList(board.cellsOf(building.col(), building.row(),
                        building.type.cols, building.type.rows)));
            }
        }
        path = targets.isEmpty()
                ? null
                : PathField.to(board.cols(), board.rows(), this::stepCost, targets);

        Building core = core();
        if (core == null) {
            corePath = null;
            return;
        }
        corePath = PathField.to(board.cols(), board.rows(), this::stepCost,
                Arrays.asList(board.cellsOf(core.col(), core.row(),
                        core.type.cols, core.type.rows)));
    }

    /**
     * 敌人会不会把这一座当成目的地。
     *
     * <p><b>塔和核心一视同仁</b>——这是"优先级相同"那句话落到代码里的样子：
     * 不因为谁的名字叫核心就优先奔它。所以一座塔只要离敌人比核心近，敌人就是
     * 冲着塔来的，会绕路过去拆。
     *
     * <p><b>墙不算目标</b>，这是保留下来的一条例外（{@link BuildingStats#WALL_PATH_COST}
     * 给了它一个天价）：墙是引导工具，敌人绕着走，只有真的绕不过去才啃。
     * 要是墙也进目标集合，砌墙就变成"求敌人来啃"，漏斗那套设计当场作废。
     *
     * <p>耐久归零但还没清算的那一座也算不上（{@code hp <= 0f}）：
     * 一帧里塔可以打掉它、敌人还想往那儿走，不该把它算成目的地。
     */
    private boolean isTarget(Building building) {
        return building.type != BuildingType.WALL && building.hp > 0f;
    }

    /**
     * 踩进这一格，寻路表上要花多少。
     *
     * <p>空地是 {@link PathField#OPEN_COST}，<b>墙是天价</b>
     * （{@link BuildingStats#WALL_PATH_COST}，比整块战场所有格子加起来还大，
     * 所以"绕多远都比拆近"）。塔和核心不用给价——它们是表的<b>目标</b>，
     * 那几格的价钱恒为 0（{@link PathField} 铺表时就把目标钉成 0 了）。
     *
     * <p>这里只看"是不是墙"，不看它是几级：等级影响的是挨打能力，
     * 不影响"值不值得绕"。等级要是也影响价钱，玩家升一级墙就会让敌人的走法
     * 变一下，那是很难解释的一种联动。
     */
    private int stepCost(Cell cell) {
        Building building = occupancy.get(cell);
        return building != null && building.type == BuildingType.WALL
                ? BuildingStats.WALL_PATH_COST
                : PathField.OPEN_COST;
    }

    /**
     * 清场：不喘气的敌人移走，没耐久的建筑拆掉，核心倒下就记一笔。
     *
     * <p>放在一帧的最后做，是为了让"这一帧"对两边都是完整的：塔打完了、
     * 敌人也啃完了，再一起结算谁死了。边打边删的话，正在遍历的列表会被改，
     * 而且"这一帧到底算谁活着"会变得说不清。
     *
     * <p>建筑倒下走 {@link #remove}，不自己清占位表：拆和被打掉是同一件事，
     * 两处各写一遍迟早有一处忘了还格子，然后那块地就永远放不下东西了。
     */
    private void clearTheDead() {
        enemies.removeIf(enemy -> !enemy.alive());

        // 先看一眼有没有倒下的，没有就直接走——这个方法每帧都调，
        // 不能为了"可能没有"每次都复制一份建筑列表
        boolean anyWrecked = false;
        for (Building building : buildings) {
            if (building.hp <= 0f) {
                anyWrecked = true;
                break;
            }
        }
        if (!anyWrecked) {
            return;
        }

        for (Building building : new ArrayList<>(buildings)) {
            if (building.hp > 0f) {
                continue;
            }
            if (building.type == BuildingType.CORE) {
                overrun = true;
            }
            remove(building);
        }
    }

    @Override
    public String toString() {
        return "Battlefield{" + buildings.size() + " 座建筑, "
                + enemies.size() + " 只敌人, " + waves + " 波, 漏 " + leaks + "}";
    }
}
