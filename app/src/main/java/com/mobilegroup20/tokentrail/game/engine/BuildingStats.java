package com.mobilegroup20.tokentrail.game.engine;

import com.mobilegroup20.tokentrail.contract.model.ResourceType;

/**
 * 建筑"第几级是什么样"：血量、攻击范围、伤害、升到下一级要多少钱。
 *
 * <p><b>为什么升级价在这儿，不在 {@link ShopCatalog}。</b>货架卖的是"从无到有"
 * （{@code Item.type} 是一件商品），而升级是"某座已有的建筑涨一级"——同一种塔，
 * 当前几级决定下一级多少钱，货架上没有这一行。两个都花资源，但一个是商品目录、
 * 一个是单体属性，混在一起以后加"墙升 2 级"就会互相打架。
 *
 * <p><b>三种建筑都有耐久，只有塔有火力。</b>墙是"挡路的"、核心是"要守住的"，
 * 两者都不攻击，所以 {@link #damagePerShot}／{@link #fireIntervalSeconds}／
 * {@link #rangeCells} 对它们一律返回 0，{@link #hasRange} 是给界面判断
 * "画不画那个圈"用的。但<b>耐久三种都有</b>（{@link #maxHp}），而且都跟着等级涨
 * ——墙升级涨的是"能多挨几秒"，核心升级涨的是"这一个月的上限有多高"。
 * 三种都能升级（{@link #upgradeCost}），因为三种都有"升级能换来什么"。
 *
 * <h2>表是按级排的，不是一串 if</h2>
 *
 * <p>每一种的耐久都是 {@code {一级, 二级, 三级}}，下标就是"级数 - 1"。写成表是因为
 * 加一级、调一档的时候要能一眼看出<b>整列</b>——射程 2.5/3.0/3.5 和 dps 14/20/28
 * 摆在一起才看得出"升级涨得比线性多一点"，散在几个 switch 里就看不出来了。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}：数值平衡要能在电脑上直接跑测试调，
 * 不用装模拟器。
 */
public final class BuildingStats {

    /** 最高几级。到了就不许再升——没有上限的话最后会变成"谁钱多谁赢"。 */
    public static final int MAX_LEVEL = 3;

    /** "没有攻击范围"的统一写法，给绘图那边判断用。 */
    private static final double NO_RANGE = 0.0;

    // ---- 塔：下标 = 级数 - 1 ----

    /** 打得到几格远，从占地中心算。 */
    private static final double[] TOWER_RANGE = {2.5, 3.0, 3.5};

    /**
     * 每一发打掉多少血。
     *
     * <p>和 {@link #TOWER_FIRE_INTERVAL} <b>两个数一起才是火力</b>：除一下就是每秒伤害
     * （14÷1.0 = 14、18÷0.9 = 20、21÷0.75 = 28，正好是加弹道之前那三个数）。
     * 拆成两个是因为塔现在打的是子弹（见 {@link Projectile}），能调的就不只是"多疼"、
     * 还有"多密"：一级塔一秒一发、三级塔 0.75 秒一发，后者手感明显更急，
     * 虽然每秒伤害只差一倍。
     *
     * <p>那三个每秒伤害是<b>对着 {@link EnemyType} 的血量定出来的</b>，不是随手写的。
     * 算术（一级塔 14 dps，一只敌人横穿射程大约要在圈里待 6.7 秒——2.5 格射程
     * 加上敌人自己半个身位，杂兵 0.9 格每秒）：
     *
     * <ul>
     *   <li>杂兵 45 血：需要 3.2 秒 → 打得掉，而且富余一倍；</li>
     *   <li>快兵 28 血：需要 2 秒，而它跑得快、在圈里只待 3.7 秒 → 刚好够；</li>
     *   <li>重甲 160 血：需要 11.4 秒，而在圈里只有 6.7 秒 → <b>打不死</b>，
     *       要么两座塔叠着打，要么升到 2 级。</li>
     * </ul>
     *
     * <p><b>实际打出来的伤害会比上面算的略低一点</b>，两个原因，都是有意的：
     * ① 子弹要飞一会儿才到（{@link #PROJECTILE_SPEED_CELLS_PER_SEC}），
     * "打了"和"掉血"之间隔着一个看得见的过程；② 目标可能在半路被别的塔打死，
     * 已经在天上的那几发就打空了——所以"好几座塔一起糊一只"不再是无损的最优解。
     * 这两条加起来大概吃掉几个百分点，不影响上面那三条结论。
     *
     * <p><b>改动其中一个就得回头看另一个</b>，所以 {@code BattlefieldTest} 里有测试
     * 专门盯着"一座 1 级塔打得死杂兵、打不死重甲"这句话。
     */
    private static final double[] TOWER_DAMAGE_PER_SHOT = {14.0, 18.0, 21.0};

