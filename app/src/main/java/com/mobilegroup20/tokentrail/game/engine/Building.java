package com.mobilegroup20.tokentrail.game.engine;

import java.util.Locale;

/**
 * 已经放在战场上的一座建筑。
 *
 * <p>{@code col}/{@code row} 是它占地矩形的<b>左上角</b>，不是中心：
 * 2×2 的塔放在 (4,7) 表示它占 (4,7) (5,7) (4,8) (5,8) 四个格子。
 * 用左上角是因为它和 {@link BoardGeometry#fits(int, int, int, int)} 的参数一致，
 * 少一次换算就少一次搞错的机会。
 *
 * <p>渲染要用的是 {@link BoardGeometry#anchorX(int, int)} /
 * {@link BoardGeometry#anchorY(int, int)}（占地底边中点），不是左上角。
 */
public final class Building {

    public final BuildingType type;

    /** 占地矩形左上角的列。只有 {@link Battlefield#move} 能改，见 {@link #relocateTo}。 */
    private int col;

    /** 占地矩形左上角的行。只有 {@link Battlefield#move} 能改，见 {@link #relocateTo}。 */
    private int row;

    /** 等级。1 到 {@link BuildingStats#MAX_LEVEL}，塔可以升，见 {@code Battlefield.upgrade}。 */
    public int level = 1;

    /**
     * 还剩多少耐久。<b>可写</b>：{@link Battlefield#advance} 里被敌人啃。
     *
     * <p>满血在 {@link #maxHp()}（那是"这种建筑这一级有多少"），这里存的是
     * 被打过之后剩下的——和 {@link Enemy#hp} 是同一套分法。
     *
     * <p>构造时直接取满血，所以 {@code Battlefield.place} 不需要额外做什么。
     */
    public float hp;

    /**
     * 这一帧炮口对着谁；不是塔、或者射程里没人时是 {@code null}。
     *
     * <p><b>注意它和"这一帧开火了没有"不是一回事</b>：炮口每帧都对着目标
     * （挑目标的理由见 {@code Battlefield.frontmostInRange}），而子弹是每隔
     * {@link BuildingStats#fireIntervalSeconds} 才吐一发的。想知道"刚刚打出去的是谁"
     * 要看 {@code Battlefield.projectiles()}。
     *
     * <p><b>现在画面上没有东西读它</b>——子弹自己带着坐标（{@link Projectile}），
     * 不需要从塔这里问。留着它是因为两件事：下一版塔身/炮管要按它转向
     * （贴图接上之后），以及测试靠它表达"这座塔在瞄谁"（"打最靠前的那只"
     * "已经被子弹包住的那只跳过"这些规矩都靠它才验得了）。
     *
     * <p><b>别拿它当状态用。</b>每帧重算，塔"锁定"了某个敌人这件事不构成任何承诺：
     * 下一帧它可能就打别人了（目标死了、或者有个更靠前的进了射程）。
     * 当状态用会出现"盯着一个已经不在场上的敌人"这种问题。
     */
    public Enemy target;

    /**
     * 离下一发还有几秒。<b>{@code <= 0} 表示装填好了</b>，有目标就能立刻开火。
     *
     * <p>可写：{@link Battlefield#advance} 里每帧减一个 {@code dt}，
     * 开火时再把 {@link BuildingStats#fireIntervalSeconds} 加回去（加而不是赋值，
     * 见 {@code Battlefield.fireTowers}）。墙和核心永远用不着它，一直是 0。
     *
     * <p>新建时是 0：<b>刚放下的塔不该先等一个装填周期</b>。摆下去正好赶上敌人
     * 走到跟前，那一下要是不打，玩家会觉得这座塔坏了。
     */
    public float cooldownSeconds;

    public Building(BuildingType type, int col, int row) {
        this.type = type;
        this.col = col;
        this.row = row;
        this.hp = BuildingStats.maxHp(type, 1);
    }

    /**
     * 这一级满血多少。<b>跟着 {@link #level} 走</b>，所以升级之后自动变大。
     */
    public float maxHp() {
        return BuildingStats.maxHp(type, level);
    }

    /**
     * 还剩几成耐久，0–1。已经拆掉的返回 0，不会返回负数——血条按它画。
     */
    public float hpFraction() {
        float max = maxHp();
        if (max <= 0f) {
            return 0f;
        }
        float fraction = hp / max;
        if (fraction < 0f) {
            return 0f;
        }
        return Math.min(fraction, 1f);
    }

    /**
     * 被打过没有。<b>血条只在这之后才画</b>：满血的建筑不画条，
     * 屏幕上十八座建筑各挂一条绿杠会盖掉格子，而"没被打过"本来就是默认状态。
     */
    public boolean damaged() {
        return hp < maxHp();
    }

    /**
     * 修好，回满血。升级时调——见 {@link Battlefield#upgrade}。
     *
     * <p>为什么升级顺带修好：修是另一个动作的话，玩家得为"修"再造一套界面，
     * 而这一版没有维修费的概念。让升级把它捎上，等于"花这笔钱不只是变强，
     * 也是把这座修好"，升级这个动作就多了一层理由，不用加新按钮。
     */
    public void repair() {
        hp = maxHp();
    }

    /** 占地矩形左上角的列。 */
    public int col() {
        return col;
    }

    /** 占地矩形左上角的行。 */
    public int row() {
        return row;
    }

    /**
     * 挪到新位置。<b>包内可见是有意的：只有 {@link Battlefield#move} 该调它。</b>
     *
     * <p>坐标和占位表必须一起改——只改这里不改 {@code Battlefield.occupancy}，
     * 旧格子会一直占着（新地方放不下、老地方谁也放不了），而且不会报错，
     * 只是地图用着用着就不对了。所以改坐标这件事必须由持有占位表的那个类发起。
     *
     * <p>不"拆了重放"是刻意的：那样会新建一个 {@code Building}，
     * {@code level} 回 1，而且所有拿着旧引用的地方（详情面板、战场的高亮）
     * 都会变成指向一座已经不在场上的建筑。
     */
    void relocateTo(int col, int row) {
        this.col = col;
        this.row = row;
    }

    /** 这个格子是不是被本建筑占着。占位表就是靠它判断的。 */
    public boolean covers(Cell cell) {
        return cell.col >= col && cell.col < col + type.cols
                && cell.row >= row && cell.row < row + type.rows;
    }

    /** 占地矩形的中心格子，用来算距离、排序、或者显示血条。 */
    public Cell centre() {
        return new Cell(col + type.cols / 2, row + type.rows / 2);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s(%d,%d)%dx%d L%d %.0f/%.0f",
                type.label, col, row, type.cols, type.rows, level, hp, maxHp());
    }
}
