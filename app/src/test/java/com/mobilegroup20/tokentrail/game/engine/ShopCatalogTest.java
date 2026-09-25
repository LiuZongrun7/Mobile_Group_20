package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;

import org.junit.Test;

/**
 * {@link ShopCatalog} 的测试。
 *
 * <p>价格和余额这两件事最容易出的不是崩溃，是<b>悄悄算错</b>：多扣一种资源、
 * 余额扣成负数、或者某件商品谁都能白拿。这些在真机上要点很多下才看得出来，
 * 在这里就是一行断言。
 */
public class ShopCatalogTest {

    /** 一个够买下所有东西的余额。 */
    private static ResourceBalance rich() {
        return new ResourceBalance(1_000, 1_000, 1_000);
    }

    // ---- 货架本身 ----

    /** 每种能放上场的建筑都得能买到（核心免费也算买到），不能有放不出来的建筑。 */
    @Test
    public void everyBuildingTypeIsOnTheShelf() {
        for (BuildingType type : BuildingType.values()) {
            assertNotNull("货架上没有 " + type, ShopCatalog.find(type));
        }
    }

    /** 价格不能是负数：负价格等于买一件倒赚一件，余额会越买越多。 */
    @Test
    public void noNegativePrices() {
        for (ShopCatalog.Item item : ShopCatalog.items()) {
            for (ResourceType resource : ResourceType.values()) {
                assertTrue(item.type + " 的 " + resource + " 价格是负的",
                        item.costOf(resource) >= 0);
            }
        }
    }

    /**
     * 三种资源各自都要有去处。
     *
     * <p>这条是冲着"资源互不通兑"来的：如果所有商品都只吃一种资源，
     * 另外两种发下来就没地方花，结算发资源的规则也就形同虚设。
     */
    @Test
    public void allThreeResourcesHaveASink() {
        for (ResourceType resource : ResourceType.values()) {
            boolean used = false;
            for (ShopCatalog.Item item : ShopCatalog.items()) {
                if (item.costOf(resource) > 0) {
                    used = true;
                    break;
                }
            }
            assertTrue(resource + " 没有任何商品在收，发下来花不掉", used);
        }
    }

    /** 核心不要钱：开局就有一座，商店里这一项是"挪"不是"买第二座"。 */
    @Test
    public void coreIsFree() {
        ShopCatalog.Item core = ShopCatalog.find(BuildingType.CORE);

        assertTrue(core.free());
        assertTrue(core.affordable(new ResourceBalance()));   // 一分钱没有也拿得走
    }

    /** 除了核心，其余每件都得花钱——白送的塔等于没有经济系统。 */
    @Test
    public void everythingElseCostsSomething() {
        for (ShopCatalog.Item item : ShopCatalog.items()) {
            if (item.type == BuildingType.CORE) {
                continue;
            }
            assertFalse(item.type + " 是免费的", item.free());
        }
    }

    /** 货架上没有的建筑查不到，调用方要能拿到 null（并当成免费处理）。 */
    @Test
    public void findReturnsNullForUnknownType() {
        assertNull(ShopCatalog.find(null));
    }

    // ---- 买得起吗 ----

    /** 钱够就是够。 */
    @Test
    public void affordableWhenBalanceCoversEveryResource() {
        ShopCatalog.Item tower = ShopCatalog.find(BuildingType.TOWER);
        ResourceBalance balance = new ResourceBalance(0, tower.costOf(ResourceType.CACHE),
                tower.costOf(ResourceType.OUTPUT));

        assertTrue(tower.affordable(balance));
    }

    /**
     * 缺任何一种就是买不起。
     *
     * <p>塔同时吃缓存和输出两种资源，所以这条要逐个资源试：只缺其中一种也要拦住，
     * 否则会出现"扣了缓存但输出是欠着的"。
     */
    @Test
    public void oneMissingResourceIsEnoughToBlock() {
        ShopCatalog.Item tower = ShopCatalog.find(BuildingType.TOWER);

        for (ResourceType resource : ResourceType.values()) {
            ResourceBalance balance = rich();
            long short_ = tower.costOf(resource) - 1;
            balance.add(resource, short_ - balance.get(resource));   // 调到差一个

            assertFalse("只缺 " + resource + " 也应该买不起", tower.affordable(balance));
        }
    }

    /** 差一个也不行。边界差一，是这类判断最常见的错。 */
    @Test
    public void exactlyEnoughIsAffordableButOneLessIsNot() {
        ShopCatalog.Item wall = ShopCatalog.find(BuildingType.WALL);
        long price = wall.costOf(ResourceType.INPUT);

        assertTrue(wall.affordable(new ResourceBalance(price, 0, 0)));
        assertFalse(wall.affordable(new ResourceBalance(price - 1, 0, 0)));
    }

    // ---- 扣钱 ----

    /** 扣完正好少一份，别的资源一分不动（互不通兑）。 */
    @Test
    public void chargeTakesExactlyThePrice() {
        ShopCatalog.Item tower = ShopCatalog.find(BuildingType.TOWER);
        ResourceBalance balance = rich();
        long inputBefore = balance.input;

        tower.charge(balance);

        assertEquals(1_000 - tower.costOf(ResourceType.CACHE), balance.cache);
        assertEquals(1_000 - tower.costOf(ResourceType.OUTPUT), balance.output);
        assertEquals("没用到输入资源，不该动它", inputBefore, balance.input);
    }

    /** 买得起就一直买，余额不会变成负数。 */
    @Test
    public void balanceNeverGoesNegative() {
        ShopCatalog.Item wall = ShopCatalog.find(BuildingType.WALL);
        ResourceBalance balance = new ResourceBalance(11, 0, 0);   // 够买两座墙（5 一座）

        int bought = 0;
        while (wall.affordable(balance)) {
            wall.charge(balance);
            bought++;
            assertTrue("余额扣成负数了", balance.input >= 0);
        }

        assertEquals(2, bought);
        assertEquals(1, balance.input);
    }
}
