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

    /** 只有两种塔会攻击。墙和核心问它们的射程一律是 0，界面靠这个决定画不画范围圈。 */
    @Test
    public void onlyTheTwoTowersHaveARange() {
        assertTrue(BuildingStats.hasRange(BuildingType.TOWER));
        assertTrue(BuildingStats.hasRange(BuildingType.BALLISTA));
        assertFalse(BuildingStats.hasRange(BuildingType.WALL));
        assertFalse(BuildingStats.hasRange(BuildingType.CORE));

        assertEquals(0.0, BuildingStats.rangeCells(BuildingType.WALL, 1), 0.0001);
        assertEquals(0.0, BuildingStats.rangeCells(BuildingType.CORE, 3), 0.0001);

        // 不攻击的还得把另外两个数也交代成 0，不然 Battlefield 会照着 0 秒一发去打
        assertEquals(0.0, BuildingStats.damagePerShot(BuildingType.WALL, 1), 0.0001);
        assertEquals(0.0, BuildingStats.fireIntervalSeconds(BuildingType.WALL, 1), 0.0001);
        assertEquals(0.0, BuildingStats.damagePerShot(BuildingType.CORE, 3), 0.0001);
        assertEquals(0.0, BuildingStats.fireIntervalSeconds(BuildingType.CORE, 3), 0.0001);
    }

    /** 升级要真的换来点什么，否则"升级"就只是个花钱的按钮。 */
    @Test
    public void towerRangeGrowsWithEveryLevel() {
        for (BuildingType tower : new BuildingType[]{BuildingType.TOWER, BuildingType.BALLISTA}) {
            double l1 = BuildingStats.rangeCells(tower, 1);
            double l2 = BuildingStats.rangeCells(tower, 2);
            double l3 = BuildingStats.rangeCells(tower, 3);

            assertTrue(tower.label + " 二级该比一级远", l2 > l1);
            assertTrue(tower.label + " 三级该比二级远", l3 > l2);
        }
    }

    /**
     * <b>弩车和箭塔是反着配的，这条盯的就是那个"反"。</b>
     *
     * <p>三种关系，缺一条弩车就不再是另一种打法了：
     *
     * <ul>
     *   <li><b>射程更远</b>——不然它没有任何理由摆在箭塔后面；</li>
     *   <li><b>单发更重</b>——不然"一发一个"这个手感不存在；</li>
     *   <li><b>但每秒伤害更低</b>——这一条最要紧。射程远、单发重、DPS 还高的话，
     *       箭塔就没有存在的理由了，商店里那一行会变成"买了弩车的人永远赢"。
     *       弩车贵一倍（见 {@code ShopCatalogTest}），贵的必须是"另一种取舍"，
     *       不能是"全面更强"。</li>
     * </ul>
     *
     * <p>三级一起比，不是只比一级：两张表要是哪天被改成在某一级交叉
     * （比如三级弩车的 DPS 反超），只比一级是看不出来的。
     */
    @Test
    public void ballistaTradesDamageRateForReach() {
        for (int level = 1; level <= BuildingStats.MAX_LEVEL; level++) {
            double towerRange = BuildingStats.rangeCells(BuildingType.TOWER, level);
            double ballistaRange = BuildingStats.rangeCells(BuildingType.BALLISTA, level);
            assertTrue("第 " + level + " 级：弩车该比箭塔远", ballistaRange > towerRange);

            double towerShot = BuildingStats.damagePerShot(BuildingType.TOWER, level);
            double ballistaShot = BuildingStats.damagePerShot(BuildingType.BALLISTA, level);
            assertTrue("第 " + level + " 级：弩车该比箭塔打得疼", ballistaShot > towerShot);

            double towerDps = towerShot / BuildingStats.fireIntervalSeconds(BuildingType.TOWER, level);
            double ballistaDps = ballistaShot
                    / BuildingStats.fireIntervalSeconds(BuildingType.BALLISTA, level);
            assertTrue("第 " + level + " 级：弩车的每秒伤害该比箭塔低"
                            + "（不然弩车是全面更强，箭塔就没用了）",
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
        assertEquals(BuildingStats.rangeCells(BuildingType.TOWER, 1),
                BuildingStats.rangeCells(BuildingType.TOWER, 0), 0.0001);
        assertEquals(BuildingStats.rangeCells(BuildingType.TOWER, 1),
                BuildingStats.rangeCells(BuildingType.TOWER, -5), 0.0001);
        assertEquals(BuildingStats.rangeCells(BuildingType.TOWER, BuildingStats.MAX_LEVEL),
                BuildingStats.rangeCells(BuildingType.TOWER, 99), 0.0001);
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
        Building tower = field.place(BuildingType.TOWER, 5, 5);
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
        Building wall = field.place(BuildingType.WALL, 5, 5);
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
        Building tower = field.place(BuildingType.TOWER, 5, 5);

        assertSame(tower, field.buildingAt(new Cell(5, 5)));
        assertTrue(field.upgrade(tower));
        assertEquals(2, field.buildingAt(new Cell(5, 5)).level);
        // 2×2 的塔，四格都指向同一座
        assertSame(tower, field.buildingAt(new Cell(6, 6)));
    }
}
