package com.mobilegroup20.tokentrail.contract.model;

/**
 * 玩家手里的三种建造资源余额。
 *
 * <p>写死三个字段而不是用 {@code Map<ResourceType, Long>}：字段名能直接当服务端
 * 的字段名用，序列化不会出错，游戏里读余额也是直取，不用查表。代价是加第四种资源时
 * 要改这个类——按目前的设计不会有第四种。
 *
 * <p>这份数据<b>以服务端为准</b>，本地那份只是显示缓存。原因：结算要按天推进，
 * 如果余额只存在本地，重装应用、换设备或者多开都能把已经结算过的天再领一遍。
 */
public class ResourceBalance {

    public long input;

    public long cache;

    public long output;

    public ResourceBalance() {
    }

    public ResourceBalance(long input, long cache, long output) {
        this.input = input;
        this.cache = cache;
        this.output = output;
    }

    /** 按类型取余额，给界面循环渲染用。 */
    public long get(ResourceType type) {
        switch (type) {
            case INPUT:
                return input;
            case CACHE:
                return cache;
            case OUTPUT:
                return output;
            default:
                throw new IllegalArgumentException("未知资源类型: " + type);
        }
    }

    /** 按类型加余额，结算时按天累加用。 */
    public void add(ResourceType type, long amount) {
        switch (type) {
            case INPUT:
                input += amount;
                return;
            case CACHE:
                cache += amount;
                return;
            case OUTPUT:
                output += amount;
                return;
            default:
                throw new IllegalArgumentException("未知资源类型: " + type);
        }
    }
}