    /**
     * 两发之间隔几秒。下标 = 级数 - 1，和 {@link #TOWER_DAMAGE_PER_SHOT} 一一对应。
     *
     * <p>三档都取"一秒上下"是有原因的：这个数同时是<b>画面上能看见的节奏</b>。
     * 一级塔一秒一发，玩家数得出"再来一发就死"；如果配成 0.15 秒一发，
     * 屏幕上会变成一条连续的虚线，那和加弹道之前那条照射的连线就没区别了。
     */
    private static final double[] TOWER_FIRE_INTERVAL = {1.0, 0.9, 0.75};

    /**
     * 子弹飞多快，格/秒。所有塔、所有等级一样。
     *
     * <p>9 格/秒是"看得见在飞、但不用等"的那一档：最远的射程（3.5 格）从出膛到命中
     * 大约 0.39 秒，二十几帧。再快就等于瞬移（又变回一条连线），
     * 再慢玩家会觉得"这座塔反应好迟钝"。
     *
     * <p><b>必须比敌人快得多</b>：最快的快兵 1.6 格/秒，子弹比它快五倍多，
     * 所以追得上。追不上就会出现"子弹吊在敌人屁股后面飞、伤害永远不落地"——
     * 那是 {@link Projectile} 这套"追着打"的模型唯一会崩掉的方式。
     */
    public static final float PROJECTILE_SPEED_CELLS_PER_SEC = 9.0f;

    /**
     * 塔的耐久。比墙薄——它不该同时又是输出又是沙包。
     *
     * <p><b>升级涨血涨得比墙少</b>（+30%/+23% 对墙的 +47%/+45%）：塔升级买的主要是
     * 火力和射程，血是捎带的。要是塔升级同时又变得很耐揍，"塔和墙"这对分工就糊了
     * ——玩家会发现铺一座高级塔比"塔 + 墙"划算，墙就没人砌了。
     */
    private static final float[] TOWER_HP = {100f, 130f, 160f};

    /**
     * 墙的耐久。下标 = 级数 - 1。
     *
     * <p>比塔厚（150 对 100）而且便宜得多（5 个 INPUT 对 25 CACHE + 10 OUTPUT），
     * 因为墙<b>只会站着挨打</b>：一面墙的全部价值就是替后面的塔多争取几秒。
     * 一只杂兵 12 dps 要啃 12.5 秒，够一座塔打死它两三回了——前提是那面墙
     * 在塔的火力范围里，不在的话墙就只是延迟一下。
     *
     * <p><b>墙升级是纯买时间</b>，所以涨幅给得大：一面墙升到 2 级多挨 5.8 秒、
     * 3 级再多挨 8.3 秒，而价格只是 INPUT（最富裕的那种资源，见 {@link ShopCatalog}）。
     * 这是"资源没处花"时最顺手的出口——也正因为如此，涨价涨得比涨血快
     * （5 → 6 → 12），不然铺满三级墙会变成唯一解。
     */
    private static final float[] WALL_HP = {150f, 220f, 320f};

