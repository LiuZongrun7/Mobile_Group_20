package com.mobilegroup20.tokentrail.game.engine;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 商店：能买什么、花哪种资源、多少钱。
 *
 * <p><b>价格写在这里，不写进 {@code contract/}。</b>它是待调整的平衡参数，不是接口
 * （{@code docs/CONTRACTS.md} §8）。改价格只动这个文件，界面和契约都不用碰——
 * 这意味着数值可以随时调，而不算"改契约"。
 *
 * <p><b>这里只有"从无到有"的价。</b>升级（已有的塔涨一级）在
 * {@link BuildingStats#upgradeCost}：那种价格随当前等级变，不是货架上的一行。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}：能在电脑上直接跑单元测试调平衡，
 * 不用装模拟器。见 {@code game/engine} 的包注释。
 *
 * <h2>为什么塔要吃两种资源</h2>
 *
 * <p>三种资源互不通兑（{@code CONTRACTS.md} §8），所以每件商品都必须说清楚吃哪一种。
 * 如果所有商品都只吃 {@link ResourceType#INPUT}，另外两种资源就没有去处——
 * 结算发下来的 {@code CACHE} 和 {@code OUTPUT} 会一直堆着，赛季一长就没意义了。
 * 所以这里按"哪种资源该管什么"分开：
 *
 * <ul>
 *   <li><b>墙吃 INPUT</b>：INPUT 是四个 token 桶里最大的一块（桩数据里占一半以上），
 *       需要一个能大量消耗它的地方。墙 1×1、可以砌很多，正好；</li>
 *   <li><b>塔吃 CACHE + OUTPUT</b>：输出 token 是最贵的一档（$15/1M，是输入的 5 倍），
 *       换来的资源也最金贵，压在唯一的防御建筑上，让"少说废话"这件事在玩法里有回报；</li>
 *   <li><b>核心不要钱</b>：开局就有一座，商店里这一项是<b>挪</b>不是买第二座
 *       （{@code Battlefield.moveCore}）。</li>
 * </ul>
 */
public final class ShopCatalog {

    /** 一件商品。 */
    public static final class Item {

        /** 买的是哪种建筑。 */
        public final BuildingType type;

        /** 卖多少钱。算法在 {@link Cost} 里，和升级价共用一套。 */
        private final Cost price;

        private Item(BuildingType type) {
            this.type = type;
            this.price = Cost.FREE;
        }

        private Item(BuildingType type, Cost price) {
            this.type = type;
            this.price = price;
        }

        /** 链式设价，可叠加多种资源。 */
        private Item cost(ResourceType resource, long amount) {
            return new Item(type, price.plus(resource, amount));
        }

        /** 这一项要花多少某种资源。没用到的那种返回 0。 */
        public long costOf(ResourceType resource) {
            return price.amount(resource);
        }

        /** 这一项的完整价钱。升级那边问价时要看整笔，不只是某一种。 */
        public Cost price() {
            return price;
        }

        /** 不要钱（核心）。 */
        public boolean free() {
            return price.free();
        }

        /** 余额够不够买这一件。<b>几种资源都要够</b>才算够，缺一不可。 */
        public boolean affordable(ResourceBalance balance) {
            return price.affordable(balance);
        }

        /**
         * 扣钱。
         *
         * <p><b>只在 {@link #affordable} 为真时调。</b>判断和扣钱分成两步，
         * 是为了让界面能"先问价、画对了再成交"——拖动时的虚影要在落下去之前
         * 就知道买不买得起（{@code BattlefieldView.BuildGate}）。
         */
        public void charge(ResourceBalance balance) {
            price.charge(balance);
        }
    }

    /**
     * 货架。顺序就是商店里的显示顺序：先塔（主要防守），再墙（补缺口），最后核心。
     *
     * <p><b>数值的来路</b>：1 个单位资源 = 1 万 token（{@code MainActivity} 里的
     * {@code TOKENS_PER_UNIT}，即每 100 万 token 换 100 个）。桩数据下一个月的量
     * 大致是 input 5.4M / cache 3.5M / output 1.0M，折成资源约 540 / 350 / 95。
     * 塔 25+10、墙 5，算下来开局能造 9 座塔、一百多面墙——够摆出一个局面，
     * 又不至于随便铺满。**这三个数是待调的**，真机上玩两局再改。
     */
    private static final List<Item> ITEMS;

    static {
        List<Item> items = new ArrayList<>();

        items.add(new Item(BuildingType.TOWER)
                .cost(ResourceType.CACHE, 25)
                .cost(ResourceType.OUTPUT, 10));

        items.add(new Item(BuildingType.WALL)
                .cost(ResourceType.INPUT, 5));

        // 免费：场上本来就有一座，这一项的作用是"换它到别处"
        items.add(new Item(BuildingType.CORE));

        ITEMS = Collections.unmodifiableList(items);
    }

    private ShopCatalog() {
    }

    /** 整个货架，顺序固定。 */
    public static List<Item> items() {
        return ITEMS;
    }

    /**
     * 按建筑种类找货。
     *
     * <p>{@code BattlefieldView} 的虚影只有 {@link BuildingType} 这一个入参
     * （它不该知道"价格"这回事），所以扣钱/问价时要靠这个反查。
     * 找不到返回 {@code null}，调用方必须当成"不要钱"处理——宁可免费放行，
     * 也不要因为查不到就把玩家卡死。
     */
    public static Item find(BuildingType type) {
        for (Item item : ITEMS) {
            if (item.type == type) {
                return item;
            }
        }
        return null;
    }
}
