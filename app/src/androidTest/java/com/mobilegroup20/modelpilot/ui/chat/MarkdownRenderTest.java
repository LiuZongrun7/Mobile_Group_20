package com.mobilegroup20.modelpilot.ui.chat;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 渲染一段带公式的 Markdown（真机）。
 *
 * <p>为什么用仪器测试而不是点界面：行内公式（`$x^2$`）一开 App 就崩，
 * 而崩在**哪里**光看界面看不出来——仪器测试的失败报告里带着完整堆栈，
 * 那是我要的东西。它也顺便成了"这段文本能渲染"的回归测试。
 */
@RunWith(AndroidJUnit4.class)
public class MarkdownRenderTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void block_formula_renders() {
        assertRenders("由勾股定理：\n\n$$c=\\sqrt{a^2+b^2}$$\n\n所以斜边是 5。");
    }

    @Test
    public void inline_formula_renders() {
        // **断言"真的换成了不是文字的东西"**：只断言"没抛异常"是不够的——
        // 第一版就是这么写的，于是"行内公式其实没渲染"这件事它照样通过。
        // 公式是靠 ReplacementSpan 顶掉文字实现的，所以查有没有这种 span。
        assertReplacesText("由勾股定理 $a^2+b^2=c^2$ 可得结果。");
    }

    @Test
    public void a_plain_dollar_amount_is_not_treated_as_math() {
        // "价格 $5 到 $10" 里有两个 $，内联解析器会不会把它当公式？
        // 无论它渲染成什么，**都不能崩**——这是这一条要钉住的。
        assertRenders("这次调用大概花了 $5 到 $10，具体看用量。");
    }

    @Test
    public void mixed_content_renders() {
        assertRenders("### 步骤\n\n1. 先算 $a^2$\n2. 再算 $b^2$\n\n$$c=\\sqrt{a^2+b^2}$$\n\n| 项 | 值 |\n|---|---|\n| a | 3 |\n");
    }

    /** 渲染之后，文本里应该出现"顶掉文字的 span"（公式/图片都是这么实现的）。 */
    private void assertReplacesText(String markdown) {
        final TextView view = new TextView(context);
        final Throwable[] failure = new Throwable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Markdown.render(view, markdown);
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) {
            throw new AssertionError("渲染这段失败: " + markdown, failure[0]);
        }
        CharSequence text = view.getText();
        boolean replaced = false;
        if (text instanceof android.text.Spanned) {
            for (Object span : ((android.text.Spanned) text).getSpans(
                    0, text.length(), android.text.style.ReplacementSpan.class)) {
                if (span != null) {
                    replaced = true;
                }
            }
        }
        org.junit.Assert.assertTrue("行内公式没有变成公式（文字里没有 ReplacementSpan）: " + markdown,
                replaced);
    }

    private void assertRenders(String markdown) {
        final TextView view = new TextView(context);
        // Markwon 要求在主线程渲染，而仪器测试默认不在主线程；顺手把异常抓下来
        // —— 失败报告里带着完整堆栈，那正是我复现这次崩溃的目的。
        final Throwable[] failure = new Throwable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Markdown.render(view, markdown);
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) {
            throw new AssertionError("渲染这段失败: " + markdown, failure[0]);
        }
        assertNotNull("渲染之后应该拿到一段文本（哪怕是回退的原文）", view.getText());
    }
}
