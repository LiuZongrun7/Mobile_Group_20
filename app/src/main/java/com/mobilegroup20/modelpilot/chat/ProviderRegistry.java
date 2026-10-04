package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 六家 provider 的注册表：**能力、上下文上限、参考价、base URL 都在这儿**。
 *
 * <p>为什么要有一张"以官方文档为准"的表，而不是让每个调用点自己判断：
 *
 * 1. **Auto 只能按它选**。设计稿里"这个任务下 DeepSeek/Claude 置灰"不是界面写死的，
 *    是能力表算出来的（图片任务 → 只有支持 vision 的模型可选）。
 * 2. **木桶效应要它**。所有已启用模型里最小的那个上下文上限决定了什么时候压缩；
 *    上限写在各处的话，加一个模型就可能悄悄把阈值拉错。
 * 3. **价格是数据**。价格会变，而且我们**只写实测过的**：没查到官方定价的模型
 *    价格留 null，界面上显示"价格未知"，Auto 也不会把它当"最便宜"。
 *
 * <p><b>`baseUrl` 的语义是"到资源路径之前"</b>：客户端会拼上 `/chat/completions`
 * （OpenAI 兼容家）或 `/v1/messages`（Anthropic）。所以 OpenAI 与 Kimi 必须带 `/v1`
 * ——少了它真跑就是 404，而 404 看起来像"这家挂了"，很难查。用户在设置里改 base URL 时
 * 也按这个口径填（自建反代一般是 `https://my-proxy.example/v1`）。
 *
 * <p><b>上限与价格的出处</b>：能填的都填官方文档值，并在 {@code priceSource} 留链接；
 * 查不到的那几个留 null + 注释写"待补"，**不许估**。真实价格最终以服务端的价目表为准
 * （手机这张表只用来给 Auto 排序与界面展示）。
 */
public final class ProviderRegistry {

    public static final String DEEPSEEK = "DEEPSEEK";
    public static final String OPENAI = "OPENAI";
    public static final String GLM = "GLM";
    public static final String KIMI = "KIMI";
    public static final String MIMO = "MIMO";
    public static final String SEED = "SEED";
    public static final String ANTHROPIC = "ANTHROPIC";

    private final List<ProviderSpec> providers;

    private ProviderRegistry(List<ProviderSpec> providers) {
        this.providers = Collections.unmodifiableList(providers);
    }

    /** 出厂默认表。用户在设置里改过 base URL 的那几家，用 {@link #withBaseUrl} 覆盖。 */
    public static ProviderRegistry defaults() {
        List<ProviderSpec> list = new ArrayList<>();

        // DeepSeek：官方定价页按"缓存命中/未命中/输出"三段给，正是我们的四桶口径。
        list.add(new ProviderSpec(DEEPSEEK, "DeepSeek", "https://api.deepseek.com",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(DEEPSEEK, "deepseek-chat", "DeepSeek V3.2", 128_000, true, false, false, true,
                        140_845L, 2_816L, 0L, 563_380L,
                        "https://api-docs.deepseek.com/quick_start/pricing/"),
                model(DEEPSEEK, "deepseek-reasoner", "DeepSeek Reasoner", 128_000, true, false, false, true,
                        140_845L, 2_816L, 0L, 563_380L,
                        "https://api-docs.deepseek.com/quick_start/pricing/")), true));

