package com.mobilegroup20.modelpilot.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 自定义端点表单里的两段输入解析。
 *
 * <p>为什么值得单测：这两处都直接决定木桶效应（压缩阈值）——
 * 模型清单空了这家就没模型可用，上下文上限填错（0、负数、字母）会让阈值算成 0
 * = 从不压缩，而表现是"聊到一半上游报超长"，完全看不出跟设置有关。
 */
public class ProviderEditorInputTest {

    @Test public void model_ids_split_on_commas_and_newlines() {
        List<String> models = ProviderEditorDialog.splitModels("a, b\nc ,, a");
        // 去空白、去空项、**去重**：重复的模型 id 会在模型弹层里出现两行同样的东西。
        assertEquals(Arrays.asList("a", "b", "c"), models);
    }

    @Test public void blank_model_input_yields_nothing() {
        assertTrue(ProviderEditorDialog.splitModels("   ").isEmpty());
        assertTrue(ProviderEditorDialog.splitModels(null).isEmpty());
    }

    @Test public void context_limit_must_be_a_positive_number() {
        assertEquals(Integer.valueOf(128000), ProviderEditorDialog.parsePositive("128000"));
        assertEquals(Integer.valueOf(128000), ProviderEditorDialog.parsePositive(" 128000 "));
        // 下面这些都必须被拒：0 与负数会把阈值算成 0（= 从不压缩），
        // 而"从不压缩"的表现是上游报超长，用户查不到这里。
        assertNull(ProviderEditorDialog.parsePositive("0"));
        assertNull(ProviderEditorDialog.parsePositive("-1"));
        assertNull(ProviderEditorDialog.parsePositive("128k"));
        assertNull(ProviderEditorDialog.parsePositive(""));
        assertNull(ProviderEditorDialog.parsePositive(null));
    }
}
