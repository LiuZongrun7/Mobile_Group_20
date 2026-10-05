package com.mobilegroup20.modelpilot.ui.chat;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;

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
 *   <li><b>块公式 `$$...$$` 能渲染了</b>（真机验过）：关键是 `blocksLegacy(true)`——
 *       ext-latex 默认只认"`$$` 单独一行 + 公式 + `$$` 单独一行"那种写法，
 *       而模型十次有九次写成一行 `$$公式$$`；只开默认的话屏幕上就是原文
 *       （2026-10-05 真机上踩的，查错方向先歪到了"段落边界"上）。</li>
 *   <li><b>行内 `$x^2$` 暂时关着</b>（`inlinesEnabled(false)`）：打开它之后
 *       **App 一渲染就崩**（"屡次停止运行"）。所以现在行内公式仍是原文——
 *       这是一个已知缺口，不是设计选择；下次要单独查它（大概率与内联解析器
 *       对普通文本的扫描有关）。**块公式不受影响。**</li>
 *   <li><b>挂了 errorHandler 打日志</b>：Markwon 的 latex 出错是**静默回退成文本**的，
 *       不挂的话只有原文、日志里什么都没有。</li>
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
        if (text.indexOf('$') < 0) {
            return text;                 // 一个 $ 都没有：不用动（含空串）
        }
        // **单个 $ 包起来的行内公式 → 补成 $$**。
        // Markwon 的 latex 内联触发符是 `$$`（不是单个 `$`），而模型写行内公式
        // 十次有九次用单个 `$`，于是屏幕上就是 `$a^2+b^2$` 的原文。
        // 转换用**数学界的通行规则**：开头的 `$` 后面不能是空白、结尾的 `$` 前面不能是空白
        // ——这样"价格 $5 到 $10"不会被误当成公式（结尾那个 $ 前面是空格），
        // 而 `$x = 5$` 这种会正常转换。
        String inlined = text.replaceAll(
                "\\$([^\\s$][^$\\n]*?[^\\s$])\\$", "\\$\\$$1\\$\\$");
        // 行首的 $$ 前面补空行；行尾的 $$ 后面补空行。用 (?m) 多行模式逐行看。
        String fixed = inlined.replaceAll("(?m)^[ \\t]*(\\$\\$)", "\n$1");
        fixed = fixed.replaceAll("(?m)(\\$\\$)[ \\t]*$", "$1\n");
        // 上面可能补出三四个连续换行，收成最多两个（段落之间一个空行就够）。
        return fixed.replaceAll("\\n{3,}", "\n\n");
    }

    private static Markwon build(Context context) {
        float mathSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MATH_TEXT_SP,
                context.getResources().getDisplayMetrics());
        // **三种公式写法都要打开**（2026-10-05 真机排查的结论）：
        //   · blocksLegacy：`$$公式$$` **写在一行里**——模型十次有九次是这么写的，
        //     而 ext-latex 的默认块语法是"$$ 单独一行 + 公式 + $$ 单独一行"那种。
        //     只开默认那种的话，屏幕上就是 `$$c=\sqrt{a^2+b^2}$$` 的原文——
        //     这正是我第一版看到的现象，查了半天段落边界（不是那个原因）。
        //   · blocksEnabled：那种"$$ / 公式 / $$"分行的写法也留着。
        //   · inlinesEnabled：行内 `$x^2$`（数学回答里到处都是）。
        JLatexMathPlugin latex = JLatexMathPlugin.create(mathSizePx, builder -> builder
                .blocksEnabled(true)
                .blocksLegacy(true)
                .inlinesEnabled(true)
                // **Markwon 的 latex 出错是静默回退成文本的**：不挂这个 handler 的话，
                // 屏幕上只有原文、日志里什么都没有，只能靠猜（今天就猜了两轮）。
                .errorHandler((source, error) -> {
                    android.util.Log.e("ModelPilot", "公式渲染失败: " + source, error);
                    return null;        // null = 回退成文本，别让整条回答消失
                }));
        return Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                // **行内公式要求这个插件在场**：`JLatexMathPlugin.configure()` 里
                // 有一句 `registry.require(MarkwonInlineParserPlugin.class)`，
                // 没有它就抛 `IllegalStateException: Requested plugin is not added` ——
                // 而那句是在 `Markwon.build()` 里跑的，所以表现是"App 一渲染就崩"，
                // 而不是"公式显示不出来"。
                // 之前把 `inlinesEnabled(false)` 时它不查这一句，所以块公式能用、行内一开就崩。
                // 这个类本来就被 ext-latex 传递依赖进来了（io.noties.markwon:inline-parser），
                // 一行注册的事，查了两轮才看到——**那两轮都是靠猜，真正定位靠的是
                // `MarkdownRenderTest` 打印出来的堆栈**。
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(latex)
                .build();
    }
}
