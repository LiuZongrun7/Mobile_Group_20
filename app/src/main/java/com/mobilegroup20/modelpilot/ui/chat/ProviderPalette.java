package com.mobilegroup20.modelpilot.ui.chat;

import com.mobilegroup20.modelpilot.chat.ProviderRegistry;

/**
 * 徽章的配色：**一家一个颜色**（设计稿里 GPT 是绿的、DeepSeek 是蓝的、Claude 是紫的）。
 *
 * <p>两个刻意的选择：
 *
 * <ol>
 *   <li><b>颜色不是品牌色，是"能分开"的颜色。</b>我们不去抄各家的 logo 色值，
 *       只保证六家在同一个屏幕上互相分得开，而且深浅都在浅底上够对比。
 *       抄品牌色还得跟各家 VI 的用法较劲（有的规定底色上必须放白 logo），不划算。</li>
 *   <li><b>认不出来的 provider 给灰色，并且字母取 id 首字母。</b>用户以后自己加一家
 *       （或者我们加了新家忘了配色），界面上出现一个灰色徽章 + 首字母仍然能读，
 *       比变成透明、或者显示成别家的颜色好。</li>
 * </ol>
 *
 * <p>字母标记（而不是小 logo）也是故意的：六个手画的多边形标志远不如一个首字母
 * 好认，而且加一家就得画一个。将来要做真 logo 的话，换的是这个类，不是布局。
 */
public final class ProviderPalette {

    /** 徽章底色（浅）、文字色（深）、字母底色（中）、字母文字色。 */
    public static final class Colors {
        public final int badgeBackground;
        public final int badgeText;
        public final int markBackground;
        public final int markText;

        Colors(int badgeBackground, int badgeText, int markBackground, int markText) {
            this.badgeBackground = badgeBackground;
            this.badgeText = badgeText;
            this.markBackground = markBackground;
            this.markText = markText;
        }
    }

    private static final Colors DEEPSEEK = new Colors(0xFFDCE9FF, 0xFF123A7A, 0xFF4A86E8, 0xFFFFFFFF);
    private static final Colors OPENAI = new Colors(0xFFD9F2E4, 0xFF0F4A32, 0xFF10A37F, 0xFFFFFFFF);
    private static final Colors ANTHROPIC = new Colors(0xFFEBE1FB, 0xFF3B2170, 0xFF7C5CD6, 0xFFFFFFFF);
    private static final Colors GLM = new Colors(0xFFFFE9D6, 0xFF6B3608, 0xFFE8873A, 0xFFFFFFFF);
    private static final Colors KIMI = new Colors(0xFFD8F1F4, 0xFF0C4A52, 0xFF1FA3B4, 0xFFFFFFFF);
    private static final Colors MIMO = new Colors(0xFFFFE1E1, 0xFF6E1B1B, 0xFFE0605F, 0xFFFFFFFF);
    private static final Colors SEED = new Colors(0xFFE6E8FF, 0xFF232A6B, 0xFF5C66D6, 0xFFFFFFFF);
    private static final Colors UNKNOWN = new Colors(0xFFE9EDF3, 0xFF3A4A61, 0xFF9AA5B6, 0xFFFFFFFF);

    private ProviderPalette() {
    }

    public static Colors of(String providerId) {
        if (providerId == null) {
            return UNKNOWN;
        }
        switch (providerId) {
            case ProviderRegistry.DEEPSEEK: return DEEPSEEK;
            case ProviderRegistry.OPENAI: return OPENAI;
            case ProviderRegistry.ANTHROPIC: return ANTHROPIC;
            case ProviderRegistry.GLM: return GLM;
            case ProviderRegistry.KIMI: return KIMI;
            case ProviderRegistry.MIMO: return MIMO;
            case ProviderRegistry.SEED: return SEED;
            default: return UNKNOWN;
        }
    }

    /**
     * 徽章上那个字母。**用 modelId 而不是 providerId 更准**（同一家可能有
     * `deepseek-chat` 和 `deepseek-reasoner`，将来也可能有别家的模型挂在同一家代理上），
     * 所以优先按模型名首字母，取不到再退回 provider 首字母。
     */
    public static String mark(String providerId, String modelId) {
        String source = modelId != null && !modelId.isEmpty() ? modelId : providerId;
        if (source == null || source.isEmpty()) {
            return "?";
        }
        return source.substring(0, 1).toUpperCase(java.util.Locale.US);
    }
}
