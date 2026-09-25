package com.mobilegroup20.tokentrail.game.engine;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;

/**
 * 一笔开销：三种资源各要多少。
 *
 * <p>货架上的价格（{@link ShopCatalog}）和升级价（{@link BuildingStats}）都是这个类。
 * 抽出来是因为两处的算法一模一样——都是"几种资源各自够不够、够就一起扣"，
 * 而"三种资源互不通兑"这条规矩（{@code CONTRACTS.md} §8）要求每一处都按
 * <b>逐种检查</b>来做，不能只看总数。抄第二遍的时候最容易顺手写成"加起来够就行"。
 *
 * <p><b>不可变。</b>{@link #plus} 返回新的，不改自己。价格表是 {@code static final}
 * 常量，被多处共享；可变的话谁扣一次钱就把全表改了，而且是那种"玩到第二局才发作"的 bug。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}。
 */
public final class Cost {

    /** 不要钱。核心的"价格"、以及任何查不到价的场合都用它。 */
    public static final Cost FREE = new Cost(new long[ResourceType.values().length]);

    /** 下标是 {@link ResourceType#ordinal()}。 */
    private final long[] amounts;

    private Cost(long[] amounts) {
        this.amounts = amounts;
    }

    /** 单独一种资源的一笔开销。 */
    public static Cost of(ResourceType resource, long amount) {
        return FREE.plus(resource, amount);
    }

    /**
     * 再加上一种资源。
     *
     * <p>同名资源<b>再设一次是改写、不是叠加</b>：{@code of(CACHE, 10).plus(CACHE, 3)}
     * 得到的是 3 而不是 13。价格是按"这一项吃哪几种"顺手写下来的链式调用，
     * 写重了多半是笔误，叠加会把它悄悄藏起来。
     */
    public Cost plus(ResourceType resource, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("价格不能是负数: " + resource + " " + amount);
        }
        if (amounts[resource.ordinal()] == amount) {
            return this;
        }
        long[] next = amounts.clone();
        next[resource.ordinal()] = amount;
        return new Cost(next);
    }

    /** 这一笔要花多少某种资源。没用到的那些返回 0。 */
    public long amount(ResourceType resource) {
        return amounts[resource.ordinal()];
    }

    /** 不要钱。 */
    public boolean free() {
        for (long amount : amounts) {
            if (amount > 0) {
                return false;
            }
        }
        return true;
    }

    /** 余额够不够付这一笔。<b>三种资源都要够</b>才算够，缺一不可。 */
    public boolean affordable(ResourceBalance balance) {
        for (ResourceType resource : ResourceType.values()) {
            if (balance.get(resource) < amounts[resource.ordinal()]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 扣钱。
     *
     * <p><b>只在 {@link #affordable} 为真时调。</b>这里不检查、也不抛异常：
     * 判断和扣钱分成两步，是为了让界面能"先问价、画对了再成交"——拖动时的虚影
     * 要在落下去之前就知道买不买得起（{@code BattlefieldView.BuildGate}）。
     * 余额扣不成负数由调用方保证，{@code ShopCatalogTest} 盯着这个约定。
     */
    public void charge(ResourceBalance balance) {
        for (ResourceType resource : ResourceType.values()) {
            balance.add(resource, -amounts[resource.ordinal()]);
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Cost(");
        for (ResourceType resource : ResourceType.values()) {
            if (text.length() > 5) {
                text.append(" + ");
            }
            text.append(resource).append(' ').append(amounts[resource.ordinal()]);
        }
        return text.append(')').toString();
    }
}
