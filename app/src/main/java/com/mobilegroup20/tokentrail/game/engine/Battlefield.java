package com.mobilegroup20.tokentrail.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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
     * <p>通道也是"左边更长"这件事的落点：{@link BoardGeometry#COLS} 是 80 格，
     * 减掉这 8 格和右边的 {@link #MOUNTAIN_COLS} 格，可建区正好 66 格。
     *
     * <p>2026-09-27 跟着战场翻倍从 4 改成 8。通道的<b>作用</b>不变
     * （让玩家看清是哪条路再决定补哪儿），格数翻倍只是因为整张地图翻倍了
     * ——留 8 格和留 4 格在屏幕上是同样长的一段。
     */
    public static final int ENEMY_LANE_COLS = 8;

    /**
     * 最右边几列是 {@link Terrain#MOUNTAIN 山区}（也不能建东西）。
     *
     * <p>作用是给地图一个自然的右边界：核心要贴着右边摆，没有这一段的话
     * 核心就像是贴在画面边缘被裁掉了。它同时也是"以后要在这边加东西"的余量。
     *
     * <p>换成纯玩法的话说：这几格是 {@link #terrainAt} 里唯一一处"不能建、
     * 但敌人也不会从这儿进来"的地——它和通道是两回事，所以颜色也不一样。
     *
     * <p>2026-09-27 跟着战场翻倍从 3 改成 6，理由和 {@link #ENEMY_LANE_COLS} 一样：
     * 屏幕上是同样宽的一段石头。
     */
    public static final int MOUNTAIN_COLS = 6;

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
     * <p>代价是建筑一动要铺两遍。铺一遍是 2880 个格子的 Dijkstra，摆一座塔
     * 触发一次，可以忽略。（2026-09-27 战场从 40×18 翻倍到 80×36，铺表规模 ×4；
     * 仍然是"建筑动一下才铺"，不是每帧。）
     *
     * <p>{@code null} 表示场上没有核心。
     */
    private PathField corePath;

    /**
     * 上面那张表要不要重铺。
     *
     * <p><b>只在建筑动了的时候置位</b>，不是每帧。铺一次是 2880 个格子的 Dijkstra，
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

    /**
     * 现在允不允许动建筑——<b>放新的、挪、升级，三件事一起管</b>。
     *
     * <p><b>场上有敌人就不许。</b>打起来之后还能改阵型的话，有几件事说不清楚：
     * <ul>
     *   <li>开打之后把塔从这条路口挪到那条路口，等于中途换掉自己刚才的摆位，
     *       "这一波打得怎么样"就没法归因了——赢也好输也好，都能用"我还没摆完"
     *       解释掉；</li>
     *   <li>拖动中的那座建筑<b>在引擎里一直待在原地</b>（画面上那个位移只是画的，
     *       见 {@code BattlefieldView.carryDCol}）——它一边挨打一边被拎在半空，
     *       松手落地的那一下该按哪边算？与其定一条"拖起来免疫"的规矩，
     *       不如不让它在打的时候被拎起来；</li>
     *   <li>升级最直接：花资源把一座塔当场升一级，<b>耐久回满、射界变大</b>，
     *       这一波立刻变成另一场仗。参考部落冲突，改阵型永远是开打<b>之前</b>的事。</li>
     * </ul>
     *
     * <p><b>判的是"场上有敌人"，不是"这一局还没结束"。</b>两波之间的空当（以及
     * 开局那段）本来就是留给玩家摆位和升级的，整局锁死的话这个游戏就只剩看。
     * 所以看 {@code enemies} 空不空：一波清完到下一波进场之间，随时可以改。
     *
     * <p><b>这只是一句问话，不是保险。</b>{@link #place} / {@link #moveAll} /
     * {@link #upgrade} 自己都不查它。那几个方法同时是<b>摆场景的 API</b>——
     * {@code MainActivity.buildDemoScene} 和大量测试都拿它们铺初始阵型，
     * 而铺的时候场上未必干净（测试里经常先放建筑再 {@code spawn} 敌人来试射界）。
     * 在那几个方法里加一道"有敌人就拒绝"，等于把"摆场景"和"玩家操作"混成一条路，
     * 一多半现成的用例会当场变红。所以这条规矩由<b>界面来问、界面来挡</b>，
     * 和 {@link #canPlace}、"买不买得起"是同一层的事。
     */
    public boolean canReworkBuildings() {
        return enemies.isEmpty();
    }

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

    // ---- 整排 ----

    /**
     * 一座建筑<b>所在的那一整排</b>：跟它同类型、而且首尾相接的那一串。
     *
     * <p>界面上"点两下选中整排"要的就是这个：一列墙、一列塔、一行塔。
     * 选出来的一排可以一起挪、一起升级（{@link #moveAll}）。
     *
     * <p><b>竖排优先。</b>两条都成立时（比如一个 2×2 的方块）取竖的那条：
     * 战场上纵深比宽度金贵，"这一列"是玩家布防时脑子里真正的那条线。
     *
     * <p><b>"相接"是按占地算的</b>，不是按左上角差一格。2×2 的塔占
     * (10,4)-(11,5)，那么第 6 行那座（占 6-7 行）就是挨着的，第 8 行那座也是——
     * 这符合看到的样子。反过来，中间空了一格就不算一排：断开的两个塔群
     * 合成一排，一起挪的时候会拉成一条，谁也没想那样。
     *
     * <p><b>不比等级。</b>一列墙里有一座升过级了，它还是那一列。玩家眼里的
     * "一列墙"是摆在那儿的一串，不是"一串一样强的"。一起升级时到顶的会跳过。
     *
     * @return 至少含 {@code building} 自己；{@code building} 不在场上（拆过了、
     *         或者是别的局留下的）时返回空表。调用方不该改这个表。
     */
    public List<Building> lineOf(Building building) {
        if (building == null || !buildings.contains(building)) {
            return Collections.emptyList();
        }
        List<Building> column = runThrough(building, 0, -1, 0, 1);
        if (column.size() > 1) {
            return Collections.unmodifiableList(column);
        }
        List<Building> row = runThrough(building, -1, 0, 1, 0);
        if (row.size() > 1) {
            return Collections.unmodifiableList(row);
        }
        return Collections.singletonList(building);
    }

    /**
     * 从 {@code origin} 出发，沿一条轴两头都走到头，返回整条线上的建筑。
     *
     * @param backCol 朝坐标小的那头找时，每走一座跨几列（上/左方向）
     * @param backRow 同上，行方向
     * @param fwdCol  朝坐标大的那头找时，每走一座跨几列（下/右方向）
     * @param fwdRow  同上
     */
    private List<Building> runThrough(Building origin, int backCol, int backRow,
                                      int fwdCol, int fwdRow) {
        List<Building> line = new ArrayList<>();
        Deque<Building> head = new ArrayDeque<>();
        for (Building cursor = origin; ; ) {
            Building next = neighbourAlong(cursor, backCol, backRow);
            if (next == null) {
                break;
            }
            head.addFirst(next);
            cursor = next;
        }
        line.addAll(head);
        line.add(origin);
        for (Building cursor = origin; ; ) {
            Building next = neighbourAlong(cursor, fwdCol, fwdRow);
            if (next == null) {
                break;
            }
            line.add(next);
            cursor = next;
        }
        return line;
    }

    /**
     * {@code building} 在那条轴上紧挨着的下一座同类型建筑，没有就是 {@code null}。
     *
     * <p>跨的步子是一个<b>占地</b>（{@code cols}/{@code rows}），不是一格：
     * 2×2 的塔往上找是 {@code row - 2}，1×1 的墙是 {@code row - 1}。
     */
    private Building neighbourAlong(Building building, int dCol, int dRow) {
        int col = building.col() + dCol * building.type.cols;
        int row = building.row() + dRow * building.type.rows;
        if (!board.fits(col, row, building.type.cols, building.type.rows)) {
            return null;
        }
        Building other = occupancy.get(new Cell(col, row));
        if (other == null || other.type != building.type) {
            return null;
        }
        // 占着这一格还不够，左上角得正好落在这儿。1×1 的墙查自己那格必然成立；
        // 挡的是"一座更大的建筑从旁边盖过来"——它的左上角在别处，
        // 按它的占地往回退才是它的上/左邻居，不是按这一格。
        return other.col() == col && other.row() == row ? other : null;
    }

    /**
     * {@link #moveAll} 的<b>试算</b>：这一排挪过去成不成立，一个字都不改。
     *
     * <p>分开是因为拖动时的虚影要在手指还没抬起来的时候就知道该画绿还是画红
     * （同 {@code BuildGate} 的"先问价、后成交"）。挪动不花钱，所以这里
     * 没有价格那一问，只有"地够不够"。
     *
     * @return 整排都挪得过去返回 {@code true}；空表或 {@code null} 返回
     *         {@code false}（"没东西可挪"不是成功）
     */
    public boolean canMoveAll(List<Building> group, int dCol, int dRow) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        for (Building building : group) {
            if (!buildings.contains(building)) {
                return false;
            }
            if (dCol == 0 && dRow == 0) {
                continue;   // 原地：位置不用查，但"它在不在场上"上面查过了
            }
            int col = building.col() + dCol;
            int row = building.row() + dRow;
            if (!board.fits(col, row, building.type.cols, building.type.rows)) {
                return false;
            }
            for (Cell cell : board.cellsOf(col, row, building.type.cols, building.type.rows)) {
                if (!terrainAt(cell.col, cell.row).buildable()) {
                    return false;
                }
                Building occupant = occupancy.get(cell);
                if (occupant != null && !group.contains(occupant)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 把一整排建筑<b>整体挪一个位移</b>。
     *
     * <p>和 {@link #move} 一样不花钱，区别只有一个：要么整排都挪过去，要么整排都不动。
     * 没有"挪过去三座、剩下两座卡住了"这种中间状态——那会让玩家自己都记不清
     * 哪几座动了。
     *
     * <p><b>判定时必须把整排自己都当成空地。</b>一排墙往右挪一格，新格子有一半
     * 正是这一排里邻座的旧格子；不把它们让开的话"整体平移"这个最常见的用法
     * 会当场判失败。所以判定循环里问的是"占着这格的人是不是自己人"。
     *
     * <p>走法和 {@link #move} 同一个顺序，只是整排一起：先整排 {@code vacate}、
     * 再整排改坐标、最后整排 {@code occupy}。顺序反过来（挪一座占一座）在
     * 平移时会两两互卡。
     *
     * <p>判定那一段在 {@link #canMoveAll} 里，这一层只管改。分出来是给
     * 拖动的虚影用的：手指还按着的时候就得知道该画绿还是画红。
     *
     * @param group 要挪的那一排，通常是 {@link #lineOf} 的结果
     * @param dCol  整体横移几列，可以是负数
     * @param dRow  整体纵移几行，可以是负数
     * @return 挪成功了返回 {@code true}；整排里只要有<b>任何一座</b>挪不过去
     *         （不在场上、出界、压到通道或山区、压到排外的建筑）就返回
     *         {@code false}，且一座都不动。空的 {@code group} 返回 {@code false}。
     */
    public boolean moveAll(List<Building> group, int dCol, int dRow) {
        if (!canMoveAll(group, dCol, dRow)) {
            return false;
        }
        if (dCol == 0 && dRow == 0) {
            return true;   // 原地：什么也不用做，但这是成功，不是失败
        }

        // 顺序同 move：先全让开、再全改坐标、最后全占上
        for (Building building : group) {
            vacate(building);
        }
        for (Building building : group) {
            building.relocateTo(building.col() + dCol, building.row() + dRow);
        }
        for (Building building : group) {
            occupy(building);
        }
        return true;
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
     * 不然一只敌人被挡在射界外等了三秒，塔一进射界就补上三发，看着像卡了一下。
     *
     * <p>{@code while} 而不是 {@code if}：dt 大到跨过一整个间隔时（测试里会
     * {@code advance(5f)}），该补的发数要全补上，射速才和帧率无关。上限见
     * {@link #MAX_SHOTS_PER_FRAME}。
     */
    private void fireTowers(float dtSeconds) {
        for (Building tower : buildings) {
            // "会不会开火"问的是射界在不在——取一次就够，出膛时还要用它的溅射半径。
            // 从前的守卫是 {@code fireIntervalSeconds(...) <= 0}，和这里是同一件事的
            // 两种问法；改成射界之后这一支更直接，而且顺带把 arc 拿到手，
            // 不用在下面再查一遍表。
            BuildingStats.FireArc arc = BuildingStats.fireArc(tower.type, tower.level);
            if (arc == null) {
                continue;   // 墙和核心：压根没有"开火"这回事
            }
            double interval = BuildingStats.fireIntervalSeconds(tower.type, tower.level);

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
                        // 出膛点是**占地中心**：射界的圆心、判定用的圆心、
                        // 从前那条连线的起点，三处现在还是同一个点
                        tower.col() + tower.type.cols / 2f,
                        tower.row() + tower.type.rows / 2f,
                        (float) BuildingStats.damagePerShot(tower.type, tower.level),
                        BuildingStats.PROJECTILE_SPEED_CELLS_PER_SEC,
                        // 溅射半径出膛时就定死，和伤害一样：这门炮半路被拆了，
                        // 天上那一发落下来还是炸一格。
                        arc.splashCells));
                tower.cooldownSeconds += (float) interval;
            }
        }
    }

    /**
     * 天上那些子弹飞一帧，飞到了就结算。
     *
     * <p><b>没有溅射的（弩车）打中的是"出膛时瞄的那一只"，不是"落点上现在站着谁"。</b>
     * 目标半路死了（或者已经被清场移走）这一发就白飞了——不减别人的血，
     * 也不复活它。留这个规矩是因为它才是塔防里那个直觉："这根弩箭是冲它去的"。
     *
     * <p><b>有溅射的（大炮）反过来：结算的是落点。</b>见 {@link #splash}。
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
            if (shot.splashCells > 0f) {
                splash(shot);
            } else if (shot.lands()) {
                shot.target.hp -= shot.damage;
            }
        }
    }

    /**
     * 一发炮弹落地：<b>落点周围一格之内活着的都吃这一发的伤害</b>，一个不少。
     *
     * <p>和上面那支的单体结算有一处关键差别：<b>它不看"出膛时瞄的那只还活着没有"。</b>
     * 炮弹已经在天上了，落下来就是一响——出膛时瞄的那只半路被别的塔打死了，
     * 弹坑还在，站在旁边的照样挨。这正是"群伤"这个词的全部意思，
     * 也是这门炮和弩车的分工：弩车是"点掉一只"，大炮是"这一片都别站着"。
     *
     * <p>所以打死的那只<b>不用特判</b>：它 {@code alive()} 是假，自然不在结算之列；
     * 而它旁边那些还喘气的，一个都不会因为"目标没了"而逃过一劫。
     *
     * <p><b>判定"碰到就算"</b>（{@code distance <= 溅射半径 + 半身位}），和射界
     * 那两条边一个规矩：身子探进弹坑里就吃伤害。重甲比杂兵宽，所以它在弹坑边上
     * 更容易被扫到——和"厚就要挨更多打"是同一套账，见
     * {@link BuildingStats.FireArc#catches}。
     *
     * <p>遍历整支 {@code enemies} 是刻意的：一发炮弹最多几十次浮点比较，
     * 而大炮一秒才一发。为它建个空间索引不值得，那种表还会随着敌人移动失效。
     *
     * <p>这里直接改 {@code hp}、不调 {@code clearTheDead()}：清场在
     * {@link #advance} 的最后一步统一做，这里清会让"这一帧刚死的"从列表里消失，
     * 后面 {@code moveEnemies} 的迭代器就崩了。
     */
    private void splash(Projectile shot) {
        for (Enemy enemy : enemies) {
            if (!enemy.alive()) {
                continue;
            }
            double dx = enemy.centreX() - shot.x;
            double dy = enemy.centreY() - shot.y;
            if (Math.hypot(dx, dy) <= shot.splashCells + enemy.type.halfBodyCells()) {
                enemy.hp -= shot.damage;
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
     * <p>复杂度是"塔 × 射界里的敌人 × 天上的子弹"，一帧几十×几十×几十次浮点加法；
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
     * 射界里<b>最靠前</b>的那只敌人，没有就 {@code null}。
     *
     * <p>距离是<b>中心到中心</b>，用格子坐标算：建筑取占地矩形的中心，
     * 敌人取身体的中心（{@link Enemy#centreX()}）。两个中心都在"连续格子坐标"
     * 这一套里，和界面上画的那个扇环用的是同一个圆心——画在哪儿就打到哪儿，
     * 不会出现"看着在射界里却没挨打"。
     *
     * <p><b>判定的全部内容就是一句 {@link BuildingStats.FireArc#catches}。</b>
     * 内径、外径、张角、身位这四件事的相互关系（尤其是"盲区那条边也要把身位算进去"）
     * 只写在那一处，见 {@link BuildingStats.FireArc}。从前这里是散着的三句
     * ——"算距离、比射程、比斜率"——大炮加上十格内径之后，那种写法一定会漏掉
     * 其中一个（漏了内径它就变成二十格的正圆，漏了张角它就变成整圈），
     * 而两种漏法都不报错。
     *
     * <p><b>"碰到就算"这个规矩是为了紧贴城墙的那只。</b>敌人的判定点是它的
     * <b>中心</b>，只按中心算的话，半个身子探进射界里的敌人反而不挨打。真机上就是
     * 摆在墙后面的那座弩车：敌人贴在墙左边，中心到中心正好比它 3.0 格的射程多一点点
     * （墙宽 1 格 + 敌人半个身位 + 塔自己的半个占地，再加上纵向错开半格），
     * 差 0.05 格打不着。而城墙的全部价值就是"替后面的塔多争取几秒"
     * （见 {@link BuildingStats#WALL_HP}）——塔不打贴在墙上的那只，墙就白砌了。
     * 加上这半个身位之后，墙后紧贴的那座打得着，而且画面上"射界碰到了敌人"和
     * "敌人在挨打"这两件事重新对得上。
     *
     * <p><b>大炮不参与这件事，而且不是漏了。</b>它十格以内是盲区
     * （{@link BuildingStats#CANNON_INNER_CELLS}），贴在墙上的敌人正好落在盲区里——
     * 拿大炮守墙是摆错了，该摆的是弩车。所以上面那段"墙后的塔打得着"说的是弩车。
     *
     * <p>身位是<b>逐只</b>算的，不是全局一个常数：重甲比杂兵宽，所以它能从
     * 更远处就开始挨打。这正好和它"厚"这件事配套——不然画面上一只大块头
     * 站在射界外挨打，或者小个子快兵冲进射界里还不挨打。
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
     * <p>"就近"在画面上的好处是说得通（射界只有几格，塔不会越过贴脸的那只去打
     * 斜对面的），但代价是塔<b>不再优先拦截最危险的那只</b>：火力被近处、其实
     * 还离核心很远的敌人分走，该拦的那只就多走了几步。塔防里这几步就是漏怪。
     * <b>大炮让这一条更值钱</b>：它一条走廊罩十格深，挑错了目标就白打一整轮。
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
        BuildingStats.FireArc arc = BuildingStats.fireArc(tower.type, tower.level);
        if (arc == null) {
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
            double dx = enemy.centreX() - centreX;
            double dy = enemy.centreY() - centreY;
            // **射界的判定整个在 BuildingStats.FireArc 里**——内径、外径、
            // 张角、身位四件事的相互关系只该有一份，理由见那个类的注释。
            // 这里从前是"算距离、比射程、再比一次斜率"三句，现在一句。
            if (!arc.catches(dx, dy, enemy.type.halfBodyCells())) {
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
     * <p><b>这是全项目唯一一处改敌人坐标的地方</b>，所以"走了多远"也在这儿记
     * （{@link Enemy#travelled}）：渲染靠它翻走路的帧，记在别处就会漏掉
     * "竖着换行"那几格——那几格 {@code x} 是不动的。
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
            e.travelled += distance;
            return Math.max(0f, budget - distance);
        }
        e.x += dx / distance * budget;
        e.y += dy / distance * budget;
        e.travelled += budget;
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
