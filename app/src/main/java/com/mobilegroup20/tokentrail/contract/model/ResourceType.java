package com.mobilegroup20.tokentrail.contract.model;

/**
 * 游戏里的三种建造资源，和 token 的类别一一对应。
 *
 * <p><b>三种资源不互通</b>——输入换来的资源买不了只能拿输出资源买的东西。这是故意的：
 * 缓存命中的 token 单价只有原价的十分之一左右，如果按原始 token 数换同一种资源，
 * 「多用缓存」就成了刷资源的漏洞。换成互不通兑的三条线之后，这个洞就没了。
 *
 * <p>三种资源的换算率目前定成一样（每 100 万 token 换 100 个），这是<b>待调整</b>的
 * 平衡参数，不是接口的一部分：数值放在实现里，别写进契约类型，改的时候才不用动结构。
 */
public enum ResourceType {

    /** 由普通输入 token 换来的资源。 */
    INPUT("Input"),

    /** 由缓存读 token 换来的资源。 */
    CACHE("Cache"),

    /** 由输出 token 换来的资源。 */
    OUTPUT("Output");

    public final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }
}
