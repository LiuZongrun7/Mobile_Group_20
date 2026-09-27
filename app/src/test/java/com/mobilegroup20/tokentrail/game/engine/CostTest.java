package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;

import org.junit.Test;

/**
 * {@link Cost} 和 {@link BuildingStats} 的测试。
 *
 * <p>盯两件事：
 *
 * <ol>
 *   <li><b>三种资源互不通兑</b>（{@code CONTRACTS.md} §8）：够不够要逐种看，
 *       缺一种就是不够。写成"加起来够就行"的话，只有 OUTPUT、没有 CACHE
 *       也能造塔——这正是不通兑最容易被破坏的地方；</li>
 *   <li><b>升级价随等级变</b>，而且到顶之后必须返回 {@code null} 而不是
 *       一个零元 {@code Cost}。这两种情况界面上长得像（都不要钱），
 *       混起来的话满级建筑的升级按钮会亮着、点下去既不掉钱也不升级；
 *   </li>
 *   <li><b>三种建筑都能升</b>，而且升级吃哪种资源和建造价一致——塔是
 *       CACHE + OUTPUT、墙是 INPUT。资源不通兑，"造得起、升不起"是一种
 *       说不清的局面，所以有一个测试专门盯着这条对应关系。
 *   </li>
 * </ol>
 */
public class CostTest {

    private static ResourceBalance balance(long input, long cache, long output) {
        return new ResourceBalance(input, cache, output);
    }

    /** 和 BattlefieldTest 同一个口径的典型战场（格子大小不影响这几条规则）。 */
    private static Battlefield battlefield() {
        return new Battlefield(BoardGeometry.fit(387f * 2.625f, 643f * 2.625f));
    }

    // ---- Cost 本身 ----

    @Test
    public void freeCostIsFreeAndChargesNothing() {
        ResourceBalance wallet = balance(10, 20, 30);
        assertTrue(Cost.FREE.free());
        assertTrue(Cost.FREE.affordable(wallet));

        Cost.FREE.charge(wallet);

        assertEquals(10, wallet.input);
        assertEquals(20, wallet.cache);
        assertEquals(30, wallet.output);
    }

    @Test
    public void plusIsSetNotAdd() {
        // 同一种资源设两次是改写：价格表是链式写出来的，写重了多半是笔误，
        // 叠加会把它悄悄藏起来（见 Cost#plus 的注释）。
        Cost cost = Cost.of(ResourceType.CACHE, 10).plus(ResourceType.CACHE, 3);
        assertEquals(3, cost.amount(ResourceType.CACHE));
    }

    /** 常量表是全项目共用的，扣一次钱不许改到表本身。 */
    @Test
    public void chargeDoesNotMutateTheCost() {
        Cost cost = Cost.of(ResourceType.CACHE, 25);
        cost.charge(balance(0, 100, 0));

        assertEquals(25, cost.amount(ResourceType.CACHE));
        assertFalse(cost.free());
    }

