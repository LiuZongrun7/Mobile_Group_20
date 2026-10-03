package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 模型弹层的三段（设计稿 `04-models.png`）：Auto 置顶、能手选的、**做不了这个任务的置灰**。
 *
 * <p>最要紧的一条是"**没用 key 的 provider 不出现、能力不够的出现但置灰**"：
 * 前者列出来会让人以为能选，后者藏起来会让人以为 App 坏了。
 */
public class ModelChoicesTest {

    private static ModelChoices.Group group(List<ModelChoices.Group> groups,
                                            ModelChoices.Group.Kind kind) {
        for (ModelChoices.Group group : groups) {
            if (group.kind == kind) {
                return group;
            }
        }
        throw new AssertionError("没有这一段: " + kind);
    }

    @Test public void auto_is_always_first_and_says_what_it_does() {
        List<ModelChoices.Group> groups = ModelChoices.build(ProviderRegistry.defaults(),
                Arrays.asList(ProviderRegistry.DEEPSEEK), TaskKind.TEXT, null, null);

        assertEquals(ModelChoices.Group.Kind.AUTO, groups.get(0).kind);
        ModelChoices.Choice auto = groups.get(0).choices.get(0);
        assertEquals("Auto", auto.title);
        assertTrue("要说清它不是某个模型：" + auto.subtitle,
                auto.subtitle.contains("each message"));
        assertTrue("当前是 Auto 时它要是选中态", auto.selectable);
    }

    @Test public void only_providers_with_a_key_show_up() {
        List<ModelChoices.Group> groups = ModelChoices.build(ProviderRegistry.defaults(),
                Arrays.asList(ProviderRegistry.DEEPSEEK), TaskKind.TEXT, null, null);

        ModelChoices.Group manual = group(groups, ModelChoices.Group.Kind.MANUAL);
        assertEquals(2, manual.choices.size());                    // DeepSeek 两个型号
        for (ModelChoices.Choice choice : manual.choices) {
            assertEquals(ProviderRegistry.DEEPSEEK, choice.providerId);
        }
        assertTrue("OpenAI 没配 key，不该出现",
                manual.choices.stream().noneMatch(c -> ProviderRegistry.OPENAI.equals(c.providerId)));
    }

    @Test public void models_that_cannot_do_the_task_are_listed_but_greyed() {
        List<ModelChoices.Group> groups = ModelChoices.build(ProviderRegistry.defaults(),
                Arrays.asList(ProviderRegistry.DEEPSEEK, ProviderRegistry.OPENAI), TaskKind.IMAGE,
                null, null);

        ModelChoices.Group unavailable = group(groups, ModelChoices.Group.Kind.UNAVAILABLE);
        assertFalse("DeepSeek 不能看图，要在置灰段里而不是消失", unavailable.choices.isEmpty());
        assertTrue(unavailable.choices.get(0).subtitle.contains("不支持图片"));
        // 能看图的那家在可选段里
        ModelChoices.Group manual = group(groups, ModelChoices.Group.Kind.MANUAL);
        assertTrue(manual.choices.stream().allMatch(c -> ProviderRegistry.OPENAI.equals(c.providerId)));
    }

    @Test public void an_unknown_price_is_said_out_loud_not_left_blank() {
        List<ModelChoices.Group> groups = ModelChoices.build(ProviderRegistry.defaults(),
                Arrays.asList(ProviderRegistry.OPENAI), TaskKind.TEXT, null, null);

        ModelChoices.Choice choice = group(groups, ModelChoices.Group.Kind.MANUAL).choices.get(0);
        assertTrue("没查到官方价就要写出来，空着会被当成免费：" + choice.subtitle,
                choice.subtitle.contains("价格未知"));
    }

    @Test public void the_manually_selected_model_is_marked_not_auto() {
        List<ModelChoices.Group> groups = ModelChoices.build(ProviderRegistry.defaults(),
                Arrays.asList(ProviderRegistry.DEEPSEEK), TaskKind.TEXT,
                ProviderRegistry.DEEPSEEK, "deepseek-chat");

        assertFalse("手动选中时 Auto 不是选中态",
                group(groups, ModelChoices.Group.Kind.AUTO).choices.get(0).selectable);
    }
}
