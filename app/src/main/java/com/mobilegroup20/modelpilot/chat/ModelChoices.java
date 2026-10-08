package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型选择弹层要显示什么（设计稿 `04-models.png`）——**这一层不发请求、不碰界面**，
 * 只把"哪些能选、哪些要置灰、为什么"算出来，所以它可以被单测钉住。
 *
 * <p>设计稿那张图的三段，正好对应三条规矩：
 *
 * 1. **`Auto` 永远在最上面**：它是默认选项，而且带一句 "Chooses for each message"——
 *    用户得知道这不是"某个模型"，而是"每次帮你挑"。
 * 2. **`Choose manually`**：能力满足的模型，按 provider 分组列出来。
 * 3. **`Unavailable for this task`**：能力不满足的**照样列出来但置灰**，并写明原因。
 *    为什么不干脆藏起来：用户会去找"我明明配了 Claude，怎么不见了"。
 *    列出来 + 一句"这个任务它做不了"，比消失更好解释。
 */
public final class ModelChoices {

    /** 一段（Auto 段 / 手动段 / 置灰段）。 */
    public static final class Group {
        public enum Kind { AUTO, MANUAL, UNAVAILABLE }
        public final Kind kind;
        public final List<Choice> choices;

        Group(Kind kind, List<Choice> choices) {
            this.kind = kind;
            this.choices = Collections.unmodifiableList(choices);
        }
    }

    /** 一条可选项。自动段只有一条（`providerId`/`modelId` 为 null，代表"每次帮我挑"）。 */
    public static final class Choice {
        public final String providerId;
        public final String modelId;
        public final String title;
        public final String subtitle;
        /** 选它的时候用哪个（Auto 段为空）。 */
        public final boolean selectable;

        Choice(String providerId, String modelId, String title, String subtitle, boolean selectable) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.title = title;
            this.subtitle = subtitle;
            this.selectable = selectable;
        }
    }

    private ModelChoices() {
    }

    /**
     * 算出弹层要显示的三段。
     *
     * @param enabledProviderIds 用户配了 key 的 provider（没配的那几家**不出现在这里**：
     *                           列表里列一堆"没 key 的模型"只会让人以为能选）
     * @param task               这次要做什么（决定谁进置灰段）
     * @param manualProviderId   当前手动选中的 provider；null 表示当前是 Auto
     * @param manualModelId      当前手动选中的模型
     */
    public static List<Group> build(ProviderRegistry registry, List<String> enabledProviderIds,
                                    TaskKind task, String manualProviderId, String manualModelId) {
        List<Group> groups = new ArrayList<>();
        groups.add(new Group(Group.Kind.AUTO, Collections.singletonList(
                new Choice(null, null, "Auto",
                        "Chooses for each message", manualProviderId == null))));

        List<Choice> available = new ArrayList<>();
        List<Choice> unavailable = new ArrayList<>();
        for (ProviderSpec provider : registry.providers()) {
            if (!enabledProviderIds.contains(provider.providerId)) {
                continue;
            }
            for (ModelSpec model : provider.models) {
                boolean selectable = model.supports(task);
                String subtitle = describe(model, task, selectable);
                Choice choice = new Choice(provider.providerId, model.modelId,
                        model.displayName, subtitle, selectable);
                if (selectable) {
                    available.add(choice);
                } else {
                    unavailable.add(choice);
                }
            }
        }
        groups.add(new Group(Group.Kind.MANUAL, available));
        groups.add(new Group(Group.Kind.UNAVAILABLE, unavailable));
        return groups;
    }

    /**
     * 副标题：能力 + 上下文 + 价格。**只在知道价的时候才写价**——
     * "价格未知"要写出来，不能空着让人以为是免费（`CONTRACTS.md` §4）。
     */
    private static String describe(ModelSpec model, TaskKind task, boolean selectable) {
        StringBuilder out = new StringBuilder();
        if (!selectable) {
            out.append("不支持").append(taskName(task)).append(" · ");
        }
        out.append(model.contextLimit / 1000).append("K context");
        if (model.priced()) {
            out.append(String.format(java.util.Locale.US, " · Input $%.4f / Output $%.4f per 1M",
                    model.inputMicros / 1_000_000.0, model.outputMicros / 1_000_000.0));
        } else {
            out.append(" · 价格未知");
        }
        if (model.vision) {
            out.append(" · 可看图");
        }
        return out.toString();
    }

    private static String taskName(TaskKind task) {
        switch (task) {
            case IMAGE: return "图片";
            case PDF: return "PDF";
            case TOOLS: return "工具调用";
            default: return "文本";
        }
    }
}
