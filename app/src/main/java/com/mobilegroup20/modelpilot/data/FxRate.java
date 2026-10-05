package com.mobilegroup20.modelpilot.data;

import com.mobilegroup20.modelpilot.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * <b>用户自己填的汇率</b>：1 美元 = 多少人民币，外加「谁在什么时候按什么来源填的」。
 *
 * <p>为什么要单独一个类、而不是继续用一个常量：设计稿
 * （`docs/modelpilot-ui/INSIGHTS.md` §2）对跨币种的要求原话是
 * 「<b>跨币种要记录原币、汇率来源与日期；没有汇率时不能直接合计</b>」。
 * 一个写死在代码里的汇率满足不了这条——它没有来源、没有日期，而且我们没有任何
 * 依据说它是对的。所以汇率变成<b>用户输入的一项数据</b>：
 *
 * <ul>
 *   <li>没有内置默认值。没填就是 {@link #UNSET}，界面退回美元显示并说明，不编数；</li>
 *   <li>填的时候连<b>来源</b>一起记（可以是"招行现汇卖出价"这种自由文字，允许留空）；</li>
 *   <li>每次改动追加一条历史（{@link #encodeHistory}），所以"这个数当时是按哪个汇率算的"
 *       事后查得到——这就是「留痕」。</li>
 * </ul>
 *
 * <p><b>这个类是纯的</b>（不碰 Android：只用到 {@link TimeUtils} 的日期换算），
 * 所以解析、合理性判断、历史编解码都能用 JUnit 直接钉住。落盘在
 * {@link Currency} 里，那边只管读写 prefs。
 *
 * <p>金额与汇率都用<b>微单位</b>（1e-6）的 {@code long}：<b>钱不许有浮点误差</b>，
 * 汇率的两位小数（7.15）在微单位下是整数（7_150_000），换算途中不会掉精度。
 */
public final class FxRate {

    /** 没填。{@link #cnyPerUsdMicros} 为 0 就是它，别把 0 当成"汇率是 0"。 */
    public static final FxRate UNSET = new FxRate(0L, 0L, null);

    /** 历史记录里两条之间、以及字段之间的分隔符（见 {@link #encodeHistory}）。 */
    private static final String ROW_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = "|";

    /** 1 美元合多少人民币，微人民币。0 = 没填。 */
    public final long cnyPerUsdMicros;

    /** 这个汇率是什么时候填的（epoch 毫秒）。0 = 不知道（历史的边界情况）。 */
    public final long enteredAtMillis;

    /** 来源，用户自由填写，可为 null / 空。**只保存，不解释**。 */
    public final String source;

    public FxRate(long cnyPerUsdMicros, long enteredAtMillis, String source) {
        this.cnyPerUsdMicros = cnyPerUsdMicros;
        this.enteredAtMillis = enteredAtMillis;
        this.source = source == null || source.trim().isEmpty() ? null : source.trim();
    }

    /** 填过汇率没有。**没填就不能把美元换成人民币**（见 {@link Currency}）。 */
    public boolean isSet() {
        return cnyPerUsdMicros > 0L;
    }

    /** 有来源没有。界面上有就显示、没有就不显示，不显示成"来源：未知"。 */
    public boolean hasSource() {
        return source != null;
    }

    /** 填写的日期（`2026-06-01`）；不知道就是空串。 */
    public String enteredOn() {
        return enteredAtMillis > 0L ? TimeUtils.dayOf(enteredAtMillis) : "";
    }

    /** 给人看的汇率，如 `7.15`。 */
    public String label() {
        return format(cnyPerUsdMicros);
    }

    /**
     * 微单位汇率 → 给人看的字符串：`7.15`、`7.1`、`7`。
     *
     * <p>末尾的 0 去掉是<b>有意的</b>：用户填 7.15 就该看到 7.15，而不是 7.150000。
     * 但也不四舍五入到两位——万一人填了 7.1532，显示成 7.15 就和他填的数不一样了，
     * 那正好违反"屏幕上的数必须能追回原始值"。
     */
    public static String format(long micros) {
        if (micros <= 0L) {
            return "";
        }
        long whole = micros / 1_000_000L;
        long frac = micros % 1_000_000L;
        if (frac == 0L) {
            return String.valueOf(whole);
        }
        String fracText = String.format(Locale.US, "%06d", frac);
        int end = fracText.length();
        while (end > 0 && fracText.charAt(end - 1) == '0') {
            end--;
        }
        return whole + "." + fracText.substring(0, end);
    }

    /**
     * 用户输入的一段文字 → 微单位汇率。认不出来返回 0 = 没填。
     *
     * <p>故意<b>宽容</b>：真实输入是 `7.15`、` 7.15 `、`1 USD = 7.15`、`7.15 CNY/USD`
     * 甚至 `¥7.15`。处理办法是——先取最后一个等号<b>右边</b>的部分（左边通常是
     * "1 USD"这种说明），再只留下数字和小数点。单位文字一律丢掉。
     *
     * <p>宽容的代价是"abc7.15def"也会被认成 7.15，这是可以接受的：那个框里
     * 本来也只可能填一个数。真正填错的东西（比如把方向搞反）由
     * {@link #looksInverted} 拦，而不是靠这里变严格。
     */
    public static long parse(String raw) {
        if (raw == null) {
            return 0L;
        }
        String text = raw.trim().toLowerCase(Locale.US);
        if (text.isEmpty()) {
            return 0L;
        }
        int equals = text.lastIndexOf('=');
        if (equals >= 0) {
            text = text.substring(equals + 1);
        }
        if (text.indexOf('-') >= 0) {
            // 负汇率不是"不认识"，是"填错了"：丢掉负号会把它变成一个正数存进去，
            // 而 -7.15 和 7.15 在屏幕上（换算前）看不出区别。宁可当成没填。
            return 0L;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                digits.append(ch);
            }
        }
        if (digits.length() == 0) {
            return 0L;
        }
        return Money.parseMicros(digits.toString());
    }

    /**
     * 这个数看着像不像是<b>填反了</b>：把「1 元 = 0.1398 美元」当成了「1 美元 = …」。
     *
     * <p>判据是"小于 1"——我们只做美元 → 人民币，而 1 美元从来都值不止 1 元。
     * 填反了不拦住的话，页面上的金额会差 50 倍（0.1398²），
     * 而且它长得完全像一个正常数字。
     */
    public static boolean looksInverted(long micros) {
        return micros > 0L && micros < 1_000_000L;
    }

    /** 把汇率倒过来（`0.1398` → `7.153075`）。用来在界面上提议"你是不是想填这个"。 */
    public static long inverted(long micros) {
        if (micros <= 0L) {
            return 0L;
        }
        return 1_000_000_000_000L / micros;
    }

    /**
     * 是不是一个"正常量级"的汇率：1 ~ 100 元/美元。
     *
     * <p>超出这个范围不直接拒绝（万一将来支持别的币种），只在界面上多问一句
     * "确认按它换算吗"，因为一个手滑多打一位数的汇率会把整页金额悄悄改掉。
     */
    public static boolean plausible(long micros) {
        return micros >= 1_000_000L && micros <= 100_000_000L;
    }

    /**
     * 历史记一条 <b>`填写时间|汇率|来源`</b>，多条用换行分隔（新的在前）。
     *
     * <p>为什么不用 JSON：这里只有三个字段，而解析失败要能降级成"没有历史"
     * （见 {@link #decodeHistory}），手写格式的失败面比引一个序列化器小。
     *
     * <p>来源里的 `|` 和换行会被换成空格：它是用户随手写的文字，
     * 不洗的话一条备注就能把整个历史串拆坏。
     */
    public static String encodeHistory(List<FxRate> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (FxRate entry : entries) {
            if (entry == null || !entry.isSet()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(ROW_SEPARATOR);
            }
            out.append(entry.enteredAtMillis).append(FIELD_SEPARATOR)
                    .append(entry.cnyPerUsdMicros).append(FIELD_SEPARATOR)
                    .append(entry.source == null ? "" : sanitize(entry.source));
        }
        return out.toString();
    }

    /**
     * 历史串 → 条目（新的在前）。坏行<b>跳过</b>，不抛异常也不让整段作废。
     *
     * <p>历史是"给人看的证据"，它坏掉不该连累汇率本身能不能用——
     * 所以宁可少显示几条，也不能因为一条脏记录让设置页打不开。
     */
    public static List<FxRate> decodeHistory(String encoded) {
        List<FxRate> out = new ArrayList<>();
        if (encoded == null || encoded.trim().isEmpty()) {
            return out;
        }
        for (String row : encoded.split(ROW_SEPARATOR)) {
            if (row.trim().isEmpty()) {
                continue;
            }
            String[] fields = row.split("\\" + FIELD_SEPARATOR, -1);
            if (fields.length < 2) {
                continue;
            }
            try {
                long at = Long.parseLong(fields[0].trim());
                long micros = Long.parseLong(fields[1].trim());
                String source = fields.length > 2 ? fields[2].trim() : "";
                FxRate entry = new FxRate(micros, at, source);
                if (entry.isSet()) {
                    out.add(entry);
                }
            } catch (NumberFormatException badRow) {
                // 坏行跳过：历史是证据，不是运行必需的数据。
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String sanitize(String text) {
        return text.replace(FIELD_SEPARATOR, " ")
                .replace(ROW_SEPARATOR, " ")
                .replace("\r", " ")
                .trim();
    }
}