    /**
     * 核心的耐久。下标 = 级数 - 1。
     *
     * <p>600 点是个"来得及反应"的数：一只杂兵 12 dps 要啃 50 秒，
     * 但五只一起啃就是 8 秒——所以漏了怪是有救的（塔会把站在核心跟前啃的
     * 那些打死，它们是停着不动的活靶子），漏了一群就直接结束。
     * <b>这个数决定"漏怪"这件事有多可怕</b>，调它之前先想清楚要哪种紧张感。
     *
     * <p><b>核心升级是和塔抢钱</b>（都吃 CACHE + OUTPUT，见 {@link #upgradeCost}）：
     * 加厚核心是"承认自己会漏"，再摆一座塔是"争取不漏"。这个取舍是故意的——
     * 如果核心升级不要 OUTPUT，玩家就没有理由不去点它。
     */
    private static final float[] CORE_HP = {600f, 900f, 1300f};

    /**
     * 墙在寻路表里算多贵的一格。
     *
     * <p><b>比整块战场所有格子加起来还大</b>（{@code 40×18 = 720}）：这样
     * "绕多远都比拆近"，墙于是成了<b>漏斗</b>——敌人绕着走，玩家砌墙是为了
     * 把敌人赶到塔底下，而不是为了挡死。只有真的封死、无路可绕时，表上的箭头
     * 才会指进墙里，敌人才动手拆。
     *
     * <p>如果哪天战场大到超过这个数，绕路就会变得比拆墙贵——这个数要跟着涨。
     *
     * <p><b>塔和核心不需要这个数</b>，因为它们是寻路表的<b>目标</b>
     * （{@code Battlefield.isTarget}）：目标那几格的价钱恒为 0，踩进去的价
     * 根本不参与计算。所以这里是"墙专属"的一个数，不再是一张按类型分的表
     * ——那版里给塔的那一档（曾经是 20、后来是 1）是<b>死代码</b>：
     * 塔进了目标集合之后，那个值没有任何一处读得到。
     *
     * <p>和 {@code nearestBite} 那条"墙是例外"是配套的，不是重复：那条管的是
     * "敌人停下来之后咬谁"，这条管的是"敌人会不会走到需要停下来的地方"。
     * 两个都要对上，才会出现"绕着墙走、冲着塔去"。
     */
    public static final int WALL_PATH_COST = 4096;

    private BuildingStats() {
    }

    /**
     * 这一级满血多少。<b>三种建筑都走这张表</b>（墙和核心以前是一个定值，
     * 现在也按级涨了——见 {@link #WALL_HP}／{@link #CORE_HP}）。
     *
     * <p>等级越界按最近的一级算，理由同 {@link #rangeCells}。
     */
    public static float maxHp(BuildingType type, int level) {
        switch (type) {
            case CORE:
                return CORE_HP[clamp(level) - 1];
            case WALL:
                return WALL_HP[clamp(level) - 1];
            default:
                return TOWER_HP[clamp(level) - 1];
        }
    }

    /**
     * 这一级每一发打掉多少血。<b>0 = 不会攻击</b>。
     *
     * <p>这是塔真正"配"的那个数：{@code Battlefield} 出膛时把它抄进
     * {@link Projectile#damage}，之后就算这座塔被拆了，天上的那几发也还是这个伤害。
     */
    public static double damagePerShot(BuildingType type, int level) {
        if (!hasRange(type)) {
            return 0.0;
        }
        return TOWER_DAMAGE_PER_SHOT[clamp(level) - 1];
    }

    /**
     * 这一级两发之间隔几秒。<b>0 = 不会攻击</b>。
     *
     * <p>和 {@link #damagePerShot} 一起被 {@code Battlefield} 用：
     * 装填计时器每帧减 {@code dt}，减到 0 以下就吐一发、再把间隔加回去。
     *
     * <p>等级越界按最近的一级算，理由见 {@link #rangeCells}。
     */
    public static double fireIntervalSeconds(BuildingType type, int level) {
        if (!hasRange(type)) {
            return 0.0;
        }
        return TOWER_FIRE_INTERVAL[clamp(level) - 1];
    }

