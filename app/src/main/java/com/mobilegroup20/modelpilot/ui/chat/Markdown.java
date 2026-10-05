package com.mobilegroup20.modelpilot.ui.chat;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * 回答的渲染：**Markdown + LaTeX 公式**。
 *
 * <p>为什么要它：问一道数学题，模型返回的是 Markdown 加 `$$...$$` 公式。
 * 不渲染的话屏幕上就是 `###`、`**`、`\frac{a}{b}`、`$$` 的原文——
 * 内容是对的，但没法看（`docs/CHAT_ENGINE.md` 的对话页本来是纯文本 TextView）。
 *
 * <p>三个取舍：
 *
 * <ol>
 *   <li><b>只渲染模型说的话，不渲染用户自己打的字。</b>用户输入里的 `*` 或 `_`
 *       被当成 Markdown 会很意外（"我打的星号呢"），而用户极少打 Markdown。</li>
 *   <li><b>`Markwon` 只建一次</b>（静态单例）：它内部有解析器、缓存和线程池，
 *       每条消息新建一个是浪费；但它持有 Context，所以**只存 Application 的**，
 *       存 Activity 就是把它钉住不放。</li>
 *   <li><b>公式（`$$...$$`）目前还没渲染出来</b>（2026-10-05 的状态）：
 *       Markwon 认 Markdown（标题/加粗/表格/代码块/列表），但 `JLatexMathPlugin`
 *       注册之后 `$$...$$` 仍然以原文显示。段落边界已经规范化过（见 {@link #normalize}），
 *       所以不是"三行并成一个段落"那个问题；下一步要查的是 jlatexmath 的字体资源
 *       与插件用法（Markwon 的 latex 解析异常是**静默回退成文本**的，
 *       所以日志里什么都看不到——这一点本身要改：包一层 try/catch 打日志）。</li>
 * </ol>
 */
public final class Markdown {

    /** 公式用的字号（sp）。和回答正文的 15sp 接近，公式才不会比正文突兀。 */
    private static final float MATH_TEXT_SP = 15f;

    private static Markwon instance;

    private Markdown() {
    }

    /** 把一段 Markdown 渲染进这个 TextView。**必须在主线程调**（Markwon 的约定）。 */
    public static void render(TextView view, CharSequence text) {
        if (view == null) {
            return;
        }
        if (instance == null) {
            instance = build(view.getContext().getApplicationContext());
        }
        instance.setMarkdown(view, normalize(text == null ? "" : text.toString()));
    }

    /**
     * 渲染前把公式块的**段落边界**补齐。
     *
     * <p>为什么需要：Markwon 的 latex 扩展要求 `$$...$$` 自己是一个块（CommonMark 里
     * 连续的两行会并成**同一个段落**）。模型输出的是这样：
     * <pre>
     * 由勾股定理：
     * $$c=\sqrt{a^2+b^2}$$
     * 所以斜边是 5。
     * </pre>
     * 三行连在一起 = 一个段落，而段落不是以 `$$` 开头，于是插件不认，
     * 用户看到的就是 `$$...$$` 的原文（"我明明开了公式渲染"）。
     * 这里在公式块前后各补一个空行，把它变成独立段落——**这是我们能替模型做的规范**，
     * 比要求模型"记得空行"可靠。
     */
    static String normalize(String text) {
        if (text.indexOf("$$") < 0) {
            return text;
        }
        // 行首的 $$ 前面补空行；行尾的 $$ 后面补空行。用 (?m) 多行模式逐行看。
        String fixed = text.replaceAll("(?m)^[ \\t]*(\\$\\$)", "\n$1");
        fixed = fixed.replaceAll("(?m)(\\$\\$)[ \\t]*$", "$1\n");
        // 上面可能补出三四个连续换行，收成最多两个（段落之间一个空行就够）。
        return fixed.replaceAll("\\n{3,}", "\n\n");
    }

    private static Markwon build(Context context) {
        float mathSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MATH_TEXT_SP,
                context.getResources().getDisplayMetrics());
        return Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(JLatexMathPlugin.create(mathSizePx))
                .build();
    }
}
