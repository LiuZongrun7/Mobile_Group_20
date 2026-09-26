package com.mobilegroup20.tokentrail.contract.model;

/**
 * 被追踪的编码 agent / API 提供方。
 *
 * <p>命名口径（2026-09 定，同日改过一次）：第三家是<b>小米 MiMo</b>，枚举写
 * {@code MIMO}、界面写 {@code "Xiaomi MiMo"}。大纲早期这里写的是智谱的 GLM，
 * 跟 MiMo <b>不是同一家</b>，改版后已统一成 MiMo；作为<b>提供方名</b>，
 * 代码和文档里不要再出现 glm、zhipu、z.ai、zai。
 *
 * <p><b>但 ZCode 不一样，它是客户端，不能删。</b>大纲 §2 的
 * “ZCode supports Xiaomi MiMo as a model provider” 说的就是这件事：
 * ZCode 是用户装在机器上的那个编码 agent，MiMo 是它背后计费的提供方，
 * {@code https://zcode.z.ai/...} 那个域名是 ZCode 自己的配置文档，
 * 拿它当提供方名叫就错了。
 *
 * <p>注意「提供方」和「模型」是两个层级：真正决定单价的是 model
 * （见 {@link PricingRate}），同一家下面 mimo-v2.6-pro 和 mimo-v2.6-flash
 * 不是一个价。所以每条用量记录两个字段都要留。
 *
 * <p><b>改这个枚举的值要顺手清一次本地库。</b>Room 里存的是 {@code name()}
 * （见 {@code LocalConverters}），把 GLM 改名成 MIMO 之后，老库里 provider='GLM'
 * 的行读出来会抛 {@code IllegalArgumentException}。现在还没有装出去的版本，
 * 所以直接改就行；等有了真实用户数据，改名就得配一次 Room 迁移把值也改掉。
 */
public enum Provider {

    OPENAI("OpenAI"),
    MIMO("Xiaomi MiMo"),
    DEEPSEEK("DeepSeek");

    /** 界面上展示的名字。别在布局或代码里另写字符串，改这里一处就够。 */
    public final String displayName;

    Provider(String displayName) {
        this.displayName = displayName;
    }
}
