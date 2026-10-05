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

    @Test public void a_price_field_is_null_when_blank_never_zero() {
        // **留空 = 不知道，不是 0**：0 是"免费"，null 是"价格未知"。界面上这两句话不一样，
        // 账本里也不一样（costMicros = null 会显示"价格未知"，0 会显示"花了 0 元"）。
        assertNull(ProviderEditorDialog.usdMicros(""));
        assertNull(ProviderEditorDialog.usdMicros(null));
        assertNull("不是数字就当没填", ProviderEditorDialog.usdMicros("abc"));
        assertNull("负数不是价格", ProviderEditorDialog.usdMicros("-1"));
    }

    @Test public void a_typed_price_becomes_micros_per_million() {
        // 界面上填的是"每 1M token 多少美元"，内部价目表的口径是微美元/1M。
        assertEquals(Long.valueOf(140_845L), ProviderEditorDialog.usdMicros("0.140845"));
        assertEquals(Long.valueOf(563_380L), ProviderEditorDialog.usdMicros("0.56338"));
        assertEquals(Long.valueOf(1_000_000L), ProviderEditorDialog.usdMicros("1"));
        assertEquals("0 是合法的（免费/不计费），和留空不是一回事",
                Long.valueOf(0L), ProviderEditorDialog.usdMicros("0"));
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
