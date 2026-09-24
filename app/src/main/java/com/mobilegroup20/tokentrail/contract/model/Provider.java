package com.mobilegroup20.tokentrail.contract.model;

/**
 * 被追踪的编码 agent / API 提供方。
 *
 * <p>命名口径（2026-09 定）：智谱那一侧一律写 <b>GLM</b>。Z.ai 是智谱的国际站，
 * 大纲早期写的 "ZCode / Z.ai" 就是它，以后代码和文档都不要再出现 zhipu、zai、
 * zcode 这些写法，否则联调时两边对不上。
 *
 * <p>注意「提供方」和「模型」是两个层级：真正决定单价的是 model
 * （见 {@link PricingRate}），同一家下面 glm-4-plus 和 glm-4-flash 不是一个价。
 * 所以每条用量记录两个字段都要留。
 */
public enum Provider {

    OPENAI("OpenAI"),
    GLM("GLM"),
    DEEPSEEK("DeepSeek");

    /** 界面上展示的名字。别在布局或代码里另写字符串，改这里一处就够。 */
    public final String displayName;

    Provider(String displayName) {
        this.displayName = displayName;
    }
}