        // OpenAI：官方定价页。
        list.add(new ProviderSpec(OPENAI, "OpenAI", "https://api.openai.com/v1",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(OPENAI, "gpt-5.5", "GPT-5.5", 400_000, true, true, true, true,
                        null, null, null, null, null),
                model(OPENAI, "gpt-5-mini", "GPT-5 mini", 400_000, true, true, true, true,
                        null, null, null, null, null)), true));

        // GLM（智谱）：OpenAI 兼容端点。
        list.add(new ProviderSpec(GLM, "GLM", "https://open.bigmodel.cn/api/paas/v4",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(GLM, "glm-4.6", "GLM-4.6", 200_000, true, false, false, true,
                        null, null, null, null, null))));

        // Kimi（月之暗面）。
        list.add(new ProviderSpec(KIMI, "Kimi", "https://api.moonshot.cn/v1",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(KIMI, "kimi-k2-0905-preview", "Kimi K2", 256_000, true, false, false, true,
                        null, null, null, null, null))));

        // MiMo（小米）：base URL 与价格待按官方文档核（`docs/DATA_SOURCES.md` 里
        // 记过它的接口形态，但**没实测过价格，所以这里留 null**）。
        list.add(new ProviderSpec(MIMO, "MiMo", "https://api.xiaomimimo.com",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(MIMO, "mimo-v2.6-pro", "MiMo v2.6 Pro", 128_000, true, false, false, false,
                        null, null, null, null, null))));

        // Seed（豆包）：同上，待补官方 base URL 与价格。
        list.add(new ProviderSpec(SEED, "Seed", "https://ark.cn-beijing.volces.com/api/v3",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList(
                model(SEED, "doubao-seed-1.6", "Seed 1.6", 256_000, true, true, false, true,
                        null, null, null, null, null))));

        // Anthropic：另一套形状（system 顶层、tool_use/tool_result）。留着备用，
        // 用户在设置里填了 key 才会出现在候选里。
        list.add(new ProviderSpec(ANTHROPIC, "Anthropic", "https://api.anthropic.com",
                ProviderSpec.Adapter.ANTHROPIC, Arrays.asList(
                model(ANTHROPIC, "claude-sonnet-4-6", "Claude Sonnet 4.6", 200_000,
                        true, true, true, true, null, null, null, null, null))));

        return new ProviderRegistry(list);
    }

    private static ModelSpec model(String providerId, String modelId, String displayName,
                                   int contextLimit, boolean text, boolean vision, boolean pdf,
                                   boolean tools, Long input, Long cacheRead, Long cacheWrite,
                                   Long output, String source) {
        return new ModelSpec(providerId, modelId, displayName, contextLimit,
                text, vision, pdf, tools, input, cacheRead, cacheWrite, output, source);
    }

    public List<ProviderSpec> providers() {
        return providers;
    }

    public ProviderSpec provider(String providerId) {
        for (ProviderSpec spec : providers) {
            if (spec.providerId.equals(providerId)) {
                return spec;
            }
        }
        return null;
    }

    public ModelSpec model(String providerId, String modelId) {
        ProviderSpec spec = provider(providerId);
        return spec == null ? null : spec.model(modelId);
    }

    /** 用户在设置里改了 base URL（自建反代、区域节点）时用这个覆盖。 */
    public ProviderRegistry withBaseUrl(String providerId, String baseUrl) {
        List<ProviderSpec> copy = new ArrayList<>();
        for (ProviderSpec spec : providers) {
            copy.add(spec.providerId.equals(providerId) ? spec.withBaseUrl(baseUrl) : spec);
        }
        return new ProviderRegistry(copy);
    }

    /**
     * 把用户自定义的端点并进来（2026-10-04 加）。
     *
     * <p>并进来之后它就是"普通的第六+家"：模型弹层会列它、Auto 会考虑它、
     * 木桶效应会把它的上下文上限算进最小的那块板。**它们必须走同一条路**——
     * 自定义端点如果只在"发送"那一处被特殊处理，就会出现"能选它，但压缩阈值
     * 没把它算进去"，而那正是切过去就超长的经典成因。
     *
     * <p>同 id 覆盖：用户改了某一家的地址后重新加载，不该出现两条同 id 的记录。
     */
    public ProviderRegistry withCustom(List<ProviderSpec> custom) {
        if (custom == null || custom.isEmpty()) {
            return this;
        }
        List<ProviderSpec> copy = new ArrayList<>(providers);
        for (ProviderSpec spec : custom) {
            if (spec == null) {
                continue;
            }
            copy.removeIf(existing -> existing.providerId.equals(spec.providerId));
            copy.add(spec);
        }
        return new ProviderRegistry(copy);
    }

    /**
     * 木桶效应里那块**最短的板**：所有已启用模型里最小的上下文上限。
     *
     * <p>`enabled` 是"用户配了 key 的 provider"——没配 key 的模型就算上限再小也与我们
     * 无关（我们根本不会往那儿发）。一个都没配时返回 0，调用方据此说"先去设置里填 key"。
     */
    public int smallestContextLimit(List<String> enabledProviderIds) {
        int smallest = 0;
        for (ProviderSpec spec : providers) {
            if (!enabledProviderIds.contains(spec.providerId)) {
                continue;
            }
            for (ModelSpec model : spec.models) {
                if (smallest == 0 || model.contextLimit < smallest) {
                    smallest = model.contextLimit;
                }
            }
        }
        return smallest;
    }
}