    @Test
    public void plusReturnsANewInstance() {
        Cost base = Cost.of(ResourceType.INPUT, 5);
        Cost extended = base.plus(ResourceType.OUTPUT, 2);

        assertNotSame(base, extended);
        assertEquals(0, base.amount(ResourceType.OUTPUT));   // 原价没被改
        assertEquals(5, extended.amount(ResourceType.INPUT)); // 原来那种还在
        assertEquals(2, extended.amount(ResourceType.OUTPUT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativePrices() {
        Cost.of(ResourceType.INPUT, -1);
    }

    // ---- 一次买好几座 ----

    /**
     * {@link Cost#times} 是"同一笔开销来 N 份"：一次建一排墙要问的是五份的价。
     *
     * <p>它和 {@code plus(ResourceType, long)} 那条"同名资源再设一次是改写"
     * 的规矩不冲突——这里压根没有两份不同的价要做主，只是乘。
     */
    @Test
    public void timesMultipliesEveryResourceItAsksFor() {
        Cost one = Cost.of(ResourceType.CACHE, 25).plus(ResourceType.OUTPUT, 10);

        Cost five = one.times(5);

        assertEquals(125, five.amount(ResourceType.CACHE));
        assertEquals(50, five.amount(ResourceType.OUTPUT));
        assertEquals("没用到的那种仍然是 0，不是被乘出来的 0", 0,
                five.amount(ResourceType.INPUT));
        assertEquals("原价不动：Cost 是不可变的", 25, one.amount(ResourceType.CACHE));
    }

    @Test
    public void timesOneIsTheSameCostAndTimesZeroIsFree() {
        Cost one = Cost.of(ResourceType.INPUT, 5);

        assertSame("一份就是它自己，不必新造一个", one, one.times(1));
        assertTrue(one.times(0).free());
        assertEquals("零份不花任何一种资源", 0, one.times(0).amount(ResourceType.INPUT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANegativeCount() {
        Cost.of(ResourceType.INPUT, 5).times(-1);
    }

    /**
     * {@link Cost#plus(Cost)} 是"两笔账合起来"，逐种相加。
     *
     * <p>名字和 {@code plus(ResourceType, long)} 很像，语义反着——那个是<b>改写</b>
     * （写重了当笔误），这个是<b>相加</b>（本来就该加起来）。整排升级时
     * 每一座的价各不相同，合总价靠的是这一条。
     */
    @Test
    public void plusAddsTwoCostsTogetherInsteadOfOverwriting() {
        Cost tower = Cost.of(ResourceType.CACHE, 25).plus(ResourceType.OUTPUT, 10);
        Cost wall = Cost.of(ResourceType.INPUT, 5).plus(ResourceType.CACHE, 25);

        Cost both = tower.plus(wall);

        assertEquals("同一种资源要相加，不是后一笔盖掉前一笔",
                50, both.amount(ResourceType.CACHE));
        assertEquals(5, both.amount(ResourceType.INPUT));
        assertEquals(10, both.amount(ResourceType.OUTPUT));
    }

    @Test
    public void plusWithFreeChangesNothing() {
        Cost cost = Cost.of(ResourceType.OUTPUT, 7);

        assertSame(cost, cost.plus(Cost.FREE));
        assertSame(cost, cost.plus(null));
        assertSame("空的一笔加有的一笔就是有的一笔", cost, Cost.FREE.plus(cost));
    }

    // ---- 逐种检查，不是看总数 ----

    @Test
    public void everyResourceMustBeCovered() {
        Cost cost = Cost.of(ResourceType.CACHE, 25).plus(ResourceType.OUTPUT, 10);

        // 总数（35）远超这一笔，但 CACHE 一分没有 —— 不通兑，就是不够
        assertFalse(cost.affordable(balance(1000, 0, 1000)));

        assertTrue(cost.affordable(balance(0, 25, 10)));   // 刚好够
        assertFalse(cost.affordable(balance(0, 24, 10)));  // 差一个也不行
        assertFalse(cost.affordable(balance(0, 25, 9)));
    }

    @Test
    public void chargeTakesExactlyTheAskingPrice() {
        Cost cost = Cost.of(ResourceType.CACHE, 25).plus(ResourceType.OUTPUT, 10);
        ResourceBalance wallet = balance(100, 100, 100);

        cost.charge(wallet);

        assertEquals(100, wallet.input);   // 没用到的那种，一分不动
        assertEquals(75, wallet.cache);
        assertEquals(90, wallet.output);
    }

    // ---- 范围 ----

    /** 只有两种塔会攻击。墙和核心问它们的射界一律是 {@code null}，界面靠这个决定画不画。 */
    @Test
    public void onlyTheTwoTowersHaveARange() {
        assertTrue(BuildingStats.hasRange(BuildingType.TOWER));
        assertTrue(BuildingStats.hasRange(BuildingType.BALLISTA));
        assertFalse(BuildingStats.hasRange(BuildingType.WALL));
        assertFalse(BuildingStats.hasRange(BuildingType.CORE));

        assertNull(BuildingStats.fireArc(BuildingType.WALL, 1));
        assertNull(BuildingStats.fireArc(BuildingType.CORE, 3));

        // 不攻击的还得把另外两个数也交代成 0，不然 Battlefield 会照着 0 秒一发去打
        assertEquals(0.0, BuildingStats.damagePerShot(BuildingType.WALL, 1), 0.0001);
        assertEquals(0.0, BuildingStats.fireIntervalSeconds(BuildingType.WALL, 1), 0.0001);
        assertEquals(0.0, BuildingStats.damagePerShot(BuildingType.CORE, 3), 0.0001);
        assertEquals(0.0, BuildingStats.fireIntervalSeconds(BuildingType.CORE, 3), 0.0001);
    }

    /**
     * <b>大炮的射界就是那四个数：内径 10、外径 20、半角 30°、溅射 1 格。</b>
     *
     * <p>这条测试是从一句需求直接抄下来的（"十格到二十格的圆环，正左上下各 30 度，
     * 六分之一的圆环，炮弹群伤半径一格"），所以它写死数字是<b>故意</b>的：
     * 这四个数一改，玩法就换了一种武器，那时候该有人回来看一眼这句需求，
     * 而不是让测试跟着自动变绿。别的测试都从 {@code BuildingStats} 里读，
     * 只有这一条负责钉住"到底是多少"。
     *
     * <p>半角那一项顺带验算"六分之一"：上下各 30° 就是 60° 的扇面，
     * 60 ÷ 360 = 1/6。写出来是为了让下一个人不用自己去推这个除法。
     *
     * <p><b>半角从 15° 改成 30° 是 2026-09-27 的事</b>，而且它是这四个数里唯一
     * 因为"手感"被改的那个（另外三个一次都没动过）：十五度的时候二十格外的
     * 半宽只有五格多，摆位要对着某一条走廊；三十度放到十一格半，
     * "摆在这一侧"就够了。改宽就是改"好不好摆"，所以这里必须有人盯着。
     */
    @Test
    public void theCannonFiresThroughATenToTwentyCellWedge() {
        BuildingStats.FireArc arc = BuildingStats.fireArc(BuildingType.TOWER, 1);

        assertEquals("内径十格：十格以内是盲区", 10.0, arc.innerCells, 1e-9);
        assertEquals("外径二十格", 20.0, arc.outerCells, 1e-9);
        assertEquals("正左上下各三十度", 30.0, arc.halfAngleDeg, 1e-9);
        assertEquals("整圈的六分之一", 1.0 / 6.0, 2.0 * arc.halfAngleDeg / 360.0, 1e-9);
        assertEquals("炮弹群伤半径一格", 1.0f, arc.splashCells, 1e-9f);
    }

    /**
     * <b>大炮是"少而重"，弩车是"密而轻"——两边的单发和间隔正好反着走。</b>
     *
     * <p>2026-09-27 大炮的单发从 14/18/21 提到 24/31/38、装填从 1.0/0.9/0.75
     * 放到 1.6/1.5/1.35，<b>这两个数是同一次改动、方向相反</b>，所以合起来
     * 每秒伤害几乎没变（14/20/28 → 15.0/20.7/28.1）。这条测试盯的就是那个"合起来"：
     * 只改一个的话，大炮要么变成双向加强（张角已经翻倍了），要么变成纯粹削弱。
     *
     * <p>写成区间而不是写死 15.0/20.7/28.1：DPS 是配平出来的结果，不是需求。
     * 三条边才是需求——<b>单发必须够重</b>（比从前重一半以上，不然"伤害高一些"
     * 这句话在屏幕上读不出来）、<b>节奏必须够慢</b>（比从前慢一半以上）、
     * <b>DPS 必须还是原来的量级</b>（张角那笔账已经付过了）。
     */
    @Test
    public void theCannonTradesRateForWeightWithoutGainingDps() {
        double[] oldDps = {14.0, 20.0, 28.0};
        for (int level = 1; level <= BuildingStats.MAX_LEVEL; level++) {
            double shot = BuildingStats.damagePerShot(BuildingType.TOWER, level);
            double interval = BuildingStats.fireIntervalSeconds(BuildingType.TOWER, level);
            double dps = shot / interval;
            String at = "第 " + level + " 级：";

            assertTrue(at + "单发该比从前重一半以上（24 对 14 那一档）",
                    shot >= 24.0);
            assertTrue(at + "装填该比从前慢一半以上（1.6 对 1.0 那一档）",
                    interval >= 1.35);
            assertEquals(at + "每秒伤害该还在原来那一档（差一成以内）",
                    oldDps[level - 1], dps, oldDps[level - 1] * 0.1);
        }
    }

    /**
     * <b>大炮的射界三级一模一样：升级不改射程。</b>
     *
     * <p>这是明确设定，不是"忘了给它排表"。和它配套的另外半句是"升级仍然买到
     * 火力和耐久"——所以这里顺手把"伤害确实涨了"也钉住，不然哪天有人把
     * {@code fireArc} 改成不看等级的同时，把伤害表也一起变成常量，
     * 大炮就会变成"升级只涨血"，而这条测试当时是绿的。
     */
    @Test
    public void theCannonKeepsTheSameArcAtEveryLevel() {
        BuildingStats.FireArc l1 = BuildingStats.fireArc(BuildingType.TOWER, 1);

        for (int level = 2; level <= BuildingStats.MAX_LEVEL; level++) {
            BuildingStats.FireArc other = BuildingStats.fireArc(BuildingType.TOWER, level);
            assertEquals("第 " + level + " 级的内径该和一级一样", l1.innerCells, other.innerCells, 1e-9);
            assertEquals("第 " + level + " 级的外径该和一级一样", l1.outerCells, other.outerCells, 1e-9);
            assertEquals("第 " + level + " 级的张角该和一级一样", l1.halfAngleDeg, other.halfAngleDeg, 1e-9);
            assertEquals("第 " + level + " 级的溅射该和一级一样", l1.splashCells, other.splashCells, 1e-9);

            assertTrue("升级还是要买到火力的，不然升级就只剩涨血了",
                    BuildingStats.damagePerShot(BuildingType.TOWER, level)
                            > BuildingStats.damagePerShot(BuildingType.TOWER, level - 1));
        }
    }

    /**
     * <b>射界那四条边是"碰到就算"，而且四条边都得管用。</b>
     *
     * <p>{@code FireArc} 是两种塔共用的那一个判定，{@code Battlefield} 挑目标
     * 走的也是它，所以边界上的算术只该有一处。上面那几条测的是"数是多少"，
     * 这条测的是<b>那些数真的被当边界用了</b>——四条边各卡一次，
     * 少写一条（比如忘了内径，或者 {@code <} 写成了 {@code <=} 的反面）
     * 都会在这里露出来，而不是等到摆到阵地上才发现敌人贴脸不掉血。
     *
     * <p>量的是<b>身子</b>不是圆心：半个身位也算"碰到"，
     * 所以杂兵在外径 20.5 格处就够得着（20.5 - 0.5 = 20），20.6 就够不着了。
     * 内径反过来——9.5 格处身子探进盲区，9.4 就整只缩在里面。
     *
     * <p>张角那条用 0 身位量，因为量的是纯粹的角度：十格外那条边的高度是
     * {@code 10 × tan30°}，站在线上算、多一丝不算。
     */
    @Test
    public void theFireArcCatchesExactlyWhatTouchesIt() {
        BuildingStats.FireArc cannon = BuildingStats.fireArc(BuildingType.TOWER, 1);
        double halfBody = EnemyType.GRUNT.halfBodyCells();

        assertTrue("外径边上：身子刚探进来", cannon.catches(-20.5, 0.0, halfBody));
        assertFalse("再远一格就够不着了", cannon.catches(-20.6, 0.0, halfBody));

        assertTrue("内径边上：身子刚缩进盲区", cannon.catches(-9.5, 0.0, halfBody));
        assertFalse("整只都在盲区里", cannon.catches(-9.4, 0.0, halfBody));

        double edgeY = 10.0 * Math.tan(Math.toRadians(30.0));
        assertTrue("正左偏三十度正好在线上", cannon.catches(-10.0, edgeY, 0.0));
        assertFalse("再偏一丝就出界", cannon.catches(-10.0, edgeY + 1e-6, 0.0));

        assertFalse("朝右的一律不算，哪怕距离一模一样",
                cannon.catches(10.0, 0.0, halfBody));
    }

    /** 弩车那一半没变：升级要真的换来更远，否则"升级"就只是个花钱的按钮。 */
    @Test
    public void ballistaRangeGrowsWithEveryLevel() {
        BuildingStats.FireArc l1 = BuildingStats.fireArc(BuildingType.BALLISTA, 1);
        BuildingStats.FireArc l2 = BuildingStats.fireArc(BuildingType.BALLISTA, 2);
        BuildingStats.FireArc l3 = BuildingStats.fireArc(BuildingType.BALLISTA, 3);

        assertTrue("二级该比一级远", l2.outerCells > l1.outerCells);
        assertTrue("三级该比二级远", l3.outerCells > l2.outerCells);
    }

    /**
     * <b>大炮和弩车是反着配的，这条盯的就是那个"反"。</b>
     *
     * <p>2026-09-27 大炮换成二十格扇环之后，"弩车射程更远"这句话反过来了，
     * 所以这条测试也跟着换了一份口径。现在它的分工是<b>远近</b>，不是高低：
     *
     * <ul>
     *   <li><b>大炮打得到二十格、但十格以内是哑的</b>；弩车只打四格，
     *       但那四格以内没有盲区。两者<b>谁也不能替谁</b>——这就是它们的全部区别，
     *       也是"两种塔"这件事还成立的理由；</li>
     *   <li><b>弩车单发更重</b>——不然"一发一个"这个手感不存在；</li>
     *   <li><b>但每秒伤害更低</b>——这一条最要紧。单发重、DPS 还高的话，
     *       弩车就成了全面更强的那一个（它还比大炮贵一倍，见
     *       {@code ShopCatalogTest}），贵的必须是"另一种取舍"。</li>
     * </ul>
     *
     * <p>三级一起比，不是只比一级：两张表要是哪天被改成在某一级交叉
     * （比如三级弩车的 DPS 反超），只比一级是看不出来的。
     */
    @Test
    public void ballistaTradesDamageRateForReach() {
        for (int level = 1; level <= BuildingStats.MAX_LEVEL; level++) {
            BuildingStats.FireArc cannon = BuildingStats.fireArc(BuildingType.TOWER, level);
            BuildingStats.FireArc ballista = BuildingStats.fireArc(BuildingType.BALLISTA, level);

            assertTrue("第 " + level + " 级：大炮该比弩车远得多", cannon.outerCells > ballista.outerCells);
            assertTrue("第 " + level + " 级：大炮该有盲区、弩车该没有",
                    cannon.innerCells > 0.0 && ballista.innerCells == 0.0);

            double towerShot = BuildingStats.damagePerShot(BuildingType.TOWER, level);
            double ballistaShot = BuildingStats.damagePerShot(BuildingType.BALLISTA, level);
            assertTrue("第 " + level + " 级：弩车该比大炮打得疼", ballistaShot > towerShot);

            double towerDps = towerShot / BuildingStats.fireIntervalSeconds(BuildingType.TOWER, level);
            double ballistaDps = ballistaShot
                    / BuildingStats.fireIntervalSeconds(BuildingType.BALLISTA, level);
            assertTrue("第 " + level + " 级：弩车的每秒伤害该比大炮低"
                            + "（不然弩车是全面更强，大炮就没用了）",
                    ballistaDps < towerDps);
        }
    }

    /**
     * <b>弩车的每一发都该"一发一个"。</b>
     *
     * <p>这是它和箭塔在手感上真正的区别，也是上面那条"DPS 更低"换来的东西：
     * 打得慢不要紧，要紧的是每一发都终结掉一只。所以拿 {@code EnemyType} 的血量
     * 当尺子量一遍——<b>改弩车伤害或改敌人血量，这条会立刻炸</b>，
     * 这正是它存在的意义（和箭塔那边"打得死杂兵、打不死重甲"一个路子）。
     */
    @Test
    public void everyBallistaShotTakesDownOneGrunt() {
        float runnerHp = EnemyType.RUNNER.maxHp;
        float gruntHp = EnemyType.GRUNT.maxHp;

        // 一级：一发带走快兵（快兵血最少，也跑得最快，最需要"发了就死"）
        assertTrue("一级弩车一发该带走快兵",
                BuildingStats.damagePerShot(BuildingType.BALLISTA, 1) >= runnerHp);
        // 二级起：一发带走杂兵。一级打不死是有意的——不然一级就全能了
        assertTrue("二级弩车一发该带走杂兵",
                BuildingStats.damagePerShot(BuildingType.BALLISTA, 2) >= gruntHp);
        assertTrue("三级更不该打不死",
                BuildingStats.damagePerShot(BuildingType.BALLISTA, 3) >= gruntHp);
        // 但重甲仍然要好几发：不然"重甲"这个兵种的意义就没了
        assertTrue("三级弩车也不该一发带走重甲",
                BuildingStats.damagePerShot(BuildingType.BALLISTA, 3) < EnemyType.BRUTE.maxHp);
    }

    /** 等级越界按最近的一级算，不抛异常：这个方法在每帧的绘制路径上。 */
    @Test
    public void outOfRangeLevelsClampInsteadOfThrowing() {
        // 用弩车量这一条，因为它的射界真的跟着等级变——大炮那一支压根不读等级，
        // 拿它量的话"夹住了"和"没夹住"结果一样，等于没验（那条在
        // theCannonKeepsTheSameArcAtEveryLevel 里单独验）。
        assertEquals(BuildingStats.fireArc(BuildingType.BALLISTA, 1).outerCells,
                BuildingStats.fireArc(BuildingType.BALLISTA, 0).outerCells, 0.0001);
        assertEquals(BuildingStats.fireArc(BuildingType.BALLISTA, 1).outerCells,
                BuildingStats.fireArc(BuildingType.BALLISTA, -5).outerCells, 0.0001);
        assertEquals(
                BuildingStats.fireArc(BuildingType.BALLISTA, BuildingStats.MAX_LEVEL).outerCells,
                BuildingStats.fireArc(BuildingType.BALLISTA, 99).outerCells, 0.0001);
    }

    // ---- 升级价 ----

    /**
     * <b>三种建筑都能升，而且都能一路升到顶。</b>
     *
     * <p>以前只有塔能升（墙和核心返回 {@code null}）。三种都能升，是因为三种
     * 都有"升级换来什么"：塔换火力和射程，墙和核心换耐久（见
     * {@link BuildingStats#maxHp}）。于是 {@code upgradeCost} 的 {@code null}
     * 现在只剩一个意思——<b>到顶了</b>。
     *
     * <p>逐级走一遍而不是只问第 1 级：中间断一档（比如只有 1→2 有价、2→3 没有）
     * 在界面上表现为"升到 2 级之后按钮变成 Max level"，那是查起来很费劲的一种错。
     */
    @Test
    public void everyBuildingTypeCanBeUpgradedAllTheWayToMaxLevel() {
        for (BuildingType type : BuildingType.values()) {
            for (int level = 1; level < BuildingStats.MAX_LEVEL; level++) {
                assertTrue(type.label + " 第 " + level + " 级该能升",
                        BuildingStats.canUpgrade(type, level));
                assertNotNull(type.label + " 第 " + level + " 级该有价",
                        BuildingStats.upgradeCost(type, level));
            }
            assertFalse(type.label + " 到顶了不该能升",
                    BuildingStats.canUpgrade(type, BuildingStats.MAX_LEVEL));
            assertNull(type.label + " 到顶了不该有价",
                    BuildingStats.upgradeCost(type, BuildingStats.MAX_LEVEL));
        }
    }

    /**
     * 升级吃哪种资源，和建造价保持一致。
     *
     * <p>三种资源互不通兑，所以"这一座吃哪种"必须前后是同一套，不然会出现
     * 玩家攒了一堆 INPUT、却发现用 INPUT 造的墙要拿 OUTPUT 来升级这种局面。
     * 价格多少是可以调的（那几个数本来就待调），<b>对应关系不能调</b>。
     */
    @Test
    public void upgradeCostsUseTheSameResourceAsBuildingOne() {
        Cost towerBuild = ShopCatalog.find(BuildingType.TOWER).price();
        Cost towerUpgrade = BuildingStats.upgradeCost(BuildingType.TOWER, 1);
        assertTrue("塔升级也该吃 CACHE", towerUpgrade.amount(ResourceType.CACHE) > 0);
        assertTrue("塔升级也该吃 OUTPUT", towerUpgrade.amount(ResourceType.OUTPUT) > 0);
        assertEquals("塔升级不该冒出新的一种资源",
                0, towerUpgrade.amount(ResourceType.INPUT));
        assertEquals(0, towerBuild.amount(ResourceType.INPUT));

        Cost wallUpgrade = BuildingStats.upgradeCost(BuildingType.WALL, 1);
        assertTrue("墙升级只吃 INPUT", wallUpgrade.amount(ResourceType.INPUT) > 0);
        assertEquals("墙升级不该吃 CACHE", 0, wallUpgrade.amount(ResourceType.CACHE));
        assertEquals("墙升级不该吃 OUTPUT", 0, wallUpgrade.amount(ResourceType.OUTPUT));

        // 三种资源里，墙是唯一大量消耗 INPUT 的地方（ShopCatalog 的类注释），
        // 所以这条同时也在盯着"INPUT 有没有去处"
        assertTrue(ShopCatalog.find(BuildingType.WALL).price().amount(ResourceType.INPUT) > 0);
    }

    /**
     * <b>升级换来的是耐久，一级比一级厚。</b>
     *
     * <p>墙和核心没有火力，耐久就是它们升级的全部意义——所以这张表必须是递增的。
     * 三种一起查：塔那条（火力）由 {@link #towerRangeGrowsWithEveryLevel} 盯着，
     * 这里盯的是血。
     */
    @Test
    public void everyLevelIsTougherThanTheOneBefore() {
        for (BuildingType type : BuildingType.values()) {
            for (int level = 1; level < BuildingStats.MAX_LEVEL; level++) {
                float here = BuildingStats.maxHp(type, level);
                float next = BuildingStats.maxHp(type, level + 1);
                assertTrue(type.label + " 第 " + (level + 1) + " 级该比第 " + level + " 级厚",
                        next > here);
            }
        }
    }

    @Test
    public void upgradeCostsMoreAtHigherLevels() {
        Cost first = BuildingStats.upgradeCost(BuildingType.TOWER, 1);
        Cost second = BuildingStats.upgradeCost(BuildingType.TOWER, 2);

        assertTrue(first.amount(ResourceType.CACHE) < second.amount(ResourceType.CACHE));
        assertTrue(first.amount(ResourceType.OUTPUT) < second.amount(ResourceType.OUTPUT));
    }

    /**
     * 到顶返回 {@code null}，不是零元。
     *
     * <p>这一条是这组测试里最要紧的：界面上"不能升"和"免费升"都必须区分开，
     * 混成一个值的话，到了 3 级的塔还会显示一个亮着的免费升级按钮。
     */
    @Test
    public void maxLevelHasNoPriceRatherThanAFreeOne() {
        assertNull(BuildingStats.upgradeCost(BuildingType.TOWER, BuildingStats.MAX_LEVEL));
        assertNull(BuildingStats.upgradeCost(BuildingType.TOWER, BuildingStats.MAX_LEVEL + 1));
        assertFalse(BuildingStats.canUpgrade(BuildingType.TOWER, BuildingStats.MAX_LEVEL));
    }

    /** 一路升到顶，钱花得掉、等级上得去，而且最后停在上限。 */
    @Test
    public void upgradingToMaxLevelCostsWhatTheTableSays() {
        Battlefield field = battlefield();
        Building tower = field.place(BuildingType.TOWER, field.firstBuildableCol(), 5);
        ResourceBalance wallet = balance(0, 1000, 1000);

        long spentCache = 0;
        long spentOutput = 0;
        while (true) {
            Cost cost = BuildingStats.upgradeCost(tower.type, tower.level);
            if (cost == null) {
                break;
            }
            assertTrue(cost.affordable(wallet));
            cost.charge(wallet);
            spentCache += cost.amount(ResourceType.CACHE);
            spentOutput += cost.amount(ResourceType.OUTPUT);
            assertTrue(field.upgrade(tower));
        }

        assertEquals(BuildingStats.MAX_LEVEL, tower.level);
        assertEquals(1000 - spentCache, wallet.cache);
        assertEquals(1000 - spentOutput, wallet.output);
        // 到顶之后引擎也不该再涨
        assertFalse(field.upgrade(tower));
        assertEquals(BuildingStats.MAX_LEVEL, tower.level);
    }

    // ---- 引擎那边 ----

    @Test
    public void upgradingAnUnknownBuildingDoesNothing() {
        Battlefield field = battlefield();
        Building stranger = new Building(BuildingType.TOWER, 3, 3);

        assertFalse(field.upgrade(stranger));
        assertEquals(1, stranger.level);   // 不在场上就不该动它
        assertFalse(field.upgrade(null));
    }

    /**
     * 墙和核心在引擎那边也真的升得动，而且<b>升完是满的</b>。
     *
     * <p>"升完回满血"对这两种比塔更要紧：它们的升级只换耐久，不回满的话
     * "升级"和"修好"就得分成两个动作来做，而这一版没有维修费那套东西
     * （见 {@code Building.repair}）。所以这里先打掉一点血再升，验的是
     * "升级之后按新等级满血"，不是"血没变"。
     */
    @Test
    public void wallsAndCoresUpgradeThroughTheEngineAndComeBackWhole() {
        Battlefield field = battlefield();
        Building wall = field.place(BuildingType.WALL, field.firstBuildableCol(), 5);
        Building core = field.place(BuildingType.CORE, 20, 8);

        wall.hp -= 60f;
        core.hp -= 200f;

        assertTrue(field.upgrade(wall));
        assertEquals(2, wall.level);
        assertEquals("按二级的满血回满", BuildingStats.maxHp(BuildingType.WALL, 2), wall.hp, 1e-3f);

        assertTrue(field.upgrade(core));
        assertEquals(2, core.level);
        assertEquals(BuildingStats.maxHp(BuildingType.CORE, 2), core.hp, 1e-3f);

        // 升级改的是场上的那一座本身，面板刷新靠这个
        assertSame(core, field.core());
    }

    /** 放在场上的是同一个对象，升级看得见（详情面板靠这个刷新）。 */
    @Test
    public void theUpgradedBuildingIsTheOneOnTheField() {
        Battlefield field = battlefield();
        int col = field.firstBuildableCol();
        Building tower = field.place(BuildingType.TOWER, col, 5);

        assertSame(tower, field.buildingAt(new Cell(col, 5)));
        assertTrue(field.upgrade(tower));
        assertEquals(2, field.buildingAt(new Cell(col, 5)).level);
        // 2×2 的塔，四格都指向同一座
        assertSame(tower, field.buildingAt(new Cell(col + 1, 6)));
    }
}