    /**
     * 这一级打得到几格远（从建筑中心算）。
     *
     * <p>返回 {@code 0} 表示这玩意儿不会攻击。单位是<b>格</b>不是像素——
     * 格子大小随屏幕和缩放变，格数不变，绘图那边乘 {@code BoardGeometry.cellPx()}。
     *
     * <p><b>这个数是画面上那个圈的半径</b>，所以它和"打不打得到"必须对得上：
     * 判定时会把敌人自己半个身位加进来（"圈碰到就算"，
     * 见 {@code Battlefield.frontmostInRange}），于是不会出现
     * "敌人的半个身子在圈里却不挨打"。
     *
     * <p>等级越界（0、负数、超过 {@link #MAX_LEVEL}）按最近的一级算，不抛异常：
     * 这个方法在每一帧的绘制路径上，为一个不该出现的等级把整局搞崩不值得。
     */
    public static double rangeCells(BuildingType type, int level) {
        if (!hasRange(type)) {
            return NO_RANGE;
        }
        return TOWER_RANGE[clamp(level) - 1];
    }

    /** 这种建筑会不会攻击。决定详情面板里画不画那个范围圈。 */
    public static boolean hasRange(BuildingType type) {
        return type == BuildingType.TOWER;
    }

    /**
     * 这一级还能不能再升。
     *
     * <p>和 {@link #upgradeCost} 是同一件事的两种问法：这里只想知道"按钮该不该亮"，
     * 那里想知道"要多少钱"。判断只有一处，两者不会说岔。
     */
    public static boolean canUpgrade(BuildingType type, int level) {
        return upgradeCost(type, level) != null;
    }

    /**
     * 从这一级升到下一级要花多少；到顶了返回 {@code null}。
     *
     * <p><b>三种建筑都能升</b>，因为三种都有"升级换来什么"：塔换火力射程、
     * 墙换耐久、核心换耐久（见 {@link #maxHp}）。所以这个方法的 {@code null}
     * 现在只有一个意思——<b>到顶了</b>。界面上因此只需要一个"fully upgraded"
     * 的说法，不用再解释"这种建筑这个版本还不能升"。
     *
     * <p><b>返回 {@code null} 而不是 {@link Cost#FREE}</b>：免费是"能升，不要钱"，
     * 这里是"根本没有这一档"。混成一个值的话，满级建筑的升级按钮会亮着、
     * 点下去不要钱也不升级。
     *
     * <p><b>吃哪种资源是跟着建造价走的</b>（{@link ShopCatalog} 里那套分工）：
     * 塔吃 CACHE + OUTPUT，所以塔升级也吃这两样；墙吃 INPUT，所以墙升级只吃 INPUT。
     * 三种资源互不通兑，所以"这座建筑吃哪种"必须前后一致——不然会出现
     * "造得起、升不起"这种说不清的局面。
     *
     * <p>数值来路：新建一座塔是 CACHE 25 + OUTPUT 10。升级比新建便宜一点
     * （省下的是"地盘"），但第二级涨幅明显——不然铺满一级塔再慢慢升就成了唯一解。
     * 核心升级<b>故意和塔抢同一种资源</b>，理由见 {@link #CORE_HP}。
     * <b>这几个数都是待调的</b>，真机玩两局再改。
     */
    public static Cost upgradeCost(BuildingType type, int level) {
        if (level < 1 || level >= MAX_LEVEL) {
            return null;
        }
        boolean firstStep = level == 1;
        switch (type) {
            case WALL:
                // 只吃 INPUT：和建造价同一种资源，理由见上面那段
                return firstStep
                        ? Cost.of(ResourceType.INPUT, 6)
                        : Cost.of(ResourceType.INPUT, 12);
            case CORE:
                // 和塔抢 CACHE + OUTPUT，但比塔贵：加厚核心是"承认自己会漏"
                return firstStep
                        ? Cost.of(ResourceType.CACHE, 30).plus(ResourceType.OUTPUT, 12)
                        : Cost.of(ResourceType.CACHE, 50).plus(ResourceType.OUTPUT, 20);
            default:
                return firstStep
                        ? Cost.of(ResourceType.CACHE, 20).plus(ResourceType.OUTPUT, 8)
                        : Cost.of(ResourceType.CACHE, 35).plus(ResourceType.OUTPUT, 15);
        }
    }

    private static int clamp(int level) {
        if (level < 1) {
            return 1;
        }
        return Math.min(level, MAX_LEVEL);
    }
}
