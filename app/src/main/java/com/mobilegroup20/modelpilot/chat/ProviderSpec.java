package com.mobilegroup20.modelpilot.chat;

import java.util.Collections;
import java.util.List;

/**
 * 一家 provider：往哪发（base URL）、有哪些模型、用哪个渲染器。
 *
 * <p>六家里五家是 OpenAI 兼容的，所以它们的差别其实只有 base URL 与模型清单——
 * 这正是"一套适配器 + 一张配置表"能接六家的原因。Anthropic 的 Messages API
 * 是另一套形状（system 在顶层、tool_use/tool_result 分开），单独一个渲染器。
 */
public final class ProviderSpec {

    public enum Adapter {
        /** OpenAI 的 {@code /chat/completions}：DeepSeek / OpenAI / GLM / Kimi / MiMo / Seed。 */
        OPENAI_COMPATIBLE,
        /** Anthropic 的 Messages API。 */
        ANTHROPIC
    }

    public final String providerId;
    public final String displayName;
    /** 用户可以在设置里改（自建反代、区域节点），所以它是配置不是常量。 */
    public final String baseUrl;
    public final Adapter adapter;
    public final List<ModelSpec> models;
    /**
     * 流式请求要不要带 `stream_options.include_usage`。
     *
     * <p>不带的话 **OpenAI 根本不发 usage 块**（账本只能记"未知"）；带了而这家不认
     * 这个字段的话会 400。所以它是**每家的一个开关**，不是全局常量——
     * 六家里只有实测过的那几家打开，其余等验证（认不认这个字段，试一次就知道）。
     */
    public final boolean streamUsage;

    public ProviderSpec(String providerId, String displayName, String baseUrl, Adapter adapter,
                        List<ModelSpec> models) {
        this(providerId, displayName, baseUrl, adapter, models, false);
    }

    public ProviderSpec(String providerId, String displayName, String baseUrl, Adapter adapter,
                        List<ModelSpec> models, boolean streamUsage) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.adapter = adapter;
        this.models = Collections.unmodifiableList(models);
        this.streamUsage = streamUsage;
    }

    public ModelSpec model(String modelId) {
        for (ModelSpec model : models) {
            if (model.modelId.equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    public ProviderSpec withBaseUrl(String url) {
        return new ProviderSpec(providerId, displayName, url, adapter, models, streamUsage);
    }
}
