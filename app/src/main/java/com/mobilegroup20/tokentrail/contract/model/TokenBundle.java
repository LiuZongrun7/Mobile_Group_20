package com.mobilegroup20.tokentrail.contract.model;

/**
 * 一组 token 计数，按计费口径分成四类。
 *
 * <p><b>为什么缓存要分读写两种。</b>只记一个「缓存」字段是没法算钱的：读缓存命中
 * 通常只要原价的十分之一，而写缓存（把内容写进缓存那一次）往往比原价还贵。两者
 * 单价不同，混在一起账单永远对不上。所以 {@link #cacheRead} 和 {@link #cacheWrite}
 * 必须分开记。
 *
 * <p>这个类只用于<b>汇总值</b>（工具返回值、游戏换算的入参）。原始记录
 * {@link UsageCall} 和按天汇总 {@link DailyUsage} 保持四个平铺的 long，
 * 因为那两个结构要直接映射到数据库列和 Firestore 字段，平铺更好对齐。
 */
public class TokenBundle {

    /** 普通输入：按原价计费的那部分提示词。 */
    public long input;

    /** 缓存读：命中缓存的提示词，单价通常是最低的。 */
    public long cacheRead;

    /** 缓存写：把内容写入缓存的那一次，单价通常最高。没有缓存机制的模型填 0。 */
    public long cacheWrite;

    /** 输出。<b>推理模型的思考 token 也算在这里</b>——provider 是按输出计费的，
     *  如果导入日志时把它们单独放着，就会漏计费。 */
    public long output;

    /** Gson / Jackson / Firestore 反序列化都要用到无参构造，别删。 */
    public TokenBundle() {
    }

    public TokenBundle(long input, long cacheRead, long cacheWrite, long output) {
        this.input = input;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.output = output;
    }

    /** 四类之和。用于「一共花了多少 token」这种口径，注意它不等于钱。 */
    public long total() {
        return input + cacheRead + cacheWrite + output;
    }

    /** 累加另一个 bundle，用于跨天或跨模型求和。 */
    public void add(TokenBundle other) {
        if (other == null) {
            return;
        }
        this.input += other.input;
        this.cacheRead += other.cacheRead;
        this.cacheWrite += other.cacheWrite;
        this.output += other.output;
    }

    public static TokenBundle from(DailyUsage daily) {
        return new TokenBundle(daily.input, daily.cacheRead, daily.cacheWrite, daily.output);
    }

    public static TokenBundle from(UsageCall call) {
        return new TokenBundle(call.input, call.cacheRead, call.cacheWrite, call.output);
    }
}
